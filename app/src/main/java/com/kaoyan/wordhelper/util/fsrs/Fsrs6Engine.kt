package com.kaoyan.wordhelper.util.fsrs

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/*
 * FSRS-6 core, adapted for this project.
 *
 * Algorithm and default parameters are based on
 * https://github.com/open-spaced-repetition/FSRS-Kotlin
 * (MIT License, as stated in that repository's README).
 *
 * The upstream Kotlin file is coupled to Android/Compose UI. This file keeps
 * only the scheduling math and aligns three formulas with the official
 * open-spaced-repetition/py-fsrs reference implementation:
 *   1. initial stability is w[rating-1] (upstream incorrectly capped it at 0.1);
 *   2. the forgetting curve is a power curve (upstream used exp(-t/S));
 *   3. linear damping is (10 - D) * delta / 9 (upstream used delta * (10 - D/9)).
 */

enum class FsrsRating(val value: Int) {
    AGAIN(1),
    HARD(2),
    GOOD(3),
    EASY(4)
}

data class FsrsState(
    val stability: Double,
    val difficulty: Double
)

data class FsrsReviewResult(
    val state: FsrsState,
    val intervalDays: Int,
    val retrievability: Double
)

class Fsrs6Engine(
    private val requestRetention: Double = DEFAULT_REQUEST_RETENTION,
    private val parameters: DoubleArray = DEFAULT_PARAMETERS,
    private val enableFuzz: Boolean = false,
    private val random: Random = Random.Default
) {
    init {
        require(parameters.size >= PARAMETER_COUNT) {
            "FSRS-6 requires $PARAMETER_COUNT parameters, got ${parameters.size}"
        }
        require(requestRetention > 0.0 && requestRetention <= 1.0) {
            "requestRetention must be in (0, 1], got $requestRetention"
        }
    }

    private val decay: Double = -parameters[20]
    private val factor: Double = 0.9.pow(1.0 / decay) - 1.0

    /**
     * Calculates the next FSRS state and interval.
     *
     * @param state current FSRS state, or null for a card that has never been reviewed
     * @param rating the user's grade
     * @param elapsedDays whole days since the last review (0 for a new card / same-day review)
     */
    fun review(
        state: FsrsState?,
        rating: FsrsRating,
        elapsedDays: Double
    ): FsrsReviewResult {
        val safeElapsedDays = elapsedDays.coerceAtLeast(0.0)

        if (state == null || state.stability <= 0.0) {
            val initialState = FsrsState(
                stability = initialStability(rating),
                difficulty = initialDifficulty(rating)
            )
            return FsrsReviewResult(
                state = initialState,
                intervalDays = nextInterval(initialState.stability),
                retrievability = 0.0
            )
        }

        val currentRetrievability = calculateRetrievability(safeElapsedDays, state.stability)
        val nextStability = when {
            safeElapsedDays < 1.0 -> shortTermStability(state.stability, rating)
            rating == FsrsRating.AGAIN ->
                forgetStability(state.difficulty, state.stability, currentRetrievability)
            else ->
                recallStability(state.difficulty, state.stability, currentRetrievability, rating)
        }
        val nextDifficulty = calculateNextDifficulty(state.difficulty, rating)
        val nextState = FsrsState(stability = nextStability, difficulty = nextDifficulty)

        return FsrsReviewResult(
            state = nextState,
            intervalDays = nextInterval(nextState.stability),
            retrievability = currentRetrievability
        )
    }

    private fun initialStability(rating: FsrsRating): Double {
        return parameters[rating.value - 1].coerceAtLeast(MIN_STABILITY)
    }

    private fun initialDifficulty(rating: FsrsRating, clamp: Boolean = true): Double {
        val raw = parameters[4] - exp(parameters[5] * (rating.value - 1)) + 1.0
        return if (clamp) raw.coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY) else raw
    }

    private fun calculateNextDifficulty(currentDifficulty: Double, rating: FsrsRating): Double {
        val deltaDifficulty = -parameters[6] * (rating.value - 3)
        val damped = (10.0 - currentDifficulty) * deltaDifficulty / 9.0
        val reverted = parameters[7] * initialDifficulty(FsrsRating.EASY, clamp = false) +
            (1.0 - parameters[7]) * (currentDifficulty + damped)
        return reverted.coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY)
    }

    private fun calculateRetrievability(elapsedDays: Double, stability: Double): Double {
        if (stability <= 0.0) return 0.0
        return (1.0 + factor * elapsedDays / stability).pow(decay)
    }

    private fun shortTermStability(stability: Double, rating: FsrsRating): Double {
        var increase = exp(parameters[17] * (rating.value - 3 + parameters[18])) *
            stability.pow(-parameters[19])
        if (rating != FsrsRating.AGAIN) {
            increase = max(increase, 1.0)
        }
        return (stability * increase).coerceAtLeast(MIN_STABILITY)
    }

    private fun forgetStability(
        difficulty: Double,
        stability: Double,
        retrievability: Double
    ): Double {
        val longTerm = parameters[11] *
            difficulty.pow(-parameters[12]) *
            ((stability + 1.0).pow(parameters[13]) - 1.0) *
            exp((1.0 - retrievability) * parameters[14])
        val shortTerm = stability / exp(parameters[17] * parameters[18])
        return min(longTerm, shortTerm).coerceAtLeast(MIN_STABILITY)
    }

    private fun recallStability(
        difficulty: Double,
        stability: Double,
        retrievability: Double,
        rating: FsrsRating
    ): Double {
        val hardPenalty = if (rating == FsrsRating.HARD) parameters[15] else 1.0
        val easyBonus = if (rating == FsrsRating.EASY) parameters[16] else 1.0
        val growth = exp(parameters[8]) *
            (11.0 - difficulty) *
            stability.pow(-parameters[9]) *
            (exp((1.0 - retrievability) * parameters[10]) - 1.0) *
            hardPenalty *
            easyBonus
        return (stability * (1.0 + growth)).coerceAtLeast(MIN_STABILITY)
    }

    private fun nextInterval(stability: Double): Int {
        val rawInterval = (stability / factor) *
            (requestRetention.pow(1.0 / decay) - 1.0)
        val fuzzed = if (enableFuzz) applyFuzz(rawInterval) else rawInterval
        return fuzzed.roundToInt().coerceIn(1, MAX_INTERVAL_DAYS)
    }

    private fun applyFuzz(interval: Double): Double {
        if (interval < 2.5) return interval
        val rounded = interval.roundToInt()
        val minInterval = max(2, (rounded * 0.95 - 1.0).roundToInt())
        val maxInterval = (rounded * 1.05 + 1.0).roundToInt()
        return floor(random.nextDouble() * (maxInterval - minInterval + 1) + minInterval)
    }

    companion object {
        const val DEFAULT_REQUEST_RETENTION = 0.9
        const val PARAMETER_COUNT = 21

        private val DEFAULT_PARAMETERS_SOURCE = doubleArrayOf(
            0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194,
            0.001, 1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629,
            1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542
        )

        private const val MIN_DIFFICULTY = 1.0
        private const val MAX_DIFFICULTY = 10.0
        private const val MIN_STABILITY = 0.001
        private const val MAX_INTERVAL_DAYS = 36_500

        val DEFAULT_PARAMETERS: DoubleArray
            get() = DEFAULT_PARAMETERS_SOURCE.copyOf()
    }
}
