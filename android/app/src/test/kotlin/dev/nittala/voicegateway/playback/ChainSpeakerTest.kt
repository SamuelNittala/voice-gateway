package dev.nittala.voicegateway.playback

import dev.nittala.voicegateway.audio.FocusResult
import dev.nittala.voicegateway.core.speech.Engine
import dev.nittala.voicegateway.core.speech.Engine.Chatterbox
import dev.nittala.voicegateway.core.speech.Engine.Deepgram
import dev.nittala.voicegateway.core.speech.Engine.Phone
import dev.nittala.voicegateway.core.speech.EngineChain
import dev.nittala.voicegateway.playback.PlaybackEvent.FellBack
import dev.nittala.voicegateway.playback.PlaybackEvent.Failed
import dev.nittala.voicegateway.playback.PlaybackEvent.Finished
import dev.nittala.voicegateway.playback.PlaybackEvent.Interrupted
import dev.nittala.voicegateway.playback.PlaybackEvent.Started
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec (A9): [ChainSpeaker] with scripted engines, a fake focus gate and virtual time as the clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChainSpeakerTest {
    private val all = EngineChain.of(listOf("chatterbox", "deepgram"))
    private val note1 = "Chatterbox unavailable, used Deepgram"
    private val note2 = "Chatterbox and Deepgram unavailable, used the phone's voice"

    private class Rig(scripts: Map<Engine, List<Step>>, focusAnswer: FocusResult = FocusResult.Granted) {
        val voice = ScriptedVoice(scripts)
        val focus = FakeFocusGate(focusAnswer)
        val speaker = ChainSpeaker(Engine.entries.associateWith { voice }, focus)
    }

    private fun rig(c: List<Step>, d: List<Step> = Scripts.plays, p: List<Step> = Scripts.plays) =
        Rig(mapOf(Chatterbox to c, Deepgram to d, Phone to p))

    /** Plays to the end in virtual time. */
    private fun TestScope.playAll(r: Rig, chain: EngineChain = all, withName: Boolean = false): List<PlaybackEvent> {
        val out = mutableListOf<PlaybackEvent>()
        launch { r.speaker.play(event(), withName, chain).toList(out) }
        advanceUntilIdle()
        return out
    }

    @Test
    fun firstEnginePlays() = runTest {
        val r = rig(Scripts.plays)
        assertEquals(listOf(Started(Chatterbox), Finished), playAll(r))
        assertEquals(listOf(Chatterbox), r.voice.asked)
    }

    @Test
    fun chatterboxFailsThenDeepgramPlaysWithTheNote() = runTest {
        val r = rig(Scripts.failsBeforeStart())
        assertEquals(
            listOf(Failed(Chatterbox, "HTTP 502"), FellBack(Chatterbox, Deepgram, note1), Started(Deepgram), Finished),
            playAll(r),
        )
        assertEquals(listOf(Chatterbox, Deepgram), r.voice.asked)
    }

    @Test
    fun bothServerVoicesFailThenThePhoneSpeaks() = runTest {
        val r = rig(Scripts.failsBeforeStart(), Scripts.failsBeforeStart("HTTP 404"))
        assertEquals(
            listOf(
                Failed(Chatterbox, "HTTP 502"), FellBack(Chatterbox, Deepgram, note1),
                Failed(Deepgram, "HTTP 404"), FellBack(Deepgram, Phone, note2),
                Started(Phone), Finished,
            ),
            playAll(r),
        )
    }

    @Test
    fun everythingFailsEndsWithATerminalFailureAndNoRepeat() = runTest {
        val r = rig(Scripts.failsBeforeStart(), Scripts.failsBeforeStart(), Scripts.failsBeforeStart("tts error"))
        val out = playAll(r)
        assertEquals(Failed(Phone, "tts error"), out.last())
        assertEquals(listOf(Chatterbox, Deepgram, Phone), r.voice.asked) // each engine exactly once
    }

    @Test
    fun aChainWithoutServerVoicesUsesOnlyThePhone() = runTest {
        val r = rig(Scripts.plays)
        assertEquals(listOf(Started(Phone), Finished), playAll(r, EngineChain.of(listOf("chatterbox"), Phone)))
        assertEquals(listOf(Phone), r.voice.asked)
    }

    @Test
    fun anEngineWithNoPlayerCountsAsFailed() = runTest {
        val voice = ScriptedVoice(mapOf(Deepgram to Scripts.plays))
        val speaker = ChainSpeaker(mapOf(Deepgram to voice), FakeFocusGate())
        val out = mutableListOf<PlaybackEvent>()
        launch { speaker.play(event(), false, all).toList(out) }
        advanceUntilIdle()
        assertEquals(
            listOf(Failed(Chatterbox, ChainSpeaker.NO_PLAYER), FellBack(Chatterbox, Deepgram, note1), Started(Deepgram), Finished),
            out,
        )
    }

    @Test
    fun startLimitGivesUpAt15SecondsAndStopsTheLateStart() = runTest {
        val r = rig(Scripts.startsLate)
        val out = mutableListOf<PlaybackEvent>()
        launch { r.speaker.play(event(), false, all).toList(out) }
        advanceTimeBy(14_999)
        runCurrent()
        assertEquals(emptyList<PlaybackEvent>(), out)
        assertEquals(listOf(Chatterbox), r.voice.asked)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(Failed(Chatterbox, ChainSpeaker.NOT_STARTED), FellBack(Chatterbox, Deepgram, note1)), out)
        assertEquals(listOf(Chatterbox), r.voice.stopped) // given up: its late start can never play
        advanceUntilIdle()
        assertFalse(Started(Chatterbox) in out)
        assertEquals(listOf(Started(Deepgram), Finished), out.drop(2))
    }

    @Test
    fun aClipThatNeverStartsFallsBackOnEachEngine() = runTest {
        val r = rig(Scripts.neverStarts, Scripts.neverStarts, Scripts.neverStarts)
        val out = playAll(r)
        assertEquals(Failed(Phone, ChainSpeaker.NOT_STARTED), out.last())
        assertEquals(listOf(Chatterbox, Deepgram, Phone), r.voice.stopped)
        assertEquals(45_000, currentTime)
    }

    @Test
    fun aClipThatBreaksPartWayEndsWithoutASecondVoice() = runTest {
        val r = rig(Scripts.breaksPartWay())
        assertEquals(listOf(Started(Chatterbox), Failed(Chatterbox, "stream cut")), playAll(r))
        assertEquals(listOf(Chatterbox), r.voice.asked)
    }

    @Test
    fun playLimitStopsAClipThatNeverEnds() = runTest {
        val r = rig(Scripts.startsAndHangs)
        val out = playAll(r)
        assertEquals(listOf(Started(Chatterbox), Finished), out)
        assertEquals(listOf(Chatterbox), r.voice.stopped)
        assertEquals(60_300, currentTime)
    }

    private fun interruptDuring(engine: Engine, halt: (ChainSpeaker) -> Unit) = runTest {
        val fail = Scripts.failsBeforeStart()
        val r = when (engine) {
            Chatterbox -> rig(Scripts.startsAndHangs)
            Deepgram -> rig(fail, Scripts.startsAndHangs)
            Phone -> rig(fail, fail, Scripts.startsAndHangs)
        }
        val out = mutableListOf<PlaybackEvent>()
        val job = launch { r.speaker.play(event(), false, all).toList(out) }
        advanceTimeBy(5_000)
        assertEquals(Started(engine), out.last())
        halt(r.speaker)
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(Interrupted, out.last())
        assertEquals(listOf(engine), r.voice.stopped) // audio stopped at once
        assertEquals(engine, r.voice.asked.last()) // nothing tried after it
        assertFalse(r.focus.held)
    }

    @Test fun interruptDuringChatterbox() = interruptDuring(Chatterbox) { it.interrupt() }

    @Test fun interruptDuringDeepgram() = interruptDuring(Deepgram) { it.interrupt() }

    @Test fun interruptDuringThePhoneVoice() = interruptDuring(Phone) { it.interrupt() }

    @Test fun stopDuringPlayback() = interruptDuring(Deepgram) { it.stop() }

    @Test fun muteStopsEveryEngine() {
        interruptDuring(Chatterbox) { it.muted = true }
        interruptDuring(Deepgram) { it.muted = true }
        interruptDuring(Phone) { it.muted = true }
    }

    @Test
    fun interruptWhileWaitingForTheFirstSound() = runTest {
        val r = rig(Scripts.neverStarts)
        val out = mutableListOf<PlaybackEvent>()
        launch { r.speaker.play(event(), false, all).toList(out) }
        advanceTimeBy(3_000)
        r.speaker.interrupt()
        runCurrent()
        assertEquals(listOf(Interrupted), out)
        assertEquals(listOf(Chatterbox), r.voice.stopped)
        advanceUntilIdle()
        assertEquals(listOf(Chatterbox), r.voice.asked)
    }

    @Test
    fun playWhileMutedAsksNoEngine() = runTest {
        val r = rig(Scripts.plays)
        r.speaker.muted = true
        assertEquals(listOf(Interrupted), playAll(r))
        assertEquals(emptyList<Engine>(), r.voice.asked)
        assertEquals(0, r.focus.acquired)
        r.speaker.muted = false
        assertEquals(listOf(Started(Chatterbox), Finished), playAll(r))
    }

    @Test
    fun anInterruptBeforeThePlayDoesNotStopTheNextOne() = runTest {
        val r = rig(Scripts.plays)
        r.speaker.interrupt()
        r.speaker.stop()
        assertEquals(listOf(Started(Chatterbox), Finished), playAll(r))
    }

    @Test
    fun focusIsHeldWhilePlayingAndReleasedAfter() = runTest {
        val r = rig(Scripts.failsBeforeStart())
        val out = mutableListOf<PlaybackEvent>()
        launch { r.speaker.play(event(), false, all).toList(out) }
        advanceTimeBy(1_000)
        assertTrue(r.focus.held)
        assertEquals(1, r.focus.acquired)
        advanceUntilIdle()
        assertFalse(r.focus.held)
        assertEquals(1, r.focus.acquired) // once per event, not per engine
    }

    @Test
    fun refusedFocusAsksNoEngine() = runTest {
        val r = Rig(mapOf(Chatterbox to Scripts.plays), FocusResult.Denied)
        assertEquals(listOf(Failed(Chatterbox, ChainSpeaker.NO_FOCUS)), playAll(r))
        assertEquals(emptyList<Engine>(), r.voice.asked)
    }

    @Test
    fun focusLostWhilePlayingStopsTheAudio() = runTest {
        val r = rig(Scripts.startsAndHangs)
        val out = mutableListOf<PlaybackEvent>()
        launch { r.speaker.play(event(), false, all).toList(out) }
        advanceTimeBy(1_000)
        r.focus.duck() // a short sound elsewhere: keep playing
        runCurrent()
        assertEquals(listOf(Started(Chatterbox)), out)
        r.focus.lose(permanent = false)
        runCurrent()
        assertEquals(listOf(Started(Chatterbox), Interrupted), out)
        assertEquals(listOf(Chatterbox), r.voice.stopped)
    }

    @Test
    fun theEventAndWithNameGoToEveryEngineUnchanged() = runTest {
        val r = rig(Scripts.failsBeforeStart(), Scripts.failsBeforeStart())
        playAll(r, withName = true)
        assertEquals(List(3) { "Tests pass." to true }, r.voice.texts)
    }
}
