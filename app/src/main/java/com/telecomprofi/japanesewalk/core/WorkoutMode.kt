package com.telecomprofi.japanesewalk.core

/**
 * What the session is. The interval structure is identical either way — five
 * sets of three minutes hard and three minutes easy, judged by heart rate —
 * only the thing you change to get there differs: your pace on foot, the
 * trainer's resistance on a bike.
 *
 * Everything user-facing that says "slow" or "fast" reads its wording from here,
 * so a cycling session never tells you to walk faster.
 */
enum class WorkoutMode(
    val label: String,
    val easyLabel: String,
    val hardLabel: String,
    val easySpoken: String,
    val hardSpoken: String,
    val cadenceUnit: String,
    /** Walking outdoors is the only mode that has anything to measure with GPS. */
    val usesGps: Boolean,
) {
    WALK(
        label = "Walking",
        easyLabel = "SLOW",
        hardLabel = "FAST",
        easySpoken = "Ease off. Slow walk",
        hardSpoken = "Fast interval",
        cadenceUnit = "steps/min",
        usesGps = true,
    ),
    CYCLE(
        label = "Indoor cycling",
        easyLabel = "EASY",
        hardLabel = "HARD",
        easySpoken = "Ease off. Spin easy",
        hardSpoken = "Hard effort",
        cadenceUnit = "rpm",
        usesGps = false,
    ),
    ;

    fun label(phase: Phase): String = when (phase) {
        Phase.FAST -> hardLabel
        Phase.SLOW -> easyLabel
        Phase.WARMUP -> "WARM-UP"
        Phase.COOLDOWN -> "COOL-DOWN"
    }
}

/** How the app asks a smart trainer for more or less effort. */
enum class TrainerControlMode(val label: String, val blurb: String) {
    POWER(
        "Target power (ERG)",
        "The trainer holds the watts you ask for whatever your cadence. What a Kickr does best, " +
            "and what makes the intervals identical every session.",
    ),
    RESISTANCE(
        "Resistance level",
        "A fixed brake setting. How hard it feels then depends on how fast you pedal, which is " +
            "closer to riding outdoors but less repeatable.",
    ),
    NONE(
        "Coach only",
        "The app announces each change and leaves the trainer alone.",
    ),
}
