package dev.nittala.voicegateway.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RateBudgetTest {
    private fun uploads(times: List<Long>): RateBudget =
        times.fold(RateBudget()) { b, t -> b.started(t).finished() }

    @Test
    fun freshBudgetAllowsAnUpload() = assertEquals(BudgetDecision.Go, RateBudget().check(0))

    @Test
    fun tenUploadsInAMinuteAreAllowed() {
        val b = uploads((0L until 9L).map { it * 1_000 })
        assertEquals(BudgetDecision.Go, b.check(9_000))
    }

    @Test
    fun theEleventhInAMinuteWaitsForTheOldestToLeaveTheWindow() {
        val b = uploads((0L until 10L).map { it * 1_000 })
        assertEquals(BudgetDecision.WaitUntil(60_000, WaitReason.Rate), b.check(10_000))
        assertEquals(BudgetDecision.WaitUntil(60_000, WaitReason.Rate), b.check(59_999))
        assertEquals(BudgetDecision.Go, b.check(60_000))
    }

    @Test
    fun theWindowRolls() {
        val b = uploads((0L until 10L).map { it * 1_000 }).started(60_000).finished()
        assertEquals(BudgetDecision.WaitUntil(61_000, WaitReason.Rate), b.check(60_500))
    }

    @Test
    fun oldStartsAreDropped() {
        val b = uploads((0L until 10L).map { it * 1_000 }).started(200_000)
        assertEquals(listOf(200_000L), b.starts)
    }

    @Test
    fun oneInFlightAtATime() {
        val b = RateBudget().started(0)
        assertEquals(BudgetDecision.InFlight, b.check(1))
        assertEquals(BudgetDecision.Go, b.finished().check(1))
    }

    @Test(expected = IllegalStateException::class)
    fun startingWhileInFlightThrows() {
        RateBudget().started(0).started(1)
    }

    @Test(expected = IllegalStateException::class)
    fun startingOverTheRateThrows() {
        uploads((0L until 10L).map { it * 1_000 }).started(10_000)
    }

    @Test
    fun retryAfterBlocksUntilItsEnd() {
        val b = RateBudget().started(0).finished().retryAfter(1_000, 7)
        assertEquals(BudgetDecision.WaitUntil(8_000, WaitReason.RetryAfter), b.check(1_000))
        assertEquals(BudgetDecision.Go, b.check(8_000))
    }

    @Test
    fun aShorterRetryAfterNeverShortensTheBlock() {
        val b = RateBudget().retryAfter(0, 30).retryAfter(1_000, 2)
        assertEquals(BudgetDecision.WaitUntil(30_000, WaitReason.RetryAfter), b.check(1_000))
    }

    @Test
    fun negativeRetryAfterIsZero() = assertEquals(BudgetDecision.Go, RateBudget().retryAfter(5_000, -3).check(5_000))

    @Test
    fun theLaterWaitWinsWhenBothApply() {
        val full = uploads((0L until 10L).map { it * 1_000 })
        assertEquals(BudgetDecision.WaitUntil(90_000, WaitReason.RetryAfter), full.retryAfter(10_000, 80).check(10_000))
        assertEquals(BudgetDecision.WaitUntil(60_000, WaitReason.Rate), full.retryAfter(10_000, 5).check(10_000))
    }

    @Test
    fun inFlightComesFirst() =
        assertEquals(BudgetDecision.InFlight, RateBudget().started(0).retryAfter(0, 10).check(1))

    @Test
    fun neverMoreThanTenStartsInAnyMinuteUnderGreedyUse() {
        var b = RateBudget()
        val starts = mutableListOf<Long>()
        for (t in 0L..300_000L step 250) {
            if (b.check(t) == BudgetDecision.Go) { b = b.started(t).finished(); starts += t }
        }
        for (s in starts) assertTrue(starts.count { it in s until s + 60_000 } <= 10)
        assertEquals(51, starts.size) // 10 in each of the minutes at 0, 60, 120, 180, 240 s, then one at 300 s
    }
}
