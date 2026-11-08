package dev.nittala.voicegateway

import android.app.Application
import android.media.AudioManager
import android.net.ConnectivityManager
import android.util.Log
import dev.nittala.voicegateway.audio.AudioManagerFocusRequester
import dev.nittala.voicegateway.audio.AudioRecordCapture
import dev.nittala.voicegateway.audio.AudioRecordFrameSource
import dev.nittala.voicegateway.audio.RecordingCallbackSilenceMonitor
import dev.nittala.voicegateway.audio.SileroVoiceProbability
import dev.nittala.voicegateway.audio.SystemAudioFocusGate
import dev.nittala.voicegateway.audio.ToneEarcons
import dev.nittala.voicegateway.core.api.HttpGatewayApi
import dev.nittala.voicegateway.core.api.gatewayClient
import dev.nittala.voicegateway.core.config.BuildVariant
import dev.nittala.voicegateway.core.loop.LoopInput
import dev.nittala.voicegateway.core.loop.LoopState
import dev.nittala.voicegateway.core.loop.Phase
import dev.nittala.voicegateway.core.speech.Engine
import dev.nittala.voicegateway.core.ui.SettingsUiState
import dev.nittala.voicegateway.net.ConnectivityNetworkMonitor
import dev.nittala.voicegateway.permission.MicPermissionGate
import dev.nittala.voicegateway.permission.perform
import dev.nittala.voicegateway.playback.AndroidTtsVoice
import dev.nittala.voicegateway.playback.ChainSpeaker
import dev.nittala.voicegateway.playback.ExoSpeaker
import dev.nittala.voicegateway.playback.PhoneTtsSpeaker
import dev.nittala.voicegateway.playback.PlaybackFocusRequester
import dev.nittala.voicegateway.playback.TtsPhoneVoice
import dev.nittala.voicegateway.service.AndroidServiceControl
import dev.nittala.voicegateway.service.AppActions
import dev.nittala.voicegateway.service.GatewayAccount
import dev.nittala.voicegateway.service.GatewayLink
import dev.nittala.voicegateway.service.LinkHolder
import dev.nittala.voicegateway.service.LoopController
import dev.nittala.voicegateway.service.VibratorHaptics
import dev.nittala.voicegateway.storage.AesGcmCipher
import dev.nittala.voicegateway.storage.AndroidKeystoreKeys
import dev.nittala.voicegateway.storage.DataStoreKeyValueStore
import dev.nittala.voicegateway.storage.EncryptedSecretStore
import dev.nittala.voicegateway.storage.PersistentCookieStore
import dev.nittala.voicegateway.storage.StoredSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Pure: the base-URL rules of this build ([BuildConfig.DEBUG]: debug builds also allow http to a tailnet IP). */
fun buildVariantOf(debug: Boolean): BuildVariant = if (debug) BuildVariant.DEBUG else BuildVariant.RELEASE

/**
 * Spec (A10): the composition root, the one place the app's objects are built and wired (no DI framework). One per
 * process, held by [VoiceGatewayApplication]; the activity and the service read [controller], [actions] and the
 * state flows from it.
 *
 * - The stores use the real build variant ([buildVariantOf] of BuildConfig.DEBUG).
 * - One OkHttp client per base URL ([gatewayClient]) is shared by login, every API call and ExoPlayer's audio, so
 *   they share one cookie jar ([PersistentCookieStore], encrypted with the Keystore key).
 * - The loop starts LoggedOut until [ready]: then Idle when a cookie is stored, else the Login screen.
 * - A 401 tries one silent re-login when "Remember passcode" is on.
 */
class AppGraph(private val app: Application) {
    val variant: BuildVariant = buildVariantOf(BuildConfig.DEBUG)
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val secrets = EncryptedSecretStore(
        AesGcmCipher(AndroidKeystoreKeys()),
        DataStoreKeyValueStore.open(app, DataStoreKeyValueStore.SECRETS_FILE),
    )
    private val cookies = PersistentCookieStore(secrets)
    private val settings = StoredSettings(
        DataStoreKeyValueStore.open(app, DataStoreKeyValueStore.SETTINGS_FILE), secrets, cookies, variant,
    )
    private val audioManager = app.getSystemService(AudioManager::class.java)
    private val tts = AndroidTtsVoice(app)

    private val links = LinkHolder { url -> link(url) }

    private fun link(url: String): GatewayLink {
        val base = HttpGatewayApi.parseBase(url)
        val client = gatewayClient(base, cookies)
        val exo = ExoSpeaker(app, base, client)
        val speaker = ChainSpeaker(
            mapOf(Engine.Chatterbox to exo, Engine.Deepgram to exo, Engine.Phone to PhoneTtsSpeaker(tts)),
            SystemAudioFocusGate(PlaybackFocusRequester(audioManager)),
        )
        return GatewayLink(HttpGatewayApi(url, cookies, sharedClient = client), speaker)
    }

    val controller = LoopController(
        initial = LoopState(worktree = "", phase = Phase.LoggedOut),
        links = links,
        capture = AudioRecordCapture(
            openSource = { AudioRecordFrameSource.open(app) },
            openVoiceProbability = { SileroVoiceProbability(app) },
            monitor = RecordingCallbackSilenceMonitor(audioManager),
            // DEBUG only: a "cap Listening" diagnostic about every 3 s while waiting for speech.
            progressEveryFrames = if (BuildConfig.DEBUG) DEBUG_PROGRESS_FRAMES else 0,
        ),
        listenFocus = SystemAudioFocusGate(AudioManagerFocusRequester(audioManager)),
        voice = TtsPhoneVoice(tts),
        earcons = ToneEarcons(),
        haptics = VibratorHaptics(app),
        service = AndroidServiceControl(app),
        network = ConnectivityNetworkMonitor(app.getSystemService(ConnectivityManager::class.java)),
        scope = main,
        clock = System::currentTimeMillis,
        // DEBUG only: numbers and reason codes to logcat (tag VG) and the screen; release builds log nothing.
        debug = if (BuildConfig.DEBUG) { line -> Log.d(DEBUG_TAG, line) } else null,
    )

    val account = GatewayAccount(settings, cookies, links, controller, main, Dispatchers.IO, variant)

    val actions = AppActions(controller, account)

    /**
     * The one microphone permission gate: the screens' mic actions wait on it, then run on [actions]. DEBUG builds log
     * codes only (tag VG).
     */
    val micGate = MicPermissionGate(
        run = { actions.perform(it) },
        debug = if (BuildConfig.DEBUG) { line -> Log.d(DEBUG_TAG, line) } else null,
    )

    private val _ready = MutableStateFlow(false)

    /** False until the stored settings were read (the screen shows nothing before, so Login never flashes). */
    val ready: StateFlow<Boolean> = _ready

    val settingsState: StateFlow<SettingsUiState> =
        combine(account.loginState, controller.state, controller.speakerChoice) { login, loop, speaker ->
            SettingsUiState(login.baseUrl, login.rememberPasscode, loop.muted, speaker, variant)
        }.stateIn(main, SharingStarted.Eagerly, SettingsUiState(variant = variant))

    fun start() {
        controller.start()
        main.launch {
            if (account.restore()) controller.send(LoopInput.LoggedIn)
            _ready.value = true
        }
        main.launch {
            controller.state.map { it.phase == Phase.LoggedOut }.distinctUntilChanged().collect { out ->
                if (out && _ready.value) account.silentRelogin()
            }
        }
    }
}

private const val DEBUG_TAG = "VG"

/** About 3 s of 32 ms frames. */
private const val DEBUG_PROGRESS_FRAMES = 94
