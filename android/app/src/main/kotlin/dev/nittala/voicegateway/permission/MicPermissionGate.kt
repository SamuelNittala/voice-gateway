package dev.nittala.voicegateway.permission

import dev.nittala.voicegateway.core.permission.MicAction
import dev.nittala.voicegateway.core.permission.MicGate
import dev.nittala.voicegateway.core.permission.MicGateState
import dev.nittala.voicegateway.core.permission.MicGateStep
import dev.nittala.voicegateway.ui.VoiceUiActions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The Android side of the microphone permission, behind a small interface (the activity implements it; tests fake it). */
interface MicPermissionPort {
    /** RECORD_AUDIO is granted now. */
    fun granted(): Boolean

    /** `shouldShowRequestPermissionRationale(RECORD_AUDIO)`: read right after a denial, false means denied for good. */
    fun rationale(): Boolean

    /** Show Android's prompt for RECORD_AUDIO (and POST_NOTIFICATIONS, optional); the answer comes to [MicPermissionGate.onResult]. */
    fun request()

    /** Open this app's page in the system settings. */
    fun openSettings()
}

/**
 * Spec: the ONE gate every microphone-opening action goes through (the rules are `MicGate`, pure, in :core).
 * Lives as long as the process (in `AppGraph`), so a prompt survives a rotation; the activity [attach]es its port.
 * [run] performs an action once it may (the real, ungated actions). [debug] gets codes only (DEBUG builds).
 */
class MicPermissionGate(
    private val run: (MicAction) -> Unit,
    private val debug: ((String) -> Unit)? = null,
) {
    private val _state = MutableStateFlow(MicGateState())
    val state: StateFlow<MicGateState> = _state

    private var port: MicPermissionPort? = null

    fun attach(port: MicPermissionPort) {
        this.port = port
    }

    /** Only the port still attached is removed (a new activity may have attached its own first). */
    fun detach(port: MicPermissionPort) {
        if (this.port === port) this.port = null
    }

    /** A tap on something that will open the mic. Without an attached activity nothing can be asked: nothing runs. */
    fun ask(action: MicAction) {
        val p = port ?: return
        val step = MicGate.onAction(_state.value, p.granted(), action)
        val what = when {
            step.run != null -> "proceed"
            step.request -> "request"
            step.state.pending != null -> "waiting"
            else -> "settings"
        }
        debug?.invoke("perm $what ${action.code}")
        apply(step, p)
    }

    /** Android's answer for RECORD_AUDIO. The rationale is read now, right after the answer. */
    fun onResult(granted: Boolean) {
        val rationale = port?.rationale() ?: true
        val step = MicGate.onResult(_state.value, granted, rationale)
        debug?.invoke(if (granted) "perm granted" else "perm denied forever=${!rationale}")
        apply(step, port)
    }

    /** The app is in front again (maybe back from the settings). */
    fun onReturn() {
        val p = port ?: return
        val next = MicGate.onReturn(_state.value, p.granted())
        if (next != _state.value) debug?.invoke("perm granted on return")
        _state.value = next
    }

    fun openSettings() {
        port?.openSettings()
    }

    private fun apply(step: MicGateStep, p: MicPermissionPort?) {
        _state.value = step.state
        if (step.request) p?.request()
        step.run?.let(run)
    }
}

/** Runs [action] on these (ungated) actions. */
fun VoiceUiActions.perform(action: MicAction): Unit = when (action) {
    MicAction.Start -> start()
    MicAction.TapOrb -> tapOrb()
    MicAction.Interrupt -> interrupt()
    is MicAction.Confirm -> if (action.yes) yes() else no()
    MicAction.SendHeld -> sendHeld()
    is MicAction.SendTyped -> sendTyped(action.text)
    MicAction.NewWorktree -> newWorktree()
}

/**
 * The actions the screens get: every action that will open the mic goes through [gate]; everything else, and
 * [openAppSettings] to the gate's port, passes straight through to [actions].
 */
class GatedActions(private val actions: VoiceUiActions, private val gate: MicPermissionGate) : VoiceUiActions by actions {
    override fun start() = gate.ask(MicAction.Start)
    override fun tapOrb() = gate.ask(MicAction.TapOrb)
    override fun interrupt() = gate.ask(MicAction.Interrupt)
    override fun yes() = gate.ask(MicAction.Confirm(true))
    override fun no() = gate.ask(MicAction.Confirm(false))
    override fun sendHeld() = gate.ask(MicAction.SendHeld)
    override fun sendTyped(text: String) = gate.ask(MicAction.SendTyped(text))
    override fun newWorktree() = gate.ask(MicAction.NewWorktree)
    override fun openAppSettings() = gate.openSettings()
}
