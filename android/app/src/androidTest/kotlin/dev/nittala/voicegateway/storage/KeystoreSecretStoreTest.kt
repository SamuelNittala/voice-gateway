package dev.nittala.voicegateway.storage

import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.nittala.voicegateway.core.api.StoredCookie
import dev.nittala.voicegateway.core.config.BuildVariant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64
import java.util.UUID

/**
 * Spec (A7), on a device: the real Android Keystore key and the real DataStore file. Each test uses its own key
 * alias and file so tests never share state, and deletes both afterwards.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreSecretStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString().replace("-", "")
    private val alias = "vg_test_$id"
    private val fileName = "vg_test_secrets_$id"
    private val keys = AndroidKeystoreKeys(alias)
    private val disk = DataStoreKeyValueStore.open(context, fileName)
    private val store = EncryptedSecretStore(AesGcmCipher(keys), disk)
    private val secret = "test-secret-value-0001"
    private val otherAliases = mutableListOf<AndroidKeystoreKeys>()

    @After
    fun cleanUp() {
        keys.delete()
        otherAliases.forEach { it.delete() }
        context.preferencesDataStoreFile(fileName).delete()
    }

    private fun fileBytes(): ByteArray = context.preferencesDataStoreFile(fileName).let { if (it.exists()) it.readBytes() else ByteArray(0) }

    private fun ByteArray.contains(needle: ByteArray): Boolean =
        (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }

    @Test
    fun roundTripsThroughTheKeystore() {
        store.put("passcode", secret)
        assertEquals(secret, store.get("passcode"))
        // A second store over the same file and alias (as after a process restart) still reads it.
        assertEquals(secret, EncryptedSecretStore(AesGcmCipher(AndroidKeystoreKeys(alias)), disk).get("passcode"))
    }

    @Test
    fun keyIsNonExportable() {
        val key = keys.encryptionKey()
        assertNull(key.encoded)
        assertNotNull(keys.decryptionKey())
    }

    @Test
    fun wrongKeyFailsClosed() {
        store.put("passcode", secret)
        val other = AndroidKeystoreKeys("${alias}_other").also { otherAliases += it }
        other.encryptionKey()
        assertNull(EncryptedSecretStore(AesGcmCipher(other), disk).get("passcode"))
    }

    @Test
    fun lostKeyFailsClosed() {
        store.put("passcode", secret)
        keys.delete()
        assertNull(store.get("passcode"))
    }

    @Test
    fun tamperedCiphertextFailsClosed() {
        store.put("passcode", secret)
        val blob = Base64.getDecoder().decode(disk.get("passcode"))
        listOf(0, 1, 13, blob.size - 1).forEach { i ->
            val tampered = blob.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            disk.put("passcode", Base64.getEncoder().encodeToString(tampered))
            assertNull("byte $i", store.get("passcode"))
        }
    }

    @Test
    fun noPlaintextOnDisk() {
        store.put("passcode", secret)
        val bytes = fileBytes()
        assertFalse(bytes.isEmpty())
        assertFalse(bytes.contains(secret.toByteArray()))
        assertFalse(bytes.contains(Base64.getEncoder().encodeToString(secret.toByteArray()).toByteArray()))
    }

    @Test
    fun cookieIsClearedOnLogoutAndOn401() {
        val cookies = PersistentCookieStore(store)
        val settings = StoredSettings(InMemoryPrefs(), store, cookies, BuildVariant.RELEASE)
        val cookie = StoredCookie("test-cookie-0001", Long.MAX_VALUE)

        cookies.put(cookie)
        assertEquals(cookie, cookies.get())
        assertFalse(fileBytes().contains(cookie.value.toByteArray()))
        settings.logout()
        assertNull(cookies.get())
        assertNull(disk.get(PersistentCookieStore.ENTRY))

        cookies.put(cookie)
        cookies.put(null) // What HttpGatewayApi does on a 401.
        assertNull(disk.get(PersistentCookieStore.ENTRY))
    }

    @Test
    fun rememberedPasscodeIsEncryptedAndWipedWhenTurnedOff() {
        val settings = StoredSettings(InMemoryPrefs(), store, PersistentCookieStore(store), BuildVariant.RELEASE)
        settings.setRememberPasscode(true)
        settings.rememberPasscode(secret)
        assertEquals(secret, settings.rememberedPasscode())
        assertFalse(fileBytes().contains(secret.toByteArray()))
        settings.setRememberPasscode(false)
        assertNull(disk.get(StoredSettings.PASSCODE_ENTRY))
    }

    private class InMemoryPrefs : KeyValueStore {
        private val entries = mutableMapOf<String, String>()
        override fun get(key: String): String? = entries[key]
        override fun put(key: String, value: String?) {
            if (value == null) entries.remove(key) else entries[key] = value
        }
    }
}
