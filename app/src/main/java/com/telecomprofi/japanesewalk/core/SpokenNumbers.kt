package com.telecomprofi.japanesewalk.core

/**
 * Numbers as words, for the voice coach.
 *
 * "Set 5 of 5" is read by some speech engines as "five oh five" — the digits
 * run together into one number and the cue becomes gibberish at exactly the
 * moment it matters. Writing the words out removes the engine's chance to
 * guess: "Fifth set of five" is unambiguous in every voice, and it is how a
 * person would say it anyway.
 *
 * Only small numbers are needed — a session has a handful of sets — so this
 * stops where usefulness does and falls back to the digits beyond that.
 */
object SpokenNumbers {

    private val CARDINALS = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight",
        "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
        "sixteen", "seventeen", "eighteen", "nineteen", "twenty",
    )

    private val ORDINALS = listOf(
        "zeroth", "first", "second", "third", "fourth", "fifth", "sixth",
        "seventh", "eighth", "ninth", "tenth", "eleventh", "twelfth",
        "thirteenth", "fourteenth", "fifteenth", "sixteenth", "seventeenth",
        "eighteenth", "nineteenth", "twentieth",
    )

    /** "five". Beyond twenty the digits are clearer than the words anyway. */
    fun cardinal(n: Int): String = CARDINALS.getOrElse(n) { n.toString() }

    /** "fifth", and "21st" style beyond the table. */
    fun ordinal(n: Int): String = ORDINALS.getOrElse(n) { "${n}${suffix(n)}" }

    /** Capitalised, for the start of a sentence. */
    fun ordinalSentence(n: Int): String = ordinal(n).replaceFirstChar { it.uppercase() }

    private fun suffix(n: Int): String = when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }

    /**
     * "Third set of five" — the way the position in a session is said aloud.
     * Falls back to a plain phrasing when the figures are not yet known.
     */
    fun setOf(setNumber: Int, totalSets: Int): String = when {
        setNumber <= 0 || totalSets <= 0 -> "set"
        else -> "${ordinal(setNumber)} set of ${cardinal(totalSets)}"
    }
}
