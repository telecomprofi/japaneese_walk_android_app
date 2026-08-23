package com.telecomprofi.japanesewalk.game

import kotlin.math.ceil
import kotlin.math.roundToInt

data class Reminder(val title: String, val body: String)

/**
 * Text for the daily reminder about the weekly walking target.
 *
 * Plain and factual on purpose: it states where the week stands and what would
 * finish it, with no scores, streaks or congratulation.
 */
object ReminderCopy {

    fun forWeek(
        weekMinutes: Int,
        targetMinutes: Int,
        daysLeftInWeek: Int,
        sessionMinutes: Int,
        walkedToday: Boolean,
    ): Reminder {
        val remaining = (targetMinutes - weekMinutes).coerceAtLeast(0)
        val pct = if (targetMinutes == 0) 100 else ((weekMinutes * 100.0) / targetMinutes).roundToInt()
        val sessionsLeft = if (sessionMinutes <= 0) 0 else ceil(remaining.toDouble() / sessionMinutes).toInt()

        if (remaining == 0) {
            return Reminder(
                title = "$weekMinutes of $targetMinutes min this week",
                body = "Weekly target reached with $daysLeftInWeek day${plural(daysLeftInWeek)} of the week left.",
            )
        }

        val body = when {
            walkedToday && sessionsLeft <= 1 -> "One more walk completes the week."
            walkedToday -> "$sessionsLeft more walk${plural(sessionsLeft)} to reach $targetMinutes minutes."
            daysLeftInWeek == 1 ->
                "Last day of the week. $remaining min still to go."
            sessionsLeft > daysLeftInWeek ->
                "$remaining min left, $daysLeftInWeek day${plural(daysLeftInWeek)} remaining."
            else ->
                "$sessionsLeft walk${plural(sessionsLeft)} left, $daysLeftInWeek day${plural(daysLeftInWeek)} to do them."
        }
        return Reminder("$weekMinutes of $targetMinutes min this week ($pct%)", body)
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"
}
