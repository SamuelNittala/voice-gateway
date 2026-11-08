package dev.nittala.voicegateway.core.worktree

import dev.nittala.voicegateway.core.speech.ApprovalPhrase

/** What a spoken reply to the plan card means. */
enum class CardVerdict { Create, Cancel, Miss }

/**
 * Spec (W6, plan section 5 "Voice on the card"): the only spoken replies the plan card takes. Matched like the
 * approval phrases: the WHOLE utterance after [ApprovalPhrase.normalise] must equal one entry, never a substring, so
 * "create it in the other repo" or a bare "yes" is a [CardVerdict.Miss]. Base and candidate choices are taps only.
 */
object CardPhrase {
    /** Already normalised. */
    val CREATE: List<String> = listOf("create it", "yes create it", "go ahead and create it")

    /** Already normalised. */
    val CANCEL: List<String> = listOf("cancel", "cancel it", "no cancel", "stop")

    /** Pure: [CardVerdict.Create], [CardVerdict.Cancel] or [CardVerdict.Miss] for a transcript. */
    fun judge(text: String): CardVerdict = when (ApprovalPhrase.normalise(text)) {
        in CREATE -> CardVerdict.Create
        in CANCEL -> CardVerdict.Cancel
        else -> CardVerdict.Miss
    }
}
