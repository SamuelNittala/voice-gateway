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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.nittala.voicegateway.core.speech.Engine
import dev.nittala.voicegateway.core.ui.SettingsUiState
import dev.nittala.voicegateway.core.ui.baseUrlProblem

/** Pure: the speaker the radio group shows as chosen; the gateway default is Chatterbox first. */
fun shownSpeaker(speaker: Engine?): Engine = speaker ?: Engine.Chatterbox

/**
 * Settings: gateway address (refusals explained in plain words, saved only when acceptable), remember passcode,
 * mute, the speaker (Chatterbox, Deepgram, or the phone's voice) and logout (behind a confirm dialog).
 */
@Composable
fun SettingsScreen(state: SettingsUiState, actions: VoiceUiActions, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var baseUrl by rememberSaveable(state.baseUrl) { mutableStateOf(state.baseUrl) }
    var confirmLogout by remember { mutableStateOf(false) }
    val problem = baseUrlProblem(baseUrl, state.variant)
    val changed = baseUrl != state.baseUrl

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp).testTag("settings_back")) { Text("Back") }
                Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            }

            Heading("Gateway")
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Gateway address") },
                isError = problem != null,
                supportingText = {
                    Text(problem ?: "Changing the address logs you out and forgets the saved passcode.")
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().testTag("settings_base_url"),
            )
            Button(
                onClick = { actions.setBaseUrl(baseUrl) },
                enabled = changed && problem == null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("settings_save_url"),
            ) { Text("Save address") }

            ToggleRow("Remember passcode", "Saved encrypted on this phone. Turning it off deletes it.", state.rememberPasscode, "settings_remember") {
                actions.setRememberPasscode(it)
            }
            ToggleRow("Mute spoken replies", null, state.muted, "settings_mute") { actions.setMuted(it) }
            ToggleRow(
                "Speak progress updates",
                "Short spoken updates while the agent works. Off leaves only the soft tick.",
                state.spokenProgress, "settings_progress",
            ) { actions.setSpokenProgress(it) }

            HorizontalDivider()
            Heading("Speaker")
            Column(Modifier.selectableGroup()) {
                Engine.entries.forEach { engine ->
                    val selected = shownSpeaker(state.speaker) == engine
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = selected, role = Role.RadioButton) { actions.setSpeaker(engine) }
                            .testTag("speaker_${engine.wire}"),
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Text(speakerLabel(engine), modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }

            HorizontalDivider()
            OutlinedButton(
                onClick = { confirmLogout = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("settings_logout"),
            ) { Text("Log out") }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Log out?") },
            text = { Text("The login and any saved passcode are deleted from this phone.") },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; actions.logout() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Log out")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
            },
        )
    }
}

private fun speakerLabel(engine: Engine): String = when (engine) {
    Engine.Chatterbox -> "Chatterbox (falls back to Deepgram, then the phone)"
    Engine.Deepgram -> "Deepgram (falls back to the phone)"
    Engine.Phone -> "The phone's own voice"
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

@Composable
private fun ToggleRow(title: String, help: String?, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title)
            help?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            modifier = Modifier.testTag(tag).semantics {
                contentDescription = title
                stateDescription = if (checked) "On" else "Off"
            },
        )
    }
}
