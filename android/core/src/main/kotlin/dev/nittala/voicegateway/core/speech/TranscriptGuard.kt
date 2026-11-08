package dev.nittala.voicegateway.core.speech

/** Why [TranscriptGuard] refused a transcript. Holds no text, so it is safe to log. */
enum class TranscriptRejection {
    /** G4: starts with `/` or `!` (after control characters and leading whitespace), never sent by voice. */
    Command,

    /** Empty, whitespace or punctuation only. */
    Empty,

    /** The whole text is one of Whisper's known inventions ([TranscriptGuard.NOISE_PHRASES]). */
    KnownPhrase,

    /** Letters present but none of them Latin while the phone's language uses Latin script (Whisper drifting). */
    NonLatin,

    /** Fewer than [TranscriptGuard.MIN_WORDS] words (in answer mode one word from the answer set is allowed). */
    TooFewWords,

    /** The same word or phrase three or more times in a row (Whisper looping on noise). */
    Repeated,

    /** More than [TranscriptGuard.MAX_WORDS_PER_VOICED_SECOND] words per second of voiced audio. */
    TooFast,
}

/** The outcome of [TranscriptGuard.judge]. */
sealed interface TranscriptVerdict {
    data object Accepted : TranscriptVerdict

    data class Rejected(val reason: TranscriptRejection) : TranscriptVerdict
}

/**
 * G3 and the phone half of G4 (plan section 3): decides whether a Whisper transcript may be sent by voice.
 * Pure and deterministic. Checks, in this order (the first failing one is the reason):
 * 1. [TranscriptRejection.Command]: first character after control characters and leading whitespace is `/` or `!`
 *    (the server's S2 check: `clean_text` then `lstrip().startswith(("/", "!"))`), so a lone "/" is a command.
 * 2. [TranscriptRejection.Empty]: nothing left after server-style collapsing ([ServerText]).
 * 3. [TranscriptRejection.KnownPhrase]: [isNoise], the server's `is_voice_noise` (whole text, never a substring).
 * 4. [TranscriptRejection.NonLatin]: only when `expectLatin`; the text has letters and none is Latin script.
 * 5. [TranscriptRejection.TooFewWords]: words (of [ApprovalPhrase.normalise]) fewer than [MIN_WORDS]; in answer
 *    mode a single word from [ANSWER_WORDS] passes. "okay" is refused everywhere by rule 3.
 * 6. [TranscriptRejection.Repeated]: some run of n words (n >= 1) appears [MAX_REPEATS] or more times back to back.
 * 7. [TranscriptRejection.TooFast]: words / voiced seconds > [MAX_WORDS_PER_VOICED_SECOND]; exactly 4 passes.
 *    Zero voiced time fails.
 */
object TranscriptGuard {
    /** Same entries and order as gateway.py `VOICE_NOISE_PHRASES` (in `is_voice_noise`'s form). */
    val NOISE_PHRASES: List<String> = listOf(
        "thank you", "thanks for watching", "thank you for watching", "you", "bye", "okay", "so",
        "please subscribe", "subtitles by the amara org community", "i m sorry",
    )

    /** Plan section 3, "Answer mode": the only single words accepted, and only in answer mode. */
    val ANSWER_WORDS: Set<String> = setOf("yes", "no", "yeah", "nope", "cancel", "stop", "one", "two", "three")

    /** Plan section 3, G3: "fewer than 2 words" is refused. */
    const val MIN_WORDS = 2

    /** Plan section 3, G3: "more than 4 words per voiced second" is refused. */
    const val MAX_WORDS_PER_VOICED_SECOND = 4

    /** Plan section 3, G3: "a phrase repeated 3+ times" is refused. */
    const val MAX_REPEATS = 3

    /**
     * Pure. [voicedMs] is the utterance's voiced time from [UtteranceStats.voicedMs]; [answerMode] is true only in
     * AwaitingAnswer; [expectLatin] is true when the phone's language is written in Latin script (English).
     */
    fun judge(text: String, voicedMs: Long, answerMode: Boolean, expectLatin: Boolean): TranscriptVerdict {
        val words = ApprovalPhrase.words(text)
        val reason = when {
            isCommand(text) -> TranscriptRejection.Command
            ServerText.collapse(text).isEmpty() -> TranscriptRejection.Empty
            isNoise(text) -> TranscriptRejection.KnownPhrase
            expectLatin && hasOnlyNonLatinLetters(text) -> TranscriptRejection.NonLatin
            words.size < MIN_WORDS && !(answerMode && words.size == 1 && words[0] in ANSWER_WORDS) ->
                TranscriptRejection.TooFewWords
            hasRepeatedRun(words) -> TranscriptRejection.Repeated
            isTooFast(words.size, voicedMs) -> TranscriptRejection.TooFast
            else -> null
        }
        return reason?.let { TranscriptVerdict.Rejected(it) } ?: TranscriptVerdict.Accepted
    }

    /** Pure, gateway.py `is_voice_noise`: the collapsed text (apostrophes count as spaces) is in [NOISE_PHRASES]. */
    fun isNoise(text: String): Boolean = ServerText.collapse(text) in NOISE_PHRASES

    /** The server drops control characters (`clean_text`) and then leading whitespace before looking. */
    private fun isCommand(text: String): Boolean {
        val first = text.firstOrNull { !it.isWhitespace() && !isControl(it) }
        return first == '/' || first == '!'
    }

    private fun isControl(c: Char): Boolean = when (Character.getType(c).toByte()) {
        Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED -> true
        else -> false
    }

    private fun hasOnlyNonLatinLetters(text: String): Boolean {
        val letters = text.codePoints().filter { Character.isLetter(it) }.toArray()
        return letters.isNotEmpty() && letters.none { Character.UnicodeScript.of(it) == Character.UnicodeScript.LATIN }
    }

    /** Whether some run of n >= 1 words appears [MAX_REPEATS] or more times back to back. */
    private fun hasRepeatedRun(words: List<String>): Boolean =
        (1..words.size / MAX_REPEATS).any { n ->
            (0..words.size - n * MAX_REPEATS).any { start ->
                val unit = words.subList(start, start + n)
                (1 until MAX_REPEATS).all { k -> words.subList(start + k * n, start + (k + 1) * n) == unit }
            }
        }

    /** words / (voicedMs / 1000) > 4, in whole numbers; zero voiced time with any word is too fast. */
    private fun isTooFast(wordCount: Int, voicedMs: Long): Boolean =
        wordCount * 1000L > MAX_WORDS_PER_VOICED_SECOND * voicedMs
}
