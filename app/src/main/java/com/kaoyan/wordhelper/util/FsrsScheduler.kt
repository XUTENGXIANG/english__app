package com.kaoyan.wordhelper.util

import com.kaoyan.wordhelper.data.entity.Progress
import com.kaoyan.wordhelper.data.model.SpellingOutcome
import com.kaoyan.wordhelper.data.model.StudyRating
import com.kaoyan.wordhelper.util.fsrs.Fsrs6Engine
import com.kaoyan.wordhelper.util.fsrs.FsrsRating
import com.kaoyan.wordhelper.util.fsrs.FsrsState

/**
 * Adapter between the app's [Progress] model and the pure FSRS-6 engine.
 *
 * The rating buttons are mapped as:
 *   AGAIN -> Again, HARD -> Hard, GOOD -> Good
 * Spelling outcomes are mapped as:
 *   FAILED -> Again, RETRY_SUCCESS -> Hard, HINTED -> Good, PERFECT -> Good
 */
object FsrsScheduler {

    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private const val DEFAULT_EASE_FACTOR = 2.5f
    private const val DEFAULT_WARM_START_DIFFICULTY = 5.0
    private const val MASTERED_INTERVAL_DAYS = 21
    private const val MASTERED_MIN_REVIEW_COUNT = 2

    private val engine = Fsrs6Engine(enableFuzz = false)

    data class ScheduleResult(
        val repetitions: Int,
        val intervalDays: Int,
        val easeFactor: Float,
        val nextReviewTime: Long,
        val status: Int,
        val stability: Double,
        val difficulty: Double
    )

    fun schedule(
        current: Progress?,
        rating: StudyRating,
        now: Long = System.currentTimeMillis()
    ): ScheduleResult {
        val fsrsRating = rating.toFsrsRating()
        val result = engine.review(
            state = current.toFsrsState(),
            rating = fsrsRating,
            elapsedDays = elapsedDays(current, now)
        )
        val countsAsReview = rating != StudyRating.AGAIN
        val reviewCount = (current?.reviewCount ?: 0) + if (countsAsReview) 1 else 0

        return ScheduleResult(
            repetitions = if (countsAsReview) (current?.repetitions ?: 0) + 1 else 0,
            intervalDays = result.intervalDays,
            easeFactor = current?.easeFactor ?: DEFAULT_EASE_FACTOR,
            nextReviewTime = Sm2Scheduler.nextReviewTimeByDays(result.intervalDays, now),
            status = resolveStatus(result.intervalDays, reviewCount),
            stability = result.state.stability,
            difficulty = result.state.difficulty
        )
    }

    fun scheduleSpelling(
        current: Progress?,
        outcome: SpellingOutcome,
        now: Long = System.currentTimeMillis()
    ): ScheduleResult {
        return schedule(current = current, rating = outcome.toStudyRating(), now = now)
    }

    private fun StudyRating.toFsrsRating(): FsrsRating {
        return when (this) {
            StudyRating.AGAIN -> FsrsRating.AGAIN
            StudyRating.HARD -> FsrsRating.HARD
            StudyRating.GOOD -> FsrsRating.GOOD
        }
    }

    private fun SpellingOutcome.toStudyRating(): StudyRating {
        return when (this) {
            SpellingOutcome.FAILED -> StudyRating.AGAIN
            SpellingOutcome.RETRY_SUCCESS -> StudyRating.HARD
            SpellingOutcome.HINTED -> StudyRating.GOOD
            SpellingOutcome.PERFECT -> StudyRating.GOOD
        }
    }

    private fun Progress?.toFsrsState(): FsrsState? {
        if (this == null) return null
        if (fsrsStability > 0.0 && fsrsDifficulty > 0.0) {
            return FsrsState(stability = fsrsStability, difficulty = fsrsDifficulty)
        }
        // Cards created before FSRS was enabled have no FSRS state. Warm-start
        // them from the existing interval instead of resetting their progress.
        if (intervalDays > 0 && reviewCount > 0) {
            return FsrsState(
                stability = intervalDays.toDouble(),
                difficulty = DEFAULT_WARM_START_DIFFICULTY
            )
        }
        return null
    }

    private fun elapsedDays(current: Progress?, now: Long): Double {
        if (current == null) return 0.0
        val lastReviewTime = current.lastReviewTime.takeIf { it > 0L }
            ?: Sm2Scheduler.estimateLastReviewTime(current)
            ?: return 0.0
        val elapsedMs = (now - lastReviewTime).coerceAtLeast(0L)
        return (elapsedMs / DAY_MS).toDouble()
    }

    private fun resolveStatus(intervalDays: Int, reviewCount: Int): Int {
        return if (
            intervalDays >= MASTERED_INTERVAL_DAYS &&
            reviewCount >= MASTERED_MIN_REVIEW_COUNT
        ) {
            Progress.STATUS_MASTERED
        } else {
            Progress.STATUS_LEARNING
        }
    }
}
