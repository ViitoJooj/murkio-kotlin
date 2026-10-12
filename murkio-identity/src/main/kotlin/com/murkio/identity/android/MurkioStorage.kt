package com.murkio.identity.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.murkio.identity.android.internal.base64Decode
import com.murkio.identity.android.internal.base64UrlEncode
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where the SDK keeps the session between runs of the app. The default
 * encrypts it with a key that never leaves the Android Keystore; pass another
 * one to `Murkio.initialize` to keep it elsewhere (or [MurkioMemoryStorage] in
 * tests). The SDK calls it off the main thread.
 */
public interface MurkioStorage {
    public fun read(key: String): String?

    public fun write(key: String, value: String)

    public fun delete(key: String)
}

/** Keeps everything in memory: the session ends with the process. */
public class MurkioMemoryStorage : MurkioStorage {
    private val values = HashMap<String, String>()

    @Synchronized
    override fun read(key: String): String? = values[key]

    @Synchronized
    override fun write(key: String, value: String) {
        values[key] = value
    }

    @Synchronized
    override fun delete(key: String) {
        values.remove(key)
    }

    /** The stored keys, for tests. */
    @Synchronized
    public fun keys(): Set<String> = values.keys.toSet()
}

/**
 * Private preferences whose values are encrypted (AES-256-GCM) with a key
 * held by the Android Keystore. The key cannot be exported, so a copy of the
 * file (a backup restored on another phone) reads as "no session".
 */
internal class KeystoreStorage(context: Context) : MurkioStorage {
    private val application = context.applicationContext

    // Opened on first use, which the SDK does off the main thread: the app's startup reads no file for it.
    private val preferences by lazy { application.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    override fun read(key: String): String? {
        val stored = preferences.getString(key, null) ?: return null
        return try {
            val bytes = base64Decode(stored) ?: throw IllegalStateException("not base64")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        } catch (_: Exception) {
            // The Keystore key is gone (restored backup, cleared credentials): the value is unreadable for good.
            delete(key)
            null
        }
    }

    override fun write(key: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        save { putString(key, base64UrlEncode(encrypted)) }
    }

    override fun delete(key: String) {
        save { remove(key) }
    }

    /**
     * Writes to disk before returning. A refresh rotates the token on the
     * server: if the new one were still in memory when the process died, the
     * old one on disk would end the session at the next start.
     */
    @SuppressLint("ApplySharedPref")
    private fun save(change: SharedPreferences.Editor.() -> Unit) {
        preferences.edit().apply(change).commit()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val FILE = "com.murkio.identity"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "com.murkio.identity.storage"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
