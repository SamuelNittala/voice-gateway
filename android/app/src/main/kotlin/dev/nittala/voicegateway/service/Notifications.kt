package dev.nittala.voicegateway.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import dev.nittala.voicegateway.MainActivity
import dev.nittala.voicegateway.core.ui.OrbTap
import dev.nittala.voicegateway.core.ui.VoiceUiState

/** Pure: the notification's line: a loop notice ("wt-foo asked a question") or "<status>, <worktree>". */
fun notificationText(ui: VoiceUiState, notice: String?): String =
    notice ?: listOf(ui.title, ui.worktree).filter { it.isNotBlank() }.joinToString(", ")

/** Pure: the label of the notification's first action, the orb's tap in this state; null when the orb does nothing. */
fun notificationTapLabel(ui: VoiceUiState): String? = when (ui.orbTap) {
    OrbTap.Start -> "Resume"
    OrbTap.Interrupt -> "Skip"
    OrbTap.Tap -> if (ui.confirmButtons) "Repeat" else if (ui.micOn) "Pause" else null
    OrbTap.None -> null
}

/**
 * Spec (A10, plan section 6): the ongoing media-style notification of the voice loop. Its actions are broadcasts to
 * this app only, handled by a receiver the running service registers, so a tap on them can never start the service.
 */
object Notifications {
    const val CHANNEL_ID = "voice_loop"
    const val NOTIFICATION_ID = 1
    const val ACTION_TAP = "dev.nittala.voicegateway.action.TAP"
    const val ACTION_END = "dev.nittala.voicegateway.action.END"

    /** Creates the low-importance channel (no sound of its own; the loop makes its own sounds). Idempotent. */
    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Voice loop", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows while the voice loop runs, with Pause and End."
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun build(context: Context, ui: VoiceUiState, notice: String?, session: MediaSession.Token?): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Voice Gateway")
            .setContentText(notificationText(ui, notice))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        val actions = mutableListOf<Int>()
        notificationTapLabel(ui)?.let {
            builder.addAction(Notification.Action.Builder(null, it, broadcast(context, ACTION_TAP, 1)).build())
            actions += actions.size
        }
        builder.addAction(Notification.Action.Builder(null, "End", broadcast(context, ACTION_END, 2)).build())
        actions += actions.size
        builder.setStyle(
            Notification.MediaStyle().setMediaSession(session).setShowActionsInCompactView(*actions.toIntArray()),
        )
        return builder.build()
    }

    private fun broadcast(context: Context, action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        context, code, Intent(action).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE,
    )
}
