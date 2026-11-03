package com.kaoyan.wordhelper.util.fsrs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Fsrs6EngineTest {

    private val engine = Fsrs6Engine(enableFuzz = false)

    @Test
    fun newCardGood_hasPositiveStabilityAndInterval() {
        val result = engine.review(state = null, rating = FsrsRating.GOOD, elapsedDays = 0.0)

        assertTrue(result.state.stability > 0.0)
        assertTrue(result.intervalDays > 0)
        assertEquals(2.3065, result.state.stability, 0.0001)
        assertEquals(2, result.intervalDays)
    }

    @Test
    fun newCard_intervalsFollowRatingOrder() {
        val again = engine.review(null, FsrsRating.AGAIN, 0.0)
        val hard = engine.review(null, FsrsRating.HARD, 0.0)
        val good = engine.review(null, FsrsRating.GOOD, 0.0)
        val easy = engine.review(null, FsrsRating.EASY, 0.0)

        assertTrue(again.state.stability <= hard.state.stability)
        assertTrue(hard.state.stability <= good.state.stability)
        assertTrue(good.state.stability <= easy.state.stability)
        assertTrue(again.intervalDays <= hard.intervalDays)
        assertTrue(hard.intervalDays <= good.intervalDays)
        assertTrue(good.intervalDays <= easy.intervalDays)
    }

    @Test
    fun againAfterGood_reducesStabilityAndInterval() {
        val good = engine.review(null, FsrsRating.GOOD, 0.0)
        val again = engine.review(good.state, FsrsRating.AGAIN, 2.0)

        assertTrue(again.state.stability < good.state.stability)
        assertTrue(again.intervalDays <= good.intervalDays)
    }

    @Test
    fun difficultyAlwaysStaysWithinBounds() {
        var state: FsrsState? = null
        val ratings = listOf(
            FsrsRating.AGAIN,
            FsrsRating.GOOD,
            FsrsRating.EASY,
            FsrsRating.HARD,
            FsrsRating.AGAIN,
            FsrsRating.EASY
        )

        ratings.forEachIndexed { index, rating ->
            val result = engine.review(state, rating, index.toDouble())
            assertTrue(result.state.difficulty >= 1.0)
            assertTrue(result.state.difficulty <= 10.0)
            state = result.state
        }
    }
}
