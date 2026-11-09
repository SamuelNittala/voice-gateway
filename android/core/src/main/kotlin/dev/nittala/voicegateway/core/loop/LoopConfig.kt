package dev.nittala.voicegateway.core.loop

import dev.nittala.voicegateway.core.speech.DetectorConfig

/**
 * W6: after Create the app switches to the new worktree. false (the default, the user's rule "do not send any input
 * when a new worktree is created"): the loop is idle there, nothing is sent and nothing is kept to send. true: the
 * request is sent by itself as the new orchestrator's first prompt once it is idle (the old hand-off).
 */
const val HANDOFF_FIRST_PROMPT = false

/** The app's working cue: one soft tick every 15 s while the agent works, the first 15 s after the send. */
const val WORKING_CUE_MS = 15_000L

/** The loop's fixed numbers (plan sections 2, 3, 5, 7). Not user settings, so they are not loosened casually. */
data class LoopConfig(
    val listenSilenceMs: Long = 45_000,
    val answerSilenceMs: Long = 20_000,
    val confirmReminderMs: Long = 60_000,
    val needsYouReminderMs: Long = 60_000,
    val sendRetryMs: Long = 3_000,
    val transcribeBusyRetryMs: Long = 2_000,
    val settledGraceMs: Long = 20_000,
    val pauseLimitMs: Long = 600_000,
    val interruptTailMs: Long = 300,
    val maxRejections: Int = 3,
    val fastPollMs: Long = 2_000,
    val slowPollMs: Long = 5_000,
    /** The phone's language is written in Latin script (G3's non-Latin drift check). */
    val expectLatin: Boolean = true,
    /** G2 thresholds. */
    val detector: DetectorConfig = DetectorConfig(),
    /** W6: send the request as the new orchestrator's first prompt ([HANDOFF_FIRST_PROMPT]). */
    val handoffFirstPrompt: Boolean = HANDOFF_FIRST_PROMPT,
    /** W6: how long the hand-off waits for two settled views before keeping the request in Held. */
    val handoffLimitMs: Long = 120_000,
    /** W6: settled views in a row before the first prompt goes. */
    val handoffSettledViews: Int = 2,
    /** The working cue's period in AgentWorking ([WORKING_CUE_MS] in the app); null: no cue. */
    val workingCueMs: Long? = null,
)
