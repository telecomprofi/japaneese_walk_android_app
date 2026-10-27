package com.telecomprofi.japanesewalk.core

/**
 * Heart-rate based energy expenditure.
 *
 * Primary model is Keytel et al. (2005), which predicts energy expenditure from
 * heart rate, body mass, age and sex — the right choice here because a chest
 * strap is already streaming HR. When no HR is available (strap dropped out)
 * the estimate falls back to a walking MET value so the session total keeps
 * advancing instead of stalling.
 */
object Calories {

    private const val KJ_PER_KCAL = 4.184

    /** kcal per minute at a given heart rate. Never negative. */
    fun kcalPerMinuteFromHr(hr: Int, p: UserProfile): Double {
        val kjPerMin = if (p.isMale) {
            -55.0969 + 0.6309 * hr + 0.1988 * p.weightKg + 0.2017 * p.ageYears
        } else {
            -20.4022 + 0.4472 * hr - 0.1263 * p.weightKg + 0.0740 * p.ageYears
        }
        return (kjPerMin / KJ_PER_KCAL).coerceAtLeast(0.0)
    }

    /** kcal per minute from a MET value (fallback when HR is unknown). */
    fun kcalPerMinuteFromMet(met: Double, p: UserProfile): Double =
        met * 3.5 * p.weightKg / 200.0

    /** Typical MET cost of the two IWT phases. */
    fun metForPhase(phase: Phase): Double = when (phase) {
        Phase.FAST -> 5.0
        Phase.SLOW -> 3.0
        Phase.WARMUP -> 3.0
        Phase.COOLDOWN -> 2.8
    }

    /**
     * Gross energy cost of pedalling, from mechanical work.
     *
     * A trainer reports the watts actually delivered to the flywheel. Human
     * gross efficiency on a bike is close to 22.5%, so the body spends roughly
     * 4.4 times the mechanical energy it puts out — which, once the kJ-to-kcal
     * conversion is applied, lands almost exactly on "kcal ≈ kJ of work". That
     * coincidence is why power meters quote the two interchangeably.
     *
     * More accurate than the heart-rate model on a bike, because it measures the
     * work rather than inferring it from the response to work.
     */
    const val CYCLING_EFFICIENCY = 0.225

    fun kcalFromPower(watts: Int, seconds: Double): Double {
        if (watts <= 0 || seconds <= 0) return 0.0
        val kJ = watts * seconds / 1000.0
        return kJ / KJ_PER_KCAL / CYCLING_EFFICIENCY
    }

    fun kcalForInterval(hr: Int?, phase: Phase, seconds: Double, p: UserProfile): Double {
        val perMin = if (hr != null && hr > 30) {
            kcalPerMinuteFromHr(hr, p)
        } else {
            kcalPerMinuteFromMet(metForPhase(phase), p)
        }
        return perMin * seconds / 60.0
    }
}
