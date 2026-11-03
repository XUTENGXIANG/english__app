package com.kaoyan.wordhelper.util

import com.kaoyan.wordhelper.data.entity.Progress
import com.kaoyan.wordhelper.data.model.StudyRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FsrsSchedulerTest {

    @Test
    fun newWordGood_hasPositiveStabilityAndInterval() {
        val now = 1_700_000_000_000L

        val result = FsrsScheduler.schedule(
            current = null,
            rating = StudyRating.GOOD,
            now = now
        )

        assertTrue("stability should be positive", result.stability > 0.0)
        assertTrue("interval should be positive", result.intervalDays > 0)
        assertEquals(2, result.intervalDays)
    }

    @Test
    fun againAfterGood_lowersStabilityOrShortensInterval() {
        val now = 1_700_000_000_000L
        val good = FsrsScheduler.schedule(
            current = null,
            rating = StudyRating.GOOD,
            now = now
        )
        val progressAfterGood = Progress(
            wordId = 1L,
            bookId = 1L,
            status = good.status,
            repetitions = good.repetitions,
            intervalDays = good.intervalDays,
            nextReviewTime = good.nextReviewTime,
            easeFactor = good.easeFactor,
            fsrsStability = good.stability,
            fsrsDifficulty = good.difficulty,
            reviewCount = 1,
            lastReviewTime = now
        )

        val again = FsrsScheduler.schedule(
            current = progressAfterGood,
            rating = StudyRating.AGAIN,
            now = now + 2 * DAY_MS
        )

        assertTrue(
            "Again should reduce stability or shorten the interval",
            again.stability < good.stability || again.intervalDays < good.intervalDays
        )
    }

    @Test
    fun tooEasy_usesEasyRating() {
        val now = 1_700_000_000_000L

        val result = FsrsScheduler.scheduleTooEasy(current = null, now = now)

        assertEquals(8, result.intervalDays)
        assertTrue(result.stability > 0.0)
    }

    @Test
    fun newWordRatings_produceIncreasingIntervals() {
        val now = 1_700_000_000_000L
        val again = FsrsScheduler.schedule(null, StudyRating.AGAIN, now)
        val hard = FsrsScheduler.schedule(null, StudyRating.HARD, now)
        val good = FsrsScheduler.schedule(null, StudyRating.GOOD, now)

        assertTrue(again.intervalDays <= hard.intervalDays)
        assertTrue(hard.intervalDays <= good.intervalDays)
        assertTrue(again.stability <= hard.stability)
        assertTrue(hard.stability <= good.stability)
    }

    private companion object {
        const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
