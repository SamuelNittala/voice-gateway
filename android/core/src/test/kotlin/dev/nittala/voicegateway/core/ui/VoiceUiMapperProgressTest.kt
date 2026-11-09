package dev.nittala.voicegateway.core.ui

import dev.nittala.voicegateway.core.loop.LoopInput
import dev.nittala.voicegateway.core.loop.PROGRESS
import dev.nittala.voicegateway.core.loop.Phase
import dev.nittala.voicegateway.core.loop.Sent
import dev.nittala.voicegateway.core.loop.TEXT
import dev.nittala.voicegateway.core.loop.VoiceLoop
import dev.nittala.voicegateway.core.loop.WORKING_AGENT
import dev.nittala.voicegateway.core.loop.progress
import dev.nittala.voicegateway.core.loop.st
import dev.nittala.voicegateway.core.loop.view
import org.junit.Assert.assertEquals
import org.junit.Test

/** Progress updates: the working screen's subtitle shows the latest progress line of this turn. */
class VoiceUiMapperProgressTest {
    private val working = Phase.AgentWorking(Sent(TEXT))

    @Test
    fun theSubtitleIsTheSentTextUntilAProgressLineCame() {
        assertEquals(TEXT, voiceUiState(st(working, WORKING_AGENT)).detail)
    }

    @Test
    fun theSubtitleIsTheLatestProgressLine() {
        val s = st(working, WORKING_AGENT).copy(lastProgress = "Running the suite")
        val ui = voiceUiState(s)
        assertEquals(VoiceMode.Working, ui.mode)
        assertEquals("Running the suite", ui.detail)
    }

    @Test
    fun aMaybeSentPromptStillSaysSo() {
        val s = st(Phase.AgentWorking(Sent(TEXT, maybeSent = true)), WORKING_AGENT).copy(lastProgress = "Running the suite")
        assertEquals("May not have been sent; check the output.", voiceUiState(s).detail)
    }

    @Test
    fun aHeardLineIsShownAfterwardsAndAnUnspokenOneToo() {
        val loop = VoiceLoop()
        val spoken = loop.step(st(working, WORKING_AGENT), LoopInput.View(view("working", events = listOf(PROGRESS))))
        val back = loop.step(spoken.state, LoopInput.PlaybackDone(PROGRESS.id))
        assertEquals(PROGRESS.text, voiceUiState(back.state).detail)
        val off = st(working, WORKING_AGENT).copy(spokenProgress = false)
        val silent = loop.step(off, LoopInput.View(view("working", events = listOf(progress("p2", text = "Building")))))
        assertEquals("Building", voiceUiState(silent.state).detail)
    }

    @Test
    fun aLongLineIsCutToTheGatewaysCap() {
        val loop = VoiceLoop()
        val long = progress("p4", text = "word ".repeat(40).trim())
        val off = st(working, WORKING_AGENT).copy(spokenProgress = false)
        val t = loop.step(off, LoopInput.View(view("working", events = listOf(long))))
        assertEquals(100, t.state.lastProgress?.length)
    }
}
