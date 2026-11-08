package dev.nittala.voicegateway.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nittala.voicegateway.core.ui.WorktreeGroup
import dev.nittala.voicegateway.core.ui.WorktreeItem

/** Pure: the words for one picker row (group, agent state, flags), so state is never colour alone. */
fun worktreeStatusWords(item: WorktreeItem): String = listOfNotNull(
    when (item.group) {
        WorktreeGroup.Attached -> "attached"
        WorktreeGroup.Running -> "running"
        WorktreeGroup.Stopped -> "stopped"
    },
    item.agentStatus?.let { "agent $it" },
    "question waiting".takeIf { item.openQuestion },
    "reply pending".takeIf { item.waiting },
    item.problem?.let { "problem: $it" },
).joinToString(", ")

/**
 * The worktree picker at the top: the current worktree, and a menu of every session by group. [onNewWorktree] adds
 * "New worktree" at the top of the menu (null: not offered now); [openRequest] opens the menu by itself (a create
 * that may have worked: the list is shown to check).
 */
@Composable
fun WorktreePicker(
    current: String,
    items: List<WorktreeItem>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    onNewWorktree: (() -> Unit)? = null,
    openRequest: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(openRequest) { if (openRequest) open = true }
    val currentItem = items.firstOrNull { it.current }
    Box(modifier) {
        TextButton(
            onClick = { open = true },
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("worktree_picker")
                .semantics { contentDescription = "Worktree $current. Tap to choose another worktree." },
        ) {
            Column {
                Text(current, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                currentItem?.let { Text(worktreeStatusWords(it), style = MaterialTheme.typography.bodySmall) }
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            onNewWorktree?.let { start ->
                DropdownMenuItem(
                    modifier = Modifier.heightIn(min = 48.dp).testTag("picker_new_worktree"),
                    text = { Text("New worktree", fontWeight = FontWeight.SemiBold) },
                    onClick = {
                        open = false
                        start()
                    },
                )
            }
            if (items.isEmpty()) {
                DropdownMenuItem(text = { Text("No worktrees yet") }, onClick = { open = false }, enabled = false)
            }
            WorktreeGroup.entries.forEach { group ->
                val rows = items.filter { it.group == group }
                if (rows.isEmpty()) return@forEach
                HorizontalDivider()
                Text(
                    group.name,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.semantics { contentDescription = "${group.name} worktrees" },
                )
                rows.forEach { item ->
                    DropdownMenuItem(
                        modifier = Modifier.heightIn(min = 48.dp),
                        text = {
                            Column {
                                Text(
                                    (if (item.current) "Current: " else "") + item.name,
                                    fontWeight = if (item.current) FontWeight.Bold else FontWeight.Normal,
                                )
                                Text(worktreeStatusWords(item), style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        onClick = {
                            open = false
                            if (!item.current) onPick(item.name)
                        },
                    )
                }
            }
        }
    }
}
