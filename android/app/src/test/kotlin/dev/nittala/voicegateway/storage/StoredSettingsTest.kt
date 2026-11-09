package dev.nittala.voicegateway.storage

import dev.nittala.voicegateway.core.api.StoredCookie
import dev.nittala.voicegateway.core.config.BaseUrlRefusal
import dev.nittala.voicegateway.core.config.BaseUrlResult
import dev.nittala.voicegateway.core.config.BuildVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec (A7): [StoredSettings] with in-memory fakes. */
class StoredSettingsTest {
    private val prefs = InMemoryKeyValueStore()
    private val secrets = FakeSecretStore()
    private val cookies = PersistentCookieStore(secrets)
    private val gateway = "https://gateway.example.ts.net"

    private fun settings(variant: BuildVariant = BuildVariant.RELEASE) = StoredSettings(prefs, secrets, cookies, variant)

    private fun loggedIn(s: StoredSettings) {
        s.setBaseUrl(gateway)
        s.setRememberPasscode(true)
        s.rememberPasscode("test-passcode")
        cookies.put(StoredCookie("test-cookie", 99L))
    }

    @Test
    fun spokenProgressIsOnByDefaultAndKept() {
        val s = settings()
        assertTrue(s.isSpokenProgressOn())
        s.setSpokenProgress(false)
        assertFalse(s.isSpokenProgressOn())
        assertEquals("false", prefs.entries[StoredSettings.SPOKEN_PROGRESS_KEY])
        assertEquals("spoken_progress", StoredSettings.SPOKEN_PROGRESS_KEY)
        assertFalse(settings().isSpokenProgressOn()) // Read again from the same store: kept.
        loggedIn(s)
        s.logout()
        assertFalse(s.isSpokenProgressOn()) // A setting, not a secret: logout keeps it.
        s.setSpokenProgress(true)
        assertTrue(s.isSpokenProgressOn())
        assertTrue(secrets.entries.keys.none { it.contains("progress") })
    }

    @Test
    fun rememberPasscodeIsOffByDefaultAndSavesNothing() {
        val s = settings()
        assertFalse(s.isRememberPasscodeOn())
        assertFalse(s.rememberPasscode("test-passcode"))
        assertNull(s.rememberedPasscode())
        assertTrue(secrets.entries.isEmpty())
    }

    @Test
    fun savesThePasscodeOnlyWhileOn() {
        val s = settings()
        s.setRememberPasscode(true)
        assertTrue(s.isRememberPasscodeOn())
        assertTrue(s.rememberPasscode("test-passcode"))
        assertEquals("test-passcode", s.rememberedPasscode())
        assertEquals("test-passcode", secrets.entries[StoredSettings.PASSCODE_ENTRY])
    }

    @Test
    fun emptyPasscodeIsNotSaved() {
        val s = settings()
        s.setRememberPasscode(true)
        assertFalse(s.rememberPasscode(""))
        assertNull(s.rememberedPasscode())
    }

    @Test
    fun turningRememberOffWipesThePasscode() {
        val s = settings()
        s.setRememberPasscode(true)
        s.rememberPasscode("test-passcode")
        s.setRememberPasscode(false)
        assertFalse(s.isRememberPasscodeOn())
        assertNull(s.rememberedPasscode())
        assertFalse(secrets.entries.containsKey(StoredSettings.PASSCODE_ENTRY))
    }

    @Test
    fun leftoverPasscodeWhileOffIsWipedOnRead() {
        secrets.entries[StoredSettings.PASSCODE_ENTRY] = "leftover"
        assertNull(settings().rememberedPasscode())
        assertFalse(secrets.entries.containsKey(StoredSettings.PASSCODE_ENTRY))
    }

    @Test
    fun storesTheCanonicalBaseUrl() {
        val s = settings()
        assertEquals(BaseUrlResult.Valid(gateway), s.setBaseUrl("HTTPS://Gateway.Example.TS.NET/"))
        assertEquals(gateway, s.baseUrl())
        assertEquals(gateway, prefs.entries[StoredSettings.BASE_URL_KEY])
    }

    @Test
    fun refusedBaseUrlChangesNothing() {
        val s = settings()
        loggedIn(s)
        assertEquals(BaseUrlResult.Refused(BaseUrlRefusal.HOST_NOT_ALLOWED), s.setBaseUrl("https://a.ts.net.evil.com"))
        assertEquals(gateway, s.baseUrl())
        assertEquals("test-passcode", s.rememberedPasscode())
        assertEquals(StoredCookie("test-cookie", 99L), cookies.get())
    }

    @Test
    fun cleartextFollowsTheVariant() {
        assertEquals(BaseUrlResult.Refused(BaseUrlRefusal.CLEARTEXT), settings().setBaseUrl("http://100.64.0.1:8766"))
        assertNull(settings().baseUrl())
        assertEquals(BaseUrlResult.Valid("http://100.64.0.1:8766"), settings(BuildVariant.DEBUG).setBaseUrl("http://100.64.0.1:8766"))
        assertEquals("http://100.64.0.1:8766", settings(BuildVariant.DEBUG).baseUrl())
    }

    @Test
    fun storedUrlIsValidatedAgainOnRead() {
        listOf("https://evil.com", "https://user:pw@gateway.example.ts.net", "http://100.64.0.1", " https://a.ts.net").forEach { planted ->
            prefs.entries[StoredSettings.BASE_URL_KEY] = planted
            assertNull(planted, settings().baseUrl())
            assertFalse(planted, prefs.entries.containsKey(StoredSettings.BASE_URL_KEY))
        }
    }

    @Test
    fun debugUrlStoredEarlierIsRefusedByARelease() {
        settings(BuildVariant.DEBUG).setBaseUrl("http://10.0.2.2:8766")
        assertNull(settings(BuildVariant.RELEASE).baseUrl())
    }

    @Test
    fun changingTheBaseUrlForgetsCookieAndPasscode() {
        val s = settings()
        loggedIn(s)
        s.setBaseUrl("https://other.example.ts.net")
        assertEquals("https://other.example.ts.net", s.baseUrl())
        assertNull(cookies.get())
        assertNull(s.rememberedPasscode())
        assertTrue(s.isRememberPasscodeOn())
        assertTrue(secrets.entries.isEmpty())
    }

    @Test
    fun settingTheSameUrlAgainKeepsTheLogin() {
        val s = settings()
        loggedIn(s)
        s.setBaseUrl("https://GATEWAY.example.ts.net/")
        assertEquals(StoredCookie("test-cookie", 99L), cookies.get())
        assertEquals("test-passcode", s.rememberedPasscode())
    }

    @Test
    fun replacingAnInvalidStoredUrlForgetsSecrets() {
        loggedIn(settings())
        prefs.entries[StoredSettings.BASE_URL_KEY] = "https://evil.com"
        settings().setBaseUrl(gateway)
        assertTrue(secrets.entries.isEmpty())
    }

    @Test
    fun logoutForgetsCookieAndPasscodeOnly() {
        val s = settings()
        loggedIn(s)
        s.logout()
        assertNull(cookies.get())
        assertNull(s.rememberedPasscode())
        assertTrue(secrets.entries.isEmpty())
        assertEquals(gateway, s.baseUrl())
        assertTrue(s.isRememberPasscodeOn())
    }
}
