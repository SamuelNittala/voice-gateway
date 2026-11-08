package dev.nittala.voicegateway.service

import dev.nittala.voicegateway.core.model.SessionView
import dev.nittala.voicegateway.core.model.SessionsView
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec (A10, plan section 5): [Poller] and [pollDelayMs] with a fake gateway and virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class PollerTest {
    private class Rig(val test: TestScope) {
        val api = FakeGatewayApi()
        val views = mutableListOf<Pair<Long, SessionView>>()
        val sessions = mutableListOf<Long>()
        var loggedOut = 0
        val poller = Poller({ api }, test.backgroundScope, object : Poller.Listener {
            override fun onView(view: SessionView) {
                views += test.testScheduler.currentTime to view
            }

            override fun onSessions(view: SessionsView) {
                sessions += test.testScheduler.currentTime
            }

            override fun onLoggedOut() {
                loggedOut++
            }
        })

        fun advance(ms: Long) {
            test.advanceTimeBy(ms)
            test.runCurrent()
        }

        val viewTimes get() = views.map { it.first }
    }

    private fun rig(block: Rig.() -> Unit) = runTest { Rig(this).block() }

    @Test
    fun backoffIsTwoFourEightThenFifteenSecondsNeverBelowTheInterval() {
        assertEquals(2_000L, pollDelayMs(2_000, 0))
        assertEquals(5_000L, pollDelayMs(5_000, 0))
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 15_000L, 15_000L), (1..5).map { pollDelayMs(2_000, it) })
        assertEquals(listOf(5_000L, 5_000L, 8_000L, 15_000L), (1..4).map { pollDelayMs(5_000, it) })
    }

    @Test
    fun pollsTheWorktreeAtOnceThenEveryInterval() = rig {
        poller.setWorktree(WT)
        poller.setInterval(2_000)
        advance(4_500)
        assertEquals(listOf(0L, 2_000L, 4_000L), viewTimes)
        assertTrue(api.sessionNames.all { it == WT })
    }

    @Test
    fun aNewIntervalPollsAtOnceThenOnTheNewSchedule() = rig {
        poller.setWorktree(WT)
        poller.setInterval(5_000)
        advance(1_000)
        poller.setInterval(2_000)
        advance(2_500)
        assertEquals(listOf(0L, 1_000L, 3_000L), viewTimes)
    }

    @Test
    fun noIntervalOrNoWorktreeMeansNoSessionPolls() = rig {
        poller.setInterval(2_000)
        advance(10_000)
        assertTrue(api.sessionNames.isEmpty())
        poller.setWorktree(WT)
        advance(1)
        poller.setInterval(null)
        advance(10_000)
        assertEquals(1, api.sessionNames.size)
    }

    @Test
    fun aDifferentWorktreeIsPolledAtOnce() = rig {
        poller.setWorktree(WT)
        poller.setInterval(5_000)
        advance(1_000)
        poller.setWorktree("wt-api")
        advance(1)
        assertEquals(listOf(WT, "wt-api"), api.sessionNames)
    }

    @Test
    fun failuresBackOffAndASuccessRestoresTheInterval() = rig {
        api.offline = true
        poller.setWorktree(WT)
        poller.setInterval(2_000)
        advance(14_500)
        // 0 fails, +2 s, +4 s, +8 s: polls at 0, 2, 6, 14 s.
        assertEquals(4, api.sessionNames.size)
        api.offline = false
        advance(15_000)
        assertEquals(listOf(29_000L), viewTimes)
        advance(4_000)
        assertEquals(listOf(29_000L, 31_000L, 33_000L), viewTimes)
    }

    @Test
    fun a401IsReportedOnceAndStopsBothPolls() = rig {
        poller.setWorktree(WT)
        poller.setInterval(2_000)
        poller.setSessionsWanted(true)
        advance(1)
        api.loggedOut = true
        advance(10_000)
        assertEquals(1, loggedOut)
        val calls = api.calls.size
        advance(30_000)
        assertEquals(calls, api.calls.size)
    }

    @Test
    fun theSessionsListIsPolledEveryFiveSecondsOnlyWhileWanted() = rig {
        poller.setSessionsWanted(true)
        advance(10_500)
        assertEquals(listOf(0L, 5_000L, 10_000L), sessions)
        poller.setSessionsWanted(false)
        advance(20_000)
        assertEquals(3, sessions.size)
    }

    @Test
    fun stopEndsEverything() = rig {
        poller.setWorktree(WT)
        poller.setInterval(2_000)
        poller.setSessionsWanted(true)
        advance(1)
        poller.stop()
        val calls = api.calls.size
        advance(30_000)
        assertEquals(calls, api.calls.size)
    }
}
