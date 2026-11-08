package dev.nittala.voicegateway

import android.app.Application
import dev.nittala.voicegateway.service.Notifications

/** Holds the one [AppGraph] of the process and creates the notification channel. */
class VoiceGatewayApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannel(this)
        graph = AppGraph(this).also { it.start() }
    }
}
