package dev.nittala.voicegateway.core.speech

import dev.nittala.voicegateway.core.model.AskKind

/**
 * Approval phrases (plan section 3, "Approval phrases"). Used only to label a sent line "approval" on screen and in
 * tests; it never changes what is sent. Mirrors gateway.py `normalise_phrase` and `VOICE_APPROVAL_PHRASES`; a test
 * reads both lists out of gateway.py, so a change on one side fails until the other follows.
 *
 * The strict [PHRASES] are needed only for a destructive question (`ask: "confirm"`, `ASK!:`); a plain `ASK:`
 * question (`ask: true`) is approved by a plain yes, sure, go ahead or the like ([PLAIN]), as the server's
 * instruction says.
 */
object ApprovalPhrase {
    /** Same entries and order as gateway.py `VOICE_APPROVAL_PHRASES`, already normalised, each 3+ words. */
    val PHRASES: List<String> = listOf("yes go ahead", "yes do it", "yes please proceed", "yes thats right")

    /** Whole replies, normalised, that approve a plain `ASK:` question besides [PHRASES]. Never a negative word. */
    val PLAIN: List<String> = listOf(
        "yes", "yeah", "sure", "go ahead", "do it", "yes please", "yes sure", "sure go ahead", "yeah go ahead",
        "sure do it", "please do", "please go ahead",
    )

    /**
     * Pure: whether [text] approves a question of kind [ask]. [AskKind.Confirm] takes only [PHRASES];
     * [AskKind.Answer] also takes [PLAIN]; [AskKind.None] (not a question) never approves.
     */
    fun approves(text: String, ask: AskKind): Boolean = when (ask) {
        AskKind.Confirm -> matches(text)
        AskKind.Answer -> matches(text) || normalise(text) in PLAIN
        AskKind.None -> false
    }

    /**
     * Pure, gateway.py `normalise_phrase`: lowercase; apostrophes (`'` and `’`) removed (`that's` -> `thats`);
     * every other run of characters that are not letters or digits becomes one space; trimmed.
     */
    fun normalise(text: String): String = ServerText.collapse(withoutApostrophes(text))

    /** Pure: whether the **whole** normalised [text] equals one entry of [PHRASES] (never a substring match). */
    fun matches(text: String): Boolean = normalise(text) in PHRASES

    /** Pure: the words of [normalise] (empty for empty text, like the server's `.split()`). */
    internal fun words(text: String): List<String> = ServerText.words(withoutApostrophes(text))

    /** Lowercasing first, as the server does, cannot create or remove an apostrophe, so the order is free here. */
    private fun withoutApostrophes(text: String) = text.replace("'", "").replace("’", "")
}
