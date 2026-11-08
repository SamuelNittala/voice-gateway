package dev.nittala.voicegateway.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.nittala.voicegateway.core.ui.LoginUiState
import dev.nittala.voicegateway.core.ui.baseUrlProblem

/**
 * Log in: gateway address (explained live in plain words when it would be refused), passcode, and the
 * remember-passcode opt-in (off unless the user turned it on). The passcode lives only in this composition
 * (`remember`, not `rememberSaveable`), so it is never written to saved instance state.
 */
@Composable
fun LoginScreen(state: LoginUiState, actions: VoiceUiActions, modifier: Modifier = Modifier) {
    var baseUrl by rememberSaveable(state.baseUrl) { mutableStateOf(state.baseUrl) }
    var passcode by remember { mutableStateOf("") }
    var remember by rememberSaveable(state.rememberPasscode) { mutableStateOf(state.rememberPasscode) }
    val problem = if (baseUrl.isEmpty()) null else baseUrlProblem(baseUrl, state.variant)
    val canLogIn = !state.busy && baseUrl.isNotEmpty() && problem == null && passcode.isNotEmpty()

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Voice Gateway", style = MaterialTheme.typography.headlineLarge)
            Text("Log in to the gateway on your Mac.", style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Gateway address") },
                placeholder = { Text("https://mac.tail1234.ts.net") },
                isError = problem != null,
                supportingText = problem?.let { { Text(it) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().testTag("login_base_url"),
            )
            OutlinedTextField(
                value = passcode,
                onValueChange = { passcode = it },
                label = { Text("Passcode") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().testTag("login_passcode"),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Remember passcode")
                    Text("Saved encrypted on this phone. Off by default.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = remember,
                    onCheckedChange = { remember = it },
                    modifier = Modifier.testTag("login_remember").semantics {
                        contentDescription = "Remember passcode"
                        stateDescription = if (remember) "On" else "Off"
                    },
                )
            }
            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("login_error").semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }
            Button(
                onClick = { actions.login(baseUrl, passcode, remember) },
                enabled = canLogIn,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("login_submit"),
            ) { Text(if (state.busy) "Logging in..." else "Log in") }
        }
    }
}
