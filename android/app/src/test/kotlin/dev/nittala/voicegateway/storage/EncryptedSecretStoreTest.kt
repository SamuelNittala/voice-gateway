package dev.nittala.voicegateway.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Spec (A7): [EncryptedSecretStore] with real AES-GCM and a software key. The Keystore key itself is androidTest. */
class EncryptedSecretStoreTest {
    private val keys = SoftwareKeys()
    private val disk = InMemoryKeyValueStore()
    private val store = EncryptedSecretStore(AesGcmCipher(keys), disk)
    private val secret = "test-secret-value-0001"

    private fun blob(name: String): ByteArray = Base64.getDecoder().decode(disk.entries.getValue(name))

    @Test
    fun roundTrips() {
        store.put("passcode", secret)
        assertEquals(secret, store.get("passcode"))
    }

    @Test
    fun roundTripsUnicodeAndEmpty() {
        store.put("a", "pässcode 你好")
        store.put("b", "")
        assertEquals("pässcode 你好", store.get("a"))
        assertEquals("", store.get("b"))
    }

    @Test
    fun absentIsNull() = assertNull(store.get("passcode"))

    @Test
    fun putNullDeletes() {
        store.put("passcode", secret)
        store.put("passcode", null)
        assertNull(store.get("passcode"))
        assertTrue(disk.entries.isEmpty())
    }

    @Test
    fun onlyCiphertextReachesTheStore() {
        store.put("passcode", secret)
        val stored = disk.entries.getValue("passcode")
        assertFalse(stored.contains(secret))
        assertFalse(String(blob("passcode"), Charsets.ISO_8859_1).contains(secret))
        assertFalse(stored.contains(Base64.getEncoder().encodeToString(secret.toByteArray())))
        val bytes = blob("passcode")
        assertEquals(1, bytes[0].toInt())
        assertEquals(1 + 12 + secret.length + 16, bytes.size)
    }

    @Test
    fun freshIvEveryTime() {
        store.put("a", secret)
        val first = disk.entries.getValue("a")
        store.put("a", secret)
        assertNotEquals(first, disk.entries.getValue("a"))
    }

    @Test
    fun wrongKeyFailsClosed() {
        store.put("passcode", secret)
        keys.key = SoftwareKeys.newKey()
        assertNull(store.get("passcode"))
    }

    @Test
    fun lostKeyFailsClosed() {
        store.put("passcode", secret)
        keys.key = null
        assertNull(store.get("passcode"))
    }

    @Test
    fun everyTamperedByteFailsClosed() {
        store.put("passcode", secret)
        val original = blob("passcode")
        original.indices.forEach { i ->
            val tampered = original.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            disk.entries["passcode"] = Base64.getEncoder().encodeToString(tampered)
            assertNull("byte $i", store.get("passcode"))
        }
    }

    @Test
    fun truncatedOrGarbageFailsClosed() {
        store.put("passcode", secret)
        val original = blob("passcode")
        listOf(
            Base64.getEncoder().encodeToString(original.copyOf(original.size - 1)),
            Base64.getEncoder().encodeToString(original.copyOf(13)),
            "",
            "not base64 !!!",
            secret, // a plaintext value planted in the file is never returned
        ).forEach { planted ->
            disk.entries["passcode"] = planted
            assertNull(planted, store.get("passcode"))
        }
    }

    @Test
    fun blobMovedToAnotherNameFailsClosed() {
        store.put("passcode", secret)
        disk.entries["vg_login"] = disk.entries.getValue("passcode")
        assertNull(store.get("vg_login"))
    }

    @Test
    fun encryptionFailureThrowsAndLeavesNothing() {
        store.put("passcode", secret)
        keys.failEncryption = true
        assertThrows(SecretStoreException::class.java) { store.put("passcode", "new-value") }
        assertTrue(disk.entries.isEmpty())
        assertNull(store.get("passcode"))
    }
}
