package cn.gxnu.campus.runtime

import cn.gxnu.campus.core.ConnectionState
import cn.gxnu.campus.core.ConnectionStatus

/** Event identity lets a dismissed notice acknowledge only the notice it displayed. */
internal class FeedbackEvents {
    var id: Long = 0
        private set
    var message: String? = null
        private set
    private var highestAttemptId = 0L
    private val announcedStatuses = mutableSetOf<ConnectionStatus>()

    fun publish(message: String) {
        id++
        this.message = message
    }

    fun observeConnection(connection: ConnectionState) {
        if (connection.attemptId <= 0 || connection.attemptId < highestAttemptId) return
        if (connection.attemptId > highestAttemptId) {
            highestAttemptId = connection.attemptId
            announcedStatuses.clear()
        }
        if (connection.status in resultStatuses && announcedStatuses.add(connection.status)) publish(connection.message)
    }

    fun clear(expectedId: Long? = null) {
        if (expectedId == null || expectedId == id) message = null
    }

    private companion object {
        val resultStatuses = setOf(ConnectionStatus.ONLINE, ConnectionStatus.AUTH_ERROR,
            ConnectionStatus.UNREACHABLE, ConnectionStatus.CANCELLED, ConnectionStatus.NEED_PERMISSION,
            ConnectionStatus.NO_WIFI, ConnectionStatus.OUTSIDE_CAMPUS)
    }
}
