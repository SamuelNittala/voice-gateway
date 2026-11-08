package dev.nittala.voicegateway.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import dev.nittala.voicegateway.core.ui.NewWorktreeUi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nittala.voicegateway.core.speech.Engine
import dev.nittala.voicegateway.core.ui.LoginUiState
import dev.nittala.voicegateway.core.ui.VoiceMode
import dev.nittala.voicegateway.core.ui.VoiceUiState
import dev.nittala.voicegateway.ui.theme.VoiceGatewayTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Compose UI tests from the sample states (A11). Need a device or emulator. */
@RunWith(AndroidJUnit4::class)
class VoiceScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val actions = RecordingActions()

    private fun showVoice(initial: VoiceUiState): (VoiceUiState) -> Unit {
        var state by mutableStateOf(initial)
        rule.setContent { VoiceGatewayTheme(dynamicColor = false) { VoiceScreen(state, actions, onOpenSettings = {}) } }
        return { state = it }
    }

    @Test
    fun confirmButtonsAppearOnlyWhileAwaitingATap() {
        val setState = showVoice(SampleStates.byMode.getValue(VoiceMode.Idle))
        SampleStates.byMode.filterKeys { it != VoiceMode.NeedsYou && it != VoiceMode.Stopped }.forEach { (mode, s) ->
            setState(s)
            rule.waitForIdle()
            if (mode == VoiceMode.AwaitingTap) {
                rule.onNodeWithTag("confirm_yes").assertExists()
                rule.onNodeWithTag("confirm_no").assertExists()
            } else {
                rule.onNodeWithTag("confirm_yes").assertDoesNotExist()
                rule.onNodeWithTag("confirm_no").assertDoesNotExist()
            }
        }
    }

    @Test
    fun yesAndNoCallTheirActions() {
        showVoice(SampleStates.byMode.getValue(VoiceMode.AwaitingTap))
        rule.onNodeWithTag("question").assertExists()
        rule.onNodeWithTag("confirm_yes").performClick()
        rule.onNodeWithTag("confirm_no").performClick()
        assertEquals(listOf("yes", "no"), actions.calls)
    }

    @Test
    fun stopShowsOnlyWhileTheAgentWorks() {
        val setState = showVoice(SampleStates.byMode.getValue(VoiceMode.Working))
        rule.onNodeWithTag("stop").performClick()
        assertEquals(listOf("stop"), actions.calls)
        setState(SampleStates.byMode.getValue(VoiceMode.Idle))
        rule.onNodeWithTag("stop").assertDoesNotExist()
    }

    @Test
    fun tapWhileSpeakingInterrupts() {
        showVoice(SampleStates.byMode.getValue(VoiceMode.Speaking))
        rule.onNodeWithTag("orb").performClick()
        assertEquals(listOf("interrupt"), actions.calls)
    }

    @Test
    fun heldShowsTextAndReason() {
        val held = SampleStates.byMode.getValue(VoiceMode.Held)
        showVoice(held)
        rule.onNodeWithTag("held_text").assertExists()
        rule.onNodeWithText(held.held!!.reason).assertExists()
        rule.onNodeWithTag("send_held").performClick()
        assertEquals(listOf("sendHeld"), actions.calls)
    }

    @Test
    fun outputIsShownLiterally() {
        val state = SampleStates.byMode.getValue(VoiceMode.NeedsYou)
        rule.setContent { VoiceGatewayTheme(dynamicColor = false) { DetailsSheetContent(state, actions) } }
        // The exact raw string, tags and entities included: nothing was parsed as markup.
        rule.onNodeWithText(SampleStates.OUTPUT).assertExists()
        rule.onNodeWithText("Bash(rm -rf build) Allow?", substring = true).assertDoesNotExist()
    }

    @Test
    fun keysAppearOnlyWhenBlockedAndAreSent() {
        val state = SampleStates.byMode.getValue(VoiceMode.NeedsYou)
        rule.setContent { VoiceGatewayTheme(dynamicColor = false) { DetailsSheetContent(state, actions) } }
        VoiceUiState.ALLOWED_KEYS.forEach { rule.onNodeWithTag("key_$it").assertExists() }
        rule.onNodeWithTag("key_1").performClick()
        assertEquals(listOf("key:1"), actions.calls)
    }

    @Test
    fun noKeysOutsideNeedsYou() {
        val state = SampleStates.byMode.getValue(VoiceMode.Working)
        rule.setContent { VoiceGatewayTheme(dynamicColor = false) { DetailsSheetContent(state, actions) } }
        rule.onNodeWithTag("key_1").assertDoesNotExist()
    }

    @Test
    fun restartAsksFirst() {
        val state = SampleStates.byMode.getValue(VoiceMode.Stopped)
        rule.setContent { VoiceGatewayTheme(dynamicColor = false) { DetailsSheetContent(state, actions) } }
        rule.onNodeWithTag("restart").performClick()
        assertEquals(emptyList<String>(), actions.calls)
        rule.onNodeWithTag("restart_confirm").performClick()
        assertEquals(listOf("restart"), actions.calls)
    }

    // ---- W6: new worktree ---------------------------------------------------------------------------------------------

    private fun flow(name: String) = SampleStates.newWorktree.getValue(name)

    @Test
    fun theNewWorktreeButtonIsOfferedBetweenTurnsOnly() {
        val setState = showVoice(SampleStates.byMode.getValue(VoiceMode.Idle))
        rule.onNodeWithTag("new_worktree").performClick()
        assertEquals(listOf("newWorktree"), actions.calls)
        setState(SampleStates.byMode.getValue(VoiceMode.Capturing))
        rule.onNodeWithTag("new_worktree").assertDoesNotExist()
        setState(flow("card ready"))
        rule.onNodeWithTag("new_worktree").assertDoesNotExist()
    }

    @Test
    fun aTypedRequestIsPlanned() {
        showVoice(flow("request typed"))
        rule.onNodeWithTag("nw_plan").assertIsNotEnabled()
        rule.onNodeWithTag("nw_request_input").performTextInput("a worktree in notes for the readme")
        rule.onNodeWithTag("nw_plan").performClick()
        assertEquals(listOf("planWorktree:a worktree in notes for the readme"), actions.calls)
    }

    @Test
    fun candidatesAreTaps() {
        showVoice(flow("choosing"))
        rule.onNodeWithText("Which repo did you mean?").assertExists()
        rule.onNodeWithTag("nw_candidate_a1b2c3d4e5f6").performClick()
        assertEquals(listOf("pickRepo:a1b2c3d4e5f6"), actions.calls)
    }

    @Test
    fun thePlanCardAtPhoneWidthShowsThePlanAndItsTapsCallTheActions() {
        val card = flow("card ready").newWorktree as NewWorktreeUi.Card
        rule.setContent {
            VoiceGatewayTheme(dynamicColor = false) {
                Box(Modifier.width(360.dp)) { NewWorktreePanel(card, actions) }
            }
        }
        rule.onNodeWithText(card.card.repo).assertIsDisplayed()
        rule.onNodeWithText(card.card.branch).assertIsDisplayed()
        rule.onNodeWithText(card.card.baseLine!!).assertExists()
        rule.onNodeWithText(card.card.path).assertExists()
        rule.onNodeWithTag("nw_hint").assertExists()
        rule.onNodeWithTag("nw_base_production-4.6.2").assertIsNotEnabled()
        rule.onNodeWithTag("nw_base_develop").performClick()
        rule.onNodeWithTag("nw_base_input").performTextInput("production 4.6.1")
        rule.onNodeWithTag("nw_base_use").performClick()
        rule.onNodeWithTag("nw_create").assertIsDisplayed().performClick()
        rule.onNodeWithTag("nw_cancel").performClick()
        assertEquals(
            listOf("pickBase:develop", "typeBase:production 4.6.1", "createWorktree", "cancelWorktree"),
            actions.calls,
        )
    }

    @Test
    fun createIsOffUntilABaseIsPickedAndAnExpiredPlanSaysPlanAgain() {
        val setState = showVoice(flow("card needs base"))
        rule.onNodeWithTag("nw_create").assertIsNotEnabled()
        setState(flow("card expired"))
        rule.onNodeWithTag("nw_create").assertTextEquals("Plan again").performClick()
        assertEquals(listOf("createWorktree"), actions.calls)
    }

    @Test
    fun aFailedFetchOffersTheDownloadedCopy() {
        showVoice(flow("fetch failed"))
        rule.onNodeWithTag("nw_offline").performClick()
        rule.onNodeWithTag("nw_plan_again").performClick()
        assertEquals(listOf("createFromCopy", "createWorktree"), actions.calls)
    }

    @Test
    fun theHandoffShowsTheRequestAndCanKeepItUnsent() {
        showVoice(flow("handoff"))
        rule.onNodeWithTag("nw_request").assertExists()
        rule.onNodeWithTag("nw_cancel").performClick()
        assertEquals(listOf("cancelWorktree"), actions.calls)
    }

    @Test
    fun aRefusedMicrophoneShowsAPersistentMessageWithoutTheSettingsButtonWhileAndroidMayAskAgain() {
        showVoice(SampleStates.micNotices.getValue("permission denied"))
        rule.onNodeWithTag("mic_notice").assertIsDisplayed()
        rule.onNodeWithText("Microphone permission is needed to listen").assertExists()
        rule.onNodeWithTag("mic_settings").assertDoesNotExist()
    }

    @Test
    fun aMicrophoneRefusedForGoodOffersTheAppSettings() {
        showVoice(SampleStates.micNotices.getValue("permission denied forever"))
        rule.onNodeWithText("Microphone permission is needed to listen").assertExists()
        rule.onNodeWithTag("mic_settings").performClick()
        assertEquals(listOf("openAppSettings"), actions.calls)
    }

    @Test
    fun aMicFailureInTheFlowKeepsThePanelAndTheMessage() {
        showVoice(SampleStates.micNotices.getValue("mic failed in the flow"))
        rule.onNodeWithTag("mic_notice").assertExists()
        rule.onNodeWithTag("new_worktree_panel").assertExists()
        rule.onNodeWithTag("nw_request_input").assertExists()
        rule.onNodeWithTag("mic_settings").assertDoesNotExist()
    }

    @Test
    fun noMessageWhenTheMicrophoneIsFine() {
        val setState = showVoice(SampleStates.byMode.getValue(VoiceMode.Idle))
        rule.onNodeWithTag("mic_notice").assertDoesNotExist()
        setState(flow("request"))
        rule.waitForIdle()
        rule.onNodeWithTag("mic_notice").assertDoesNotExist()
    }

    @Test
    fun loginRemembersNothingByDefaultAndExplainsABadAddress() {
        rule.setContent {
            VoiceGatewayTheme(dynamicColor = false) { LoginScreen(LoginUiState(baseUrl = "http://example.com"), actions) }
        }
        rule.onNodeWithTag("login_remember").assertIsOff()
        rule.onNodeWithText("Use https://", substring = true).assertExists()
        rule.onNodeWithTag("login_submit").assertIsNotEnabled()
    }
}

/** Records each action by name. */
class RecordingActions : VoiceUiActions {
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
    override fun sendTyped(text: String) { calls += "sendTyped" }
    override fun switchWorktree(name: String) { calls += "switch:$name" }
    override fun setMuted(muted: Boolean) { calls += "mute:$muted" }
    override fun login(baseUrl: String, passcode: String, remember: Boolean) { calls += "login" }
    override fun logout() { calls += "logout" }
    override fun setBaseUrl(raw: String) { calls += "setBaseUrl" }
    override fun setRememberPasscode(on: Boolean) { calls += "remember:$on" }
    override fun setSpeaker(engine: Engine) { calls += "speaker:${engine.wire}" }
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
