package dev.nittala.voicegateway.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineChainTest {
    private val both = listOf("chatterbox", "deepgram")

    /** Tries every engine of a fresh chain, failing each in turn; returns the order tried. */
    private fun tried(chain: EngineChain): List<Engine> =
        generateSequence(chain) { c -> c.current?.let { c.failed(it) } }.mapNotNull { it.current }.toList()

    @Test
    fun defaultIsChatterboxThenDeepgramThenPhone() =
        assertEquals(listOf(Engine.Chatterbox, Engine.Deepgram, Engine.Phone), tried(EngineChain.of(both)))

    @Test
    fun chatterboxChosenSameOrder() =
        assertEquals(listOf(Engine.Chatterbox, Engine.Deepgram, Engine.Phone), tried(EngineChain.of(both, Engine.Chatterbox)))

    @Test
    fun deepgramChosenSkipsChatterbox() =
        assertEquals(listOf(Engine.Deepgram, Engine.Phone), tried(EngineChain.of(both, Engine.Deepgram)))

    @Test
    fun phoneChosenUsesOnlyThePhone() = assertEquals(listOf(Engine.Phone), tried(EngineChain.of(both, Engine.Phone)))

    @Test
    fun onlyListedEnginesAreTried() {
        assertEquals(listOf(Engine.Deepgram, Engine.Phone), tried(EngineChain.of(listOf("deepgram"))))
        assertEquals(listOf(Engine.Chatterbox, Engine.Phone), tried(EngineChain.of(listOf("chatterbox"))))
        assertEquals(listOf(Engine.Phone), tried(EngineChain.of(emptyList())))
        assertEquals(listOf(Engine.Phone), tried(EngineChain.of(emptyList(), Engine.Deepgram)))
    }

    @Test
    fun unknownNamesDuplicatesAndPhoneInVoicesAreIgnored() =
        assertEquals(
            listOf(Engine.Chatterbox, Engine.Deepgram, Engine.Phone),
            tried(EngineChain.of(listOf("deepgram", "elevenlabs", "chatterbox", "deepgram", "phone", "browser"))),
        )

    @Test
    fun eachFailureMovesOnOnceAndNeverRepeats() {
        val c = EngineChain.of(both)
        val afterOne = c.failed(Engine.Chatterbox)
        assertEquals(Engine.Deepgram, afterOne.current)
        assertEquals(afterOne, afterOne.failed(Engine.Chatterbox)) // a repeat report changes nothing
        assertEquals(afterOne, afterOne.failed(Engine.Phone)) // not the current one: nothing
        assertEquals(Engine.Phone, afterOne.failed(Engine.Deepgram).current)
    }

    @Test
    fun allFailedGivesUp() {
        val done = EngineChain.of(listOf("deepgram")).failed(Engine.Deepgram).failed(Engine.Phone)
        assertNull(done.current)
        assertEquals(done, done.failed(Engine.Phone))
    }

    @Test
    fun noteNamesTheFailuresAndTheVoiceUsed() {
        val c = EngineChain.of(both)
        assertEquals("", c.note())
        assertEquals("Chatterbox unavailable, used Deepgram", c.failed(Engine.Chatterbox).note())
        assertEquals(
            "Chatterbox and Deepgram unavailable, used the phone's voice",
            c.failed(Engine.Chatterbox).failed(Engine.Deepgram).note(),
        )
    }

    @Test
    fun aChosenEngineNotSetUpCountsAsUnavailable() {
        val c = EngineChain.of(listOf("deepgram"), Engine.Chatterbox)
        assertEquals(Engine.Deepgram, c.current)
        assertEquals("Chatterbox unavailable, used Deepgram", c.note())
    }

    @Test
    fun noNoteOnceEverythingFailed() =
        assertEquals("", EngineChain.of(emptyList()).failed(Engine.Phone).note())
}
