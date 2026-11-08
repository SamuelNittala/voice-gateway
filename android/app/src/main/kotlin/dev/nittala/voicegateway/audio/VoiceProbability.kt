package dev.nittala.voicegateway.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.Closeable
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Spec (A8): the speech probability (0..1) of one 512-sample frame of 16 kHz PCM16. Stateful: frames must be fed in
 * order, one instance per capture session. Not thread safe: call from the reading thread only. [close] frees it.
 */
interface VoiceProbability : Closeable {
    fun probability(frame: ShortArray): Float
}

/** Pure: PCM16 to the model's float input, `sample / 32767` (the scaling android-vad uses). */
fun pcmToModelInput(pcm: ShortArray): FloatArray = FloatArray(pcm.size) { pcm[it] / PCM_SCALE }

private const val PCM_SCALE = 32767f

/**
 * Silero VAD (the `silero_vad.onnx` model bundled by `com.github.gkonovalov.android-vad:silero`) run directly on
 * ONNX Runtime. android-vad's `VadSilero` only answers yes/no against its own fixed thresholds, while the detector
 * needs the raw probability (start >= 0.6, end < 0.35), so this runs the same model with the same tensors:
 * inputs `input` [1, 512] float, `sr` [1] long, `h` and `c` [2, 1, 64] float; outputs 0 = probability [1, 1],
 * 1 = new `h`, 2 = new `c`. A failed inference throws (the capture session then fails; never a guessed value).
 */
class SileroVoiceProbability(context: Context) : VoiceProbability {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private var h = FloatArray(STATE_SIZE)
    private var c = FloatArray(STATE_SIZE)

    init {
        val model = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
        }
        session = options.use { env.createSession(model, it) }
    }

    override fun probability(frame: ShortArray): Float {
        val input = OnnxTensor.createTensor(env, FloatBuffer.wrap(pcmToModelInput(frame)), longArrayOf(1, frame.size.toLong()))
        val sr = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(SAMPLE_RATE)), longArrayOf(1))
        val hIn = OnnxTensor.createTensor(env, FloatBuffer.wrap(h), STATE_SHAPE)
        val cIn = OnnxTensor.createTensor(env, FloatBuffer.wrap(c), STATE_SHAPE)
        try {
            session.run(mapOf("input" to input, "sr" to sr, "h" to hIn, "c" to cIn)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val out = result.get(0).value as Array<FloatArray>
                h = flatten(result.get(1).value)
                c = flatten(result.get(2).value)
                return out[0][0]
            }
        } finally {
            input.close()
            sr.close()
            hIn.close()
            cIn.close()
        }
    }

    override fun close() {
        session.close()
    }

    private fun flatten(value: Any): FloatArray {
        @Suppress("UNCHECKED_CAST")
        val nested = value as Array<Array<FloatArray>>
        return nested.flatMap { layer -> layer.flatMap { it.asIterable() } }.toFloatArray()
    }

    private companion object {
        const val MODEL_ASSET = "silero_vad.onnx"
        const val SAMPLE_RATE = 16_000L
        const val STATE_SIZE = 2 * 1 * 64
        val STATE_SHAPE = longArrayOf(2, 1, 64)
    }
}
