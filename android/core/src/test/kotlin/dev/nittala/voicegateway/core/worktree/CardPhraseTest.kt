package dev.nittala.voicegateway.core.worktree

import dev.nittala.voicegateway.core.speech.ApprovalPhrase
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec (W6, plan section 5 test 4): whole-phrase matching for the plan card, with the approval phrases' normaliser. */
class CardPhraseTest {
    @Test
    fun everyCreatePhraseAsWhisperWritesIt() {
        for (text in listOf(
            "create it", "Create it.", "CREATE IT!", "  create   it  ", "Yes, create it.", "yes create it",
            "Go ahead and create it.", "go ahead, and create it", "Create—it", "create it…",
        )) assertEquals(text, CardVerdict.Create, CardPhrase.judge(text))
    }

    @Test
    fun everyCancelPhraseAsWhisperWritesIt() {
        for (text in listOf("cancel", "Cancel.", "Cancel it!", "No, cancel.", "no cancel", "Stop.", "STOP"))
            assertEquals(text, CardVerdict.Cancel, CardPhrase.judge(text))
    }

    @Test
    fun nearMissesAreNotMatched() {
        for (text in listOf(
            "create it in backend", "create it in the other one", "yes", "okay create", "okay", "create",
            "please create it", "yes go ahead", "don't create it", "cancel that and create it", "stop it now",
            "create it create it", "", "...", "create the worktree", "go ahead",
        )) assertEquals(text, CardVerdict.Miss, CardPhrase.judge(text))
    }

    @Test
    fun thePhrasesAreAlreadyNormalisedWithTheSharedNormaliser() {
        for (phrase in CardPhrase.CREATE + CardPhrase.CANCEL) assertEquals(phrase, ApprovalPhrase.normalise(phrase))
        assertEquals(listOf("create it", "yes create it", "go ahead and create it"), CardPhrase.CREATE)
        assertEquals(listOf("cancel", "cancel it", "no cancel", "stop"), CardPhrase.CANCEL)
    }
}
