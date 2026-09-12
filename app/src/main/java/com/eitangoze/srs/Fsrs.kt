package com.eitangoze.srs

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * FSRS-6 (Free Spaced Repetition Scheduler).
 *
 * The formulas below mirror the reference implementation in
 * open-spaced-repetition/py-fsrs (fsrs/scheduler.py). FSRS models memory with two
 * latent variables — stability S (how slowly a memory decays) and difficulty D —
 * and schedules the next review at the point where predicted recall probability
 * falls to [desiredRetention].
 *
 * Why this and not SM-2 (the classic Anki algorithm): FSRS fits a forgetting curve
 * per card instead of nudging a fixed ease factor, which in the project's own
 * benchmarks reaches the same retention with noticeably fewer reviews.
 */
object Fsrs {

    /** Default FSRS-6 parameters (w[0]..w[20]) from the reference implementation. */
    val DEFAULT_PARAMETERS = doubleArrayOf(
        0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001,
        1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629, 1.6483, 0.6014,
        1.8729, 0.5425, 0.0912, 0.0658, 0.1542,
    )

    /**
     * Predicted probability of recall [elapsedDays] after the last review.
     *
     * Free of scheduler settings on purpose: desired retention decides *when* to
     * review, but the forgetting curve itself depends only on stability and decay.
     * That makes it safe to ask "what will this look like on exam day?".
     */
    fun recallAfter(
        elapsedDays: Double,
        stability: Double,
        decayParameter: Double = DEFAULT_PARAMETERS[20],
    ): Double {
        if (stability <= 0.0) return 0.0
        if (elapsedDays <= 0.0) return 1.0
        val decay = -decayParameter
        val factor = 0.9.pow(1.0 / decay) - 1.0
        return (1.0 + factor * elapsedDays / stability).pow(decay).coerceIn(0.0, 1.0)
    }

    const val STABILITY_MIN = 0.001
    const val MIN_DIFFICULTY = 1.0
    const val MAX_DIFFICULTY = 10.0
}

enum class Rating(val value: Int, val labelJa: String) {
    AGAIN(1, "もう一度"),
    HARD(2, "難しい"),
    GOOD(3, "できた"),
    EASY(4, "簡単");

    companion object {
        fun fromValue(v: Int): Rating = entries.first { it.value == v }
    }
}

enum class CardPhase {
    NEW, LEARNING, REVIEW, RELEARNING;

    companion object {
        fun fromName(n: String): CardPhase = entries.firstOrNull { it.name == n } ?: NEW
    }
}

/** The mutable scheduling state of a single card. Times are epoch milliseconds. */
data class SrsState(
    val stability: Double = 0.0,
    val difficulty: Double = 0.0,
    val due: Long = 0L,
    val lastReview: Long? = null,
    val phase: CardPhase = CardPhase.NEW,
    val step: Int = 0,
    val reps: Int = 0,
    val lapses: Int = 0,
)

private const val MINUTE_MS = 60_000L
private const val DAY_MS = 86_400_000L

/**
 * @param desiredRetention target probability of recall at review time (0.7..0.98).
 *   Higher means more reviews and better retention; 0.9 is the recommended default.
 * @param learningSteps short fixed intervals a brand-new card walks through before
 *   it graduates to FSRS day-scale scheduling.
 */
class FsrsScheduler(
    val parameters: DoubleArray = Fsrs.DEFAULT_PARAMETERS,
    val desiredRetention: Double = 0.9,
    val learningSteps: List<Long> = listOf(MINUTE_MS, 10 * MINUTE_MS),
    val relearningSteps: List<Long> = listOf(10 * MINUTE_MS),
    val maximumInterval: Int = 36500,
    val enableFuzz: Boolean = true,
) {
    private val w = parameters
    private val decay = -w[20]
    private val factor = 0.9.pow(1.0 / decay) - 1.0

    // ---- core equations -----------------------------------------------------

    /** Predicted probability of recall after [elapsedDays] with stability [stability]. */
    fun retrievability(elapsedDays: Double, stability: Double): Double {
        if (stability <= 0.0) return 0.0
        return (1.0 + factor * elapsedDays / stability).pow(decay)
    }

    /** Current recall probability of [state] at time [now]. */
    fun retrievability(state: SrsState, now: Long): Double {
        val last = state.lastReview ?: return 0.0
        if (state.phase == CardPhase.NEW || state.stability <= 0.0) return 0.0
        val elapsedDays = max(0.0, (now - last).toDouble() / DAY_MS)
        return retrievability(elapsedDays, state.stability)
    }

    /** Days until recall probability decays from 1.0 down to [desiredRetention]. */
    private fun nextIntervalDays(stability: Double): Int {
        val raw = (stability / factor) * (desiredRetention.pow(1.0 / decay) - 1.0)
        return min(max(raw.roundToInt(), 1), maximumInterval)
    }

    private fun initialStability(rating: Rating): Double =
        clampStability(w[rating.value - 1])

    private fun initialDifficulty(rating: Rating, clamp: Boolean): Double {
        val d = w[4] - exp(w[5] * (rating.value - 1)) + 1.0
        return if (clamp) clampDifficulty(d) else d
    }

    private fun clampStability(s: Double) = max(s, Fsrs.STABILITY_MIN)

    private fun clampDifficulty(d: Double) =
        min(max(d, Fsrs.MIN_DIFFICULTY), Fsrs.MAX_DIFFICULTY)

    private fun nextDifficulty(difficulty: Double, rating: Rating): Double {
        val deltaD = -(w[6] * (rating.value - 3))
        // Linear damping: a card already near D=10 moves less than an easy one.
        val damped = difficulty + (10.0 - difficulty) * deltaD / 9.0
        // Mean reversion pulls difficulty back toward the "Easy" anchor over time.
        val anchor = initialDifficulty(Rating.EASY, clamp = false)
        return clampDifficulty(w[7] * anchor + (1 - w[7]) * damped)
    }

    private fun shortTermStability(stability: Double, rating: Rating): Double {
        var inc = exp(w[17] * (rating.value - 3 + w[18])) * stability.pow(-w[19])
        if (rating != Rating.AGAIN) inc = max(inc, 1.0)
        return clampStability(stability * inc)
    }

    private fun nextForgetStability(d: Double, s: Double, r: Double): Double {
        val longTerm = w[11] * d.pow(-w[12]) * ((s + 1.0).pow(w[13]) - 1.0) * exp((1 - r) * w[14])
        val shortTerm = s / exp(w[17] * w[18])
        return min(longTerm, shortTerm)
    }

    private fun nextRecallStability(d: Double, s: Double, r: Double, rating: Rating): Double {
        val hardPenalty = if (rating == Rating.HARD) w[15] else 1.0
        val easyBonus = if (rating == Rating.EASY) w[16] else 1.0
        return s * (1.0 + exp(w[8]) * (11.0 - d) * s.pow(-w[9]) *
            (exp((1 - r) * w[10]) - 1.0) * hardPenalty * easyBonus)
    }

    private fun nextStability(d: Double, s: Double, r: Double, rating: Rating): Double =
        clampStability(
            if (rating == Rating.AGAIN) nextForgetStability(d, s, r)
            else nextRecallStability(d, s, r, rating)
        )

    // ---- review -------------------------------------------------------------

    /**
     * Apply a grade and return the new scheduling state.
     *
     * @param now review timestamp in epoch millis.
     */
    fun review(state: SrsState, rating: Rating, now: Long): SrsState {
        var stability = state.stability
        var difficulty = state.difficulty

        val daysSinceLast = state.lastReview?.let { (now - it).toDouble() / DAY_MS }

        if (state.phase == CardPhase.NEW) {
            stability = initialStability(rating)
            difficulty = initialDifficulty(rating, clamp = true)
        } else if (daysSinceLast != null && daysSinceLast < 1.0) {
            // Same-day repeat: use the short-term stability curve so cramming a card
            // several times in one session doesn't inflate its interval.
            stability = shortTermStability(stability, rating)
            difficulty = nextDifficulty(difficulty, rating)
        } else {
            val r = retrievability(state, now)
            stability = nextStability(difficulty, stability, r, rating)
            difficulty = nextDifficulty(difficulty, rating)
        }

        var phase = state.phase
        var step = state.step
        val delay: Long

        when (state.phase) {
            CardPhase.NEW -> {
                if (learningSteps.isEmpty()) {
                    phase = CardPhase.REVIEW
                    step = 0
                    delay = intervalWithFuzz(nextIntervalDays(stability))
                } else when (rating) {
                    Rating.AGAIN -> { phase = CardPhase.LEARNING; step = 0; delay = learningSteps[0] }
                    Rating.HARD -> {
                        phase = CardPhase.LEARNING; step = 0
                        delay = if (learningSteps.size > 1) (learningSteps[0] + learningSteps[1]) / 2
                        else (learningSteps[0] * 1.5).toLong()
                    }
                    Rating.GOOD -> {
                        if (learningSteps.size == 1) {
                            phase = CardPhase.REVIEW; step = 0
                            delay = intervalWithFuzz(nextIntervalDays(stability))
                        } else { phase = CardPhase.LEARNING; step = 1; delay = learningSteps[1] }
                    }
                    Rating.EASY -> {
                        phase = CardPhase.REVIEW; step = 0
                        delay = intervalWithFuzz(nextIntervalDays(stability))
                    }
                }
            }

            CardPhase.LEARNING, CardPhase.RELEARNING -> {
                val steps = if (state.phase == CardPhase.LEARNING) learningSteps else relearningSteps
                if (steps.isEmpty() || step >= steps.size) {
                    phase = CardPhase.REVIEW; step = 0
                    delay = intervalWithFuzz(nextIntervalDays(stability))
                } else when (rating) {
                    Rating.AGAIN -> { step = 0; delay = steps[0] }
                    Rating.HARD -> {
                        delay = when {
                            step == 0 && steps.size == 1 -> (steps[0] * 1.5).toLong()
                            step == 0 -> (steps[0] + steps[1]) / 2
                            else -> steps[step]
                        }
                    }
                    Rating.GOOD -> {
                        if (step + 1 >= steps.size) {
                            phase = CardPhase.REVIEW; step = 0
                            delay = intervalWithFuzz(nextIntervalDays(stability))
                        } else { step += 1; delay = steps[step] }
                    }
                    Rating.EASY -> {
                        phase = CardPhase.REVIEW; step = 0
                        delay = intervalWithFuzz(nextIntervalDays(stability))
                    }
                }
            }

            CardPhase.REVIEW -> {
                if (rating == Rating.AGAIN) {
                    if (relearningSteps.isEmpty()) {
                        phase = CardPhase.REVIEW; step = 0
                        delay = intervalWithFuzz(nextIntervalDays(stability))
                    } else { phase = CardPhase.RELEARNING; step = 0; delay = relearningSteps[0] }
                } else {
                    phase = CardPhase.REVIEW; step = 0
                    delay = intervalWithFuzz(nextIntervalDays(stability))
                }
            }
        }

        return state.copy(
            stability = stability,
            difficulty = difficulty,
            due = now + delay,
            lastReview = now,
            phase = phase,
            step = step,
            reps = state.reps + 1,
            lapses = state.lapses + if (rating == Rating.AGAIN && state.phase == CardPhase.REVIEW) 1 else 0,
        )
    }

    /** Delay each of the four buttons would produce, for the "next: 3d" hints. */
    fun previewDelays(state: SrsState, now: Long): Map<Rating, Long> =
        Rating.entries.associateWith { r ->
            max(0L, withoutFuzz().review(state, r, now).due - now)
        }

    private fun withoutFuzz(): FsrsScheduler =
        if (!enableFuzz) this
        else FsrsScheduler(parameters, desiredRetention, learningSteps, relearningSteps, maximumInterval, false)

    private fun intervalWithFuzz(days: Int): Long {
        if (!enableFuzz || days < 3) return days * DAY_MS
        // Spread long intervals slightly so cards learned together don't clump forever.
        val delta = fuzzDelta(days)
        val lo = max(2, (days - delta).roundToInt())
        val hi = min(maximumInterval, (days + delta).roundToInt())
        val picked = if (hi <= lo) lo else Random.nextInt(lo, hi + 1)
        return picked.toLong() * DAY_MS
    }

    private fun fuzzDelta(days: Int): Double {
        var delta = 1.0
        val ranges = listOf(Triple(2.5, 7.0, 0.15), Triple(7.0, 20.0, 0.1), Triple(20.0, Double.MAX_VALUE, 0.05))
        for ((start, end, f) in ranges) {
            delta += f * max(min(days.toDouble(), end) - start, 0.0)
        }
        return delta
    }

    companion object {
        /** Days of memory left before recall drops below [target]; used for stats. */
        fun daysUntil(stability: Double, target: Double, decay: Double = -Fsrs.DEFAULT_PARAMETERS[20]): Double {
            val f = 0.9.pow(1.0 / decay) - 1.0
            return (stability / f) * (exp(ln(target) / decay) - 1.0)
        }
    }
}
