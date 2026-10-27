package com.telecomprofi.japanesewalk.workout

import com.telecomprofi.japanesewalk.core.HrZone
import com.telecomprofi.japanesewalk.core.IntervalPlan
import com.telecomprofi.japanesewalk.core.IwtProtocol
import com.telecomprofi.japanesewalk.core.Phase
import com.telecomprofi.japanesewalk.core.Segment
import com.telecomprofi.japanesewalk.core.TargetBand
import com.telecomprofi.japanesewalk.core.TargetVerdict
import com.telecomprofi.japanesewalk.core.TrainerControlMode
import com.telecomprofi.japanesewalk.core.UserProfile
import com.telecomprofi.japanesewalk.core.WorkoutMode
import com.telecomprofi.japanesewalk.core.DistanceTracker
import com.telecomprofi.japanesewalk.core.Pace
import com.telecomprofi.japanesewalk.core.WorkoutAccumulator
import com.telecomprofi.japanesewalk.data.db.HrSampleEntity
import com.telecomprofi.japanesewalk.data.prefs.CoachSettings
import com.telecomprofi.japanesewalk.data.prefs.CoolDownSettings
import com.telecomprofi.japanesewalk.data.prefs.CycleSettings
import com.telecomprofi.japanesewalk.hrm.ContactStatus
import com.telecomprofi.japanesewalk.hrm.HeartRateSource
import com.telecomprofi.japanesewalk.hrm.HrmState
import com.telecomprofi.japanesewalk.music.NowPlaying
import com.telecomprofi.japanesewalk.music.WorkoutMusicPlayer
import com.telecomprofi.japanesewalk.trainer.TrainerControl
import com.telecomprofi.japanesewalk.trainer.TrainerState
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

/** Give up on cool-down tracking if the strap has been silent this long. */
private const val COOLDOWN_STRAP_GIVEUP_SEC = 90

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
    private val coolDownSettings: CoolDownSettings = CoolDownSettings(),
    private val mode: WorkoutMode = WorkoutMode.WALK,
    private val cycle: CycleSettings = CycleSettings(),
    /** A smart trainer, when riding indoors. Null on foot. */
    private val trainer: TrainerControl? = null,
    private val source: HeartRateSource,
    private val coach: VoiceCoach?,
    private val music: WorkoutMusicPlayer?,
    /** Optional: absent or unpermitted GPS simply leaves the distance at zero. */
    private val location: LocationTracker? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onFinished: suspend (SessionResult) -> Unit,
) {
    val plan = IntervalPlan(protocol, profile.intensityModel)
    private val acc = WorkoutAccumulator(profile)

    /** Snapshotted once: the UI must render the plan being run, not the one in Settings. */
    private val shape = SessionShape(
        sets = protocol.sets,
        slowSec = protocol.slowSec,
        fastSec = protocol.fastSec,
        warmupSec = protocol.warmupSec,
        startWithSlow = protocol.startWithSlow,
    )
    private val zoneBands = HrZone.bands(profile)

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
    /**
     * Run-length of the **ramped** band's verdict, which is the band the coach
     * judges with. It cannot borrow the accumulator's streak: that one tracks
     * the settled band, for adherence, and a long on-target settled streak
     * would satisfy the persistence gate for an off-target ramped verdict —
     * i.e. correct someone sitting dead centre of the prescribed window.
     */
    private var driftVerdict = TargetVerdict.ON_TARGET
    private var driftStreakSec = 0
    private var lastCueAt = 0L
    private val samples = mutableListOf<HrSampleEntity>()
    private var lastSampleSec = -10

    private val distance = DistanceTracker()
    private var lastFixSpeedMps = 0.0
    private var lastFixAt = 0L

    // --- cool-down tracking (after the plan is finished) ---
    /** Wall clock at which the plan itself completed. */
    private var coolStartedAtWall = 0L
    private var coolSec = 0
    /** Consecutive cool-down seconds spent back down in zone 1. */
    private var coolCalmSec = 0
    private var coolNoStrapSec = 0

    // --- trainer ---
    private var lastPower: Int? = null
    private var lastCadence: Int? = null
    private var lastResistance: Int? = null
    private var lastBikeAt = 0L
    private var trainerState: TrainerState = TrainerState.Idle
    /** Watts (or resistance level) last asked of the trainer. */
    private var currentTarget = 0
    /**
     * What the heart-rate seeking has learned this session, per bout type.
     *
     * Kept across bouts on purpose: if 130 W turned out to be 20 W too much for
     * the first hard interval, the second should start at 110, not re-learn the
     * same correction from scratch every three minutes.
     */
    private var offsetFastW = 0
    private var offsetSlowW = 0
    private var lastAdjustAt = 0L
    private var manualResistanceWarned = false

    /** Paused by something outside the app — a phone call, another app taking focus. */
    private var autoPaused = false
    private var interrupted = false

    private var tickJob: Job? = null
    private val watchers = mutableListOf<Job>()
    private var hrmState: HrmState = HrmState.Idle
    private var nowPlaying: NowPlaying? = null
    private var musicStatus = com.telecomprofi.japanesewalk.music.MusicStatus()

    val isRunning: Boolean get() = status == EngineStatus.RUNNING

    /**
     * Named [isSessionActive] rather than `isActive` on purpose: inside the
     * ticker's `launch { }` block that name would collide with
     * `CoroutineScope.isActive`.
     */
    val isSessionActive: Boolean
        get() = status == EngineStatus.RUNNING ||
            status == EngineStatus.PAUSED ||
            status == EngineStatus.COOLING

    /**
     * Cadence the current bout is asking for — steps a minute on foot, pedal rpm
     * on the bike. Either way it is what the music is matched to.
     */
    fun currentCadenceSpm(): Int? {
        if (status == EngineStatus.COOLING) return cadenceFor(Phase.COOLDOWN)
        val segment = plan.segmentAt(currentElapsed()) ?: return null
        return cadenceFor(segment.phase)
    }

    private fun cadenceFor(phase: Phase): Int {
        if (mode != WorkoutMode.CYCLE) return protocol.cadenceSpm(phase)
        return when (phase) {
            Phase.FAST -> cycle.hardCadenceRpm
            Phase.SLOW -> cycle.easyCadenceRpm
            Phase.WARMUP -> cycle.easyCadenceRpm
            Phase.COOLDOWN -> (cycle.easyCadenceRpm - 10).coerceAtLeast(50)
        }
    }

    /**
     * Target heart rate right now, used by the simulator and the music player.
     *
     * This is the **ramped** target, so the simulated heart rate climbs and
     * falls across bout changes the way a real one does rather than stepping.
     */
    fun currentTargetHr(): Int? {
        if (status == EngineStatus.COOLING) {
            return protocol.targetBand(Phase.COOLDOWN, profile.intensityModel).aimBpm(profile)
        }
        return plan.bandAt(currentElapsed())?.aimBpm(profile)
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
            watchers += scope.launch { player.status.collect { musicStatus = it; publish() } }
        }

        trainer?.let { t ->
            t.start()
            watchers += scope.launch {
                t.data.collect { bike ->
                    bike.powerW?.let { lastPower = it }
                    bike.cadenceRpm?.let { lastCadence = it.toInt() }
                    bike.resistance?.let { lastResistance = it }
                    lastBikeAt = clock()
                }
            }
            watchers += scope.launch {
                t.state.collect { state ->
                    trainerState = state
                    // Re-assert the target whenever control is (re)granted: a
                    // trainer that dropped out and came back is sitting at
                    // whatever it defaulted to, not at what this bout wants.
                    if (state is TrainerState.Connected && state.hasControl) {
                        plan.segmentAt(currentElapsed())?.let { applyTrainerTarget(it.phase) }
                    } else if (state is TrainerState.Connected && !manualResistanceWarned &&
                        cycle.controlMode != TrainerControlMode.NONE
                    ) {
                        manualResistanceWarned = true
                        sayCue(CoachScript.trainerManual())
                    }
                    publish()
                }
            }
        }

        sayCue(CoachScript.sessionStart(plan.totalSec / 60, protocol.sets, mode), urgent = true)
        enterSegment(plan.segments.firstOrNull(), announce = true)

        tickJob = scope.launch {
            while (isActive) {
                delay(500)
                when (status) {
                    EngineStatus.RUNNING -> tick()
                    EngineStatus.COOLING -> coolTick()
                    else -> Unit
                }
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
        autoPaused = false
        music?.play()
        publish()
    }

    fun togglePause() {
        if (status == EngineStatus.RUNNING) pause() else if (status == EngineStatus.PAUSED) resume()
    }

    /**
     * Something outside the app wants the audio — almost always an answered
     * phone call. Pause the walk and go quiet, remembering that it was not the
     * user's doing so it can be picked up again automatically.
     */
    fun interrupt() {
        if (interrupted) return
        interrupted = true
        coach?.stopSpeaking()
        if (status == EngineStatus.RUNNING) {
            autoPaused = true
            pause()
        } else {
            music?.pause()
            publish()
        }
    }

    /** The call ended. Resume only what we paused ourselves. */
    fun endInterruption() {
        if (!interrupted) return
        interrupted = false
        if (autoPaused && status == EngineStatus.PAUSED) {
            autoPaused = false
            resume()
            sayCue(CoachScript.backFromCall(), urgent = true)
        } else if (status == EngineStatus.COOLING) {
            music?.play()
            publish()
        }
    }

    val isInterrupted: Boolean get() = interrupted

    /** Stop early. The session is still saved if it was long enough to count. */
    fun stop() {
        if (!isSessionActive) return
        if (status == EngineStatus.COOLING) {
            finish(completed = true)
            return
        }
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
        if (status == EngineStatus.COOLING || status == EngineStatus.FINISHED) return acc.elapsedSec
        val extraPause = if (status == EngineStatus.PAUSED) clock() - pausedAtWall else 0L
        return (((clock() - startedAt) - pausedMs - extraPause) / 1000L).toInt().coerceAtLeast(0)
    }

    /** Power is only current if the trainer said so in the last few seconds. */
    private fun freshPower(): Int? =
        if (lastPower != null && clock() - lastBikeAt < 6_000L) lastPower else null

    private fun freshCadence(): Int? =
        if (lastCadence != null && clock() - lastBikeAt < 6_000L) lastCadence else null

    private val hasControl: Boolean
        get() = (trainerState as? TrainerState.Connected)?.hasControl == true

    private fun offsetFor(phase: Phase): Int =
        if (phase == Phase.FAST) offsetFastW else offsetSlowW

    /** What the trainer is actually asked for: the setting plus what was learned. */
    private fun targetFor(phase: Phase): Int {
        val base = baseTarget(phase)
        if (cycle.controlMode != TrainerControlMode.POWER) return base
        return (base + offsetFor(phase)).coerceIn(cycle.minWatts, cycle.maxWatts)
    }

    /** The configured target for a bout, before any heart-rate-driven nudging. */
    private fun baseTarget(phase: Phase): Int = when (cycle.controlMode) {
        TrainerControlMode.POWER -> when (phase) {
            Phase.FAST -> cycle.hardWatts
            else -> cycle.easyWatts
        }
        TrainerControlMode.RESISTANCE -> when (phase) {
            Phase.FAST -> cycle.hardResistance
            else -> cycle.easyResistance
        }
        TrainerControlMode.NONE -> 0
    }

    private fun applyTrainerTarget(phase: Phase) {
        val t = trainer ?: return
        if (mode != WorkoutMode.CYCLE) return
        if (cycle.controlMode == TrainerControlMode.NONE) return
        currentTarget = targetFor(phase)
        lastAdjustAt = clock()
        when (cycle.controlMode) {
            TrainerControlMode.POWER -> t.setTargetPower(currentTarget)
            TrainerControlMode.RESISTANCE -> t.setTargetResistance(currentTarget)
            TrainerControlMode.NONE -> Unit
        }
    }

    /**
     * Close the loop: the bout is prescribed in heart rate, the trainer is set in
     * watts, and the relationship between the two is different for everyone and
     * different again on a hot day. So the target creeps towards whatever
     * actually puts the heart rate in the band.
     *
     * Deliberately slow — a small step, well spaced, and only after the bout has
     * settled. Heart rate lags effort by most of a minute; a tight loop would
     * chase that lag and oscillate between too easy and too hard.
     */
    private fun maybeAdjustTrainer(segment: Segment, elapsed: Int, hr: Int) {
        val t = trainer ?: return
        if (mode != WorkoutMode.CYCLE || !cycle.autoAdjust || !hasControl) return
        if (cycle.controlMode != TrainerControlMode.POWER) return
        if (elapsed - segment.startSec < coachSettings.boutSettleSec) return
        val now = clock()
        if (now - lastAdjustAt < cycle.adjustEverySec * 1000L) return

        val band = plan.bandFor(segment.phase)
        val step = cycle.adjustStepWatts
        val delta = when (band.verdict(hr, profile)) {
            TargetVerdict.WAY_BELOW, TargetVerdict.BELOW -> if (band.floorMatters) step else 0
            TargetVerdict.ABOVE, TargetVerdict.WAY_ABOVE -> -step
            TargetVerdict.ON_TARGET -> 0
        }
        if (delta == 0) return

        val base = baseTarget(segment.phase)
        val next = (currentTarget + delta).coerceIn(cycle.minWatts, cycle.maxWatts)
        if (next == currentTarget) return
        // Remember the correction as an offset from the configured watts, so the
        // next bout of the same kind starts where this one ended up.
        if (segment.phase == Phase.FAST) offsetFastW = next - base else offsetSlowW = next - base
        currentTarget = next
        lastAdjustAt = now
        t.setTargetPower(next)
    }

    private fun freshHr(): Int? =
        if (lastHr != null && (clock() - lastHrAt) < STRAP_STALE_MS) lastHr else null

    private fun tick() {
        val elapsed = currentElapsed()
        val dt = elapsed - acc.elapsedSec
        if (dt <= 0) {
            publish()
            return
        }

        val hr = freshHr()
        val segment = plan.segmentAt(elapsed)
        if (segment == null) {
            // Past the end of the plan. segmentAt() is exclusive of endSec, so
            // arriving here with the last second still uncredited is normal —
            // credit it against the final bout before closing the session, or a
            // 30-minute walk reports 29 minutes and four sets out of five.
            val tail = plan.uncreditedSeconds(acc.elapsedSec)
            if (tail > 0) {
                val last = plan.segments.lastOrNull()
                acc.tick(
                    tail,
                    hr,
                    last,
                    last?.takeIf { it.phase != Phase.COOLDOWN }?.let { plan.bandFor(it.phase) },
                )
            }
            endOfPlan()
            return
        }

        val band = plan.bandAt(elapsed) ?: plan.bandFor(segment.phase)
        if (hr == null && lastHrAt != 0L && !strapWasLost) {
            strapWasLost = true
            sayCue(CoachScript.strapLost())
        }

        // Scored against the **settled** band, coached against the ramped one.
        // Scoring on the ramp would mark a fit walker who reaches the target
        // promptly as "way above" for the first minute of every fast bout, and
        // knock 15-30 points off the reported adherence. A planned cool-down is
        // not scored at all — its band is unconditionally on target, so counting
        // it would only inflate the figure.
        val scoringBand = if (segment.phase == Phase.COOLDOWN) null else plan.bandFor(segment.phase)
        acc.tick(dt, hr, segment, scoringBand, powerW = freshPower())
        trackDrift(dt, hr, band)

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
            maybeCoachDrift(segment, elapsed, hr, band)
        }

        if (hr != null) maybeAdjustTrainer(segment, elapsed, hr)

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
        if (plan.isFinished(elapsed)) endOfPlan()
    }

    /**
     * The plan is done. Either close the session now, or keep the strap on and
     * keep counting.
     *
     * People do not stop walking the instant the last bout ends — they carry on
     * for a few minutes while their heart rate comes down, and dropping the
     * connection at that moment throws away real activity. So the engine moves
     * into [EngineStatus.COOLING]: the strap stays connected, minutes and
     * calories keep accruing against the slow bout, and it closes itself out
     * when the heart rate has genuinely settled.
     */
    private fun endOfPlan() {
        if (!coolDownSettings.trackAfterFinish || coolDownSettings.maxSec <= 0) {
            finish(completed = true)
            return
        }
        if (status == EngineStatus.COOLING) return
        status = EngineStatus.COOLING
        coolStartedAtWall = clock()
        coolSec = 0
        coolCalmSec = 0
        coolNoStrapSec = 0
        applyTrainerTarget(Phase.COOLDOWN)
        music?.setTargetCadence(cadenceFor(Phase.COOLDOWN), switchTrack = true)
        sayCue(CoachScript.planComplete(plan.totalSec / 60), urgent = true)
        publish()
    }

    /**
     * One second of cool-down. Credited to the slow bout, since that is what an
     * easy walk after the last interval is, and left out of the on-target
     * accounting entirely — adherence is a statement about the prescribed
     * intervals, and cool-down was not prescribed.
     */
    private fun coolTick() {
        val elapsedWall = ((clock() - coolStartedAtWall) / 1000L).toInt()
        val dt = elapsedWall - coolSec
        if (dt <= 0) {
            publish()
            return
        }
        coolSec = elapsedWall
        val hr = freshHr()

        acc.tick(dt, hr, COOLDOWN_SEGMENT, band = null, powerW = freshPower())

        if (hr == null) {
            coolNoStrapSec += dt
            coolCalmSec = 0
        } else {
            coolNoStrapSec = 0
            val settled = HrZone.forHr(hr, profile).idx <= coolDownSettings.settledZoneIdx
            if (settled) coolCalmSec += dt else coolCalmSec = 0
            if (acc.elapsedSec - lastSampleSec >= 2) {
                lastSampleSec = acc.elapsedSec
                samples += HrSampleEntity(
                    sessionId = 0,
                    tSec = acc.elapsedSec,
                    hr = hr,
                    phase = Phase.COOLDOWN.ordinal,
                )
            }
        }

        publish()

        // Still clearly walking? Then this is not a cool-down winding down, it
        // is more of the walk, and it keeps its strap, its music and its
        // minutes — all inside the same session — up to a generous ceiling.
        val stillWorking = coolDownSettings.extendWhileActive &&
            hr != null && hr > coolDownSettings.activeHrFloor
        if (stillWorking) coolCalmSec = 0

        val ceiling = if (coolDownSettings.extendWhileActive) {
            coolDownSettings.maxExtraSec.coerceAtLeast(coolDownSettings.maxSec)
        } else {
            coolDownSettings.maxSec
        }
        val quietLongEnough = !stillWorking && coolSec >= coolDownSettings.maxSec

        val done = coolSec >= ceiling ||
            (coolCalmSec >= coolDownSettings.settledSec && !stillWorking) ||
            quietLongEnough ||
            coolNoStrapSec >= COOLDOWN_STRAP_GIVEUP_SEC
        if (done) finish(completed = true)
    }

    /**
     * Heart-rate correction, deliberately reluctant. Four separate brakes: the
     * bout must have had time to settle, the band it is judged against is the
     * ramped one so a heart still on its way up is not "under", the drift must
     * have persisted, and corrections are spaced well apart. On recovery bouts a
     * low heart rate resolves to ON_TARGET upstream, so it never reaches here.
     */
    private fun maybeCoachDrift(segment: Segment, elapsed: Int, hr: Int, band: TargetBand) {
        if (elapsed - segment.startSec < coachSettings.boutSettleSec) return
        val verdict = band.verdict(hr, profile)
        if (verdict == TargetVerdict.ON_TARGET) return
        if (driftVerdict != verdict || driftStreakSec < coachSettings.driftGraceSec) return

        val now = clock()
        val repeatGapMs = (coachSettings.minCueGapSec * 2).coerceAtLeast(60) * 1000L
        if (now - lastDriftSpokenAt < repeatGapMs) return

        // Quote the bout's real range, not the mid-transition one: "aim for
        // 96 to 108" while the band is still sliding would be confusing.
        val settled = plan.bandFor(segment.phase)
        val text = CoachScript.drift(
            verdict,
            segment.phase,
            settled.lowBpm(profile),
            settled.highBpm(profile),
            mode,
        )
        if (text.isBlank()) return
        lastDriftSpokenAt = now
        driftStreakSec = 0
        sayCue(text)
    }

    private fun targetText(): String? = when (cycle.controlMode) {
        TrainerControlMode.POWER -> "$currentTarget watts"
        TrainerControlMode.RESISTANCE -> "resistance $currentTarget"
        TrainerControlMode.NONE -> null
    }

    /** One second of "how long has the heart been off the ramped band". */
    private fun trackDrift(dt: Int, hr: Int?, band: TargetBand) {
        if (hr == null) {
            driftVerdict = TargetVerdict.ON_TARGET
            driftStreakSec = 0
            return
        }
        val verdict = band.verdict(hr, profile)
        if (verdict == driftVerdict) driftStreakSec += dt else {
            driftVerdict = verdict
            driftStreakSec = dt
        }
    }

    private fun enterSegment(segment: Segment?, announce: Boolean) {
        if (segment == null) return
        currentSegmentIndex = segment.index
        countdownSpokenFor = -1
        val band = plan.bandFor(segment.phase)
        applyTrainerTarget(segment.phase)
        if (announce) {
            val controlling = mode == WorkoutMode.CYCLE && hasControl &&
                cycle.controlMode != TrainerControlMode.NONE
            sayCue(
                CoachScript.phaseChange(
                    phase = segment.phase,
                    setNumber = segment.setNumber,
                    totalSets = protocol.sets,
                    lowBpm = band.lowBpm(profile),
                    highBpm = band.highBpm(profile),
                    mode = mode,
                    targetText = if (controlling) targetText() else null,
                    manualResistance = mode == WorkoutMode.CYCLE && !controlling,
                ),
                urgent = true,
            )
        }
        music?.setTargetCadence(cadenceFor(segment.phase), switchTrack = true)
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
            val tail = plan.uncreditedSeconds(acc.elapsedSec - coolSec)
            if (tail > 0) acc.skipAhead(tail)
        }

        val elapsed = acc.elapsedSec
        status = EngineStatus.FINISHED
        tickJob?.cancel()
        watchers.forEach { it.cancel() }
        watchers.clear()
        source.stop()
        location?.stop()
        // Let the flywheel go rather than leaving it pinned at the last target.
        trainer?.stop()
        music?.pause()

        val completedSets = plan.completedSets(elapsed)

        val result = SessionResult(
            startedAt = startedAt,
            endedAt = clock(),
            plannedSec = plan.totalSec,
            elapsedSec = elapsed,
            coolDownSec = coolSec,
            avgHr = acc.avgHr,
            maxHr = acc.maxHr,
            kcal = acc.kcalRounded(),
            zoneSeconds = acc.secondsInZone.toList(),
            onTargetSec = acc.secondsOnTarget,
            offTargetSec = acc.secondsOffTarget,
            completedSets = completedSets,
            plannedSets = protocol.sets,
            completed = completed,
            protocolLabel = protocol.label(profile.intensityModel),
            mode = mode,
            samples = samples.toList(),
        )
        publish()
        scope.launch { onFinished(result) }
    }

    private fun publish() {
        val cooling = status == EngineStatus.COOLING
        val elapsed = if (status == EngineStatus.FINISHED || cooling) acc.elapsedSec else currentElapsed()
        val planElapsed = (elapsed - coolSec).coerceAtLeast(0)
        val segment = if (cooling) COOLDOWN_SEGMENT else plan.segmentAt(planElapsed)
        val band = when {
            cooling -> protocol.targetBand(Phase.COOLDOWN, profile.intensityModel)
            else -> plan.bandAt(planElapsed)
        }
        val hr = freshHr()
        val model = profile.intensityModel

        _state.value = WorkoutUiState(
            status = status,
            elapsedSec = elapsed,
            totalSec = plan.totalSec,
            segment = segment,
            phase = segment?.phase,
            setNumber = if (cooling) protocol.sets else segment?.setNumber ?: 0,
            totalSets = protocol.sets,
            segmentRemainingSec = if (cooling) 0 else plan.remainingInSegment(planElapsed),
            segmentElapsedSec = if (cooling) coolSec else plan.elapsedInSegment(planElapsed),
            segmentTotalSec = if (cooling) coolDownSettings.maxSec else segment?.durationSec ?: 0,
            segmentProgress = plan.segmentProgress(planElapsed).toFloat(),
            sessionProgress = plan.overallProgress(planElapsed).toFloat(),
            nextPhase = if (cooling) null else plan.nextSegmentAfter(planElapsed)?.phase,
            planElapsedSec = planElapsed,
            coolDownSec = coolSec,
            interrupted = interrupted,
            hr = hr,
            zone = hr?.let { HrZone.forHr(it, profile) },
            gaugeFraction = hr?.let { HrZone.gaugeFraction(it, profile).toFloat() } ?: 0f,
            targetAimBpm = band?.aimBpm(profile) ?: 0,
            targetLowBpm = band?.lowBpm(profile) ?: 0,
            targetHighBpm = band?.highBpm(profile) ?: 0,
            targetLowFraction = band?.let { HrZone.gaugeFractionForPct(it.lowPct, model).toFloat() } ?: 0f,
            targetHighFraction = band?.let { HrZone.gaugeFractionForPct(it.highPct, model).toFloat() } ?: 0f,
            verdict = if (hr != null && band != null) band.verdict(hr, profile) else null,
            percentOfTarget = if (hr != null && band != null) band.percentOfTarget(hr, profile) else 0,
            kcal = acc.kcalRounded(),
            avgHr = acc.avgHr,
            maxHr = acc.maxHr,
            secondsInZone = acc.secondsInZone.toList(),
            adherencePercent = acc.adherencePercent,
            hrmState = hrmState,
            contact = contact,
            strapStale = hr == null && lastHrAt != 0L,
            nowPlaying = nowPlaying,
            musicStatus = musicStatus,
            targetCadenceSpm = if (cooling) {
                cadenceFor(Phase.COOLDOWN)
            } else {
                segment?.let { cadenceFor(it.phase) } ?: 0
            },
            mode = mode,
            powerW = freshPower() ?: 0,
            cadenceRpm = freshCadence() ?: 0,
            avgPowerW = acc.avgPowerW,
            workKj = acc.workKj,
            trainerTarget = currentTarget,
            resistanceLevel = lastResistance ?: 0,
            controlMode = cycle.controlMode,
            trainerConnected = trainerState is TrainerState.Connected,
            trainerControlling = hasControl,
            shape = shape,
            zoneBands = zoneBands,
            distanceMeters = distance.totalMeters,
            // A speed from a fix ten seconds ago is not a current speed.
            currentSpeedMps = if (lastFixAt != 0L && clock() - lastFixAt < 10_000) lastFixSpeedMps else 0.0,
            avgSpeedMps = Pace.averageSpeedMps(distance.totalMeters, elapsed),
            gpsTracking = location?.isTracking == true,
            gpsFixes = distance.acceptedFixes,
        )
    }

    private companion object {
        /**
         * Stand-in segment for the post-session cool-down. Phase SLOW rather
         * than COOLDOWN on purpose: the calorie model and the zone accounting
         * should treat these minutes as the easy walking they are, which is also
         * where the user asked for them to land.
         */
        val COOLDOWN_SEGMENT = Segment(
            index = -1,
            phase = Phase.SLOW,
            setNumber = 0,
            startSec = 0,
            durationSec = 0,
            targetPct = 0.0,
            cadenceSpm = 0,
        )
    }
}
