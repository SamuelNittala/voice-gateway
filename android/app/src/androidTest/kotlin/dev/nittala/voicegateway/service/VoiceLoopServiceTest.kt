package dev.nittala.voicegateway.service

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.nittala.voicegateway.VoiceGatewayApplication
import dev.nittala.voicegateway.core.loop.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec (A10, plan section 6), on a device: the notification channel, and the service going to the foreground with
 * its notification and stopping itself because the loop is not running (logged out on a fresh install). Starting
 * the real loop needs a gateway and a person; that is the live check (A12).
 */
@RunWith(AndroidJUnit4::class)
class VoiceLoopServiceTest {
    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val notifications get() = context.getSystemService(NotificationManager::class.java)

    private fun waitFor(ms: Long, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    private fun ours() = notifications.activeNotifications.any { it.id == Notifications.NOTIFICATION_ID }

    @Test
    fun theChannelIsQuiet() {
        val channel = notifications.getNotificationChannel(Notifications.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    @Test
    fun aStartWhileTheLoopIsOffEndsItselfAndRemovesTheNotification() {
        val graph = (context.applicationContext as VoiceGatewayApplication).graph
        assertFalse(graph.controller.running.value)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            context.startForegroundService(Intent(context, VoiceLoopService::class.java))
        }
        assertTrue("the service stayed up with the loop off", waitFor(5_000) { !ours() })
        assertTrue(graph.controller.state.value.phase == Phase.LoggedOut || graph.controller.state.value.phase == Phase.Idle)
    }

    @Test
    fun aNotificationTapActionWithNoServiceStartsNothing() {
        context.sendBroadcast(Intent(Notifications.ACTION_TAP).setPackage(context.packageName))
        Thread.sleep(1_000)
        assertFalse(ours())
    }
}
