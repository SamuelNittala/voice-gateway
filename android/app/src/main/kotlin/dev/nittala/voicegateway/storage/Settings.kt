package dev.nittala.voicegateway.storage

import dev.nittala.voicegateway.core.api.CookieStore
import dev.nittala.voicegateway.core.config.BaseUrlResult
import dev.nittala.voicegateway.core.config.BuildVariant
import dev.nittala.voicegateway.core.config.validateBaseUrl

/**
 * Spec (A7): the app's persisted settings and the secrets tied to them. Blocking: never on the main thread.
 *
 * - Base URL: [setBaseUrl] validates with `validateBaseUrl` for the build's variant and stores only the canonical
 *   form; a refused URL changes nothing. [baseUrl] validates again on every read; a stored value that no longer
 *   passes reads as null and is removed. Setting a DIFFERENT URL first deletes the login cookie and the remembered
 *   passcode, so neither is ever sent to a host it was not obtained for.
 * - Remember passcode: OFF by default. [rememberPasscode] saves the passcode (encrypted, same [SecretStore]) only
 *   while it is on. Turning it off wipes the saved passcode. [rememberedPasscode] is null while it is off (and wipes
 *   any leftover entry).
 * - [logout] deletes the cookie and the remembered passcode; the base URL and the remember setting stay.
 * - Speak progress updates: ON by default; stored only when off. Not a secret, kept through logout.
 */
interface AppSettings {
    fun baseUrl(): String?
    fun setBaseUrl(raw: String): BaseUrlResult
    fun isRememberPasscodeOn(): Boolean
    fun setRememberPasscode(on: Boolean)

    /** Saves [passcode] if remembering is on and it is not empty; returns whether it was saved. */
    fun rememberPasscode(passcode: String): Boolean
    fun rememberedPasscode(): String?
    fun logout()
    fun isSpokenProgressOn(): Boolean
    fun setSpokenProgress(on: Boolean)
}

/** [AppSettings] with plain values in [prefs] and secrets in [secrets] (the cookie through [cookies]). */
class StoredSettings(
    private val prefs: KeyValueStore,
    private val secrets: SecretStore,
    private val cookies: CookieStore,
    private val variant: BuildVariant,
) : AppSettings {
    override fun baseUrl(): String? {
        val stored = prefs.get(BASE_URL_KEY) ?: return null
        val result = validateBaseUrl(stored, variant)
        if (result is BaseUrlResult.Valid && result.url == stored) return stored
        prefs.put(BASE_URL_KEY, null)
        return null
    }

    override fun setBaseUrl(raw: String): BaseUrlResult {
        val result = validateBaseUrl(raw, variant)
        if (result is BaseUrlResult.Valid && result.url != baseUrl()) {
            forgetSecrets() // Before the new URL is written: a crash in between leaves no secret for either host.
            prefs.put(BASE_URL_KEY, result.url)
        }
        return result
    }

    override fun isRememberPasscodeOn(): Boolean = prefs.get(REMEMBER_PASSCODE_KEY) == "true"

    override fun setRememberPasscode(on: Boolean) {
        if (on) {
            prefs.put(REMEMBER_PASSCODE_KEY, "true")
        } else {
            secrets.put(PASSCODE_ENTRY, null) // Wipe first, then the flag.
            prefs.put(REMEMBER_PASSCODE_KEY, null)
        }
    }

    override fun rememberPasscode(passcode: String): Boolean {
        if (!isRememberPasscodeOn() || passcode.isEmpty()) return false
        secrets.put(PASSCODE_ENTRY, passcode)
        return true
    }

    override fun rememberedPasscode(): String? {
        if (isRememberPasscodeOn()) return secrets.get(PASSCODE_ENTRY)
        secrets.put(PASSCODE_ENTRY, null)
        return null
    }

    override fun logout() = forgetSecrets()

    override fun isSpokenProgressOn(): Boolean = prefs.get(SPOKEN_PROGRESS_KEY) != "false"

    override fun setSpokenProgress(on: Boolean) = prefs.put(SPOKEN_PROGRESS_KEY, if (on) null else "false")

    private fun forgetSecrets() {
        cookies.put(null)
        secrets.put(PASSCODE_ENTRY, null)
    }

    companion object {
        const val BASE_URL_KEY = "base_url"
        const val REMEMBER_PASSCODE_KEY = "remember_passcode"
        const val PASSCODE_ENTRY = "passcode"
        const val SPOKEN_PROGRESS_KEY = "spoken_progress"
    }
}
