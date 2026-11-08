package dev.nittala.voicegateway.core.speech

/**
 * Words that make the server upgrade an `ASK:` question to a tap-only confirmation (`ask: "confirm"`). Mirrors
 * gateway.py `VOICE_DESTRUCTIVE_WORDS` and the check in `split_ask`:
 * `not VOICE_DESTRUCTIVE_WORDS.isdisjoint(normalise_phrase(rest).split())`. A test reads the set out of gateway.py.
 */
object DestructiveWords {
    /** Same set as gateway.py `VOICE_DESTRUCTIVE_WORDS`. */
    val WORDS: Set<String> = setOf(
        "delete", "remove", "rm", "overwrite", "force", "push", "drop", "reset", "rebase", "wipe", "purge",
        "truncate", "deploy", "publish", "pay", "purchase", "charge",
    )

    /** Pure: whether [question], normalised by [ApprovalPhrase.normalise], has one of [WORDS] as a whole word. */
    fun appearIn(question: String): Boolean = ApprovalPhrase.words(question).any { it in WORDS }
}
