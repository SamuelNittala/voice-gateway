package dev.nittala.voicegateway.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import dev.nittala.voicegateway.core.config.BuildVariant
import dev.nittala.voicegateway.core.speech.Engine
import dev.nittala.voicegateway.core.ui.LoginUiState
import dev.nittala.voicegateway.core.ui.SettingsUiState
import dev.nittala.voicegateway.core.ui.VoiceUiState
import dev.nittala.voicegateway.ui.theme.VoiceGatewayTheme

/** Every voice state, one preview each (light and dark). */
class VoiceStatesProvider : PreviewParameterProvider<VoiceUiState> {
    override val values: Sequence<VoiceUiState> = SampleStates.all.asSequence()
}

@Preview(name = "Voice light", showBackground = true, heightDp = 780)
@Composable
private fun VoiceScreenLightPreview(@PreviewParameter(VoiceStatesProvider::class) state: VoiceUiState) {
    VoiceGatewayTheme(darkTheme = false, dynamicColor = false) { VoiceScreen(state, NoOpVoiceUiActions, onOpenSettings = {}) }
}

@Preview(name = "Voice dark", showBackground = true, heightDp = 780, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun VoiceScreenDarkPreview(@PreviewParameter(VoiceStatesProvider::class) state: VoiceUiState) {
    VoiceGatewayTheme(darkTheme = true, dynamicColor = false) { VoiceScreen(state, NoOpVoiceUiActions, onOpenSettings = {}) }
}

/** W6: every screen of the new-worktree flow (request, planning, choosing, every card state, failures, hand-off). */
class NewWorktreeStatesProvider : PreviewParameterProvider<VoiceUiState> {
    override val values: Sequence<VoiceUiState> = SampleStates.newWorktree.values.asSequence()
}

@Preview(name = "New worktree light", showBackground = true, heightDp = 1100)
@Composable
private fun NewWorktreeLightPreview(@PreviewParameter(NewWorktreeStatesProvider::class) state: VoiceUiState) {
    VoiceGatewayTheme(darkTheme = false, dynamicColor = false) { VoiceScreen(state, NoOpVoiceUiActions, onOpenSettings = {}) }
}

@Preview(name = "New worktree dark", showBackground = true, heightDp = 1100, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NewWorktreeDarkPreview(@PreviewParameter(NewWorktreeStatesProvider::class) state: VoiceUiState) {
    VoiceGatewayTheme(darkTheme = true, dynamicColor = false) { VoiceScreen(state, NoOpVoiceUiActions, onOpenSettings = {}) }
}

/** The microphone messages: refused, refused for good (with the settings button), failed in the new-worktree flow. */
class MicNoticeStatesProvider : PreviewParameterProvider<VoiceUiState> {
    override val values: Sequence<VoiceUiState> = SampleStates.micNotices.values.asSequence()
}

@Preview(name = "Mic notice light", showBackground = true, heightDp = 900)
@Composable
private fun MicNoticeLightPreview(@PreviewParameter(MicNoticeStatesProvider::class) state: VoiceUiState) {
    VoiceGatewayTheme(darkTheme = false, dynamicColor = false) { VoiceScreen(state, NoOpVoiceUiActions, onOpenSettings = {}) }
}

@Preview(name = "Mic notice dark", showBackground = true, heightDp = 900, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MicNoticeDarkPreview(@PreviewParameter(MicNoticeStatesProvider::class) state: VoiceUiState) {
    VoiceGatewayTheme(darkTheme = true, dynamicColor = false) { VoiceScreen(state, NoOpVoiceUiActions, onOpenSettings = {}) }
}

@Preview(name = "Plan card at phone width", showBackground = true, widthDp = 360)
@Composable
private fun PlanCardPreview(@PreviewParameter(NewWorktreeStatesProvider::class) state: VoiceUiState) {
    VoiceGatewayTheme(dynamicColor = false) { state.newWorktree?.let { NewWorktreePanel(it, NoOpVoiceUiActions) } }
}

@Preview(name = "Details sheet", showBackground = true, heightDp = 900)
@Composable
private fun DetailsSheetPreview(@PreviewParameter(VoiceStatesProvider::class) state: VoiceUiState) {
    VoiceGatewayTheme(dynamicColor = false) { DetailsSheetContent(state, NoOpVoiceUiActions) }
}

@Preview(name = "Login", showBackground = true)
@Composable
private fun LoginPreview() {
    VoiceGatewayTheme(dynamicColor = false) { LoginScreen(LoginUiState(baseUrl = "https://mac.tail1234.ts.net"), NoOpVoiceUiActions) }
}

@Preview(name = "Login refused URL", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LoginRefusedPreview() {
    VoiceGatewayTheme(darkTheme = true, dynamicColor = false) {
        LoginScreen(LoginUiState(baseUrl = "http://example.com", error = "Wrong passcode"), NoOpVoiceUiActions)
    }
}

@Preview(name = "Settings", showBackground = true, heightDp = 900)
@Composable
private fun SettingsPreview() {
    VoiceGatewayTheme(dynamicColor = false) {
        SettingsScreen(
            SettingsUiState(baseUrl = "https://mac.tail1234.ts.net", speaker = Engine.Deepgram, variant = BuildVariant.DEBUG),
            NoOpVoiceUiActions,
            onBack = {},
        )
    }
}
