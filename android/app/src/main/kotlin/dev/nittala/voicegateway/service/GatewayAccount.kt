package dev.nittala.voicegateway.service

import dev.nittala.voicegateway.core.api.ApiResult
import dev.nittala.voicegateway.core.api.CookieStore
import dev.nittala.voicegateway.core.config.BaseUrlResult
import dev.nittala.voicegateway.core.config.BuildVariant
import dev.nittala.voicegateway.core.loop.LoopInput
import dev.nittala.voicegateway.core.ui.LoginUiState
import dev.nittala.voicegateway.core.ui.baseUrlMessage
import dev.nittala.voicegateway.storage.AppSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The [GatewayLink] of the current base URL, rebuilt by [connect] (login may change the URL). */
class LinkHolder(private val build: (baseUrl: String) -> GatewayLink) : GatewayLinks {
    @Volatile private var link: Pair<String, GatewayLink>? = null

    /** Builds the link for [baseUrl] unless it is already the current one; null forgets it. */
    @Synchronized
    fun connect(baseUrl: String?) {
        if (baseUrl == null) {
            link = null
            return
        }
        if (link?.first != baseUrl) link = baseUrl to build(baseUrl)
    }

    override fun current(): GatewayLink? = link?.second
}

/**
 * Spec (A10): login, logout and the account settings, against [settings] (blocking: always on [io]) and the gateway.
 *
 * - [login]: the base URL is validated and stored first (refused -> [LoginUiState.error] is the plain reason and
 *   nothing is sent); then [links] connects to it and `POST /api/login` runs once, never retried. Ok -> the remember
 *   setting is applied (the passcode is saved, encrypted, only when [remember] is on), the loop gets `LoggedIn`.
 *   401 -> "Wrong passcode."; 429 -> "Too many tries. Wait N seconds." from Retry-After; anything else -> "Couldn't
 *   reach the gateway. Check the address and Tailscale." [LoginUiState.busy] while it runs; a second login while one
 *   runs is ignored.
 * - [logout]: the cookie and the saved passcode are deleted; the loop gets `Unauthorized` (Login screen).
 * - [setBaseUrl]: a valid DIFFERENT URL wipes the cookie and saved passcode (the settings rule), so the loop gets
 *   `Unauthorized`; the same URL or a refused one changes nothing.
 * - [silentRelogin]: after a 401, at most once until the next successful login, when remembering is on and a
 *   passcode is saved: one login with it; Ok -> `LoggedIn`. Never more than one try.
 * - The passcode is never logged or kept in memory beyond the call.
 */
class GatewayAccount(
    private val settings: AppSettings,
    private val cookies: CookieStore,
    private val links: LinkHolder,
    private val loop: LoopInputs,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val variant: BuildVariant,
) : Account {
    private val _login = MutableStateFlow(LoginUiState(variant = variant))
    val loginState: StateFlow<LoginUiState> = _login

    /** Set once a silent re-login was tried; cleared by every successful login. */
    @Volatile private var silentTried = false

    /** Reads the stored settings, connects to the stored base URL; true when a cookie is stored (logged in). */
    suspend fun restore(): Boolean = withContext(io) {
        val url = settings.baseUrl()
        links.connect(url)
        _login.update { it.copy(baseUrl = url.orEmpty(), rememberPasscode = settings.isRememberPasscodeOn()) }
        url != null && cookies.get() != null
    }

    override fun login(baseUrl: String, passcode: String, remember: Boolean) {
        if (_login.value.busy) return
        _login.update { it.copy(busy = true, error = null) }
        scope.launch {
            val error = withContext(io) { tryLogin(baseUrl, passcode, remember) }
            _login.update { it.copy(busy = false, error = error, rememberPasscode = remember) }
            if (error == null) loop.send(LoopInput.LoggedIn)
        }
    }

    /** One login, never retried. Null when it worked, else the plain sentence to show. */
    private suspend fun tryLogin(baseUrl: String, passcode: String, remember: Boolean): String? {
        when (val url = settings.setBaseUrl(baseUrl)) {
            is BaseUrlResult.Refused -> return baseUrlMessage(url.reason)
            is BaseUrlResult.Valid -> {
                links.connect(url.url)
                _login.update { it.copy(baseUrl = url.url) }
            }
        }
        val api = links.current()?.api ?: return UNREACHABLE
        return when (val result = api.login(passcode)) {
            is ApiResult.Ok -> {
                silentTried = false
                settings.setRememberPasscode(remember)
                settings.rememberPasscode(passcode)
                null
            }
            is ApiResult.Refused -> when (result.refusal.httpStatus) {
                401 -> "Wrong passcode."
                429 -> "Too many tries. Wait ${result.refusal.retryAfterSeconds ?: 60} seconds."
                else -> UNREACHABLE
            }
            else -> UNREACHABLE
        }
    }

    override fun logout() {
        scope.launch {
            withContext(io) { settings.logout() }
            loop.send(LoopInput.Unauthorized)
        }
    }

    override fun setBaseUrl(raw: String) {
        scope.launch {
            val changed = withContext(io) {
                val before = settings.baseUrl()
                val result = settings.setBaseUrl(raw)
                val after = (result as? BaseUrlResult.Valid)?.url
                if (after != null && after != before) {
                    links.connect(after)
                    _login.update { it.copy(baseUrl = after) }
                    true
                } else false
            }
            if (changed) loop.send(LoopInput.Unauthorized)
        }
    }

    override fun setRememberPasscode(on: Boolean) {
        _login.update { it.copy(rememberPasscode = on) }
        scope.launch { withContext(io) { settings.setRememberPasscode(on) } }
    }

    fun silentRelogin() {
        if (silentTried) return
        silentTried = true
        scope.launch {
            val ok = withContext(io) {
                val passcode = settings.rememberedPasscode() ?: return@withContext false
                val api = links.current()?.api ?: return@withContext false
                api.login(passcode) is ApiResult.Ok
            }
            if (ok) {
                silentTried = false
                loop.send(LoopInput.LoggedIn)
            }
        }
    }

    private companion object {
        const val UNREACHABLE = "Couldn't reach the gateway. Check the address and Tailscale."
    }
}
