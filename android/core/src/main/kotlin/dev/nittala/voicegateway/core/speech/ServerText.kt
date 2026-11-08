package dev.nittala.voicegateway.core.speech

import java.util.Locale

/**
 * Text normalisation shared with the server (gateway.py). Both server forms are
 * `re.sub(r"[\W_]+", " ", text.lower()).strip()` with Python's Unicode `\W`: a word character is a letter
 * (Unicode categories Lu, Ll, Lt, Lm, Lo) or a number (Nd, Nl, No); everything else, underscore included, splits.
 * Java's `\W`, even with UNICODE_CHARACTER_CLASS, differs (combining marks), so the split is done by hand.
 */
internal object ServerText {
    /** Pure: [text] lowercased, every run of non-word characters made one space, trimmed. */
    fun collapse(text: String): String = words(text).joinToString(" ")

    /** Pure: the runs of word characters of [text] lowercased, in order (the server's `.split()` of [collapse]). */
    fun words(text: String): List<String> {
        val lower = text.lowercase(Locale.ROOT)
        val words = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < lower.length) {
            val cp = lower.codePointAt(i)
            if (isWordChar(cp)) {
                current.appendCodePoint(cp)
            } else if (current.isNotEmpty()) {
                words += current.toString()
                current.setLength(0)
            }
            i += Character.charCount(cp)
        }
        if (current.isNotEmpty()) words += current.toString()
        return words
    }

    private fun isWordChar(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
        Character.MODIFIER_LETTER, Character.OTHER_LETTER,
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER,
        -> true
        else -> false
    }
}
