package com.telecomprofi.japanesewalk.core

import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * One day's training, reduced to a single number.
 *
 * @param trimp Banister's training impulse: minutes weighted by how hard the
 *        heart was working. An hour of easy walking and twenty minutes of brisk
 *        intervals are not the same training, and minutes alone cannot tell
 *        them apart.
 */
data class DayLoad(val date: LocalDate, val trimp: Double, val minutes: Int, val sessions: Int) {
    val trained: Boolean get() = sessions > 0
}

/** What the model thinks you should do today. */
enum class TrainingVerdict(val label: String, val advice: String) {
    RESTING(
        "Resting",
        "No training in the last few days. A rest week is fine; two in a row and fitness starts to slide.",
    ),
    UNDER(
        "Under the target",
        "Fewer than four half-hour walks this week. The protocol's benefits come from the weekly dose, not from any one walk.",
    ),
    ON_TRACK(
        "On track",
        "Four or more sessions and enough rest. This is the pattern the protocol was tested on.",
    ),
    BUILDING(
        "Building",
        "Load is climbing faster than your base. Good, as long as it does not continue for more than a week or two.",
    ),
    TIRED(
        "Tired",
        "Fatigue is well above your base. Expect brisk bouts to feel harder than the numbers say they should.",
    ),
    OVERREACHING(
        "Overreaching",
        "Load has run far ahead of what you are conditioned for, which is where niggles and illness come from. Take a day or two off.",
    ),
    NEEDS_REST(
        "Rest day due",
        "Several days back to back without one. Two rest days a week is part of the prescription, not a failure of it.",
    ),
}

/**
 * Fitness, fatigue and freshness, the way every endurance platform models them.
 *
 * Two exponential averages of daily training impulse: a slow one over six weeks
 * that stands for the condition you have built, and a fast one over a week that
 * stands for the tiredness you are carrying. Their difference is the useful
 * number — positive means rested, negative means you are digging into reserves.
 *
 * It is a crude model of a complicated thing. It knows nothing about sleep,
 * illness, work, or the fact that a walk in the heat costs more than the same
 * walk in the cold. Treated as a rough weekly steer it is genuinely useful;
 * treated as a verdict on a single day it is pretend precision, which is why
 * the app shows a sentence rather than a score out of a hundred.
 */
data class TrainingStatus(
    /** Six-week average load: what you are conditioned for. */
    val fitness: Double = 0.0,
    /** One-week average load: what you are carrying right now. */
    val fatigue: Double = 0.0,
    /** Sessions of at least [QUALIFYING_MINUTES] in the last seven days. */
    val weeklySessions: Int = 0,
    val weeklyMinutes: Int = 0,
    /** Days in the last seven with nothing recorded. */
    val restDays: Int = 0,
    /** Training days ending today (or yesterday, if today is still empty). */
    val consecutiveDays: Int = 0,
    /**
     * This week's training against the last four weeks of it. One means you are
     * doing what you have been doing; much above 1.3 is the ratio sports
     * medicine associates with turning up injured.
     *
     * Deliberately not fatigue ÷ fitness, which looks like the same idea and is
     * not: those two averages have different time constants, so even perfectly
     * steady training reads about 1.4 on them, and the app would nag someone
     * who is doing exactly the right thing. On matched averages every steady
     * pattern reads 1.00 and only a real jump moves it.
     */
    val loadRatio: Double = 0.0,
    val verdict: TrainingVerdict = TrainingVerdict.RESTING,
    /** False until there is enough history for the ratio to mean anything. */
    val ratioTrusted: Boolean = false,
    /** The last fortnight, oldest first, for the chart. */
    val recent: List<DayLoad> = emptyList(),
) {
    /** Positive is rested, negative is tired. Known everywhere as form. */
    val freshness: Double get() = fitness - fatigue

    val restDaysShort: Int get() = (TrainingLoad.REST_DAYS_TARGET - restDays).coerceAtLeast(0)
    val sessionsShort: Int get() = (TrainingLoad.SESSIONS_TARGET - weeklySessions).coerceAtLeast(0)
    val onTarget: Boolean get() = weeklySessions >= TrainingLoad.SESSIONS_TARGET &&
        restDays >= TrainingLoad.REST_DAYS_TARGET

    /** Should today be a day off? */
    val restToday: Boolean
        get() = verdict == TrainingVerdict.NEEDS_REST || verdict == TrainingVerdict.OVERREACHING
}

object TrainingLoad {

    /** The protocol's weekly dose: four half-hour walks. */
    const val SESSIONS_TARGET = 4
    const val QUALIFYING_MINUTES = 30

    /** And at least two days a week with nothing. */
    const val REST_DAYS_TARGET = 2

    /** Time constants, in days, for the two averages. */
    const val FITNESS_DAYS = 42.0
    const val FATIGUE_DAYS = 7.0

    /** Three days back to back is where the model starts asking for a day off. */
    const val MAX_CONSECUTIVE_DAYS = 3

    /** The two windows the load ratio compares, in days. */
    const val ACUTE_DAYS = 7
    const val CHRONIC_DAYS = 28

    /**
     * Days of actual training needed in the chronic window before the ratio is
     * believed. Without this, somebody's first good week reads 4.0 — four days
     * of training against a month of nothing — and the app tells a beginner
     * doing everything right that they are overreaching.
     */
    const val MIN_HISTORY_DAYS = 12

    /**
     * Banister's TRIMP: minutes, weighted by heart-rate reserve through an
     * exponential that reflects how disproportionately hard the top of the
     * range is. The constants are his; the sexes differ because the lactate
     * curves they were fitted to do.
     */
    fun trimp(minutes: Double, avgHr: Int, restingHr: Int, maxHr: Int, isMale: Boolean): Double {
        if (minutes <= 0.0) return 0.0
        val reserve = (maxHr - restingHr).coerceAtLeast(1)
        val hrr = ((avgHr - restingHr).toDouble() / reserve).coerceIn(0.0, 1.0)
        // No strap, or a reading too low to be real: credit the walk at an easy
        // pace rather than at nothing, since the minutes certainly happened.
        if (avgHr <= 0 || hrr <= 0.0) return minutes * 0.45
        val k = if (isMale) 0.64 * exp(1.92 * hrr) else 0.86 * exp(1.67 * hrr)
        return minutes * hrr * k
    }

    /**
     * Roll the daily loads into the two averages.
     *
     * Every day in the window contributes, including the empty ones — that is
     * the whole point of an exponential average: rest decays fatigue faster
     * than it decays fitness, which is why a few days off leave you fresher
     * without leaving you unfit.
     */
    fun averages(days: List<DayLoad>): Pair<Double, Double> {
        var fitness = 0.0
        var fatigue = 0.0
        for (day in days) {
            fitness += (day.trimp - fitness) / FITNESS_DAYS
            fatigue += (day.trimp - fatigue) / FATIGUE_DAYS
        }
        return fitness to fatigue
    }

    /**
     * Fill in the days nothing was recorded, so the averages see the gaps.
     * [days] may be sparse and in any order; the result is one entry per day
     * from [from] to [to] inclusive, oldest first.
     */
    fun fill(days: List<DayLoad>, from: LocalDate, to: LocalDate): List<DayLoad> {
        val byDate = days.groupBy { it.date }
        val out = ArrayList<DayLoad>()
        var cursor = from
        while (!cursor.isAfter(to)) {
            val same = byDate[cursor]
            out += if (same == null) {
                DayLoad(cursor, 0.0, 0, 0)
            } else {
                DayLoad(
                    date = cursor,
                    trimp = same.sumOf { it.trimp },
                    minutes = same.sumOf { it.minutes },
                    sessions = same.sumOf { it.sessions },
                )
            }
            cursor = cursor.plusDays(1)
        }
        return out
    }

    /**
     * How many days in a row have been trained, counting back from today. Today
     * being empty does not break a streak — it is not over until it is slept
     * through — so the count runs back from yesterday in that case.
     */
    fun consecutiveTrainingDays(days: List<DayLoad>): Int {
        if (days.isEmpty()) return 0
        var i = days.lastIndex
        if (!days[i].trained) i--          // today, still empty
        var streak = 0
        while (i >= 0 && days[i].trained) {
            streak++
            i--
        }
        return streak
    }

    /**
     * The whole picture, from one window of daily loads.
     *
     * The order of the tests is the order a coach would apply them: an
     * unsustainable ratio first, then days without a break, then whether the
     * weekly dose is being met at all.
     */
    fun status(days: List<DayLoad>): TrainingStatus {
        if (days.isEmpty()) return TrainingStatus()
        val (fitness, fatigue) = averages(days)
        val week = days.takeLast(7)
        val qualifying = week.count { it.minutes >= QUALIFYING_MINUTES }
        val weeklyMinutes = week.sumOf { it.minutes }
        val trainedThisWeek = week.count { it.trained }
        val restDays = week.count { !it.trained }
        val consecutive = consecutiveTrainingDays(days)
        val ratio = loadRatio(days)
        val trusted = hasHistory(days)

        // The order a coach would apply them: an unsustainable jump first, then
        // days without a break, then whether the weekly dose is being met.
        val verdict = when {
            trainedThisWeek == 0 -> TrainingVerdict.RESTING
            trusted && ratio >= 1.5 -> TrainingVerdict.OVERREACHING
            consecutive >= MAX_CONSECUTIVE_DAYS || restDays < REST_DAYS_TARGET ->
                TrainingVerdict.NEEDS_REST
            trusted && ratio >= 1.3 -> TrainingVerdict.TIRED
            qualifying < SESSIONS_TARGET -> TrainingVerdict.UNDER
            trusted && ratio >= 1.1 -> TrainingVerdict.BUILDING
            else -> TrainingVerdict.ON_TRACK
        }

        return TrainingStatus(
            fitness = fitness,
            fatigue = fatigue,
            loadRatio = ratio,
            weeklySessions = qualifying,
            weeklyMinutes = weeklyMinutes,
            restDays = restDays,
            consecutiveDays = consecutive,
            verdict = verdict,
            ratioTrusted = trusted,
            recent = days.takeLast(14),
        )
    }

    /**
     * This week's mean daily load against the last four weeks' — the acute to
     * chronic workload ratio, on matched averages so that steady training
     * reads one.
     */
    fun loadRatio(days: List<DayLoad>): Double {
        if (days.size < ACUTE_DAYS) return 0.0
        val acute = days.takeLast(ACUTE_DAYS).sumOf { it.trimp } / ACUTE_DAYS
        val window = days.takeLast(CHRONIC_DAYS)
        val chronic = window.sumOf { it.trimp } / window.size
        return if (chronic <= 0.0) 0.0 else acute / chronic
    }

    /** Enough days with training behind you for the ratio to mean something. */
    fun hasHistory(days: List<DayLoad>): Boolean =
        days.takeLast(CHRONIC_DAYS).count { it.trained } >= MIN_HISTORY_DAYS

    /** For the gauge: fitness and fatigue as a share of the fortnight's peak. */
    fun scaleFor(status: TrainingStatus): Double =
        maxOf(status.fitness, status.fatigue, 1.0)

    fun round(value: Double): Int = value.roundToInt()
}
