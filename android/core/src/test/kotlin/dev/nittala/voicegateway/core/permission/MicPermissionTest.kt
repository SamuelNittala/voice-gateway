package dev.nittala.voicegateway.core.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The microphone permission gate: the decision table, then the gate's steps. */
class MicPermissionTest {
    private val actions = listOf(
        MicAction.Start, MicAction.TapOrb, MicAction.Interrupt, MicAction.Confirm(true), MicAction.Confirm(false),
        MicAction.SendHeld, MicAction.SendTyped("run the tests"), MicAction.NewWorktree,
    )

    // ---- The decision table -----------------------------------------------------------------------------------------

    @Test
    fun theDecisionTable() {
        // granted, denied for good -> decision, for every action that opens the mic.
        for (action in actions) {
            val rows = listOf(
                Triple(true, false, MicDecision.Proceed(action)),
                Triple(true, true, MicDecision.Proceed(action)), // a grant from the settings wins over the old denial
                Triple(false, false, MicDecision.Request(action)),
                Triple(false, true, MicDecision.ShowSettings),
            )
            for ((granted, forever, expected) in rows) {
                assertEquals("$action granted=$granted forever=$forever", expected, micDecision(granted, forever, action))
            }
        }
    }

    // ---- The gate ---------------------------------------------------------------------------------------------------

    @Test
    fun grantedRunsTheActionAtOnceWithNoPrompt() {
        val step = MicGate.onAction(MicGateState(), granted = true, action = MicAction.NewWorktree)
        assertEquals(MicGateStep(MicGateState(), request = false, run = MicAction.NewWorktree), step)
    }

    @Test
    fun notGrantedShowsThePromptOnceAndRemembersTheAction() {
        val step = MicGate.onAction(MicGateState(), granted = false, action = MicAction.NewWorktree)
        assertTrue(step.request)
        assertNull(step.run)
        assertEquals(MicAction.NewWorktree, step.state.pending)
    }

    @Test
    fun aSecondTapWhileThePromptIsUpDoesNotShowItAgain() {
        val first = MicGate.onAction(MicGateState(), granted = false, action = MicAction.NewWorktree)
        val second = MicGate.onAction(first.state, granted = false, action = MicAction.Start)
        assertFalse(second.request)
        assertNull(second.run)
        assertEquals(MicAction.Start, second.state.pending)
    }

    @Test
    fun allowingRunsTheRememberedActionOnceAndClearsTheMessage() {
        val asked = MicGate.onAction(MicGateState(denial = MicDenial.Denied), false, MicAction.NewWorktree).state
        val step = MicGate.onResult(asked, granted = true, rationale = false)
        assertEquals(MicGateStep(MicGateState(), request = false, run = MicAction.NewWorktree), step)
        // A stray second answer runs nothing.
        assertNull(MicGate.onResult(step.state, granted = true, rationale = false).run)
    }

    @Test
    fun aTypedPromptIsRunWithItsTextAfterTheGrant() {
        val asked = MicGate.onAction(MicGateState(), false, MicAction.SendTyped("hello")).state
        assertEquals(MicAction.SendTyped("hello"), MicGate.onResult(asked, granted = true, rationale = false).run)
    }

    @Test
    fun denyingRunsNothingAndSaysWhetherAndroidWillAskAgain() {
        val asked = MicGate.onAction(MicGateState(), false, MicAction.NewWorktree).state
        val once = MicGate.onResult(asked, granted = false, rationale = true)
        assertEquals(MicGateStep(MicGateState(denial = MicDenial.Denied)), once)
        val forever = MicGate.onResult(asked, granted = false, rationale = false)
        assertEquals(MicGateStep(MicGateState(denial = MicDenial.DeniedForever)), forever)
    }

    @Test
    fun afterADenialTheNextTapAsksAgain() {
        val step = MicGate.onAction(MicGateState(denial = MicDenial.Denied), false, MicAction.TapOrb)
        assertTrue(step.request)
        assertEquals(MicAction.TapOrb, step.state.pending)
    }

    @Test
    fun deniedForGoodTheTapRunsNothingAndOffersTheSettings() {
        val s = MicGateState(denial = MicDenial.DeniedForever)
        val step = MicGate.onAction(s, false, MicAction.NewWorktree)
        assertEquals(MicGateStep(MicGateState(denial = MicDenial.DeniedForever)), step)
    }

    @Test
    fun aGrantFromTheSettingsClearsTheMessageAndTheNextTapRuns() {
        val s = MicGateState(denial = MicDenial.DeniedForever)
        assertEquals(s, MicGate.onReturn(s, granted = false))
        val back = MicGate.onReturn(s, granted = true)
        assertEquals(MicGateState(), back)
        assertEquals(MicAction.NewWorktree, MicGate.onAction(back, true, MicAction.NewWorktree).run)
        // Even without onReturn, a tap after a grant proceeds and clears the message.
        assertEquals(MicGateStep(MicGateState(), run = MicAction.Start), MicGate.onAction(s, true, MicAction.Start))
    }

    @Test
    fun theDebugCodeNeverHoldsTypedText() {
        val typed = MicAction.SendTyped("secret plan for the login page")
        assertEquals("SendTyped", typed.code)
        assertFalse(typed.toString().contains("secret"))
        assertEquals(listOf("Start", "TapOrb", "Interrupt", "Confirm", "Confirm", "SendHeld", "SendTyped", "NewWorktree"),
            actions.map { it.code })
    }
}
