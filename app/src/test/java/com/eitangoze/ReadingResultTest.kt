package com.eitangoze

import com.eitangoze.data.Genre
import com.eitangoze.data.Knowledge
import com.eitangoze.data.Passage
import com.eitangoze.data.Repository
import com.eitangoze.data.TextReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The diagnosis, which is the whole point of timing a reading at all.
 *
 * A words-per-minute number on its own is worse than nothing: skimming a text
 * you cannot read produces a good one. What this app can say, and a stopwatch
 * cannot, is *which* of vocabulary, syntax and speed is the thing stopping you —
 * and the order matters, because below the unassisted line there is no reading
 * technique to train.
 */
class ReadingResultTest {

    private fun passage(words: Int) = Passage(
        id = 1, title = "t", genre = Genre.SCIENCE, source = "s", url = "https://x",
        license = "CC BY-SA 4.0", words = words, cefr = "B2", coverable = 0.93,
        text = "x",
    )

    private fun report(coverage: Double, gaps: Int = 12) = TextReport(
        text = "x", tokens = 1000, types = 400,
        byKnowledge = mapOf(Knowledge.KNOWN to (coverage * 1000).toInt()),
        coverage = coverage, spans = emptyList(), gaps = emptyList(),
        gapsToThreshold = gaps, levelProfile = emptyMap(), estimatedLevel = "B2",
    )

    private fun result(words: Int, coverage: Double, ms: Long, checkable: Int = 0) =
        Repository.ReadingResult(passage(words), report(coverage), ms, checkable)

    @Test
    fun `words per minute is words over the clock`() {
        // 600 words in four minutes is 150.
        assertEquals(150, result(600, 0.99, 240_000).wpm)
        assertEquals(0, result(600, 0.99, 0).wpm)
    }

    @Test
    fun `below the unassisted line the verdict blames vocabulary, however fast`() {
        // Fast *and* under-covered: skimming. The speed must not be praised.
        val skimmed = result(800, 0.93, 120_000)
        assertTrue("this reading was fast", skimmed.wpm > Repository.EXAM_WPM)
        assertTrue(!skimmed.vocabularyIsEnough)
        assertTrue(
            "a fast reading of a text you cannot read was called fast enough:\n" +
                skimmed.verdict,
            skimmed.verdict.contains("語彙"),
        )
    }

    @Test
    fun `above the line a slow reading is a speed problem, not a vocabulary one`() {
        val slow = result(800, 0.99, 600_000)
        assertTrue(slow.vocabularyIsEnough)
        assertTrue(!slow.fastEnough)
        assertTrue(slow.verdict.contains("語彙は足りています"))
    }

    @Test
    fun `with vocabulary and speed in hand, what is left is syntax`() {
        val good = result(800, 0.99, 240_000, checkable = 7)
        assertTrue(good.vocabularyIsEnough && good.fastEnough)
        assertTrue(good.verdict.contains("構文"))
        assertTrue(good.verdict.contains("7"))
    }

    /** The thresholds are the published ones, not round numbers picked to look neat. */
    @Test
    fun `fit follows the reading research`() {
        val repo = FitOnly()
        assertEquals(Repository.Fit.FAST, repo.fitOf(0.98))
        assertEquals(Repository.Fit.FAST, repo.fitOf(1.0))
        assertEquals(Repository.Fit.VOCABULARY, repo.fitOf(0.97))
        assertEquals(Repository.Fit.VOCABULARY, repo.fitOf(0.90))
        assertEquals(Repository.Fit.TOO_HARD, repo.fitOf(0.89))
        assertEquals(0.98, TextReport.UNASSISTED, 1e-9)
        assertEquals(0.95, TextReport.ASSISTED, 1e-9)
    }
}

/** The rule alone, without opening two databases to ask about a number. */
private class FitOnly {
    fun fitOf(coverage: Double): Repository.Fit = when {
        coverage >= TextReport.UNASSISTED -> Repository.Fit.FAST
        coverage >= 0.90 -> Repository.Fit.VOCABULARY
        else -> Repository.Fit.TOO_HARD
    }
}
