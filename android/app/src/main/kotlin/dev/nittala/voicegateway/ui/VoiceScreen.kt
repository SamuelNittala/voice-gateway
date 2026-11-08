package dev.nittala.voicegateway.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.nittala.voicegateway.core.ui.HeldUi
import dev.nittala.voicegateway.core.ui.MicNoticeUi
import dev.nittala.voicegateway.core.ui.NewWorktreeUi
import dev.nittala.voicegateway.core.ui.OrbTap
import dev.nittala.voicegateway.core.ui.VoiceMode
import dev.nittala.voicegateway.core.ui.VoiceUiState

/** Pure: what a tap on the orb does, in words (TalkBack's click label); null when the orb is not a button. */
fun orbTapLabel(state: VoiceUiState): String? = when (state.orbTap) {
    OrbTap.Start -> if (state.mode == VoiceMode.Idle) "Start listening" else "Continue"
    OrbTap.Interrupt -> "Stop speaking and listen"
    OrbTap.Tap -> when (state.mode) {
        VoiceMode.Capturing -> "Finish speaking"
        VoiceMode.AwaitingTap -> "Hear the question again"
        VoiceMode.NewWorktree -> if (state.micOn) "Stop listening" else "Speak"
        else -> "Pause"
    }
    OrbTap.None -> null
}

/**
 * The one full-screen voice view for the current worktree: picker and settings at the top, the orb with the status
 * line, the question shown large, Held's kept text, Yes/No (AwaitingTap only), a large Stop while the agent works,
 * the Details button for the slide-up sheet, and (W6) "New worktree" with the flow's panel and plan card. Depends only
 * on [state] and [actions].
 */
@Composable
fun VoiceScreen(
    state: VoiceUiState,
    actions: VoiceUiActions,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    debugLine: String? = null,
) {
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.mode, state.openSheet) { if (state.openSheet) sheetOpen = true }

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                WorktreePicker(
                    state.worktree, state.worktrees, actions::switchWorktree, Modifier.weight(1f),
                    onNewWorktree = if (state.canStartNewWorktree) actions::newWorktree else null,
                    openRequest = (state.newWorktree as? NewWorktreeUi.Failed)?.openPicker == true,
                )
                if (state.canStartNewWorktree) {
                    TextButton(onClick = actions::newWorktree, modifier = Modifier.heightIn(min = 48.dp).testTag("new_worktree")) {
                        Text("New worktree")
                    }
                }
                if (state.muted) Text("Muted", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 8.dp))
                TextButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 48.dp).testTag("open_settings")) {
                    Text("Settings")
                }
            }
            if (!state.online) {
                Text(
                    "Offline: waiting for the network",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            ) {
                OrbButton(state, actions)
                Text(
                    state.title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .testTag("status")
                        .semantics {
                            liveRegion = LiveRegionMode.Polite
                            contentDescription = state.announcement
                        },
                )
                debugLine?.let {
                    // DEBUG builds only (null in release): numbers and reason codes, never spoken text.
                    SelectionContainer {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.testTag("debug_line"),
                        )
                    }
                }
                state.question?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("question"),
                    )
                }
                state.speakingFrom?.let { Text("From $it", style = MaterialTheme.typography.labelLarge) }
                state.detail?.takeIf { state.held == null }?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.testTag("detail"))
                }
                state.micNotice?.let { MicNoticeCard(it, actions) }
                state.held?.let { HeldCard(it, actions) }
                state.newWorktree?.let { NewWorktreePanel(it, actions) }
            }

            if (state.confirmButtons) ConfirmButtons(actions)
            if (state.canStop) StopButton(actions::stop)
            OutlinedButton(
                onClick = { sheetOpen = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(vertical = 8.dp)
                    .testTag("open_details"),
            ) { Text(if (state.keys.isNotEmpty()) "Details and dialog keys" else "Details") }
        }
    }

    if (sheetOpen) DetailsSheet(state, actions) { sheetOpen = false }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OrbButton(state: VoiceUiState, actions: VoiceUiActions) {
    val haptics = LocalHapticFeedback.current
    val label = orbTapLabel(state)
    val canLongPress = state.mode != VoiceMode.Idle && state.mode != VoiceMode.LoggedOut
    val mic = if (state.micOn) "Microphone on" else "Microphone off"
    Box(
        Modifier
            .widthIn(max = 320.dp)
            .fillMaxWidth(0.8f)
            .aspectRatio(1f)
            .clip(CircleShape)
            .combinedClickable(
                enabled = label != null || canLongPress,
                onClickLabel = label,
                onLongClickLabel = if (canLongPress) "Turn the voice loop off" else null,
                role = Role.Button,
                onLongClick = if (canLongPress) actions::stopListening else null,
                onClick = {
                    when (state.orbTap) {
                        OrbTap.Start -> actions.start()
                        OrbTap.Interrupt -> {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            actions.interrupt()
                        }
                        OrbTap.Tap -> actions.tapOrb()
                        OrbTap.None -> Unit
                    }
                },
            )
            .testTag("orb")
            .semantics {
                contentDescription = "Voice orb"
                stateDescription = "${state.title}. $mic"
            },
    ) {
        Orb(state.mode, state.level, Modifier.fillMaxSize())
    }
}

/**
 * The microphone message: stays while it applies (permission refused, or the mic failed in the new-worktree flow).
 * "Open settings" only when Android will not show its prompt again.
 */
@Composable
internal fun MicNoticeCard(notice: MicNoticeUi, actions: VoiceUiActions) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth().testTag("mic_notice"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                notice.message,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("mic_notice_message").semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text(notice.hint, style = MaterialTheme.typography.bodyMedium)
            if (notice.openSettings) {
                Button(
                    onClick = actions::openAppSettings,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("mic_settings"),
                ) { Text("Open settings") }
            }
        }
    }
}

@Composable
private fun HeldCard(held: HeldUi, actions: VoiceUiActions) {
    Card(Modifier.fillMaxWidth().testTag("held")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(held.reason, style = MaterialTheme.typography.bodyLarge)
            held.text?.let {
                Text("Your message:", style = MaterialTheme.typography.labelLarge)
                Text(it, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("held_text"))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (held.canSendNow) {
                    Button(onClick = actions::sendHeld, modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("send_held")) {
                        Text("Send now")
                    }
                }
                OutlinedButton(onClick = actions::discardHeld, modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("discard_held")) {
                    Text("Discard")
                }
            }
        }
    }
}

@Composable
private fun ConfirmButtons(actions: VoiceUiActions) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Button(
            onClick = actions::no,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 80.dp)
                .testTag("confirm_no")
                .semantics { contentDescription = "No, do not do that" },
        ) { Text("No", style = MaterialTheme.typography.headlineSmall) }
        Button(
            onClick = actions::yes,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 80.dp)
                .testTag("confirm_yes")
                .semantics { contentDescription = "Yes, go ahead" },
        ) { Text("Yes", style = MaterialTheme.typography.headlineSmall) }
    }
}

/** The large Stop: on the voice view and in the sheet whenever the agent works. */
@Composable
internal fun StopButton(onStop: () -> Unit) {
    Button(
        onClick = onStop,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(vertical = 4.dp)
            .testTag("stop")
            .semantics { contentDescription = "Stop the agent" },
    ) { Text("Stop", style = MaterialTheme.typography.headlineSmall) }
    Spacer(Modifier.heightIn(min = 4.dp))
}
