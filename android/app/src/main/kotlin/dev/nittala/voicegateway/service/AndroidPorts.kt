package dev.nittala.voicegateway.service

import android.content.Context
import android.content.Intent
import android.os.VibrationEffect
import android.os.VibratorManager

/** [ServiceControl] for [VoiceLoopService]. A refused start (background start rule) is swallowed. */
class AndroidServiceControl(private val context: Context) : ServiceControl {
    private val intent get() = Intent(context, VoiceLoopService::class.java)

    override fun start() {
        try {
            context.startForegroundService(intent)
        } catch (_: RuntimeException) {
            // ForegroundServiceStartNotAllowedException and friends: the loop still runs while the app is visible.
        }
    }

    override fun stop() {
        context.stopService(intent)
    }
}

/** [Haptics] on the default vibrator: one short pulse. */
class VibratorHaptics(private val context: Context) : Haptics {
    override fun vibrate() {
        val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        vibrator.vibrate(VibrationEffect.createOneShot(PULSE_MS, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private companion object {
        const val PULSE_MS = 250L
    }
}
