package com.telecomprofi.japanesewalk.workout

import com.telecomprofi.japanesewalk.core.HrZone
import com.telecomprofi.japanesewalk.core.IntervalPlan
import com.telecomprofi.japanesewalk.core.IwtProtocol
import com.telecomprofi.japanesewalk.core.Phase
import com.telecomprofi.japanesewalk.core.Segment
import com.telecomprofi.japanesewalk.core.TargetVerdict
import com.telecomprofi.japanesewalk.core.UserProfile
import com.telecomprofi.japanesewalk.core.DistanceTracker
import com.telecomprofi.japanesewalk.core.Pace
import com.telecomprofi.japanesewalk.core.WorkoutAccumulator
import com.telecomprofi.japanesewalk.data.db.HrSampleEntity
import com.telecomprofi.japanesewalk.data.prefs.CoachSettings
import com.telecomprofi.japanesewalk.hrm.ContactStatus
import com.telecomprofi.japanesewalk.hrm.HeartRateSource
import com.telecomprofi.japanesewalk.hrm.HrmState
import com.telecomprofi.japanesewalk.music.NowPlaying
import com.telecomprofi.japanesewalk.music.WorkoutMusicPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Seconds without a reading before we treat the strap as dropped out. */
private const val STRAP_STALE_MS = 8_000L

/**
 * The heart of the app: one second at a time, it advances the IWT timeline,
 * folds heart-rate samples into the running totals, tells the voice coach what
 * to say and the music player what cadence to hit.
 *
 * It owns no Android objects itself, which keeps it easy to reason about; the
 * foreground service supplies the heart-rate source, the coach and the player.
 */
class WorkoutEngine(
    private val scope: CoroutineScope,
    private val profile: UserProfile,
    private val protocol: IwtProtocol,
    private val coachSettings: CoachSettings,
    private val source: HeartRateSource,
    private val coach: VoiceCoach?,
    private val music: WorkoutMusicPlayer?,
    /** Optional: absent or unpermitted GPS simply leaves the distance at zero. */
    private val location: LocationTracker? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onFinished: suspend (SessionResult) -> Unit,
) {
    val plan = IntervalPlan(protocol)
    private val acc = WorkoutAccumulator(profile)

    private val _state = MutableStateFlow(
        WorkoutUiState(totalSec = plan.totalSec, totalSets = protocol.sets)
    )
    val state: StateFlow<WorkoutUiState> = _state.asStateFlow()

    private var startedAt = 0L
    private var pausedAtWall = 0L
    private var pausedMs = 0L
    private var status = EngineStatus.IDLE

    private var lastHr: Int? = null
    private var lastHrAt = 0L
    private var contact = ContactStatus.NOT_SUPPORTED
    private var strapWasLost = false

    private var currentSegmentIndex = -1
    private var halfwaySpoken = false
    private var countdownSpokenFor = -1
    private var lastDriftSpokenAt = 0L
    private var lastCueAt = 0L
    private val samples = mutableListOf<HrSampleEntity>()
    private var lastSampleSec = -10

    private val distance = DistanceTracker()
    private var lastFixSpeedMps = 0.0
    private var lastFixAt = 0L

    private var tickJob: Job? = null
    private val watchers = mutableListOf<Job>()
    private var hrmState: HrmState = HrmState.Idle
    private var nowPlaying: NowPlaying? = null

    val isRunning: Boolean get() = status == EngineStatus.RUNNING

    /**
     * Named [isSessionActive] rather than `isActive` on purpose: inside the
     * ticker's `launch { }` block that name would collide with
     * `CoroutineScope.isActive`.
     */
    val isSessionActive: Boolean get() = status == EngineStatus.RUNNING || status == EngineStatus.PAUSED

    /** Cadence the current bout is asking for, or null once the session is over. */
    fun currentCadenceSpm(): Int? = plan.segmentAt(currentElapsed())?.cadenceSpm

    /** Target heart rate right now, used by the simulator and the music player. */
    fun currentTargetHr(): Int? {
        val segment = plan.segmentAt(currentElapsed()) ?: return null
        return protocol.targetBand(segment.phase).aimBpm(profile)
    }

    fun start() {
        if (status != EngineStatus.IDLE) return
        startedAt = clock()
        pausedMs = 0
        status = EngineStatus.RUNNING

        source.start()
        coach?.setSpeechRate(coachSettings.speechRate)

        watchers += scope.launch {
            source.samples.collect { sample ->
                lastHr = sample.hr
                lastHrAt = clock()
                contact = sample.contact
                if (strapWasLost) {
                    strapWasLost = false
                    sayCue(CoachScript.strapBack())
                }
            }
        }
        watchers += scope.launch { source.state.collect { hrmState = it; publish() } }

        location?.let { tracker ->
            tracker.start()
            watchers += scope.launch {
                tracker.samples.collect { fix ->
                    distance.add(fix.latitude, fix.longitude, fix.accuracyM, fix.timeMs)
                    // Prefer the platform's Doppler speed; fall back to the speed
                    // implied by consecutive accepted fixes.
                    lastFixSpeedMps = fix.speedMps?.toDouble()?.takeIf { it > 0.0 }
                        ?: distance.derivedSpeedMps
                    lastFixAt = clock()
                }
            }
        }
        music?.let { player ->
            watchers += scope.launch { player.nowPlaying.collect { nowPlaying = it; publish() } }
        }

        sayCue(CoachScript.sessionStart(plan.totalSec / 60, protocol.sets), urgent = true)
        enterSegment(plan.segments.firstOrNull(), announce = true)

        tickJob = scope.launch {
            while (isActive) {
                delay(500)
                if (status == EngineStatus.RUNNING) tick()
            }
        }
        publish()
    }

    /**
     * Single funnel for everything the coach says, so the app can be talkative
     * about the things that are time-critical and sparing about everything else.
     *
     * @param urgent interval changes and countdowns — always spoken, and they
     *        reset the quiet window so a correction never treads on their heels.
     */
    private fun sayCue(text: String, urgent: Boolean = false) {
        if (!coachSettings.voiceEnabled || text.isBlank()) return
        val now = clock()
        if (!urgent && now - lastCueAt < coachSettings.minCueGapSec * 1000L) return
        lastCueAt = now
        coach?.say(text, interrupt = urgent)
    }

    fun pause() {
        if (status != EngineStatus.RUNNING) return
        status = EngineStatus.PAUSED
        pausedAtWall = clock()
        music?.pause()
        coach?.stopSpeaking()
        publish()
    }

    fun resume() {
        if (status != EngineStatus.PAUSED) return
        pausedMs += clock() - pausedAtWall
        status = EngineStatus.RUNNING
        music?.play()
        publish()
    }

    fun togglePause() {
        if (status == EngineStatus.RUNNING) pause() else if (status == EngineStatus.PAUSED) resume()
    }

    /** Stop early. The session is still saved if it was long enough to count. */
    fun stop() {
        if (!isSessionActive) return
        finish(completed = plan.isFinished(currentElapsed()))
    }

    fun skipSegment() {
        val segment = plan.segmentAt(currentElapsed()) ?: return
        // Move the clock forward to the start of the next segment.
        val jump = segment.endSec - currentElapsed()
        if (jump > 0) {
            startedAt -= jump * 1000L
            acc.skipAhead(jump)
        }
        tick()
    }

    private fun currentElapsed(): Int {
        if (startedAt == 0L) return 0
        val extraPause = if (status == EngineStatus.PAUSED) clock() - pausedAtWall else 0L
        return (((clock() - startedAt) - pausedMs - extraPause) / 1000L).toInt().coerceAtLeast(0)
    }

    private fun tick() {
        val elapsed = currentElapsed()
        val dt = elapsed - acc.elapsedSec
        if (dt <= 0) {
            publish()
            return
        }

        val fresh = lastHr != null && (clock() - lastHrAt) < STRAP_STALE_MS
        val hr = if (fresh) lastHr else null

        val segment = plan.segmentAt(elapsed)
        if (segment == null) {
            // Past the end of the plan. segmentAt() is exclusive of endSec, so
            // arriving here with the last second still uncredited is normal —
            // credit it against the final bout before closing the session, or a
            // 30-minute walk reports 29 minutes and four sets out of five.
            val tail = plan.uncreditedSeconds(acc.elapsedSec)
            if (tail > 0) {
                val last = plan.segments.lastOrNull()
                acc.tick(tail, hr, last, last?.let { protocol.targetBand(it.phase) })
            }
            finish(completed = true)
            return
        }

        val band = protocol.targetBand(segment.phase)
        if (!fresh && lastHrAt != 0L && !strapWasLost) {
            strapWasLost = true
            sayCue(CoachScript.strapLost())
        }

        acc.tick(dt, hr, segment, band)

        if (segment.index != currentSegmentIndex) enterSegment(segment, announce = true)

        val remaining = plan.remainingInSegment(elapsed)
        if (coachSettings.countdownSeconds > 0 &&
            remaining in 1..coachSettings.countdownSeconds &&
            countdownSpokenFor != segment.index
        ) {
            countdownSpokenFor = segment.index
            sayCue(CoachScript.countdown(remaining, plan.nextSegmentAfter(elapsed)?.phase), urgent = true)
        }

        if (coachSettings.halfwayCue && !halfwaySpoken && elapsed >= plan.totalSec / 2) {
            halfwaySpoken = true
            sayCue(CoachScript.halfway())
        }

        if (hr != null && coachSettings.announceZoneDrift) {
            maybeCoachDrift(segment, elapsed, hr)
        }

        if (hr != null && elapsed - lastSampleSec >= 2) {
            lastSampleSec = elapsed
            samples += HrSampleEntity(
                sessionId = 0,
                tSec = elapsed,
                hr = hr,
                phase = segment.phase.ordinal,
            )
        }

        publish()
        if (plan.isFinished(elapsed)) finish(completed = true)
    }

    /**
     * Heart-rate correction, deliberately reluctant. Three separate brakes:
     * the bout must have had time to settle, the drift must have persisted,
     * and corrections are spaced well apart. On recovery bouts a low heart
     * rate resolves to ON_TARGET upstream, so it never reaches here.
     */
    private fun maybeCoachDrift(segment: Segment, elapsed: Int, hr: Int) {
        if (elapsed - segment.startSec < coachSettings.boutSettleSec) return
        val band = protocol.targetBand(segment.phase)
        val verdict = band.verdict(hr, profile)
        if (verdict == TargetVerdict.ON_TARGET) return
        if (acc.streakSec < coachSettings.driftGraceSec) return

        val now = clock()
        val repeatGapMs = (coachSettings.minCueGapSec * 2).coerceAtLeast(60) * 1000L
        if (now - lastDriftSpokenAt < repeatGapMs) return

        val text = CoachScript.drift(verdict, segment.phase, band.lowBpm(profile), band.highBpm(profile))
        if (text.isBlank()) return
        lastDriftSpokenAt = now
        acc.resetStreak()
        sayCue(text)
    }

    private fun enterSegment(segment: Segment?, announce: Boolean) {
        if (segment == null) return
        currentSegmentIndex = segment.index
        countdownSpokenFor = -1
        val band = protocol.targetBand(segment.phase)
        if (announce) {
            sayCue(
                CoachScript.phaseChange(
                    phase = segment.phase,
                    setNumber = segment.setNumber,
                    totalSets = protocol.sets,
                    lowBpm = band.lowBpm(profile),
                    highBpm = band.highBpm(profile),
                ),
                urgent = true,
            )
        }
        music?.setTargetCadence(segment.cadenceSpm, switchTrack = true)
    }

    private fun finish(completed: Boolean) {
        if (status == EngineStatus.FINISHED) return

        // A completed session is credited the whole plan, whichever route got
        // here. There are three ways into finish() and only one of them used to
        // settle the final second, so a 30-minute walk could still report 29
        // minutes and four sets out of five depending on which tick noticed the
        // end. skipAhead advances the clock without crediting heart-rate time or
        // calories, so it is a no-op when the tick path already settled up.
        if (completed) {
            val tail = plan.uncreditedSeconds(acc.elapsedSec)
            if (tail > 0) acc.skipAhead(tail)
        }

        val elapsed = acc.elapsedSec
        status = EngineStatus.FINISHED
        tickJob?.cancel()
        watchers.forEach { it.cancel() }
        watchers.clear()
        source.stop()
        location?.stop()
        music?.pause()

        val completedSets = plan.completedSets(elapsed)

        val result = SessionResult(
            startedAt = startedAt,
            endedAt = clock(),
            plannedSec = plan.totalSec,
            elapsedSec = elapsed,
            avgHr = acc.avgHr,
            maxHr = acc.maxHr,
            kcal = acc.kcalRounded(),
            zoneSeconds = acc.secondsInZone.toList(),
            onTargetSec = acc.secondsOnTarget,
            offTargetSec = acc.secondsOffTarget,
            completedSets = completedSets,
            plannedSets = protocol.sets,
            completed = completed,
            protocolLabel = protocol.label(),
            samples = samples.toList(),
        )
        publish()
        scope.launch { onFinished(result) }
    }

    private fun publish() {
        val elapsed = if (status == EngineStatus.FINISHED) acc.elapsedSec else currentElapsed()
        val segment = plan.segmentAt(elapsed)
        val band = segment?.let { protocol.targetBand(it.phase) }
        val fresh = lastHr != null && (clock() - lastHrAt) < STRAP_STALE_MS
        val hr = if (fresh) lastHr else null

        _state.value = WorkoutUiState(
            status = status,
            elapsedSec = elapsed,
            totalSec = plan.totalSec,
            segment = segment,
            phase = segment?.phase,
            setNumber = segment?.setNumber ?: 0,
            totalSets = protocol.sets,
            segmentRemainingSec = plan.remainingInSegment(elapsed),
            segmentProgress = plan.segmentProgress(elapsed).toFloat(),
            sessionProgress = plan.overallProgress(elapsed).toFloat(),
            nextPhase = plan.nextSegmentAfter(elapsed)?.phase,
            hr = hr,
            zone = hr?.let { HrZone.forHr(it, profile) },
            gaugeFraction = hr?.let { HrZone.gaugeFraction(it, profile).toFloat() } ?: 0f,
            targetAimBpm = band?.aimBpm(profile) ?: 0,
            targetLowBpm = band?.lowBpm(profile) ?: 0,
            targetHighBpm = band?.highBpm(profile) ?: 0,
            targetLowFraction = band?.let { HrZone.gaugeFractionForPct(it.lowPct).toFloat() } ?: 0f,
            targetHighFraction = band?.let { HrZone.gaugeFractionForPct(it.highPct).toFloat() } ?: 0f,
            verdict = if (hr != null && band != null) band.verdict(hr, profile) else null,
            percentOfTarget = if (hr != null && band != null) band.percentOfTarget(hr, profile) else 0,
            kcal = acc.kcalRounded(),
            avgHr = acc.avgHr,
            maxHr = acc.maxHr,
            secondsInZone = acc.secondsInZone.toList(),
            adherencePercent = acc.adherencePercent,
            hrmState = hrmState,
            contact = contact,
            strapStale = !fresh && lastHrAt != 0L,
            nowPlaying = nowPlaying,
            targetCadenceSpm = segment?.cadenceSpm ?: 0,
            distanceMeters = distance.totalMeters,
            // A speed from a fix ten seconds ago is not a current speed.
            currentSpeedMps = if (lastFixAt != 0L && clock() - lastFixAt < 10_000) lastFixSpeedMps else 0.0,
            avgSpeedMps = Pace.averageSpeedMps(distance.totalMeters, elapsed),
            gpsTracking = location?.isTracking == true,
            gpsFixes = distance.acceptedFixes,
        )
    }
}
