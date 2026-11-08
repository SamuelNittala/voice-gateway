package dev.nittala.voicegateway.permission

import dev.nittala.voicegateway.core.permission.MicAction
import dev.nittala.voicegateway.core.permission.MicDenial
import dev.nittala.voicegateway.core.permission.MicGateState
import dev.nittala.voicegateway.core.speech.Engine
import dev.nittala.voicegateway.ui.VoiceUiActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A fake Android: the permission is whatever the test says; prompts and settings are counted. */
class FakeMicPort(var grantedNow: Boolean = false, var rationaleNow: Boolean = false) : MicPermissionPort {
    var requests = 0
    var settingsOpened = 0
    override fun granted() = grantedNow
    override fun rationale() = rationaleNow
    override fun request() { requests++ }
    override fun openSettings() { settingsOpened++ }
}

/** Records each action by name. */
class RecordedActions : VoiceUiActions {
    val calls = mutableListOf<String>()
    override fun start() { calls += "start" }
    override fun stopListening() { calls += "stopListening" }
    override fun tapOrb() { calls += "tapOrb" }
    override fun interrupt() { calls += "interrupt" }
    override fun yes() { calls += "yes" }
    override fun no() { calls += "no" }
    override fun key(key: String) { calls += "key:$key" }
    override fun stop() { calls += "stop" }
    override fun restart() { calls += "restart" }
    override fun sendHeld() { calls += "sendHeld" }
    override fun discardHeld() { calls += "discardHeld" }
    override fun sendTyped(text: String) { calls += "sendTyped:$text" }
    override fun switchWorktree(name: String) { calls += "switch:$name" }
    override fun setMuted(muted: Boolean) { calls += "mute:$muted" }
    override fun login(baseUrl: String, passcode: String, remember: Boolean) { calls += "login" }
    override fun logout() { calls += "logout" }
    override fun setBaseUrl(raw: String) { calls += "setBaseUrl" }
    override fun setRememberPasscode(on: Boolean) { calls += "remember:$on" }
    override fun setSpeaker(engine: Engine) { calls += "speaker" }
    override fun newWorktree() { calls += "newWorktree" }
    override fun planWorktree(text: String) { calls += "planWorktree:$text" }
    override fun pickRepo(repoId: String) { calls += "pickRepo:$repoId" }
    override fun pickBase(name: String) { calls += "pickBase:$name" }
    override fun typeBase(name: String) { calls += "typeBase:$name" }
    override fun createWorktree() { calls += "createWorktree" }
    override fun createFromCopy() { calls += "createFromCopy" }
    override fun cancelWorktree() { calls += "cancelWorktree" }
    override fun openAppSettings() { calls += "openAppSettings" }
}

/** The permission gate with a fake port: one gate for every mic action, Android calls counted. */
class MicPermissionGateTest {
    private val real = RecordedActions()
    private val debugLines = mutableListOf<String>()
    private val gate = MicPermissionGate(run = { real.perform(it) }, debug = { debugLines += it })
    private val port = FakeMicPort().also { gate.attach(it) }
    private val actions = GatedActions(real, gate)

    @Test
    fun withThePermissionNewWorktreeRunsAtOnce() {
        port.grantedNow = true
        actions.newWorktree()
        assertEquals(listOf("newWorktree"), real.calls)
        assertEquals(0, port.requests)
    }

    @Test
    fun theFirstNewWorktreeWithoutThePermissionAsksAndRunsAfterTheGrant() {
        actions.newWorktree()
        assertTrue("nothing may start before the answer", real.calls.isEmpty())
        assertEquals(1, port.requests)
        actions.newWorktree() // a second tap while the prompt is up
        assertEquals(1, port.requests)
        port.grantedNow = true
        gate.onResult(granted = true)
        assertEquals(listOf("newWorktree"), real.calls)
        assertEquals(MicGateState(), gate.state.value)
    }

    @Test
    fun aDenialStartsNothingAndLeavesAMessage() {
        actions.start()
        port.rationaleNow = true
        gate.onResult(granted = false)
        assertTrue(real.calls.isEmpty())
        assertEquals(MicDenial.Denied, gate.state.value.denial)
        actions.start()
        assertEquals("the prompt may be shown again", 2, port.requests)
    }

    @Test
    fun deniedForGoodTheTapOffersTheSettingsAndAGrantThereLetsTheUserContinue() {
        actions.newWorktree()
        port.rationaleNow = false
        gate.onResult(granted = false)
        assertEquals(MicDenial.DeniedForever, gate.state.value.denial)
        actions.newWorktree()
        assertEquals("Android would not show the prompt", 1, port.requests)
        assertTrue(real.calls.isEmpty())
        actions.openAppSettings()
        assertEquals(1, port.settingsOpened)
        gate.onReturn() // still not granted
        assertEquals(MicDenial.DeniedForever, gate.state.value.denial)
        port.grantedNow = true
        gate.onReturn()
        assertEquals(MicGateState(), gate.state.value)
        actions.newWorktree()
        assertEquals(listOf("newWorktree"), real.calls)
    }

    @Test
    fun everyActionThatOpensTheMicIsGatedWithItsArguments() {
        actions.start(); actions.tapOrb(); actions.interrupt(); actions.yes(); actions.no(); actions.sendHeld()
        actions.sendTyped("hello"); actions.newWorktree()
        assertTrue(real.calls.toString(), real.calls.isEmpty())
        port.grantedNow = true
        actions.start(); actions.tapOrb(); actions.interrupt(); actions.yes(); actions.no(); actions.sendHeld()
        actions.sendTyped("hello"); actions.newWorktree()
        assertEquals(listOf("start", "tapOrb", "interrupt", "yes", "no", "sendHeld", "sendTyped:hello", "newWorktree"), real.calls)
    }

    @Test
    fun theRememberedActionKeepsItsText() {
        actions.sendTyped("run the tests")
        gate.onResult(granted = true)
        assertEquals(listOf("sendTyped:run the tests"), real.calls)
    }

    @Test
    fun actionsThatNeverOpenTheMicPassStraightThrough() {
        actions.stop(); actions.stopListening(); actions.cancelWorktree(); actions.planWorktree("q"); actions.key("1")
        actions.discardHeld(); actions.restart(); actions.createWorktree(); actions.pickRepo("r")
        assertEquals(listOf("stop", "stopListening", "cancelWorktree", "planWorktree:q", "key:1", "discardHeld",
            "restart", "createWorktree", "pickRepo:r"), real.calls)
        assertEquals(0, port.requests)
    }

    @Test
    fun withoutAnAttachedActivityNothingRunsAndNothingBreaks() {
        gate.detach(port)
        actions.newWorktree()
        gate.onReturn()
        actions.openAppSettings()
        assertTrue(real.calls.isEmpty())
        assertEquals(0, port.requests)
        assertEquals(0, port.settingsOpened)
    }

    @Test
    fun theDebugLinesAreCodesOnly() {
        actions.sendTyped("secret request about the login page")
        gate.onResult(granted = false)
        actions.sendTyped("secret request about the login page")
        assertTrue(debugLines.isNotEmpty())
        for (line in debugLines) {
            assertTrue(line, line.startsWith("perm "))
            assertFalse(line, line.contains("secret") || line.contains("login"))
        }
        assertEquals(listOf("perm request SendTyped", "perm denied forever=true", "perm settings SendTyped"), debugLines)
    }
}
