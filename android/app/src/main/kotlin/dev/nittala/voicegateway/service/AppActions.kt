package dev.nittala.voicegateway.service

import dev.nittala.voicegateway.core.loop.LoopInput
import dev.nittala.voicegateway.core.speech.Engine
import dev.nittala.voicegateway.ui.VoiceUiActions

/** Login, logout and settings: the account side of the UI actions. */
interface Account {
    fun login(baseUrl: String, passcode: String, remember: Boolean)
    fun logout()
    fun setBaseUrl(raw: String)
    fun setRememberPasscode(on: Boolean)

    /** Saves "Speak progress updates" (the loop is told by [AppActions]). */
    fun setSpokenProgress(on: Boolean)
}

/**
 * Spec (A10): the real [VoiceUiActions]. Loop intents become loop inputs:
 * - [start], [tapOrb], [interrupt] -> `Tap`; [stopListening] -> `LongPress`;
 * - [yes] / [no] -> `ConfirmTap(true/false)`; [key] -> `KeyTap`; [stop] -> `StopTap`; [restart] -> `RestartTap`;
 * - [sendHeld] -> `SendNowTap`; [discardHeld] -> `DiscardTap`; [sendTyped] -> `TypedSend` (voice false);
 * - [switchWorktree] -> `WorktreeChanged`; [setMuted] -> `MuteChanged`; [setSpeaker] -> [LoopControls.setSpeaker];
 * - [setSpokenProgress] -> saved by [Account.setSpokenProgress], then `ProgressChanged`;
 * - W6: [newWorktree] -> `NewWorktreeTap`; [planWorktree] -> `NewWorktreeTyped`; [pickRepo] -> `CandidateTap`;
 *   [pickBase] -> `BaseTap`; [typeBase] -> `BaseTyped`; [createWorktree] -> `CreateTap`; [createFromCopy] ->
 *   `OfflineCreateTap`; [cancelWorktree] -> `CancelTap`.
 * Account intents go to [account]. The passcode is passed straight through and never kept here.
 */
class AppActions(private val loop: LoopControls, private val account: Account) : VoiceUiActions {
    override fun start() = loop.send(LoopInput.Tap)
    override fun stopListening() = loop.send(LoopInput.LongPress)
    override fun tapOrb() = loop.send(LoopInput.Tap)
    override fun interrupt() = loop.send(LoopInput.Tap)
    override fun yes() = loop.send(LoopInput.ConfirmTap(true))
    override fun no() = loop.send(LoopInput.ConfirmTap(false))
    override fun key(key: String) = loop.send(LoopInput.KeyTap(key))
    override fun stop() = loop.send(LoopInput.StopTap)
    override fun restart() = loop.send(LoopInput.RestartTap)
    override fun sendHeld() = loop.send(LoopInput.SendNowTap)
    override fun discardHeld() = loop.send(LoopInput.DiscardTap)
    override fun sendTyped(text: String) = loop.send(LoopInput.TypedSend(text))
    override fun switchWorktree(name: String) = loop.send(LoopInput.WorktreeChanged(name))
    override fun setMuted(muted: Boolean) = loop.send(LoopInput.MuteChanged(muted))
    override fun login(baseUrl: String, passcode: String, remember: Boolean) = account.login(baseUrl, passcode, remember)
    override fun logout() = account.logout()
    override fun setBaseUrl(raw: String) = account.setBaseUrl(raw)
    override fun setRememberPasscode(on: Boolean) = account.setRememberPasscode(on)
    override fun setSpeaker(engine: Engine) = loop.setSpeaker(engine)
    override fun setSpokenProgress(on: Boolean) {
        account.setSpokenProgress(on)
        loop.send(LoopInput.ProgressChanged(on))
    }
    override fun newWorktree() = loop.send(LoopInput.NewWorktreeTap)
    override fun planWorktree(text: String) = loop.send(LoopInput.NewWorktreeTyped(text))
    override fun pickRepo(repoId: String) = loop.send(LoopInput.CandidateTap(repoId))
    override fun pickBase(name: String) = loop.send(LoopInput.BaseTap(name))
    override fun typeBase(name: String) = loop.send(LoopInput.BaseTyped(name))
    override fun createWorktree() = loop.send(LoopInput.CreateTap)
    override fun createFromCopy() = loop.send(LoopInput.OfflineCreateTap)
    override fun cancelWorktree() = loop.send(LoopInput.CancelTap)
}
