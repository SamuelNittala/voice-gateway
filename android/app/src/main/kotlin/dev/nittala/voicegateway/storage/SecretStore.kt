package dev.nittala.voicegateway.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Spec (A7): named secrets (the login cookie, the remembered passcode), encrypted at rest. Blocking: never call it
 * on the main thread. Implementations never log names' values.
 */
interface SecretStore {
    /**
     * The secret [name], or null when it is absent OR cannot be decrypted (wrong key, lost key, tampered or
     * malformed ciphertext). Fails closed: never returns the stored bytes as plaintext, never throws.
     */
    fun get(name: String): String?

    /**
     * Encrypts and stores [value] under [name]; null deletes it. If encryption fails, throws [SecretStoreException]
     * and the entry is deleted instead: nothing unencrypted is ever written, and a stale value never survives.
     */
    fun put(name: String, value: String?)
}

class SecretStoreException(cause: Throwable) : Exception("Could not encrypt a secret.", cause)

/** Where the AES key comes from. The real one is [AndroidKeystoreKeys]; JVM tests use a software key. */
interface KeySource {
    /** The key to encrypt with, created if there is none yet. */
    fun encryptionKey(): SecretKey

    /** The key to decrypt with, or null if there is none (never creates one). */
    fun decryptionKey(): SecretKey?
}

/**
 * Spec (A7), plain JVM crypto: AES-256-GCM with a 128-bit tag and a fresh random 12-byte IV per encryption chosen
 * by the provider. Blob = `0x01` (format version) + IV + ciphertext-with-tag. [aad] (the secret's name) is
 * authenticated, so a blob copied to another name does not decrypt. [decrypt] throws on any failure.
 */
class AesGcmCipher(private val keys: KeySource) {
    fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, keys.encryptionKey())
            updateAAD(aad)
        }
        val sealed = cipher.doFinal(plain)
        val iv = cipher.iv
        if (iv == null || iv.size != IV_BYTES) throw GeneralSecurityException("Unexpected IV length.")
        return byteArrayOf(VERSION) + iv + sealed
    }

    fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
        if (blob.size < 1 + IV_BYTES + TAG_BITS / 8 || blob[0] != VERSION) throw GeneralSecurityException("Malformed.")
        val key = keys.decryptionKey() ?: throw GeneralSecurityException("No key.")
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 1, IV_BYTES))
            updateAAD(aad)
        }
        return cipher.doFinal(blob, 1 + IV_BYTES, blob.size - 1 - IV_BYTES)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/**
 * Spec (A7): [SecretStore] over a [KeyValueStore]: each entry is Base64 (no wrapping) of an [AesGcmCipher] blob with
 * the name as AAD. Nothing else is written. See [SecretStore] for the fail-closed rules.
 */
class EncryptedSecretStore(private val cipher: AesGcmCipher, private val store: KeyValueStore) : SecretStore {
    override fun get(name: String): String? {
        val stored = store.get(name) ?: return null
        return try {
            val plain = cipher.decrypt(Base64.getDecoder().decode(stored), name.toByteArray(Charsets.UTF_8))
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(plain)).toString()
        } catch (_: Exception) {
            null // Fails closed: wrong or lost key, tampering or garbage all read as "no secret".
        }
    }

    override fun put(name: String, value: String?) {
        if (value == null) {
            store.put(name, null)
            return
        }
        val blob = try {
            cipher.encrypt(value.toByteArray(Charsets.UTF_8), name.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            store.put(name, null)
            throw SecretStoreException(e)
        }
        store.put(name, Base64.getEncoder().encodeToString(blob))
    }
}

/**
 * Spec (A7): the AES-256 key in the Android Keystore under [alias]: GCM, no padding, randomized encryption required,
 * no user-authentication requirement (the foreground service uses it with the screen off). Keystore keys are
 * non-exportable: the key material never leaves the keystore (`encoded` is null).
 */
class AndroidKeystoreKeys(private val alias: String = DEFAULT_ALIAS) : KeySource {
    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    override fun decryptionKey(): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    @Synchronized
    override fun encryptionKey(): SecretKey = decryptionKey() ?: generate()

    /** Deletes the key: every secret encrypted with it becomes unreadable (and so reads as absent). */
    fun delete() = keyStore().deleteEntry(alias)

    private fun generate(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
        init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        generateKey()
    }

    companion object {
        const val DEFAULT_ALIAS = "vg_secrets"
        private const val PROVIDER = "AndroidKeyStore"
    }
}
