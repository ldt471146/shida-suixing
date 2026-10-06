package cn.gxnu.campus.data

import cn.gxnu.campus.core.Credentials
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test

class CredentialSessionTransitionTest {
    private class MemoryCiphertext : CiphertextStorage {
        var value: ByteArray? = null
        var beforeWrite: () -> Unit = {}
        var rejectWrite = false
        override fun read() = value?.clone()
        override fun write(value: ByteArray) {
            beforeWrite()
            if (rejectWrite) throw IllegalStateException("fixture disk unavailable")
            this.value = value.clone()
        }
        override fun remove() { value = null }
    }

    private fun session(storage: MemoryCiphertext): CredentialSession {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return CredentialSession(CredentialStore(storage, CredentialKeyAccess { key }))
    }

    // Keeping the previous credentials live until write() returns permits a connection with stale inputs.
    @Test fun replacingAnAccountRevokesOldCredentialsBeforeTheDiskWrite() {
        val storage = MemoryCiphertext()
        val session = session(storage)
        session.save(Credentials("previous-fixture", "previous-password"), true)
        storage.beforeWrite = {
            assertNull("previous credentials must not remain usable during persistence", session.credentials)
            assertFalse(session.remembered)
        }

        val replacement = Credentials("replacement-fixture", "replacement-password")
        session.save(replacement, true)

        assertEquals(replacement, session.credentials)
        assertTrue(session.remembered)
    }

    @Test fun aFailedReplacementDoesNotResumeThePreviousAccount() {
        val storage = MemoryCiphertext()
        val session = session(storage)
        session.save(Credentials("previous-fixture", "previous-password"), true)
        storage.rejectWrite = true

        try {
            session.save(Credentials("replacement-fixture", "replacement-password"), true)
            fail("the disk failure must reach the caller")
        } catch (_: CredentialStorageException) {
            assertNull("failed edits must not silently submit the previous account", session.credentials)
            assertFalse(session.remembered)
        }
    }
}
