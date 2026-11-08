package dev.nittala.voicegateway.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.nittala.voicegateway.core.ui.VoiceUiState

/** The slide-up details sheet. Opened by the Details button, and by itself when [VoiceUiState.openSheet]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsSheet(state: VoiceUiState, actions: VoiceUiActions, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
        DetailsSheetContent(state, actions)
    }
}

/** Plain words for a dialog key, for its button text and TalkBack. */
fun keyLabel(key: String): String = when (key) {
    "up" -> "Up"
    "down" -> "Down"
    "enter" -> "Enter"
    "esc" -> "Esc"
    else -> key
}

/**
 * The sheet's content: recent output (literal text), the dialog keys (only when the state lists them), Stop, Restart
 * (behind a confirm dialog), a typed prompt box, mute, and the engine and dictation lines.
 */
@Composable
fun DetailsSheetContent(state: VoiceUiState, actions: VoiceUiActions, modifier: Modifier = Modifier) {
    var confirmRestart by remember { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.keys.isNotEmpty()) {
            SectionTitle("Answer the dialog")
            state.keys.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { key ->
                        OutlinedButton(
                            onClick = { actions.key(key) },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 56.dp)
                                .testTag("key_$key")
                                .semantics { contentDescription = "Send key ${keyLabel(key)}" },
                        ) { Text(keyLabel(key), style = MaterialTheme.typography.titleMedium) }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        if (state.canStop) StopButton(actions::stop)

        if (state.canRestart) {
            Button(
                onClick = { confirmRestart = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("restart"),
            ) { Text("Restart ${state.worktree}") }
        }

        SectionTitle("Recent output")
        Surface(
            tonalElevation = 2.dp,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // A plain String in Text: shown literally, never parsed as markup or HTML.
            Text(
                state.output.ifEmpty { "No output yet." },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
                    .testTag("output"),
            )
        }

        if (state.canType) TypedPrompt(actions::sendTyped)

        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Mute spoken replies", modifier = Modifier.weight(1f))
            Switch(
                checked = state.muted,
                onCheckedChange = actions::setMuted,
                modifier = Modifier.testTag("mute").semantics {
                    contentDescription = "Mute spoken replies"
                    stateDescription = if (state.muted) "Muted" else "Not muted"
                },
            )
        }
        Text(state.engineInfo, style = MaterialTheme.typography.bodyMedium)
        Text(state.dictationInfo, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.heightIn(min = 16.dp))
    }

    if (confirmRestart) {
        AlertDialog(
            onDismissRequest = { confirmRestart = false },
            title = { Text("Restart ${state.worktree}?") },
            text = { Text("This starts the stopped worktree again.") },
            confirmButton = {
                TextButton(
                    onClick = { confirmRestart = false; actions.restart() },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("restart_confirm"),
                ) { Text("Restart") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestart = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

@Composable
private fun TypedPrompt(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Type a prompt") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            modifier = Modifier.weight(1f).testTag("typed_prompt"),
        )
        Button(
            onClick = { onSend(text); text = "" },
            enabled = text.isNotBlank(),
            modifier = Modifier.heightIn(min = 56.dp),
        ) { Text("Send") }
    }
}
