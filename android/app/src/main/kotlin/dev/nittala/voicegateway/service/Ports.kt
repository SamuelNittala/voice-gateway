package dev.nittala.voicegateway.service

import dev.nittala.voicegateway.core.api.GatewayApi
import dev.nittala.voicegateway.core.loop.EarconKind
import dev.nittala.voicegateway.core.loop.LoopInput
import dev.nittala.voicegateway.playback.Speaker
import kotlinx.coroutines.flow.Flow

/*
 * Spec (A10): the boundaries the LoopController drives. Each is an interface so the controller runs on the JVM with
 * fakes; the Android implementations live next to the hardware they wrap. None of them may log what they say, play
 * or send (no prompts, transcripts, passcodes, cookies or event text in logs).
 */

/** The phone's own voice for the loop's lines (`Effect.Say`). Returns once the line is over or failed; never throws. */
interface PhoneVoice {
    suspend fun say(text: String)
}

/** The loop's short tones (`Effect.Earcon`). Returns once the tone is over. */
interface Earcons {
    suspend fun play(kind: EarconKind)
}

/** `Effect.Vibrate`. */
fun interface Haptics {
    fun vibrate()
}

/**
 * The foreground service. [start] is called when the loop leaves Idle or LoggedOut, which only a tap in the visible
 * app can cause (Android 14+ allows a microphone service to start only then); [stop] on `Effect.EndService`. Both are
 * idempotent; a refused start is swallowed (the loop still runs while the app is visible).
 */
interface ServiceControl {
    fun start()
    fun stop()
}

/** Connectivity: emits true while a network with internet is up, false when it is lost. */
interface NetworkMonitor {
    val online: Flow<Boolean>
}

/** What one gateway base URL gives the loop: its API and its speaker (both bound to that URL and its cookie). */
class GatewayLink(val api: GatewayApi, val speaker: Speaker)

/** The link for the stored base URL, or null when none is set (logged out). Read on every use: login may change it. */
fun interface GatewayLinks {
    fun current(): GatewayLink?
}

/** Where loop inputs go, so the UI actions are tested without the controller. */
fun interface LoopInputs {
    fun send(input: LoopInput)
}
