package cn.gxnu.campus.runtime

import cn.gxnu.campus.core.ConnectionState
import cn.gxnu.campus.core.ConnectionStatus
import org.junit.Assert.*
import org.junit.Test

class FeedbackEventsTest {
    @Test fun aConnectionTerminalIsAnnouncedOnceButANewAttemptRepeatsTheSameError() {
        val events = FeedbackEvents()
        val failure = ConnectionState(ConnectionStatus.AUTH_ERROR, "fixture error", attemptId = 1)
        events.observeConnection(failure)
        val first = events.id
        assertEquals("fixture error", events.message)

        events.clear(first)
        events.observeConnection(failure)
        assertNull("collecting the same terminal cannot redisplay its notice", events.message)
        assertEquals(first, events.id)

        events.observeConnection(failure.copy(attemptId = 2))
        assertEquals("fixture error", events.message)
        assertTrue("a new attempt must have a new feedback event", events.id > first)
    }

    @Test fun clearingAnEarlierSnackbarCannotEraseANewerFeedbackEvent() {
        val events = FeedbackEvents()
        events.publish("first fixture notice")
        val previous = events.id
        events.publish("next fixture notice")

        events.clear(previous)

        assertEquals("next fixture notice", events.message)
        events.clear(events.id)
        assertNull(events.message)
    }

    @Test fun preparationAndCheckingDoNotEmitAConnectionResult() {
        val events = FeedbackEvents()
        events.observeConnection(ConnectionState(ConnectionStatus.PREPARING, "fixture prepare", attemptId = 1))
        events.observeConnection(ConnectionState(ConnectionStatus.CHECKING, "fixture check", attemptId = 1))

        assertNull(events.message)
        assertEquals(0L, events.id)
    }
}
