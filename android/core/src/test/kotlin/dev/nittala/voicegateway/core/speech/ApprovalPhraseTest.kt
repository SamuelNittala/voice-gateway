package dev.nittala.voicegateway.core.speech

import dev.nittala.voicegateway.core.model.AskKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalPhraseTest {
    @Test
    fun everyEntryIsNormalisedAndAtLeastThreeWords() {
        for (phrase in ApprovalPhrase.PHRASES) {
            assertTrue(phrase, phrase.split(" ").size >= 3)
            assertEquals(phrase, ApprovalPhrase.normalise(phrase))
        }
    }

    /** The server's own cases, test_gateway.py `test_normalise_phrase`. */
    @Test
    fun normaliseMatchesTheServerCases() {
        val cases = mapOf(
            "Yes, go ahead!" to "yes go ahead", "Yes, that's right." to "yes thats right",
            "Yes, that’s right" to "yes thats right", "  YES   do   it " to "yes do it",
            "yes... please-proceed?" to "yes please proceed", "" to "", "!!!" to "", "rm -rf build/" to "rm rf build",
        )
        for ((text, expected) in cases) assertEquals(text, expected, ApprovalPhrase.normalise(text))
    }

    @Test
    fun normaliseFollowsPythonWordCharacters() {
        assertEquals("snake case", ApprovalPhrase.normalise("snake_case")) // underscore splits, as [\W_]+
        assertEquals("café 42 ½", ApprovalPhrase.normalise("Café\t42\n½!")) // letters and numbers of any kind
        assertEquals("привет мир", ApprovalPhrase.normalise("Привет, мир!"))
        assertEquals("a b", ApprovalPhrase.normalise("a — b")) // no-break space and dash
        assertEquals("dont", ApprovalPhrase.normalise("DON'T"))
    }

    @Test
    fun spokenApprovalsMatch() {
        for (text in listOf(
            "Yes, go ahead!", "yes that's right.", "YES DO IT", "Yes, please proceed.", "yes, that’s right",
            " yes   go-ahead ", "Yes. Do it.",
        )) assertTrue(text, ApprovalPhrase.matches(text))
    }

    @Test
    fun shortOrPartialRepliesDoNotMatch() {
        for (text in listOf(
            "yes", "yeah", "sure", "okay", "go ahead", "yes go", "yes go ahead and delete it", "no go ahead",
            "yes yes go ahead", "", "yes do", "do it", "yes please", "yes that is right",
        )) assertFalse(text, ApprovalPhrase.matches(text))
    }

    /** A plain ASK: question (`ask: true`): a plain yes, sure, go ahead or the like approves, as the server says. */
    @Test
    fun aPlainQuestionTakesAPlainYes() {
        for (text in listOf(
            "yes", "Yes.", "yeah", "Sure, go ahead.", "go ahead", "Yes please", "do it", "yes, sure", "Yeah, go ahead!",
            "Yes, go ahead!", "yes that's right",
        )) assertTrue(text, ApprovalPhrase.approves(text, AskKind.Answer))
        for (text in listOf(
            "no", "nope", "no go ahead", "yes go ahead and delete it", "the api one", "", "okay", "cancel", "stop",
            "yes but not the tests",
        )) assertFalse(text, ApprovalPhrase.approves(text, AskKind.Answer))
    }

    /** A destructive question (`ask: "confirm"`, ASK!:): only the strict phrases, whatever the plain list holds. */
    @Test
    fun aDestructiveQuestionTakesOnlyTheStrictPhrases() {
        for (text in listOf("Yes, go ahead!", "yes do it", "Yes, please proceed.", "yes, that’s right"))
            assertTrue(text, ApprovalPhrase.approves(text, AskKind.Confirm))
        for (text in ApprovalPhrase.PLAIN.filter { it !in ApprovalPhrase.PHRASES } + listOf("sure go", "yes yes go ahead"))
            assertFalse(text, ApprovalPhrase.approves(text, AskKind.Confirm))
    }

    @Test
    fun notAQuestionIsNeverAnApproval() {
        for (text in listOf("yes", "yes go ahead")) assertFalse(text, ApprovalPhrase.approves(text, AskKind.None))
    }

    @Test
    fun thePlainListIsNormalisedAndNeverNegative() {
        for (phrase in ApprovalPhrase.PLAIN) {
            assertEquals(phrase, ApprovalPhrase.normalise(phrase))
            assertTrue(phrase, ApprovalPhrase.words(phrase).none { it in setOf("no", "not", "dont", "nope", "but") })
        }
        assertTrue(ApprovalPhrase.PLAIN.containsAll(listOf("yes", "sure go ahead")))
    }
}
