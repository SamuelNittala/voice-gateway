package dev.nittala.voicegateway.core.loop

import dev.nittala.voicegateway.core.loop.Effect.StartCapture
import dev.nittala.voicegateway.core.worktree.WorktreeFlow
import dev.nittala.voicegateway.core.worktree.WorktreeLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Microphone failures (no permission, busy, silenced) in the new-worktree flow: added rows of the transition table.
 * The flow, the typed request and the card are never discarded; the failure is said once and stays on screen; a tap
 * tries again; one tap gives one failure and one message, never a stream.
 */
class VoiceLoopMicFailureTest {
    private val loop = VoiceLoop()
    private val request = nw(WorktreeFlow.Request(listening = true))
    private val card = nw(WorktreeFlow.Card(READY, QUERY, voice = true, listening = true))
    private val unavailable = LoopInput.MicFailed(MicFailure.Unavailable)

    @Test
    fun aFailureOnTheRequestKeepsTheFlowWithTheMicOffAndShowsIt() {
        val t = loop.step(st(request), unavailable)
        assertEquals(nw(WorktreeFlow.Request(listening = false)), t.phase)
        assertEquals(MicFailure.Unavailable, t.state.micProblem)
        assertEquals(listOf(Lines.MIC_UNAVAILABLE), t.says)
        assertTrue(t.effects.none { it is StartCapture })
    }

    @Test
    fun aFailureOnTheCardKeepsTheCard() {
        for (failure in MicFailure.entries) {
            val t = loop.step(st(card), LoopInput.MicFailed(failure))
            assertEquals("$failure", nw(WorktreeFlow.Card(READY, QUERY, voice = true)), t.phase)
            assertEquals("$failure", failure, t.state.micProblem)
        }
    }

    @Test
    fun aFailureWhileCapturingForTheFlowGoesBackToTheFlow() {
        val capturing = st(Phase.Capturing(WT, purpose = CapturePurpose.Worktree(request)))
        val t = loop.step(capturing, unavailable)
        assertEquals(nw(WorktreeFlow.Request(listening = false)), t.phase)
        assertEquals(MicFailure.Unavailable, t.state.micProblem)
        assertTrue(t.effects.none { it is Effect.Upload })
    }

    @Test
    fun aTypedRequestIsStillAcceptedAfterAFailure() {
        val failed = loop.step(st(request), unavailable).state
        val t = loop.step(failed, LoopInput.NewWorktreeTyped(QUERY))
        assertEquals(nw(WorktreeFlow.Planning(QUERY, voice = false)), t.phase)
        assertEquals(listOf(Effect.PlanWorktree(QUERY, null)), t.effects.filterIsInstance<Effect.PlanWorktree>())
        assertNull("the message goes once the flow moves on", t.state.micProblem)
    }

    @Test
    fun repeatedFailuresGiveOneMessageNotAStream() {
        val t = loop.run(st(request), unavailable, unavailable, unavailable)
        assertEquals(listOf(Lines.MIC_UNAVAILABLE), t.says)
        assertEquals(1, t.effects.count { it == Effect.Earcon(EarconKind.Error) })
        assertEquals(nw(WorktreeFlow.Request(listening = false)), t.phase)
    }

    @Test
    fun aTapTriesAgainOnceAndEachTapGivesOneMessage() {
        val failed = loop.step(st(request), unavailable).state
        val retry = loop.step(failed, LoopInput.Tap)
        assertEquals(nw(WorktreeFlow.Request(listening = true)), retry.phase)
        assertEquals(1, retry.captures.size)
        assertNull("the message goes when the mic opens again", retry.state.micProblem)
        val again = loop.run(retry.state, unavailable, unavailable)
        assertEquals(listOf(Lines.MIC_UNAVAILABLE), again.says)
        assertEquals(MicFailure.Unavailable, again.state.micProblem)
        assertTrue(again.captures.isEmpty())
    }

    @Test
    fun aTapOnTheCardAfterAFailureOpensTheMicAgain() {
        val failed = loop.step(st(card), unavailable).state
        val retry = loop.step(failed, LoopInput.Tap)
        assertEquals(nw(WorktreeFlow.Card(READY, QUERY, voice = true, listening = true)), retry.phase)
        assertEquals(listOf(StartCapture(answerMode = true)), retry.captures)
        assertNull(retry.state.micProblem)
    }

    @Test
    fun cancelOrLongPressClearTheMessage() {
        val failed = loop.step(st(request), unavailable).state
        val cancel = loop.step(failed, LoopInput.CancelTap)
        assertEquals(Phase.Idle, cancel.phase)
        assertEquals(listOf(WorktreeLines.CANCELLED), cancel.says)
        assertNull(cancel.state.micProblem)
        assertNull(loop.step(failed, LoopInput.LongPress).state.micProblem)
    }

    @Test
    fun outsideTheFlowTheLoopPausesAndSetsNoFlowMessage() {
        val t = loop.step(st(Phase.Listening), unavailable)
        assertEquals(Phase.Paused(PauseReason.MicUnavailable), t.phase)
        assertNull(t.state.micProblem)
        assertEquals(Transition(t.state), loop.step(t.state, unavailable))
    }
}
