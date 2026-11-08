package dev.nittala.voicegateway.service

import dev.nittala.voicegateway.core.loop.LoopState
import dev.nittala.voicegateway.core.loop.PauseReason
import dev.nittala.voicegateway.core.loop.Phase
import dev.nittala.voicegateway.core.loop.Sent
import dev.nittala.voicegateway.core.model.AskKind
import dev.nittala.voicegateway.core.model.Event
import dev.nittala.voicegateway.core.model.EventKind
import dev.nittala.voicegateway.core.ui.voiceUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec (A10, plan section 6): the notification's text and first action, from the voice view. */
class NotificationsTest {
    private fun ui(phase: Phase) = voiceUiState(LoopState(WT, phase))
    private val confirm = Event("c1", EventKind.Summary, WT, "scratch", "Delete branch wt-old?", AskKind.Confirm)

    @Test
    fun theTextIsTheStatusAndWorktreeUnlessTheLoopHasANotice() {
        assertEquals("Listening, wt-scratch", notificationText(ui(Phase.Listening), null))
        assertEquals("wt-scratch asked a question", notificationText(ui(Phase.Listening), "wt-scratch asked a question"))
    }

    @Test
    fun theFirstActionIsTheOrbsTap() {
        assertEquals("Pause", notificationTapLabel(ui(Phase.Listening)))
        assertEquals("Resume", notificationTapLabel(ui(Phase.Paused(PauseReason.User))))
        assertEquals("Skip", notificationTapLabel(ui(Phase.Speaking(confirm, Phase.Listening))))
        assertEquals("Repeat", notificationTapLabel(ui(Phase.AwaitingTap(confirm))))
        assertNull(notificationTapLabel(ui(Phase.AgentWorking(Sent("x")))))
    }
}
