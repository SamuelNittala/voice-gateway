package dev.nittala.voicegateway.playback

import dev.nittala.voicegateway.core.speech.Engine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec (A9): [PhoneTtsSpeaker] over a fake [TtsVoice]. */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneTtsSpeakerTest {
    private class FakeTts : TtsVoice {
        val spoken = mutableListOf<String>()
        var stops = 0
        var signal: ((TtsSignal) -> Unit)? = null

        override fun speak(text: String, utteranceId: String, onSignal: (TtsSignal) -> Unit) {
            spoken += text
            signal = onSignal
        }

        override fun stop() {
            stops++
        }
    }

    @Test
    fun speaksTheEventsOwnTextOnlyAndReportsStartAndEnd() = runTest {
        val tts = FakeTts()
        val out = mutableListOf<ClipEvent>()
        val job = launch { PhoneTtsSpeaker(tts).play(event(text = "Done."), withName = true, engine = Engine.Phone).toList(out) }
        runCurrent()
        assertEquals(listOf("Done."), tts.spoken) // no worktree name, nothing added
        tts.signal!!(TtsSignal.Started)
        tts.signal!!(TtsSignal.Done)
        runCurrent()
        assertEquals(listOf(ClipEvent.Started, ClipEvent.Finished), out)
        assertEquals(true, job.isCompleted)
        assertEquals(0, tts.stops)
    }

    @Test
    fun anErrorBeforeTheStartIsAFailure() = runTest {
        val tts = FakeTts()
        val out = mutableListOf<ClipEvent>()
        launch { PhoneTtsSpeaker(tts).play(event(), false, Engine.Phone).toList(out) }
        runCurrent()
        tts.signal!!(TtsSignal.Error("no voice"))
        runCurrent()
        assertEquals(listOf(ClipEvent.Failed("no voice")), out)
    }

    @Test
    fun anErrorAfterTheStartIsAPartWayFailure() = runTest {
        val tts = FakeTts()
        val out = mutableListOf<ClipEvent>()
        launch { PhoneTtsSpeaker(tts).play(event(), false, Engine.Phone).toList(out) }
        runCurrent()
        tts.signal!!(TtsSignal.Started)
        tts.signal!!(TtsSignal.Error("synthesis"))
        runCurrent()
        assertEquals(listOf(ClipEvent.Started, ClipEvent.Failed("synthesis")), out)
    }

    @Test
    fun cancellingStopsTheVoice() = runTest {
        val tts = FakeTts()
        val job = launch { PhoneTtsSpeaker(tts).play(event(), false, Engine.Phone).toList(mutableListOf()) }
        runCurrent()
        tts.signal!!(TtsSignal.Started)
        runCurrent()
        job.cancel()
        runCurrent()
        assertEquals(1, tts.stops)
    }

    @Test
    fun anotherEngineIsRefused() = runTest {
        val tts = FakeTts()
        val out = PhoneTtsSpeaker(tts).play(event(), false, Engine.Deepgram).toList()
        assertEquals(1, out.size)
        assertEquals(true, out.single() is ClipEvent.Failed)
        assertEquals(emptyList<String>(), tts.spoken)
    }
}
