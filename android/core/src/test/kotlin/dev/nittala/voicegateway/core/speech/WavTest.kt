package dev.nittala.voicegateway.core.speech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WavTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun headerOfTwoSamplesIsTheCanonicalBytes() {
        val wav = Wav.encode(shortArrayOf(0x0102, -2))
        val expected = bytes(
            'R'.code, 'I'.code, 'F'.code, 'F'.code, 40, 0, 0, 0,
            'W'.code, 'A'.code, 'V'.code, 'E'.code,
            'f'.code, 'm'.code, 't'.code, ' '.code, 16, 0, 0, 0,
            1, 0, 1, 0,
            0x80, 0x3E, 0, 0, // 16000
            0x00, 0x7D, 0, 0, // 32000
            2, 0, 16, 0,
            'd'.code, 'a'.code, 't'.code, 'a'.code, 4, 0, 0, 0,
            0x02, 0x01, 0xFE, 0xFF,
        )
        assertArrayEquals(expected, wav)
    }

    @Test
    fun emptyPcmIsAHeaderOnly() {
        val wav = Wav.encode(ShortArray(0))
        assertEquals(Wav.HEADER_BYTES, wav.size)
        assertEquals(36, le32(wav, 4))
        assertEquals(0, le32(wav, 40))
    }

    @Test
    fun extremeSamplesAreLittleEndian() {
        val wav = Wav.encode(shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE, -1))
        assertArrayEquals(bytes(0xFF, 0x7F, 0x00, 0x80, 0xFF, 0xFF), wav.copyOfRange(44, 50))
    }

    @Test
    fun sizesForTheTwentyEightSecondCapFitTheServerLimit() {
        val samples = 28 * Wav.SAMPLE_RATE
        val wav = Wav.encode(ShortArray(samples))
        assertEquals(896_044, wav.size)
        assertEquals(samples * 2 + 36, le32(wav, 4))
        assertEquals(samples * 2, le32(wav, 40))
        assertTrue(wav.size < Wav.SERVER_MAX_BYTES)
        assertEquals(2 * 1024 * 1024, Wav.SERVER_MAX_BYTES)
    }

    @Test
    fun inputIsNotChangedAndEverySampleRoundTrips() {
        val pcm = ShortArray(1000) { (it * 97 - 30_000).toShort() }
        val copy = pcm.copyOf()
        val wav = Wav.encode(pcm)
        assertArrayEquals(copy, pcm)
        for (i in pcm.indices) {
            val lo = wav[44 + 2 * i].toInt() and 0xFF
            val hi = wav[45 + 2 * i].toInt()
            assertEquals(pcm[i], ((hi shl 8) or lo).toShort())
        }
    }

    private fun le32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
}
