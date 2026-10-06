package cn.gxnu.campus.runtime

import cn.gxnu.campus.core.Credentials
import cn.gxnu.campus.data.CiphertextStorage
import cn.gxnu.campus.data.CredentialKeyAccess
import cn.gxnu.campus.data.CredentialSession
import cn.gxnu.campus.data.CredentialStore
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RuntimeCredentialSessionTest {
    private class MemoryCiphertext : CiphertextStorage {
        var value: ByteArray? = null
        var writes = 0
        var rejectWrites = false
        override fun read() = value?.clone()
        override fun write(value: ByteArray) {
            if (rejectWrites) throw IllegalStateException("fixture disk unavailable")
            writes++
            this.value = value.clone()
        }
        override fun remove() { value = null }
    }

    private fun store(storage: MemoryCiphertext): CredentialStore {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return CredentialStore(storage, CredentialKeyAccess { key })
    }

    @Test fun asyncSavingRevokesTheAccountBeforeDiskWorkStarts() = runTest {
        val storage = MemoryCiphertext()
        val vault = store(storage)
        val session = RuntimeCredentialSession(CredentialSession(vault), RuntimeStorageQueue(StandardTestDispatcher(testScheduler)))
        assertTrue(session.save(Credentials("previous-fixture", "previous-password"), true))
        val replacement = Credentials("replacement-fixture", "replacement-password")

        val pendingSave = async(start = CoroutineStart.UNDISPATCHED) { session.save(replacement, true) }

        assertNull("network consumers cannot obtain the previous account during an IO suspension", session.state.value.credentials)
        assertTrue(session.state.value.changing)
        assertEquals("writing must be dispatched away from the caller", 1, storage.writes)
        runCurrent()
        assertTrue(pendingSave.await())
        assertEquals(replacement, session.state.value.credentials)
    }

    @Test fun deletingWhileAnEarlierSaveIsQueuedCannotRepublishItsCredentials() = runTest {
        val storage = MemoryCiphertext()
        val vault = store(storage)
        val session = RuntimeCredentialSession(CredentialSession(vault), RuntimeStorageQueue(StandardTestDispatcher(testScheduler)))
        val saving = async(start = CoroutineStart.UNDISPATCHED) { session.save(Credentials("queued-fixture", "queued-password"), true) }
        val deleting = async(start = CoroutineStart.UNDISPATCHED) { session.delete() }

        runCurrent()

        assertFalse("the superseded save cannot report a published account", saving.await())
        assertTrue(deleting.await())
        assertNull(session.state.value.credentials)
        assertFalse(session.state.value.remembered)
        assertNull("serialized deletion must also remove restorable credentials", vault.load())
    }

    @Test fun initializationDoesNotExposeFirstRunUntilRestoreHasFinished() = runTest {
        val storage = MemoryCiphertext()
        val vault = store(storage)
        val saved = Credentials("restored-fixture", "restored-password")
        vault.save(saved)
        val session = RuntimeCredentialSession(CredentialSession(vault), RuntimeStorageQueue(StandardTestDispatcher(testScheduler)))
        assertTrue(session.state.value.initializing)
        val restoring = async(start = CoroutineStart.UNDISPATCHED) { session.restore() }
        assertTrue(session.state.value.initializing)

        runCurrent()

        assertTrue(restoring.await())
        assertFalse(session.state.value.initializing)
        assertEquals(saved, session.state.value.credentials)
    }

    // If the automatic option is changed only after save(), a disk failure leaves the previous account armed.
    @Test fun aFailedReplacementPausesThePreviousAutomaticChoiceBeforeWritingTheVault() = runTest {
        val storage = MemoryCiphertext()
        val session = RuntimeCredentialSession(CredentialSession(store(storage)), RuntimeStorageQueue(StandardTestDispatcher(testScheduler)))
        assertTrue(session.save(Credentials("previous-fixture", "previous-password"), true))
        var automatic = true
        storage.rejectWrites = true

        val saved = session.save(Credentials("replacement-fixture", "replacement-password"), true,
            beforePersist = { automatic = false }, afterPersist = { automatic = true })

        assertFalse(saved)
        assertFalse("a failed replacement must not leave the previous automatic account armed", automatic)
        assertNull(session.state.value.credentials)
    }
}
