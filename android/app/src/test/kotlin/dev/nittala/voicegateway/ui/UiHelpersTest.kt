package dev.nittala.voicegateway.ui

import dev.nittala.voicegateway.core.speech.Engine
import dev.nittala.voicegateway.core.ui.OrbTap
import dev.nittala.voicegateway.core.ui.VoiceMode
import dev.nittala.voicegateway.core.ui.WorktreeGroup
import dev.nittala.voicegateway.core.ui.WorktreeItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiHelpersTest {
    @Test
    fun `samples cover every mode, each mapped to itself`() {
        assertEquals(VoiceMode.entries.toList(), SampleStates.byMode.keys.toList())
        SampleStates.byMode.forEach { (mode, state) -> assertEquals(mode, state.mode) }
    }

    @Test
    fun `the orb has a click label exactly when it is a button`() {
        SampleStates.all.forEach { s ->
            assertEquals(s.mode.toString(), s.orbTap != OrbTap.None, orbTapLabel(s) != null)
        }
        assertEquals("Stop speaking and listen", orbTapLabel(SampleStates.byMode.getValue(VoiceMode.Speaking)))
        assertEquals("Hear the question again", orbTapLabel(SampleStates.byMode.getValue(VoiceMode.AwaitingTap)))
        assertNotNull(orbTapLabel(SampleStates.byMode.getValue(VoiceMode.Idle)))
        assertNull(orbTapLabel(SampleStates.byMode.getValue(VoiceMode.Working)))
    }

    @Test
    fun `the flow's samples cover every screen and the orb label follows the mic`() {
        val screens = SampleStates.newWorktree.values.map { it.newWorktree!!::class }.toSet()
        assertEquals(7, screens.size)
        SampleStates.newWorktree.values.forEach { assertEquals(VoiceMode.NewWorktree, it.mode) }
        assertEquals("Stop listening", orbTapLabel(SampleStates.newWorktree.getValue("request")))
        assertEquals("Speak", orbTapLabel(SampleStates.newWorktree.getValue("request typed")))
        assertNull(orbTapLabel(SampleStates.newWorktree.getValue("creating")))
        assertEquals("4:59", countdownText(299))
        assertEquals("0:00", countdownText(-3))
    }

    @Test
    fun `picker rows say their state in words`() {
        val words = worktreeStatusWords(
            WorktreeItem("wt-a", WorktreeGroup.Stopped, "idle", problem = "herdr failed", openQuestion = true, waiting = true),
        )
        listOf("stopped", "agent idle", "question waiting", "reply pending", "problem: herdr failed").forEach {
            assertTrue(it, words.contains(it))
        }
        assertEquals("running", worktreeStatusWords(WorktreeItem("wt-b", WorktreeGroup.Running, null)))
    }

    @Test
    fun `key labels and default speaker`() {
        assertEquals(listOf("1", "2", "Up", "Down", "Enter", "Esc"), listOf("1", "2", "up", "down", "enter", "esc").map(::keyLabel))
        assertEquals(Engine.Chatterbox, shownSpeaker(null))
        assertEquals(Engine.Phone, shownSpeaker(Engine.Phone))
    }
}
