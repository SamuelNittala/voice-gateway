package dev.nittala.voicegateway.service

import dev.nittala.voicegateway.core.api.StoredCookie
import dev.nittala.voicegateway.core.config.BuildVariant
import dev.nittala.voicegateway.core.loop.LoopInput
import dev.nittala.voicegateway.storage.FakeSecretStore
import dev.nittala.voicegateway.storage.InMemoryKeyValueStore
import dev.nittala.voicegateway.storage.PersistentCookieStore
import dev.nittala.voicegateway.storage.StoredSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec (A10): [GatewayAccount] with the real settings rules over in-memory stores and a fake gateway. */
@OptIn(ExperimentalCoroutinesApi::class)
class GatewayAccountTest {
    private val url = "https://mac.tail1234.ts.net"

    private class Rig(test: TestScope, variant: BuildVariant = BuildVariant.RELEASE) {
        val api = FakeGatewayApi()
        val secrets = FakeSecretStore()
        val cookies = PersistentCookieStore(secrets)
        val settings = StoredSettings(InMemoryKeyValueStore(), secrets, cookies, variant)
        val connected = mutableListOf<String>()
        val links = LinkHolder { connected += it; GatewayLink(api, ScenarioSpeaker(mutableListOf())) }
        val inputs = mutableListOf<LoopInput>()
        val account = GatewayAccount(
            settings, cookies, links, { inputs += it }, test,
            StandardTestDispatcher(test.testScheduler), variant,
        )
    }

    private fun rig(variant: BuildVariant = BuildVariant.RELEASE, block: suspend TestScope.(Rig) -> Unit) =
        runTest { block(Rig(this, variant)) }

    @Test
    fun aGoodLoginStoresTheUrlConnectsAndTellsTheLoop() = rig { r ->
        r.account.login(url, "correct horse", remember = false)
        advanceUntilIdle()
        assertEquals(url, r.settings.baseUrl())
        assertEquals(listOf(url), r.connected)
        assertEquals(1, r.api.logins)
        assertEquals(listOf<LoopInput>(LoopInput.LoggedIn), r.inputs)
        assertNull(r.account.loginState.value.error)
        assertFalse(r.account.loginState.value.busy)
        assertNull(r.settings.rememberedPasscode())
    }

    @Test
    fun rememberOnSavesThePasscodeEncryptedOnlyThen() = rig { r ->
        r.account.login(url, "correct horse", remember = true)
        advanceUntilIdle()
        assertTrue(r.settings.isRememberPasscodeOn())
        assertEquals("correct horse", r.settings.rememberedPasscode())
    }

    @Test
    fun aWrongPasscodeSaysSoAndNeverRetries() = rig { r ->
        r.api.loginAnswer = "login_wrong"
        r.account.login(url, "nope", remember = true)
        advanceUntilIdle()
        assertEquals("Wrong passcode.", r.account.loginState.value.error)
        assertEquals(1, r.api.logins)
        assertTrue(r.inputs.isEmpty())
        assertNull(r.settings.rememberedPasscode())
    }

    @Test
    fun aLockoutSaysHowLongToWait() = rig { r ->
        r.api.loginAnswer = "login_locked"
        r.account.login(url, "nope", remember = false)
        advanceUntilIdle()
        assertEquals("Too many tries. Wait 31 seconds.", r.account.loginState.value.error)
    }

    @Test
    fun anUnreachableGatewaySaysToCheckTheAddress() = rig { r ->
        r.api.offline = true
        r.account.login(url, "pass", remember = false)
        advanceUntilIdle()
        assertTrue(r.account.loginState.value.error!!.startsWith("Couldn't reach the gateway"))
    }

    @Test
    fun aRefusedUrlSendsNothing() = rig { r ->
        r.account.login("http://mac.tail1234.ts.net", "pass", remember = false)
        advanceUntilIdle()
        assertEquals(0, r.api.logins)
        assertEquals("Use https:// with your Tailscale name. Plain http:// is only allowed in debug builds, to a tailnet IP.", r.account.loginState.value.error)
    }

    @Test
    fun debugBuildsAcceptATailnetIpOverHttp() = rig(BuildVariant.DEBUG) { r ->
        r.account.login("http://100.101.102.103:8766", "pass", remember = false)
        advanceUntilIdle()
        assertEquals(1, r.api.logins)
    }

    @Test
    fun logoutForgetsTheCookieAndShowsLogin() = rig { r ->
        r.settings.setBaseUrl(url)
        r.cookies.put(StoredCookie("c", Long.MAX_VALUE))
        r.account.logout()
        advanceUntilIdle()
        assertNull(r.cookies.get())
        assertEquals(listOf<LoopInput>(LoopInput.Unauthorized), r.inputs)
    }

    @Test
    fun aDifferentUrlLogsOutTheSameOneDoesNot() = rig { r ->
        r.settings.setBaseUrl(url)
        r.cookies.put(StoredCookie("c", Long.MAX_VALUE))
        r.account.setBaseUrl(url)
        advanceUntilIdle()
        assertTrue(r.inputs.isEmpty())
        r.account.setBaseUrl("https://other.tail1234.ts.net")
        advanceUntilIdle()
        assertEquals(listOf<LoopInput>(LoopInput.Unauthorized), r.inputs)
        assertNull(r.cookies.get())
    }

    @Test
    fun restoreConnectsToTheStoredUrlAndSaysWhetherACookieIsThere() = rig { r ->
        assertFalse(r.account.restore())
        r.settings.setBaseUrl(url)
        r.cookies.put(StoredCookie("c", Long.MAX_VALUE))
        assertTrue(r.account.restore())
        assertEquals(listOf(url), r.connected)
        assertEquals(url, r.account.loginState.value.baseUrl)
    }

    @Test
    fun restoreTellsTheLoopTheStoredProgressSetting() = rig { r ->
        r.account.restore()
        assertEquals(listOf<LoopInput>(LoopInput.ProgressChanged(true)), r.inputs)
        r.inputs.clear()
        r.settings.setSpokenProgress(false)
        r.account.restore()
        assertEquals(listOf<LoopInput>(LoopInput.ProgressChanged(false)), r.inputs)
    }

    @Test
    fun theProgressSettingIsSaved() = rig { r ->
        r.account.setSpokenProgress(false)
        advanceUntilIdle()
        assertFalse(r.settings.isSpokenProgressOn())
        r.account.setSpokenProgress(true)
        advanceUntilIdle()
        assertTrue(r.settings.isSpokenProgressOn())
        assertTrue(r.inputs.isEmpty()) // The loop is told by AppActions, once.
    }

    @Test
    fun silentReloginTriesOnceWithTheSavedPasscode() = rig { r ->
        r.account.login(url, "correct horse", remember = true)
        advanceUntilIdle()
        r.inputs.clear()
        r.account.silentRelogin()
        advanceUntilIdle()
        assertEquals(2, r.api.logins)
        assertEquals(listOf<LoopInput>(LoopInput.LoggedIn), r.inputs)
        r.api.loginAnswer = "login_wrong"
        r.account.silentRelogin()
        advanceUntilIdle()
        r.account.silentRelogin()
        advanceUntilIdle()
        assertEquals("one try per logout only", 3, r.api.logins)
    }

    @Test
    fun noSilentReloginWithoutASavedPasscode() = rig { r ->
        r.account.login(url, "correct horse", remember = false)
        advanceUntilIdle()
        r.account.silentRelogin()
        advanceUntilIdle()
        assertEquals(1, r.api.logins)
    }
}
