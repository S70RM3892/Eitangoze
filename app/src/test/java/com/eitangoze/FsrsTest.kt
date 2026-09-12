package com.eitangoze

import com.eitangoze.srs.CardPhase
import com.eitangoze.srs.FsrsScheduler
import com.eitangoze.srs.Rating
import com.eitangoze.srs.SrsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-checks against values computed directly from the FSRS-6 equations in the
 * reference implementation (open-spaced-repetition/py-fsrs).
 */
class FsrsTest {

    private val day = 86_400_000L
    private val t0 = 1_700_000_000_000L

    /** No learning steps, no fuzz: pure FSRS day-scale scheduling, fully deterministic. */
    private fun pure() = FsrsScheduler(
        learningSteps = emptyList(),
        relearningSteps = emptyList(),
        enableFuzz = false,
    )

    @Test
    fun forgettingCurveHitsNinetyPercentAtStability() {
        val s = pure()
        assertEquals(0.9, s.retrievability(3.0, 3.0), 1e-9)
        assertEquals(0.9, s.retrievability(17.5, 17.5), 1e-9)
        assertEquals(0.957336, s.retrievability(1.0, 3.0), 1e-6)
    }

    @Test
    fun initialStateMatchesReference() {
        val s = pure()
        val expectedStability = mapOf(
            Rating.AGAIN to 0.212, Rating.HARD to 1.2931,
            Rating.GOOD to 2.3065, Rating.EASY to 8.2956,
        )
        val expectedDifficulty = mapOf(
            Rating.AGAIN to 6.413300, Rating.HARD to 5.112171,
            Rating.GOOD to 2.118104, Rating.EASY to 1.0,
        )
        val expectedIntervalDays = mapOf(
            Rating.AGAIN to 1L, Rating.HARD to 1L, Rating.GOOD to 2L, Rating.EASY to 8L,
        )
        for (rating in Rating.entries) {
            val result = s.review(SrsState(), rating, t0)
            assertEquals(expectedStability.getValue(rating), result.stability, 1e-6)
            assertEquals(expectedDifficulty.getValue(rating), result.difficulty, 1e-6)
            assertEquals(expectedIntervalDays.getValue(rating), (result.due - t0) / day)
            assertEquals(CardPhase.REVIEW, result.phase)
        }
    }

    @Test
    fun successfulReviewMatchesReference() {
        val s = pure()
        val first = s.review(SrsState(), Rating.GOOD, t0)
        assertEquals(2.3065, first.stability, 1e-6)

        val t1 = t0 + 2 * day
        val second = s.review(first, Rating.GOOD, t1)
        assertEquals(10.964332, second.stability, 1e-5)
        assertEquals(2.111214, second.difficulty, 1e-5)
        assertEquals(11L, (second.due - t1) / day)
        assertEquals(2, second.reps)
    }

    @Test
    fun lapseShrinksStabilityAndRaisesDifficulty() {
        val s = pure()
        val first = s.review(SrsState(), Rating.GOOD, t0)
        val second = s.review(first, Rating.GOOD, t0 + 2 * day)
        val third = s.review(second, Rating.AGAIN, t0 + 13 * day)

        assertEquals(1.538337, third.stability, 1e-5)
        assertEquals(7.392238, third.difficulty, 1e-5)
        assertEquals(1, third.lapses)
    }

    @Test
    fun higherRetentionMeansShorterIntervals() {
        val relaxed = FsrsScheduler(desiredRetention = 0.85, learningSteps = emptyList(), enableFuzz = false)
        val strict = FsrsScheduler(desiredRetention = 0.95, learningSteps = emptyList(), enableFuzz = false)
        val state = SrsState(stability = 30.0, difficulty = 5.0, lastReview = t0, phase = CardPhase.REVIEW)
        val relaxedDue = relaxed.review(state, Rating.GOOD, t0 + 30 * day).due
        val strictDue = strict.review(state, Rating.GOOD, t0 + 30 * day).due
        assertTrue("95% retention must schedule sooner than 85%", strictDue < relaxedDue)
    }

    @Test
    fun newCardWalksThroughLearningSteps() {
        val s = FsrsScheduler(enableFuzz = false)
        val first = s.review(SrsState(), Rating.GOOD, t0)
        assertEquals(CardPhase.LEARNING, first.phase)
        assertEquals(10 * 60_000L, first.due - t0)

        val graduated = s.review(first, Rating.GOOD, t0 + 10 * 60_000L)
        assertEquals(CardPhase.REVIEW, graduated.phase)
        assertTrue(graduated.due - t0 >= day)
    }

    @Test
    fun againOnAReviewCardEntersRelearning() {
        val s = FsrsScheduler(enableFuzz = false)
        val state = SrsState(stability = 20.0, difficulty = 5.0, lastReview = t0, phase = CardPhase.REVIEW)
        val lapsed = s.review(state, Rating.AGAIN, t0 + 20 * day)
        assertEquals(CardPhase.RELEARNING, lapsed.phase)
        assertEquals(10 * 60_000L, lapsed.due - (t0 + 20 * day))
        assertTrue("stability must drop after a lapse", lapsed.stability < 20.0)
    }

    @Test
    fun sameDayRepeatDoesNotInflateInterval() {
        val s = FsrsScheduler(enableFuzz = false)
        val first = s.review(SrsState(), Rating.GOOD, t0)
        val again = s.review(first, Rating.GOOD, t0 + 60_000L)
        assertTrue(
            "a second look minutes later must not multiply stability the way a spaced review does",
            again.stability < first.stability * 2,
        )
    }

    @Test
    fun previewOffersAllFourOutcomes() {
        val s = FsrsScheduler()
        val state = SrsState(stability = 10.0, difficulty = 5.0, lastReview = t0, phase = CardPhase.REVIEW)
        val previews = s.previewDelays(state, t0 + 10 * day)
        assertEquals(4, previews.size)
        assertTrue(previews.getValue(Rating.AGAIN) < previews.getValue(Rating.GOOD))
        assertTrue(previews.getValue(Rating.GOOD) < previews.getValue(Rating.EASY))
    }
}
