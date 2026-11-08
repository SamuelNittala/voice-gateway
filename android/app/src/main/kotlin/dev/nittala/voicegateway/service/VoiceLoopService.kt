package dev.nittala.voicegateway.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import android.os.PowerManager
import android.view.KeyEvent
import dev.nittala.voicegateway.VoiceGatewayApplication
import dev.nittala.voicegateway.core.loop.LoopInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Spec (A10, plan section 6): the foreground service that keeps the voice loop alive with the screen off.
 *
 * - Type microphone|mediaPlayback. Started only by [ServiceControl.start] when the loop leaves Idle/LoggedOut, which
 *   only a tap in the visible app causes (Android 14+ while-in-use rule). It goes to the foreground at once.
 * - Shows the media-style notification ([Notifications]) with the loop's status, a Pause/Resume-style action (the
 *   orb's tap) and End; updated on every change. Actions are app-only broadcasts to a receiver registered here.
 * - A [MediaSession] (playing state, so media buttons route here) turns the headset button into loop inputs through
 *   [HeadsetButtons]: a press is a tap (never a Yes), a long press is Stop while the agent works.
 * - A partial wake lock (30-minute timeout, renewed on every change) is held only while the service runs, which is
 *   only while the loop runs.
 * - Stops itself as soon as the loop is no longer running (Idle, LoggedOut; `EndService` leads there too).
 * - Never restarts itself: START_NOT_STICKY. If the system kills it, the loop gets AppStopped and goes Idle; the user
 *   taps again. A start that cannot reach the foreground (no mic permission) stops at once WITHOUT AppStopped.
 */
class VoiceLoopService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var session: MediaSession? = null
    private var headset = HeadsetState()
    private var receiverRegistered = false

    /** It reached the foreground: only then was the loop running in it, so only then does its end stop the loop. */
    private var foreground = false

    private val controller get() = (application as VoiceGatewayApplication).graph.controller

    private val actions = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Notifications.ACTION_TAP -> controller.send(LoopInput.Tap)
                Notifications.ACTION_END -> controller.send(LoopInput.LongPress)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val ui = controller.ui.value
        val media = MediaSession(this, "VoiceGateway").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean = onMediaButton(mediaButtonIntent)
            })
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                    .build(),
            )
            isActive = true
        }
        session = media
        try {
            startForeground(
                Notifications.NOTIFICATION_ID,
                Notifications.build(this, ui, controller.notice.value, media.sessionToken),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } catch (_: RuntimeException) {
            // No mic permission, or started from the background: the loop runs only while the app is visible, so
            // this refused start must not end it (onDestroy sends no AppStopped): the new-worktree panel stays.
            stopSelf()
            return
        }
        foreground = true
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceGateway:loop")
            .apply { setReferenceCounted(false) }
        val filter = IntentFilter().apply {
            addAction(Notifications.ACTION_TAP)
            addAction(Notifications.ACTION_END)
        }
        registerReceiver(actions, filter, RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        scope.launch {
            combine(controller.ui, controller.notice, controller.running) { u, n, r -> Triple(u, n, r) }
                .collect { (u, n, running) ->
                    if (!running) {
                        stopSelf()
                        return@collect
                    }
                    wakeLock?.acquire(WAKE_LOCK_MS)
                    getSystemService(android.app.NotificationManager::class.java)
                        .notify(Notifications.NOTIFICATION_ID, Notifications.build(this@VoiceLoopService, u, n, media.sessionToken))
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        if (receiverRegistered) unregisterReceiver(actions)
        session?.run {
            isActive = false
            release()
        }
        wakeLock?.takeIf { it.isHeld }?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Killed or ended: the loop is gone (a no-op when it is already Idle). A service that never reached the
        // foreground never held the loop, so its end changes nothing.
        if (foreground) controller.send(LoopInput.AppStopped)
        super.onDestroy()
    }

    private fun onMediaButton(intent: Intent): Boolean {
        val event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) ?: return false
        if (event.keyCode !in HeadsetButtons.KEYS) return false
        val key = MediaKey(event.keyCode, event.action == KeyEvent.ACTION_DOWN, event.repeatCount, event.isLongPress)
        val (next, input) = HeadsetButtons.next(headset, key)
        headset = next
        input?.let(controller::send)
        return true
    }

    private companion object {
        const val WAKE_LOCK_MS = 30 * 60_000L
    }
}
