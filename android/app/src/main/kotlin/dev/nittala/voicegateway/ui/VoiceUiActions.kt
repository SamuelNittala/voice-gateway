package dev.nittala.voicegateway.ui

import dev.nittala.voicegateway.core.speech.Engine

/**
 * Spec (A11): every user intent the screens can produce. The A10 controller implements it (turning each call into a
 * `LoopInput`, a settings change or an API call); the screens depend only on this and on the `*UiState` models, so
 * previews and UI tests run them with a fake.
 *
 * Loop intents and the `LoopInput` they are meant to become:
 * - [start] (orb in Idle, Paused, Error), [tapOrb] (orb in Listening, AwaitingAnswer, Capturing, AwaitingTap) and
 *   [interrupt] (orb in Speaking): `Tap`. They are separate so the controller can log and the screen can vibrate
 *   on an interrupt.
 * - [stopListening] (long press on the orb): `LongPress`.
 * - [yes] / [no]: `ConfirmTap(true/false)`; only offered in AwaitingTap.
 * - [key]: `KeyTap(key)`; the loop sends it with its screen token. Only offered in NeedsYou.
 * - [stop]: `StopTap`. [restart]: `RestartTap`, called only after the screen's own confirm dialog.
 * - [sendHeld] / [discardHeld]: `SendNowTap` / `DiscardTap`.
 * - [sendTyped]: a typed prompt (`voice: false`, outside the loop's auto-send).
 * - [switchWorktree]: `WorktreeChanged(name)`. [setMuted]: `MuteChanged`.
 * - W6, a new worktree: [newWorktree] `NewWorktreeTap`; [planWorktree] the typed request, `NewWorktreeTyped`;
 *   [pickRepo] `CandidateTap(repoId)`; [pickBase] `BaseTap`; [typeBase] `BaseTyped`; [createWorktree] `CreateTap`
 *   (also "Plan again"); [createFromCopy] `OfflineCreateTap`; [cancelWorktree] `CancelTap`.
 *
 * Account and settings: [login] (the passcode is passed straight through, never stored by the UI; [remember] is the
 * opt-in), [logout], [setBaseUrl] (raw text; the controller validates and stores), [setRememberPasscode],
 * [setSpeaker].
 */
interface VoiceUiActions {
    fun start()
    fun stopListening()
    fun tapOrb()
    fun interrupt()
    fun yes()
    fun no()
    fun key(key: String)
    fun stop()
    fun restart()
    fun sendHeld()
    fun discardHeld()
    fun sendTyped(text: String)
    fun switchWorktree(name: String)
    fun setMuted(muted: Boolean)
    fun login(baseUrl: String, passcode: String, remember: Boolean)
    fun logout()
    fun setBaseUrl(raw: String)
    fun setRememberPasscode(on: Boolean)
    fun setSpeaker(engine: Engine)
    fun newWorktree()
    fun planWorktree(text: String)
    fun pickRepo(repoId: String)
    fun pickBase(name: String)
    fun typeBase(name: String)
    fun createWorktree()
    fun createFromCopy()
    fun cancelWorktree()

    /** The microphone notice's "Open settings": this app's page in the system settings (the permission gate does it). */
    fun openAppSettings() = Unit
}

/** Does nothing: for previews and UI tests. */
object NoOpVoiceUiActions : VoiceUiActions {
    override fun start() = Unit
    override fun stopListening() = Unit
    override fun tapOrb() = Unit
    override fun interrupt() = Unit
    override fun yes() = Unit
    override fun no() = Unit
    override fun key(key: String) = Unit
    override fun stop() = Unit
    override fun restart() = Unit
    override fun sendHeld() = Unit
    override fun discardHeld() = Unit
    override fun sendTyped(text: String) = Unit
    override fun switchWorktree(name: String) = Unit
    override fun setMuted(muted: Boolean) = Unit
    override fun login(baseUrl: String, passcode: String, remember: Boolean) = Unit
    override fun logout() = Unit
    override fun setBaseUrl(raw: String) = Unit
    override fun setRememberPasscode(on: Boolean) = Unit
    override fun setSpeaker(engine: Engine) = Unit
    override fun newWorktree() = Unit
    override fun planWorktree(text: String) = Unit
    override fun pickRepo(repoId: String) = Unit
    override fun pickBase(name: String) = Unit
    override fun typeBase(name: String) = Unit
    override fun createWorktree() = Unit
    override fun createFromCopy() = Unit
    override fun cancelWorktree() = Unit
}
