package dev.nittala.voicegateway.service

import dev.nittala.voicegateway.core.loop.LoopInput
import dev.nittala.voicegateway.core.speech.Engine
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec (A10): [AppActions], every UI intent and the loop input or account call it becomes. */
class AppActionsTest {
    private class Recorder : LoopControls, Account {
        val inputs = mutableListOf<LoopInput>()
        val calls = mutableListOf<String>()
        override fun send(input: LoopInput) {
            inputs += input
        }

        override fun setSpeaker(engine: Engine?) {
            calls += "speaker $engine"
        }

        override fun setVisible(visible: Boolean) {
            calls += "visible $visible"
        }

        override fun login(baseUrl: String, passcode: String, remember: Boolean) {
            calls += "login $baseUrl $remember"
        }

        override fun logout() {
            calls += "logout"
        }

        override fun setBaseUrl(raw: String) {
            calls += "url $raw"
        }

        override fun setRememberPasscode(on: Boolean) {
            calls += "remember $on"
        }
    }

    private val r = Recorder()
    private val actions = AppActions(r, r)

    @Test
    fun loopIntentsBecomeTheirInputs() {
        actions.start()
        actions.tapOrb()
        actions.interrupt()
        actions.stopListening()
        actions.yes()
        actions.no()
        actions.key("2")
        actions.stop()
        actions.restart()
        actions.sendHeld()
        actions.discardHeld()
        actions.sendTyped("git status")
        actions.switchWorktree("wt-api")
        actions.setMuted(true)
        assertEquals(
            listOf(
                LoopInput.Tap, LoopInput.Tap, LoopInput.Tap, LoopInput.LongPress, LoopInput.ConfirmTap(true),
                LoopInput.ConfirmTap(false), LoopInput.KeyTap("2"), LoopInput.StopTap, LoopInput.RestartTap,
                LoopInput.SendNowTap, LoopInput.DiscardTap, LoopInput.TypedSend("git status"),
                LoopInput.WorktreeChanged("wt-api"), LoopInput.MuteChanged(true),
            ),
            r.inputs,
        )
    }

    @Test
    fun newWorktreeIntentsBecomeTheirInputs() {
        actions.newWorktree()
        actions.planWorktree("a worktree in notes for the readme")
        actions.pickRepo("85dfc701aea8")
        actions.pickBase("develop")
        actions.typeBase("production 4.6.1")
        actions.createWorktree()
        actions.createFromCopy()
        actions.cancelWorktree()
        assertEquals(
            listOf(
                LoopInput.NewWorktreeTap, LoopInput.NewWorktreeTyped("a worktree in notes for the readme"),
                LoopInput.CandidateTap("85dfc701aea8"), LoopInput.BaseTap("develop"),
                LoopInput.BaseTyped("production 4.6.1"), LoopInput.CreateTap, LoopInput.OfflineCreateTap,
                LoopInput.CancelTap,
            ),
            r.inputs,
        )
    }

    @Test
    fun accountAndSettingsIntentsGoToTheAccount() {
        actions.login("https://mac.tail1234.ts.net", "secret", remember = true)
        actions.logout()
        actions.setBaseUrl("https://mac.tail1234.ts.net")
        actions.setRememberPasscode(false)
        actions.setSpeaker(Engine.Deepgram)
        assertEquals(
            listOf(
                "login https://mac.tail1234.ts.net true", "logout", "url https://mac.tail1234.ts.net", "remember false",
                "speaker Deepgram",
            ),
            r.calls,
        )
        assertEquals(emptyList<LoopInput>(), r.inputs)
    }
}
