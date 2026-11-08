package dev.nittala.voicegateway.core.loop

import dev.nittala.voicegateway.core.worktree.WorktreeFlow
import dev.nittala.voicegateway.core.worktree.WorktreeLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The default after Create (HANDOFF_FIRST_PROMPT false): the loop switches to the new worktree and stops there, idle.
 * Nothing is ever sent to the new orchestrator, nothing is held, and there is no hand-off wait or time limit.
 * The hand-off rows (switched on) are in VoiceLoopWorktreeTest.
 */
class VoiceLoopNoHandoffTest {
    private val loop = VoiceLoop()
    private val creating = nw(WorktreeFlow.Creating(READY.planId, QUERY, voice = true))

    private fun newView(status: String? = "idle") = view(status, name = SESSION)

    @Test
    fun theDefaultIsNoFirstPrompt() {
        assertFalse(HANDOFF_FIRST_PROMPT)
        assertFalse(LoopConfig().handoffFirstPrompt)
    }

    @Test
    fun createdSwitchesToTheNewWorktreeAndIsIdle() {
        val t = loop.step(st(creating), LoopInput.Created(CREATED))
        assertEquals(SESSION, t.state.worktree)
        assertEquals(AgentView.Unknown, t.state.agent)
        assertEquals(Phase.Idle, t.phase)
        assertEquals(listOf(WorktreeLines.CREATED), t.says)
        assertTrue(t.sends.isEmpty())
        assertTrue(t.effects.none { it is Effect.StartTimer && it.timer == Timer.HandoffLimit })
        assertTrue(t.captures.isEmpty())
        assertEquals(5_000L, loop.pollInterval(t.state))
    }

    @Test
    fun aTypedRequestIsNotSentEither() {
        val typed = nw(WorktreeFlow.Creating(READY.planId, QUERY, voice = false))
        val t = loop.step(st(typed), LoopInput.Created(CREATED))
        assertEquals(Phase.Idle, t.phase)
        assertTrue(t.sends.isEmpty())
    }

    @Test
    fun idleViewsOfTheNewWorktreeNeverSendAnything() {
        val created = loop.step(st(creating), LoopInput.Created(CREATED)).state
        val later = loop.run(
            created, View(newView("idle")), View(newView("done")), View(newView("idle")),
            *Timer.entries.map { LoopInput.TimerFired(it) }.toTypedArray(),
        )
        assertTrue(later.sends.isEmpty())
        assertEquals(Phase.Idle, later.phase)
        assertEquals(SESSION, later.state.worktree)
    }

    @Test
    fun nothingIsHeldAfterTheCreate() {
        val created = loop.step(st(creating), LoopInput.Created(CREATED)).state
        assertFalse(created.phase is Phase.Held)
        assertEquals(Transition(created), loop.step(created, LoopInput.SendNowTap))
    }

    @Test
    fun aCreateFromALoopingTurnStillEndsIdleOnTheNewWorktree() {
        val looping = nw(WorktreeFlow.Creating(READY.planId, QUERY, voice = true), wasLooping = true)
        val t = loop.step(st(looping), LoopInput.Created(CREATED))
        assertEquals(Phase.Idle, t.phase)
        assertEquals(SESSION, t.state.worktree)
        assertTrue(t.sends.isEmpty())
    }

    @Test
    fun aTapThenStartsTheLoopOnTheNewWorktreeLikeAnyIdle() {
        val created = loop.step(st(creating), LoopInput.Created(CREATED)).state
        val viewed = loop.step(created, View(newView("idle"))).state
        val t = loop.step(viewed, LoopInput.Tap)
        assertEquals(Phase.Listening, t.phase)
        assertTrue(t.sends.isEmpty())
    }

    @Test
    fun aPendingQuestionOfTheOldWorktreeSurvivesTheCreate() {
        val s = st(creating).copy(questions = mapOf(WT to QUESTION))
        assertEquals(QUESTION, loop.step(s, LoopInput.Created(CREATED)).state.questions[WT])
    }

    private fun View(v: dev.nittala.voicegateway.core.model.SessionView) = LoopInput.View(v)
}
