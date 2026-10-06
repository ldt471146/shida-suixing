package cn.gxnu.campus.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The user's vision API key sits in the same vault as the campus password: AES/GCM under an Android
 * Keystore key that never leaves the device, so neither the source tree nor the installed APK
 * carries a usable key. The envelope mirrors [CredentialStore] and keeps its own alias, which
 * means deleting one secret never invalidates the other.
 */
class VisionApiKeyStore internal constructor(
    private val storage: CiphertextStorage,
    private val keyAccess: CredentialKeyAccess
) {
    constructor(context: Context) : this(AndroidVisionCiphertextStorage(context), AndroidVisionKey())

    fun save(apiKey: String) = safely {
        val plaintext = apiKey.toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            // Android Keystore generates a fresh, random IV on every encryption.
            cipher.init(Cipher.ENCRYPT_MODE, keyAccess.key())
            val ciphertext = cipher.doFinal(plaintext)
            val envelope = ByteArrayOutputStream().let { bytes ->
                DataOutputStream(bytes).use {
                    it.writeInt(ENVELOPE_VERSION)
                    it.writeInt(cipher.iv.size)
                    it.write(cipher.iv)
                    it.write(ciphertext)
                }
                bytes.toByteArray()
            }
            storage.write(envelope)
        } finally { plaintext.fill(0) }
    }

    fun load(): String? = safely {
        val envelope = storage.read() ?: return@safely null
        val input = DataInputStream(ByteArrayInputStream(envelope))
        check(input.readInt() == ENVELOPE_VERSION)
        val ivLength = input.readInt()
        check(ivLength == 12)
        val iv = ByteArray(ivLength).also { input.readFully(it) }
        val ciphertext = input.readBytes()
        check(ciphertext.size in 16..4_096)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyAccess.key(), GCMParameterSpec(128, iv))
        val plaintext = cipher.doFinal(ciphertext)
        try {
            String(plaintext, Charsets.UTF_8)
        } finally { plaintext.fill(0) }
    }

    fun clear() = safely {
        // Deleting the key also invalidates any stale ciphertext if a disk write fails.
        try { storage.remove() } finally { keyAccess.delete() }
    }

    private inline fun <T> safely(block: () -> T): T = try { block() }
    catch (_: Exception) { throw VisionKeyStorageException() }

    private companion object {
        const val ENVELOPE_VERSION = 1
    }
}

class VisionKeyStorageException : Exception("API Key 暂时无法安全保存，请稍后重试。")

/** A hint the settings row may show: never the whole key, only enough to tell two keys apart. */
internal fun maskApiKey(value: String?): String {
    val key = value?.trim().orEmpty()
    return if (key.length <= 4) "" else "••••" + key.takeLast(4)
}

private class AndroidVisionCiphertextStorage(context: Context) : CiphertextStorage {
    private val applicationContext = context.applicationContext
    private val preferences by lazy { applicationContext.getSharedPreferences("campus_vision_key", Context.MODE_PRIVATE) }
    override fun read(): ByteArray? = preferences.getString("encrypted_payload", null)?.let { Base64.getDecoder().decode(it) }
    override fun write(value: ByteArray) {
        check(preferences.edit().putString("encrypted_payload", Base64.getEncoder().encodeToString(value)).commit())
    }
    override fun remove() { check(preferences.edit().clear().commit()) }
}

private class AndroidVisionKey : CredentialKeyAccess {
    private val alias = "cn.gxnu.campus.vision.v1"
    private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    override fun key(): SecretKey {
        val existing = keystore().getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }
    override fun delete() { keystore().deleteEntry(alias) }
}
