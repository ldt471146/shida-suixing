package cn.gxnu.campus.runtime

import cn.gxnu.campus.core.ConnectionState
import cn.gxnu.campus.core.ConnectionStatus
import cn.gxnu.campus.core.PortalFailure

internal enum class RuntimeDiagnosticPhase {
    RESTORE_ACCOUNT, RESTORE_OPTIONS, SAVE_ACCOUNT, DELETE_ACCOUNT, PROVIDER, AUTO_CONNECT,
    THEME, PREPARING, NEED_PERMISSION, SERVICE
}

/** Accepts only known stages and failure categories; sensitive network and account data have no input path. */
internal class RuntimeDiagnostics {
    private val entries = ArrayDeque<String>()
    private var lastConnection: Triple<Long, ConnectionStatus, PortalFailure?>? = null
    val lines: List<String> get() = entries.toList()

    fun record(phase: RuntimeDiagnosticPhase, startedAt: Long = now(), succeeded: Boolean = true) {
        append("${phase.name.lowercase()} · ${elapsed(startedAt)} ms · ${if (succeeded) "ok" else "failed"}")
    }

    fun observe(connection: ConnectionState) {
        if (connection.attemptId <= 0) return
        val key = Triple(connection.attemptId, connection.status, connection.failure)
        if (key == lastConnection) return
        lastConnection = key
        val elapsed = connection.connectionStartedAtMillis?.let(::elapsed) ?: 0
        append("attempt ${connection.attemptId} · ${connection.status.name.lowercase()} · $elapsed ms" +
            (connection.failure?.let { " · ${it.name.lowercase()}" } ?: ""))
    }

    private fun append(line: String) {
        entries.addLast(line)
        while (entries.size > 24) entries.removeFirst()
    }

    private fun elapsed(startedAt: Long): Long = (now() - startedAt).coerceAtLeast(0)
    companion object { fun now(): Long = System.nanoTime() / 1_000_000 }
}
