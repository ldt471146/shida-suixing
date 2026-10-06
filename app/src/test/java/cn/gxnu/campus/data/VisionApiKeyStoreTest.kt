package cn.gxnu.campus.data

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The API key vault, exercised with a memory storage and an ephemeral AES key. */
class VisionApiKeyStoreTest {

    private class MemoryCiphertext : CiphertextStorage {
        var value: ByteArray? = null
        override fun read() = value?.clone()
        override fun write(value: ByteArray) { this.value = value.clone() }
        override fun remove() { value = null }
    }

    private class TrackingKey(private val key: SecretKey) : CredentialKeyAccess {
        var deleted = false
        override fun key(): SecretKey = key
        override fun delete() { deleted = true }
    }

    private fun ephemeralKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun vault(storage: MemoryCiphertext, key: SecretKey = ephemeralKey()) =
        VisionApiKeyStore(storage, CredentialKeyAccess { key })

    private fun vault(storage: MemoryCiphertext, access: CredentialKeyAccess) = VisionApiKeyStore(storage, access)

    @Test fun theKeyIsEncryptedAtRestAndReadsBack() {
        val storage = MemoryCiphertext()
        val store = vault(storage)
        store.save("sk-fixture-secret-key")
        assertNotNull(storage.value)
        val storedBytes = String(storage.value!!, Charsets.ISO_8859_1)
        assertFalse("the plaintext key must not be stored", storedBytes.contains("sk-fixture-secret-key"))
        assertEquals("sk-fixture-secret-key", store.load())
        val first = storage.value!!.clone()
        store.save("sk-fixture-secret-key")
        assertFalse("each save must use a fresh IV", first.contentEquals(storage.value))
    }

    @Test fun oneVaultCannotReadAnotherVaultsCiphertext() {
        val storage = MemoryCiphertext()
        vault(storage).save("sk-fixture-secret-key")
        // A different Keystore key stands in for a reinstall or a restored backup.
        val failure = try {
            vault(storage).load()
            throw AssertionError("foreign ciphertext must not decrypt")
        } catch (failure: VisionKeyStorageException) {
            failure
        }
        assertFalse(failure.toString().contains("sk-fixture-secret-key"))
    }

    @Test fun anEmptyVaultReadsAsNoKey() {
        assertNull(vault(MemoryCiphertext()).load())
    }

    @Test fun tamperedCiphertextNeverYieldsAKey() {
        val storage = MemoryCiphertext()
        val store = vault(storage)
        store.save("sk-fixture-secret-key")
        val bytes = storage.value!!
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        storage.value = bytes
        try {
            store.load()
            throw AssertionError("tampered ciphertext must not load")
        } catch (failure: VisionKeyStorageException) {
            assertFalse(failure.toString().contains("sk-fixture-secret-key"))
        }
    }

    @Test fun deletingDropsBothTheCiphertextAndTheKey() {
        val storage = MemoryCiphertext()
        val access = TrackingKey(ephemeralKey())
        val store = vault(storage, access)
        store.save("sk-fixture-secret-key")
        store.clear()
        assertNull(storage.value)
        assertNull(store.load())
        assertTrue("the vault key is dropped too, so stale ciphertext stays unreadable", access.deleted)
    }

    @Test fun theDisplayedHintNeverRevealsTheWholeKey() {
        assertEquals("••••cdef", maskApiKey("sk-fixture-abcdef"))
        assertEquals("", maskApiKey("sk-a"))
        assertEquals("", maskApiKey(null))
        assertEquals("", maskApiKey("   "))
        assertFalse(maskApiKey("sk-fixture-abcdef").contains("sk-fixture"))
    }
}
