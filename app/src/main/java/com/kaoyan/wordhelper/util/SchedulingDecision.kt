package com.kaoyan.wordhelper.util

/**
 * Algorithm-neutral result consumed by the repository.
 *
 * SM-2 results carry no FSRS state (stability/difficulty default to 0.0);
 * FSRS results carry both. Keeping this small mapping here lets the repository
 * switch schedulers without touching [Sm2Scheduler].
 */
data class SchedulingDecision(
    val repetitions: Int,
    val intervalDays: Int,
    val easeFactor: Float,
    val nextReviewTime: Long,
    val status: Int,
    val stability: Double = 0.0,
    val difficulty: Double = 0.0
)

fun Sm2Scheduler.ScheduleResult.toDecision(): SchedulingDecision {
    return SchedulingDecision(
        repetitions = repetitions,
        intervalDays = intervalDays,
        easeFactor = easeFactor,
        nextReviewTime = nextReviewTime,
        status = status
    )
}

fun FsrsScheduler.ScheduleResult.toDecision(): SchedulingDecision {
    return SchedulingDecision(
        repetitions = repetitions,
        intervalDays = intervalDays,
        easeFactor = easeFactor,
        nextReviewTime = nextReviewTime,
        status = status,
        stability = stability,
        difficulty = difficulty
    )
}
