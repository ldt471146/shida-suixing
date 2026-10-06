package cn.gxnu.campus.runtime

import cn.gxnu.campus.core.Credentials
import cn.gxnu.campus.data.CredentialSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Acquire in the caller's order before dispatching disk work, so later edits cannot overtake earlier ones. */
internal class RuntimeStorageQueue(private val dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    private val mutex = Mutex()
    suspend fun <T> run(operation: () -> T): T = mutex.withLock { withContext(dispatcher) { operation() } }
}

internal data class RuntimeCredentialState(
    val credentials: Credentials? = null,
    val remembered: Boolean = false,
    val initializing: Boolean = true,
    val changing: Boolean = false
)

/** Caller-thread memory snapshots; the vault and its mutable session belong only to the IO queue. */
internal class RuntimeCredentialSession(
    private val session: CredentialSession,
    private val storage: RuntimeStorageQueue
) {
    private val mutableState = MutableStateFlow(RuntimeCredentialState())
    val state = mutableState.asStateFlow()
    private var generation = 0L

    suspend fun restore(): Boolean = mutate(restoring = true) { session.restore() }
    suspend fun save(credentials: Credentials, remember: Boolean, beforePersist: () -> Unit = {}, afterPersist: () -> Unit = {}): Boolean =
        mutate { beforePersist(); session.save(credentials, remember); afterPersist() }
    suspend fun delete(afterPersist: () -> Unit = {}): Boolean =
        mutate { try { session.delete() } finally { afterPersist() } }

    private suspend fun mutate(restoring: Boolean = false, operation: () -> Unit): Boolean {
        val ticket = ++generation
        // A suspended disk operation must not leave its previous account visible to network consumers.
        mutableState.value = RuntimeCredentialState(initializing = restoring, changing = !restoring)
        return try {
            val snapshot = storage.run {
                operation()
                RuntimeCredentialState(session.credentials, session.remembered, initializing = false)
            }
            if (ticket != generation) false else {
                mutableState.value = snapshot
                true
            }
        } catch (cancelled: CancellationException) {
            if (ticket == generation) mutableState.value = RuntimeCredentialState(initializing = false)
            throw cancelled
        } catch (_: Exception) {
            if (ticket == generation) mutableState.value = RuntimeCredentialState(initializing = false)
            false
        }
    }
}
