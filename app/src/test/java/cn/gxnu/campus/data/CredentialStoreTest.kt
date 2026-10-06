package cn.gxnu.campus.data

import cn.gxnu.campus.core.Credentials
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test

class CredentialStoreTest {
    private class MemoryCiphertext : CiphertextStorage {
        var value: ByteArray? = null
        override fun read() = value?.clone()
        override fun write(value: ByteArray) { this.value = value.clone() }
        override fun remove() { value = null }
    }

    private fun vault(storage: MemoryCiphertext): CredentialStore {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return CredentialStore(storage, CredentialKeyAccess { key })
    }

    @Test fun persistedCredentialsAreEncryptedAndPasswordSpacesSurvive() {
        val storage = MemoryCiphertext()
        val store = vault(storage)
        val credentials = Credentials("fixture-student-42", "  a+b&密码= x  ")
        store.save(credentials)
        assertNotNull("saving must create ciphertext", storage.value)
        val storedBytes = String(storage.value!!, Charsets.ISO_8859_1)
        assertFalse(storedBytes.contains(credentials.account))
        assertFalse(storedBytes.contains(credentials.password))
        assertEquals(credentials, store.load())
        val first = storage.value!!.clone()
        store.save(credentials)
        assertFalse("each save must use a fresh IV", first.contentEquals(storage.value))
    }

    @Test fun turningOffRememberRemovesEarlierSavedCredentialsAndKeepsOnlySession() {
        val storage = MemoryCiphertext()
        val store = vault(storage)
        val session = CredentialSession(store)
        session.save(Credentials("old-fixture", "old-fixture-password"), true)
        val temporary = Credentials("current-fixture", " temporary password ")
        session.save(temporary, false)
        assertEquals(temporary, session.credentials)
        assertFalse(session.remembered)
        assertNull(store.load())
        val nextSession = CredentialSession(store).apply { restore() }
        assertNull(nextSession.credentials)
    }

    @Test fun deletingAnAccountRemovesBothLiveAndRestorableCredentials() {
        val storage = MemoryCiphertext()
        val store = vault(storage)
        val session = CredentialSession(store)
        session.save(Credentials("deleted-fixture", "fixture-pass"), true)
        session.delete()
        assertNull(session.credentials)
        assertFalse(session.remembered)
        assertNull(store.load())
        assertNull(CredentialSession(store).apply { restore() }.credentials)
    }

    @Test fun corruptedCiphertextDoesNotReturnPartlyDecodedCredentials() {
        val storage = MemoryCiphertext()
        val store = vault(storage)
        store.save(Credentials("integrity-fixture", "fixture-pass"))
        val bytes = storage.value!!
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        storage.value = bytes
        try {
            store.load()
            fail("tampered ciphertext must not load")
        } catch (failure: Exception) {
            assertFalse(failure.toString().contains("integrity-fixture"))
            assertFalse(failure.toString().contains("fixture-pass"))
        }
    }
}
