package dev.nittala.voicegateway.core.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cases from test_gateway.py `SplitAskTests` (the destructive-word upgrade of `split_ask`). */
class DestructiveWordsTest {
    @Test
    fun everyWordInAnyCaseIsFound() {
        assertTrue(DestructiveWords.appearIn("Shall I delete the build folder?"))
        for (word in DestructiveWords.WORDS) {
            assertTrue(word, DestructiveWords.appearIn("Should I ${word.uppercase()} it?"))
            assertTrue(word, DestructiveWords.appearIn("$word?"))
        }
        assertTrue(DestructiveWords.appearIn("Force push to main?"))
        assertTrue(DestructiveWords.appearIn("rm -rf build/?"))
        assertTrue(DestructiveWords.appearIn("ok_to_delete?")) // underscore splits words, as on the server
    }

    @Test
    fun onlyWholeWordsCount() {
        for (text in listOf(
            "Which test file?", "Pushing ahead with the plan?", "Is the deployment fine?", "Removed or kept?",
            "Is the payment page right?", "", "?", "Shall I tidy up?", "charger", "dropdown",
        )) assertFalse(text, DestructiveWords.appearIn(text))
    }
}
