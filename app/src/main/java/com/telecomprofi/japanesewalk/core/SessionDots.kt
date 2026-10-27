package com.telecomprofi.japanesewalk.core

import kotlin.math.ceil

/** One circle in the week's session row. */
enum class SessionDot { DONE, PARTIAL, PLANNED }

/**
 * A circle plus, for the ones already filled, what kind of session filled it —
 * so a week of three walks and two rides reads as such at a glance rather than
 * as five identical ticks.
 */
data class SessionCircle(val state: SessionDot, val mode: WorkoutMode? = null)

object SessionDots {

    /** Hard cap: more than this will not fit a phone width at a legible size. */
    const val MAX = 7

    /**
     * The week at a glance: one circle per session the weekly target implies,
     * filled in as they are done. Extra sessions beyond the target still get a
     * circle — doing more than planned should show up, not be hidden.
     */
    fun of(
        completed: Int,
        partial: Int,
        targetMinutes: Int,
        sessionMinutes: Int,
    ): List<SessionDot> {
        val done = completed.coerceAtLeast(0)
        val part = partial.coerceAtLeast(0)
        val planned = if (sessionMinutes <= 0) {
            done + part
        } else {
            ceil(targetMinutes.toDouble() / sessionMinutes).toInt()
        }
        val total = maxOf(planned, done + part).coerceAtMost(MAX)
        return buildList {
            repeat(minOf(done, total)) { add(SessionDot.DONE) }
            repeat(minOf(part, (total - size).coerceAtLeast(0))) { add(SessionDot.PARTIAL) }
            repeat((total - size).coerceAtLeast(0)) { add(SessionDot.PLANNED) }
        }
    }

    /**
     * The same row, with the completed circles labelled by mode.
     *
     * [completedModes] is in the order the sessions happened, oldest first, so
     * the row reads left to right the way the week did.
     */
    fun withModes(
        completedModes: List<WorkoutMode>,
        partial: Int,
        targetMinutes: Int,
        sessionMinutes: Int,
    ): List<SessionCircle> {
        val states = of(completedModes.size, partial, targetMinutes, sessionMinutes)
        var done = 0
        return states.map { state ->
            if (state == SessionDot.DONE) {
                SessionCircle(state, completedModes.getOrNull(done++))
            } else {
                SessionCircle(state)
            }
        }
    }
}
