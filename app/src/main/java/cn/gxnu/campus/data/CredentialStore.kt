package cn.gxnu.campus.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import cn.gxnu.campus.core.Credentials
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

internal interface CiphertextStorage {
    fun read(): ByteArray?
    fun write(value: ByteArray)
    fun remove()
}

internal fun interface CredentialKeyAccess {
    fun key(): SecretKey
    fun delete() = Unit
}

class CredentialStore internal constructor(
    private val storage: CiphertextStorage,
    private val keyAccess: CredentialKeyAccess
) {
    constructor(context: Context) : this(AndroidCiphertextStorage(context), AndroidCredentialKey())

    fun save(credentials: Credentials) = safely {
        val plaintext = ByteArrayOutputStream().let { bytes ->
            DataOutputStream(bytes).use { output ->
                writeString(output, credentials.account)
                writeString(output, credentials.password)
            }
            bytes.toByteArray()
        }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            // Android Keystore generates a fresh, random IV on every encryption.
            cipher.init(Cipher.ENCRYPT_MODE, keyAccess.key())
            val ciphertext = cipher.doFinal(plaintext)
            val envelope = ByteArrayOutputStream().let { bytes ->
                DataOutputStream(bytes).use {
                    it.writeInt(1)
                    it.writeInt(cipher.iv.size)
                    it.write(cipher.iv)
                    it.write(ciphertext)
                }
                bytes.toByteArray()
            }
            storage.write(envelope)
        } finally { plaintext.fill(0) }
    }

    fun load(): Credentials? = safely {
        val envelope = storage.read() ?: return@safely null
        val input = DataInputStream(ByteArrayInputStream(envelope))
        check(input.readInt() == 1)
        val ivLength = input.readInt()
        check(ivLength == 12)
        val iv = ByteArray(ivLength).also { input.readFully(it) }
        val ciphertext = input.readBytes()
        check(ciphertext.size in 16..131_072)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyAccess.key(), GCMParameterSpec(128, iv))
        val plaintext = cipher.doFinal(ciphertext)
        try {
            DataInputStream(ByteArrayInputStream(plaintext)).use {
                val result = Credentials(readString(it), readString(it))
                check(it.available() == 0)
                result
            }
        } finally { plaintext.fill(0) }
    }

    fun clear() = safely {
        // Deleting the key also invalidates any stale ciphertext if a disk write fails.
        try { storage.remove() } finally { keyAccess.delete() }
    }

    private fun writeString(output: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        try { check(bytes.size <= 65_536); output.writeInt(bytes.size); output.write(bytes) }
        finally { bytes.fill(0) }
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readInt()
        check(length in 0..65_536 && length <= input.available())
        val bytes = ByteArray(length).also { input.readFully(it) }
        return try { String(bytes, Charsets.UTF_8) } finally { bytes.fill(0) }
    }

    private inline fun <T> safely(block: () -> T): T = try { block() }
    catch (_: Exception) { throw CredentialStorageException() }
}

internal class CredentialSession(private val store: CredentialStore) {
    var credentials: Credentials? = null
        private set
    var remembered: Boolean = false
        private set

    fun restore() {
        credentials = store.load()
        remembered = credentials != null
    }
    fun save(credentials: Credentials, remember: Boolean) {
        // Revoke the old account before a potentially slow or failing vault operation.
        this.credentials = null
        remembered = false
        if (remember) store.save(credentials) else store.clear()
        this.credentials = credentials
        remembered = remember
    }
    fun delete() {
        credentials = null
        remembered = false
        store.clear()
    }
}

class CredentialStorageException : Exception("安全保存不可用，请重新输入或稍后重试。")

private class AndroidCiphertextStorage(context: Context) : CiphertextStorage {
    private val applicationContext = context.applicationContext
    private val preferences by lazy { applicationContext.getSharedPreferences("campus_credentials", Context.MODE_PRIVATE) }
    override fun read(): ByteArray? = preferences.getString("encrypted_payload", null)?.let { Base64.getDecoder().decode(it) }
    override fun write(value: ByteArray) {
        check(preferences.edit().putString("encrypted_payload", Base64.getEncoder().encodeToString(value)).commit())
    }
    override fun remove() { check(preferences.edit().clear().commit()) }
}

private class AndroidCredentialKey : CredentialKeyAccess {
    private val alias = "cn.gxnu.campus.credentials.v1"
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
