package dev.nittala.voicegateway.core.speech

/**
 * WAV (RIFF) encoding of the captured utterance for `POST /api/transcribe` (`audio/wav`), plan section 2.
 *
 * The capture format is fixed: 16 kHz, mono, 16-bit signed little-endian PCM. The output is the canonical 44-byte
 * header followed by the samples:
 *
 * | offset | bytes | value                                   |
 * |--------|-------|-----------------------------------------|
 * | 0      | 4     | `RIFF`                                  |
 * | 4      | 4     | 36 + data size (little endian)          |
 * | 8      | 4     | `WAVE`                                  |
 * | 12     | 4     | `fmt `                                  |
 * | 16     | 4     | 16 (fmt chunk size)                     |
 * | 20     | 2     | 1 (PCM)                                 |
 * | 22     | 2     | 1 (channels)                            |
 * | 24     | 4     | 16000 (sample rate)                     |
 * | 28     | 4     | 32000 (byte rate = rate * 2)            |
 * | 32     | 2     | 2 (block align)                         |
 * | 34     | 2     | 16 (bits per sample)                    |
 * | 36     | 4     | `data`                                  |
 * | 40     | 4     | data size = samples * 2                 |
 * | 44     | n     | samples, little endian                  |
 *
 * 28 s (the utterance cap) is 896 044 bytes, under the server's 2 MiB `MAX_TRANSCRIBE_BYTES`.
 */
object Wav {
    const val SAMPLE_RATE = 16_000
    const val HEADER_BYTES = 44

    /** The server's upload cap, `MAX_TRANSCRIBE_BYTES` in gateway.py (2 MiB). */
    const val SERVER_MAX_BYTES = 2 * 1024 * 1024

    /** Pure: a new byte array holding the header and [pcm]; [pcm] is not changed. */
    fun encode(pcm: ShortArray): ByteArray {
        val dataBytes = pcm.size * 2
        val out = ByteArray(HEADER_BYTES + dataBytes)
        fun ascii(at: Int, text: String) = text.forEachIndexed { i, c -> out[at + i] = c.code.toByte() }
        fun le16(at: Int, value: Int) {
            out[at] = value.toByte()
            out[at + 1] = (value ushr 8).toByte()
        }
        fun le32(at: Int, value: Int) {
            le16(at, value)
            le16(at + 2, value ushr 16)
        }
        ascii(0, "RIFF")
        le32(4, 36 + dataBytes)
        ascii(8, "WAVE")
        ascii(12, "fmt ")
        le32(16, 16)
        le16(20, 1)
        le16(22, 1)
        le32(24, SAMPLE_RATE)
        le32(28, SAMPLE_RATE * 2)
        le16(32, 2)
        le16(34, 16)
        ascii(36, "data")
        le32(40, dataBytes)
        pcm.forEachIndexed { i, sample -> le16(HEADER_BYTES + 2 * i, sample.toInt()) }
        return out
    }
}
