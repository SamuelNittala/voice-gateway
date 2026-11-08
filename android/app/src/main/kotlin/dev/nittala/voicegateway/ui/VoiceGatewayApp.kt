package dev.nittala.voicegateway.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.nittala.voicegateway.core.ui.LoginUiState
import dev.nittala.voicegateway.core.ui.SettingsUiState
import dev.nittala.voicegateway.core.ui.VoiceMode
import dev.nittala.voicegateway.core.ui.VoiceUiState
import dev.nittala.voicegateway.ui.theme.VoiceGatewayTheme

/**
 * Picks the screen from state alone: Login while logged out, Settings while opened, else the voice view. No data
 * loading or navigation library: the A10 controller supplies the three states and the actions.
 */
@Composable
fun VoiceGatewayApp(
    voice: VoiceUiState,
    login: LoginUiState,
    settings: SettingsUiState,
    actions: VoiceUiActions,
    debugLine: String? = null,
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    VoiceGatewayTheme {
        when {
            voice.mode == VoiceMode.LoggedOut -> LoginScreen(login, actions)
            showSettings -> {
                BackHandler { showSettings = false }
                SettingsScreen(settings, actions, onBack = { showSettings = false })
            }
            else -> VoiceScreen(voice, actions, onOpenSettings = { showSettings = true }, debugLine = debugLine)
        }
    }
}
