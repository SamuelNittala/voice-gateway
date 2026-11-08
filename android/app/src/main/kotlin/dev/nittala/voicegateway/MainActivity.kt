package dev.nittala.voicegateway

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nittala.voicegateway.core.ui.withMicGate
import dev.nittala.voicegateway.permission.GatedActions
import dev.nittala.voicegateway.permission.MicPermissionPort
import dev.nittala.voicegateway.ui.VoiceGatewayApp
import dev.nittala.voicegateway.ui.VoiceUiActions
import dev.nittala.voicegateway.ui.theme.VoiceGatewayTheme

/**
 * Entry point, edge-to-edge: draws the controller's state with the real actions. Every action that will open the
 * microphone goes through the app's one permission gate ([AppGraph.micGate]): Android's prompt is shown when needed
 * (with the optional notification permission), the action runs once allowed, and a refusal shows a message that
 * stays (with "Open settings" once Android will not ask again). Coming back to the app re-reads the permission.
 * Visible/hidden reaches the loop (polling of the sessions list, the loop's AppVisible).
 */
class MainActivity : ComponentActivity() {
    private val graph get() = (application as VoiceGatewayApplication).graph

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        // Only the microphone decides; a refused notification permission just hides the service's notification.
        graph.micGate.onResult(granted[Manifest.permission.RECORD_AUDIO] == true)
    }

    private val port = object : MicPermissionPort {
        override fun granted() =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        override fun rationale() = shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)

        override fun request() = permissions.launch(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            } else {
                arrayOf(Manifest.permission.RECORD_AUDIO)
            },
        )

        override fun openSettings() = startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
        )
    }

    /** The real actions, with every microphone-opening one behind the permission gate. */
    private val actions: VoiceUiActions by lazy { GatedActions(graph.actions, graph.micGate) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        graph.micGate.attach(port)
        setContent {
            val ready by graph.ready.collectAsStateWithLifecycle()
            val voice by graph.controller.ui.collectAsStateWithLifecycle()
            val mic by graph.micGate.state.collectAsStateWithLifecycle()
            val login by graph.account.loginState.collectAsStateWithLifecycle()
            val settings by graph.settingsState.collectAsStateWithLifecycle()
            val debugLine by graph.controller.debugLine.collectAsStateWithLifecycle()
            if (ready) {
                VoiceGatewayApp(
                    voice = voice.withMicGate(mic), login = login, settings = settings, actions = actions,
                    debugLine = debugLine,
                )
            } else {
                VoiceGatewayTheme { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        graph.controller.setVisible(true)
    }

    override fun onResume() {
        super.onResume()
        graph.micGate.onReturn()
    }

    override fun onStop() {
        graph.controller.setVisible(false)
        super.onStop()
    }

    override fun onDestroy() {
        graph.micGate.detach(port)
        super.onDestroy()
    }
}
