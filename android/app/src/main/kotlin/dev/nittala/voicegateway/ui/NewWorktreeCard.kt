package dev.nittala.voicegateway.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nittala.voicegateway.core.ui.CardUi
import dev.nittala.voicegateway.core.ui.NewWorktreeUi
import kotlinx.coroutines.delay

/** Pure: "m:ss" for a countdown of [seconds] (never negative). */
fun countdownText(seconds: Int): String = "%d:%02d".format(maxOf(0, seconds) / 60, maxOf(0, seconds) % 60)

/**
 * W6: the new-worktree flow on the voice screen, the same content as the web page's plan card. Every string is drawn
 * as plain text. Taps only call [actions]; the loop decides what each means (a tap the loop does not take changes
 * nothing).
 */
@Composable
fun NewWorktreePanel(ui: NewWorktreeUi, actions: VoiceUiActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().testTag("new_worktree_panel"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (ui) {
            is NewWorktreeUi.Request -> RequestBox(ui, actions)
            is NewWorktreeUi.Planning -> {
                Text("Working it out...", style = MaterialTheme.typography.titleMedium, modifier = Modifier.polite())
                Text(ui.query, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("nw_query"))
                ui.previous?.let { PlanCard(it, actions, enabled = false) }
                CancelButton(actions)
            }
            is NewWorktreeUi.Choosing -> {
                Text(ui.question, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("nw_question"))
                ui.candidates.forEach { c ->
                    OutlinedButton(
                        onClick = { actions.pickRepo(c.repoId) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("nw_candidate_${c.repoId}"),
                    ) { Text(c.label) }
                }
                CancelButton(actions)
            }
            is NewWorktreeUi.Card -> PlanCard(ui.card, actions, enabled = true)
            is NewWorktreeUi.Creating -> {
                Text("Creating the worktree...", style = MaterialTheme.typography.titleMedium, modifier = Modifier.polite())
                ui.card?.let { PlanCard(it, actions, enabled = false) }
            }
            is NewWorktreeUi.Failed -> {
                Text(ui.message, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("nw_message").polite())
                if (ui.openPicker) Text("Check the worktree list at the top.", style = MaterialTheme.typography.bodyMedium)
                ui.offlineLabel?.let {
                    Button(
                        onClick = actions::createFromCopy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("nw_offline"),
                    ) { Text(it) }
                }
                if (ui.canPlanAgain) {
                    OutlinedButton(
                        onClick = actions::createWorktree,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("nw_plan_again"),
                    ) { Text("Plan again") }
                }
                CancelButton(actions)
            }
            is NewWorktreeUi.Handoff -> {
                Text("Created ${ui.session}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.polite())
                ui.started?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                ui.launch?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("Your request goes to the new orchestrator once it is ready:", style = MaterialTheme.typography.labelLarge)
                Text(ui.request, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("nw_request"))
                OutlinedButton(
                    onClick = actions::cancelWorktree,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("nw_cancel"),
                ) { Text("Keep it, do not send") }
            }
        }
    }
}

@Composable
private fun RequestBox(ui: NewWorktreeUi.Request, actions: VoiceUiActions) {
    var text by rememberSaveable { mutableStateOf("") }
    Text(
        if (ui.listening) "Listening: say which repo, and what the worktree is for." else "Type which repo, and what the worktree is for.",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.polite(),
    )
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.take(500) },
        label = { Text("A worktree in <repo> [from <base>] for <task>") },
        modifier = Modifier.fillMaxWidth().testTag("nw_request_input"),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { actions.planWorktree(text) },
            enabled = text.isNotBlank(),
            modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("nw_plan"),
        ) { Text("Plan") }
        OutlinedButton(
            onClick = actions::cancelWorktree,
            modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("nw_cancel"),
        ) { Text("Cancel") }
    }
}

/** The plan card. [enabled] false: greyed, no buttons (a re-plan or the create is running). */
@Composable
fun PlanCard(card: CardUi, actions: VoiceUiActions, enabled: Boolean) {
    Card(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f).testTag("plan_card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(card.repo, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("nw_repo"))
            Text(card.branch, style = MaterialTheme.typography.titleMedium)
            Text(card.mode, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("nw_mode"))
            card.baseLine?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("nw_base_line")) }
            card.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary) }
            card.fetchNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (card.choices.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    card.choices.forEach { name ->
                        val current = name == card.currentChoice
                        FilterChip(
                            selected = current,
                            onClick = { actions.pickBase(name) },
                            enabled = enabled && !current && !card.expired,
                            label = { Text("origin/$name") },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("nw_base_$name"),
                        )
                    }
                }
            }
            if (card.otherBase && enabled && !card.expired) OtherBase(actions)
            Text(card.path, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("nw_path"))
            if (enabled) {
                Countdown(card)
                card.hint?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("nw_hint").polite())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = actions::createWorktree,
                        enabled = card.createEnabled || card.expired,
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("nw_create"),
                    ) { Text(card.createLabel) }
                    OutlinedButton(
                        onClick = actions::cancelWorktree,
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("nw_cancel"),
                    ) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun OtherBase(actions: VoiceUiActions) {
    var base by rememberSaveable { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = base,
            onValueChange = { base = it.take(100) },
            label = { Text("Another base branch") },
            singleLine = true,
            modifier = Modifier.weight(1f).testTag("nw_base_input"),
        )
        OutlinedButton(
            onClick = { actions.typeBase(base) },
            enabled = base.isNotBlank(),
            modifier = Modifier.heightIn(min = 56.dp).testTag("nw_base_use"),
        ) { Text("Use") }
    }
}

/** Counts down from the card's `expires_in` while it is on screen; the loop's own timer makes it expire. */
@Composable
private fun Countdown(card: CardUi) {
    if (card.expired) {
        Text("The plan expired. Plan again to create.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("nw_expired"))
        return
    }
    var left by remember(card.repo, card.branch, card.baseLine, card.currentChoice) { mutableIntStateOf(card.expiresInSeconds) }
    LaunchedEffect(card.repo, card.branch, card.baseLine, card.currentChoice) {
        while (left > 0) {
            delay(1_000)
            left -= 1
        }
    }
    Text(
        "Plan expires in ${countdownText(left)}",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("nw_countdown").semantics { contentDescription = "Plan expires in ${left / 60} minutes ${left % 60} seconds" },
    )
}

@Composable
private fun CancelButton(actions: VoiceUiActions) {
    OutlinedButton(
        onClick = actions::cancelWorktree,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("nw_cancel"),
    ) { Text("Cancel") }
}

private fun Modifier.polite(): Modifier = semantics { liveRegion = LiveRegionMode.Polite }
