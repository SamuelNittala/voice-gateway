package dev.nittala.voicegateway.core.speech

import dev.nittala.voicegateway.core.speech.TranscriptRejection.Command
import dev.nittala.voicegateway.core.speech.TranscriptRejection.Empty
import dev.nittala.voicegateway.core.speech.TranscriptRejection.KnownPhrase
import dev.nittala.voicegateway.core.speech.TranscriptRejection.NonLatin
import dev.nittala.voicegateway.core.speech.TranscriptRejection.Repeated
import dev.nittala.voicegateway.core.speech.TranscriptRejection.TooFast
import dev.nittala.voicegateway.core.speech.TranscriptRejection.TooFewWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptGuardTest {
    private val plenty = 60_000L

    private fun judge(text: String, voicedMs: Long = plenty, answer: Boolean = false, latin: Boolean = true) =
        TranscriptGuard.judge(text, voicedMs, answer, latin)

    private fun assertRejected(reason: TranscriptRejection, text: String, voicedMs: Long = plenty, answer: Boolean = false, latin: Boolean = true) =
        assertEquals(text, TranscriptVerdict.Rejected(reason), judge(text, voicedMs, answer, latin))

    private fun assertAccepted(text: String, voicedMs: Long = plenty, answer: Boolean = false, latin: Boolean = true) =
        assertEquals(text, TranscriptVerdict.Accepted, judge(text, voicedMs, answer, latin))

    @Test
    fun limitsAreThePlans() {
        assertEquals(2, TranscriptGuard.MIN_WORDS)
        assertEquals(4, TranscriptGuard.MAX_WORDS_PER_VOICED_SECOND)
        assertEquals(3, TranscriptGuard.MAX_REPEATS)
        assertEquals(
            setOf("yes", "no", "yeah", "nope", "cancel", "stop", "one", "two", "three"), TranscriptGuard.ANSWER_WORDS,
        )
    }

    @Test
    fun realShortCommandsPass() {
        for (text in listOf(
            "run the tests", "Run the tests.", "what's the status?", "commit this", "fix the login bug",
            "Show me the diff.", "open a pull request", "thank you, now run the tests", "okay run the tests",
            "so what is next", "you can merge it now", "bye bye", "sorry, run it again", "yes go ahead",
            "Yes, that's right.", "no, do not do that", "use wt-api", "the api one", "Run test 3 again.",
        )) assertAccepted(text)
    }

    @Test
    fun emptyAndPunctuationOnly() {
        for (text in listOf("", "   ", "...", "?!", " - ", "\n\t", "…", " ")) assertRejected(Empty, text)
        for (text in listOf("", "...")) assertRejected(Empty, text, answer = true)
    }

    @Test
    fun slashAndBangNeverGoByVoice() {
        for (text in listOf(
            "/clear", "!rm -rf build", "  /compact now", "\u0000\u0007/help me", "\t!ls -la", "/", "! ! !",
        )) assertRejected(Command, text)
        assertRejected(Command, "/yes", answer = true)
        assertAccepted("run /tmp cleanup") // only a leading slash counts
    }

    @Test
    fun everyKnownPhraseAndHowWhisperWritesIt() {
        val heard = listOf(
            "Thank you.", "THANK YOU!", "  thanks for watching  ", "Thank you for watching!", "You", "Bye.",
            "Okay.", "So...", "Please subscribe.", "Subtitles by the Amara.org community", "I'm sorry.",
            "I’m sorry", "thank-you", "Thank you . ", "okay?!",
        )
        for (text in TranscriptGuard.NOISE_PHRASES + heard) {
            assertTrue(text, TranscriptGuard.isNoise(text))
            assertRejected(KnownPhrase, text)
            assertRejected(KnownPhrase, text, answer = true)
        }
    }

    @Test
    fun nearMissesAreNotNoise() {
        for (text in listOf(
            "thank you, now run the tests", "okay run the tests", "so what is next", "you can merge it now",
            "bye bye", "thank you very much for the fix", "sorry, run it again", "", "thank", "yous", "okay okay",
        )) assertFalse(text, TranscriptGuard.isNoise(text))
    }

    @Test
    fun fewerThanTwoWordsFailsOutsideAnswerMode() {
        for (text in listOf("yes", "Yes.", "no", "deploy", "main", "sure", "yeah!", "3")) assertRejected(TooFewWords, text)
    }

    @Test
    fun answerModeAllowsOnlyTheAnswerWords() {
        for (word in TranscriptGuard.ANSWER_WORDS) {
            assertAccepted(word, answer = true)
            assertAccepted("${word.replaceFirstChar { it.uppercase() }}.", answer = true)
            assertRejected(TooFewWords, word, answer = false)
        }
        for (text in listOf("sure", "main", "yep", "four", "proceed", "ok")) assertRejected(TooFewWords, text, answer = true)
        assertRejected(KnownPhrase, "okay", answer = true)
        assertRejected(KnownPhrase, "Okay.", answer = true)
        assertAccepted("the api one", answer = true) // every other guard applies unchanged
        assertRejected(TooFast, "the api one", voicedMs = 500, answer = true)
    }

    @Test
    fun wordRateLimitIsFourPerVoicedSecondInclusive() {
        // 8 words in 2 s is exactly 4/s: passes; 9 words fails.
        assertAccepted("one two three four five six seven eight", voicedMs = 2_000)
        assertRejected(TooFast, "one two three four five six seven eight nine", voicedMs = 2_000)
        assertAccepted("run the tests", voicedMs = 750)
        assertRejected(TooFast, "run the tests", voicedMs = 749)
        assertRejected(TooFast, "run the tests", voicedMs = 0)
        assertRejected(TooFast, "yes", voicedMs = 0, answer = true)
        assertAccepted("yes", voicedMs = 250, answer = true)
    }

    @Test
    fun propertyWordRateBoundaryForManyLengths() {
        for (words in 2..60) {
            val text = (1..words).joinToString(" ") { "w$it" }
            val boundary = (words * 1000 + 3) / 4 // smallest voicedMs with words/sec <= 4
            assertAccepted(text, voicedMs = boundary.toLong())
            assertRejected(TooFast, text, voicedMs = boundary.toLong() - 1)
        }
    }

    @Test
    fun phrasesRepeatedThreeTimesInARow() {
        for (text in listOf(
            "the the the", "run the tests run the tests run the tests", "I'm going to I'm going to I'm going to",
            "please fix it. please fix it. please fix it.", "ok so ok so ok so done", "start go go go go",
            "no no no",
        )) assertRejected(Repeated, text)
        assertRejected(Repeated, "no no no", answer = true)
        for (text in listOf(
            "put the file in the folder with the others", "run the tests run the tests", "bye bye",
            "go go now go", "one two one two one",
        )) assertAccepted(text)
    }

    @Test
    fun onlyNonLatinTextFailsWhenTheLanguageIsEnglish() {
        for (text in listOf("Продолжай работу", "字幕由社区提供", "ご視聴ありがとうございました", "شكرا لكم", "Σας ευχαριστώ πολύ")) {
            assertRejected(NonLatin, text)
            assertNotEquals(text, TranscriptVerdict.Rejected(NonLatin), judge(text, latin = false))
        }
        assertAccepted("rename it to Привет please") // mixed script passes
        assertAccepted("café au lait") // Latin with accents
        assertAccepted("run test 42") // digits are not letters
    }

    @Test
    fun rejectionNeverCarriesTheText() {
        val verdict = judge("Thanks for watching!")
        assertFalse(verdict.toString().contains("watching", ignoreCase = true))
    }

    @Test
    fun orderOfChecksDecidesTheReason() {
        assertRejected(Command, "/thank you")
        assertRejected(KnownPhrase, "you", answer = true)
        assertRejected(NonLatin, "Да")
        assertRejected(TooFewWords, "go", voicedMs = 0)
        assertRejected(Repeated, "go go go", voicedMs = 1)
    }
}
