package dev.nittala.voicegateway.core.ui

import dev.nittala.voicegateway.core.loop.LoopState
import dev.nittala.voicegateway.core.loop.MicFailure
import dev.nittala.voicegateway.core.loop.PauseReason
import dev.nittala.voicegateway.core.loop.Phase
import dev.nittala.voicegateway.core.loop.READY
import dev.nittala.voicegateway.core.loop.QUERY
import dev.nittala.voicegateway.core.permission.MicAction
import dev.nittala.voicegateway.core.permission.MicDenial
import dev.nittala.voicegateway.core.permission.MicGateState
import dev.nittala.voicegateway.core.worktree.WorktreeFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The microphone message on the voice screen: permission refused, or the mic failed in the new-worktree flow. */
class VoiceUiMapperMicTest {
    private val request = Phase.NewWorktree(WorktreeFlow.Request(listening = false))
    private val card = Phase.NewWorktree(WorktreeFlow.Card(READY, QUERY, voice = true))

    @Test
    fun thePermissionMessageTable() {
        val rows = listOf(
            MicGateState() to null,
            MicGateState(pending = MicAction.NewWorktree) to null, // the prompt is up: Android speaks for itself
            MicGateState(denial = MicDenial.Denied) to
                MicNoticeUi(MicTexts.PERMISSION_NEEDED, MicTexts.TAP_TO_ALLOW, openSettings = false),
            MicGateState(denial = MicDenial.DeniedForever) to
                MicNoticeUi(MicTexts.PERMISSION_NEEDED, MicTexts.ALLOW_IN_SETTINGS, openSettings = true),
        )
        for ((gate, expected) in rows) assertEquals("$gate", expected, micNoticeUi(gate))
        assertEquals("Microphone permission is needed to listen", MicTexts.PERMISSION_NEEDED)
    }

    @Test
    fun aMicFailureInTheFlowIsShownWithARetryHint() {
        val unavailable = LoopState("wt-scratch", request, micProblem = MicFailure.Unavailable)
        assertEquals(MicNoticeUi(MicTexts.NOT_AVAILABLE, MicTexts.TAP_TO_RETRY), voiceUiState(unavailable).micNotice)
        val silenced = LoopState("wt-scratch", card, micProblem = MicFailure.Silenced)
        assertEquals(MicNoticeUi(MicTexts.SILENCED, MicTexts.TAP_TO_RETRY), voiceUiState(silenced).micNotice)
        // The flow stays on screen with it.
        assertEquals(NewWorktreeUi.Request(listening = false), voiceUiState(unavailable).newWorktree)
    }

    @Test
    fun noMessageWithoutAProblem() {
        for (phase in listOf(Phase.Idle, Phase.Listening, request, card, Phase.Paused(PauseReason.MicUnavailable))) {
            assertNull("$phase", voiceUiState(LoopState("wt-scratch", phase)).micNotice)
        }
    }

    @Test
    fun thePermissionMessageWinsOverTheLoopsOwn() {
        val ui = voiceUiState(LoopState("wt-scratch", request, micProblem = MicFailure.Unavailable))
        assertEquals(ui, ui.withMicGate(MicGateState()))
        val denied = ui.withMicGate(MicGateState(denial = MicDenial.DeniedForever))
        assertEquals(MicNoticeUi(MicTexts.PERMISSION_NEEDED, MicTexts.ALLOW_IN_SETTINGS, openSettings = true), denied.micNotice)
        assertEquals(ui.copy(micNotice = denied.micNotice), denied)
        val idle = voiceUiState(LoopState("wt-scratch", Phase.Idle)).withMicGate(MicGateState(denial = MicDenial.Denied))
        assertEquals(MicNoticeUi(MicTexts.PERMISSION_NEEDED, MicTexts.TAP_TO_ALLOW), idle.micNotice)
    }
}
