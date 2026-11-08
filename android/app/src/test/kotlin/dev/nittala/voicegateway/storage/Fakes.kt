package dev.nittala.voicegateway.storage

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** In-memory [KeyValueStore]; [entries] is what would be on disk. */
class InMemoryKeyValueStore : KeyValueStore {
    val entries = linkedMapOf<String, String>()
    override fun get(key: String): String? = entries[key]
    override fun put(key: String, value: String?) {
        if (value == null) entries.remove(key) else entries[key] = value
    }
}

/** In-memory [SecretStore] with no crypto, for tests of the classes above it. */
class FakeSecretStore : SecretStore {
    val entries = linkedMapOf<String, String>()
    override fun get(name: String): String? = entries[name]
    override fun put(name: String, value: String?) {
        if (value == null) entries.remove(name) else entries[name] = value
    }
}

/** A software AES-256 key standing in for the Keystore. [key] can be swapped or removed to simulate a lost key. */
class SoftwareKeys(var key: SecretKey? = newKey()) : KeySource {
    var failEncryption = false
    override fun encryptionKey(): SecretKey {
        check(!failEncryption) { "keystore unavailable" }
        return key ?: newKey().also { key = it }
    }
    override fun decryptionKey(): SecretKey? = key

    companion object {
        fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
}
