package dev.nittala.voicegateway.core.worktree

import dev.nittala.voicegateway.core.api.Fixtures
import dev.nittala.voicegateway.core.model.PlanAnswer
import dev.nittala.voicegateway.core.model.planAnswerOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec (W6, plan section 5 test 5): what the phone says when the card appears. */
class CardSpeechTest {
    private fun plan(name: String) = planAnswerOf(Fixtures.load(name).bodyText) as PlanAnswer.Plan

    private val allPlans get() = listOf("plan_new_ready", "plan_existing", "plan_needs_base", "plan_close_matches", "plan_on_origin").map(::plan)

    @Test
    fun aReadyNewBranch() {
        assertEquals(
            "New branch fix/login-timeout in web-app, from origin production-4.6.2: fix the session timeout. " +
                "Say create it, or cancel.",
            CardSpeech.of(plan("plan_new_ready")),
        )
    }

    @Test
    fun anExistingBranch() {
        assertEquals(
            "Existing branch fix/login-timeout in web-app, checked out as it is. Say create it, or cancel.",
            CardSpeech.of(plan("plan_existing")),
        )
    }

    @Test
    fun notReadyAsksForABaseOnTheScreen() {
        val expected = "web-app, branch fix/login-timeout, needs a base branch. Pick one on the screen."
        assertEquals(expected, CardSpeech.of(plan("plan_needs_base")))
        assertEquals(expected, CardSpeech.of(plan("plan_close_matches")))
    }

    @Test
    fun aBaseNotDownloadedSaysSo() {
        assertEquals(
            "New branch fix/login-timeout in web-app, from origin production-4.6.4. On origin, not downloaded yet. " +
                "Say create it, or cancel.",
            CardSpeech.of(plan("plan_on_origin")),
        )
    }

    @Test
    fun neverAShaOrAPathAndAtMost250Characters() {
        for (p in allPlans) {
            val said = CardSpeech.of(p)
            p.base.sha?.let { assertFalse(said, said.contains(it)) }
            assertFalse(said, said.contains(p.path))
            assertFalse(said, said.contains("~/"))
            assertTrue(said, said.length <= CardSpeech.MAX_CHARS)
        }
    }

    @Test
    fun theSubjectIsCutAt80AtAWordAndShaOrPathWordsAreLeftOut() {
        val p = plan("plan_new_ready")
        val long = "Revert 1a2b3c4d of ~/src/app.kt because " + "the login keeps timing out ".repeat(6)
        val said = CardSpeech.of(p.copy(base = p.base.copy(subject = long)))
        val subject = said.substringAfter("production-4.6.2: ").substringBefore(". Say create it")
        assertTrue(subject, subject.length <= CardSpeech.SUBJECT_CHARS)
        assertFalse(said, said.contains("1a2b3c4d"))
        assertFalse(said, said.contains("~/src"))
        assertTrue(said, subject.split(" ").all { word -> long.split(" ").contains(word) })
        assertTrue(said.endsWith("Say create it, or cancel."))
    }

    @Test
    fun longNamesStayUnder250AndKeepTheLastSentence() {
        val p = plan("plan_new_ready")
        val huge = p.copy(branch = "feature/" + "very-long-branch-name-".repeat(5).trimEnd('-'), repo = p.repo.copy(name = "a-repo-with-a-really-long-name-".repeat(3)))
        val said = CardSpeech.of(huge)
        assertTrue(said, said.length <= CardSpeech.MAX_CHARS)
        assertTrue(said, said.endsWith(CardSpeech.SAY_CREATE))
    }

    @Test
    fun cutAtWord() {
        assertEquals("one two", CardSpeech.cutAtWord("one two three", 9))
        assertEquals("one two three", CardSpeech.cutAtWord("one two three", 13))
        assertEquals("abcdefghij", CardSpeech.cutAtWord("abcdefghijklmnop", 10))
        assertEquals("", CardSpeech.cutAtWord("", 5))
    }
}
