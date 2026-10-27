package com.telecomprofi.japanesewalk

import com.telecomprofi.japanesewalk.core.Phase
import com.telecomprofi.japanesewalk.core.SpokenNumbers
import com.telecomprofi.japanesewalk.workout.CoachScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Set 5 of 5" came out of the speech engine as "five oh five". Digits next to
 * digits are an invitation for the engine to guess, and it guesses wrong at
 * exactly the moment the cue matters.
 */
class SpokenNumbersTest {

    @Test
    fun `sets are counted in words, in order`() {
        assertEquals("first", SpokenNumbers.ordinal(1))
        assertEquals("second", SpokenNumbers.ordinal(2))
        assertEquals("third", SpokenNumbers.ordinal(3))
        assertEquals("fourth", SpokenNumbers.ordinal(4))
        assertEquals("fifth", SpokenNumbers.ordinal(5))
        assertEquals("twelfth", SpokenNumbers.ordinal(12))
    }

    @Test
    fun `a sentence starts with a capital`() {
        assertEquals("Third", SpokenNumbers.ordinalSentence(3))
        assertEquals("First", SpokenNumbers.ordinalSentence(1))
    }

    @Test
    fun `beyond the table it falls back to figures, correctly suffixed`() {
        assertEquals("21st", SpokenNumbers.ordinal(21))
        assertEquals("22nd", SpokenNumbers.ordinal(22))
        assertEquals("23rd", SpokenNumbers.ordinal(23))
        assertEquals("24th", SpokenNumbers.ordinal(24))
        // The teens are the exception every naive implementation gets wrong.
        assertEquals("111th", SpokenNumbers.ordinal(111))
        assertEquals("112th", SpokenNumbers.ordinal(112))
        assertEquals("113th", SpokenNumbers.ordinal(113))
    }

    @Test
    fun `totals are words too`() {
        assertEquals("five", SpokenNumbers.cardinal(5))
        assertEquals("ten", SpokenNumbers.cardinal(10))
        assertEquals("30", SpokenNumbers.cardinal(30))
    }

    @Test
    fun `the phrase reads the way a person would say it`() {
        assertEquals("third set of five", SpokenNumbers.setOf(3, 5))
        assertEquals("first set of four", SpokenNumbers.setOf(1, 4))
    }

    @Test
    fun `an unknown position does not produce nonsense`() {
        assertEquals("set", SpokenNumbers.setOf(0, 5))
        assertEquals("set", SpokenNumbers.setOf(3, 0))
    }

    @Test
    fun `no cue puts two numbers next to each other any more`() {
        for (set in 1..5) {
            for (phase in listOf(Phase.FAST, Phase.SLOW)) {
                val line = CoachScript.phaseChange(
                    phase = phase,
                    setNumber = set,
                    totalSets = 5,
                    lowBpm = 118,
                    highBpm = 135,
                )
                assertFalse(
                    "\"$line\" still has digits running together",
                    Regex("""\d+\s+(of|OF)\s+\d+""").containsMatchIn(line),
                )
                // The brisk cue starts the sentence with the ordinal, so it
                // arrives capitalised — "Fifth set of five".
                val spoken = line.lowercase()
                assertTrue(line, spoken.contains(SpokenNumbers.ordinal(set)))
                assertTrue(line, spoken.contains("set of five"))
            }
        }
    }

    @Test
    fun `the heart-rate figures are still spoken as numbers`() {
        // Words are for positions in a sequence; a heart rate is a quantity and
        // "one hundred and eighteen" would be worse, not better.
        val line = CoachScript.phaseChange(Phase.FAST, 2, 5, 118, 135)
        assertTrue(line.contains("118"))
        assertTrue(line.contains("135"))
    }
}
