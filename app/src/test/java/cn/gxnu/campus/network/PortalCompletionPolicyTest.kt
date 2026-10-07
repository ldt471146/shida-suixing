package cn.gxnu.campus.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The embedded page may only call a login finished on the same HTTPS 204 evidence the automatic
 * attempt uses. These cases pin the polling schedule, the give-up reason, and which outcome the
 * app may leave the page for on its own.
 */
class PortalCompletionPolicyTest {

    @Test fun aPageOpenedWhileAlreadyOnlineIsProbedImmediately() {
        assertEquals(PortalDecision.Probe(0L), PortalCompletionPolicy.next(elapsedMillis = 0, probe = null))
    }

    @Test fun anOnlineProbeSettlesOnSuccess() {
        assertEquals(
            PortalDecision.Settle(PortalCompletion.Online),
            PortalCompletionPolicy.next(elapsedMillis = 0, probe = PortalProbe.ONLINE)
        )
        // Still online after a long watch is success, not a timeout.
        assertEquals(
            PortalDecision.Settle(PortalCompletion.Online),
            PortalCompletionPolicy.next(PortalCompletionPolicy.POLL_DEADLINE_MILLIS, PortalProbe.ONLINE)
        )
    }

    @Test fun anUnansweredProbeKeepsPolling() {
        assertEquals(
            PortalDecision.Probe(PortalCompletionPolicy.POLL_INTERVAL_MILLIS),
            PortalCompletionPolicy.next(elapsedMillis = 0, probe = PortalProbe.OFFLINE)
        )
        assertEquals(
            PortalDecision.Probe(PortalCompletionPolicy.POLL_INTERVAL_MILLIS),
            PortalCompletionPolicy.next(PortalCompletionPolicy.POLL_INTERVAL_MILLIS, PortalProbe.UNREACHABLE)
        )
    }

    @Test fun theWatchNeverAsksForAProbeItCannotFitInsideTheDeadline() {
        val lastRoomForAProbe = PortalCompletionPolicy.POLL_DEADLINE_MILLIS - PortalCompletionPolicy.POLL_INTERVAL_MILLIS
        assertEquals(
            PortalDecision.Probe(PortalCompletionPolicy.POLL_INTERVAL_MILLIS),
            PortalCompletionPolicy.next(lastRoomForAProbe - 1, PortalProbe.OFFLINE)
        )
        assertTrue(PortalCompletionPolicy.next(lastRoomForAProbe, PortalProbe.OFFLINE) is PortalDecision.Settle)
        assertTrue(PortalCompletionPolicy.next(PortalCompletionPolicy.POLL_DEADLINE_MILLIS, PortalProbe.OFFLINE) is PortalDecision.Settle)
    }

    @Test fun theDeadlineLeavesThePageForAManualLoginInsteadOfClaimingSuccess() {
        val completion = settled(PortalCompletionPolicy.next(PortalCompletionPolicy.POLL_DEADLINE_MILLIS, PortalProbe.OFFLINE))
        assertTrue(completion is PortalCompletion.Manual)
        val reason = (completion as PortalCompletion.Manual).reason
        assertTrue(reason.contains("手动登录"))
        assertFalse(reason.contains("成功"))
    }

    @Test fun aProbeThatCannotReachTheWiFiExplainsThatSeparatelyFromAnOfflineProbe() {
        val unreachable = (settled(PortalCompletionPolicy.next(PortalCompletionPolicy.POLL_DEADLINE_MILLIS, PortalProbe.UNREACHABLE))
            as PortalCompletion.Manual).reason
        val offline = (settled(PortalCompletionPolicy.next(PortalCompletionPolicy.POLL_DEADLINE_MILLIS, PortalProbe.OFFLINE))
            as PortalCompletion.Manual).reason
        assertNotEquals(offline, unreachable)
        assertTrue(unreachable.contains("校园 Wi-Fi"))
    }

    @Test fun theWatchIsBoundedToAWholeMinuteAtASaneInterval() {
        assertEquals(60_000L, PortalCompletionPolicy.POLL_DEADLINE_MILLIS)
        assertEquals(2_000L, PortalCompletionPolicy.POLL_INTERVAL_MILLIS)
        // Fast enough that the user does not wait on a page that already succeeded.
        assertTrue(PortalCompletionPolicy.POLL_INTERVAL_MILLIS < PortalCompletionPolicy.POLL_DEADLINE_MILLIS / 10)
    }

    @Test fun onlySuccessReturnsToTheAppByItself() {
        assertEquals(PortalCompletionPolicy.CONFIRMATION_MILLIS, PortalCompletionPolicy.autoReturnDelay(PortalCompletion.Online))
        assertEquals(1_500L, PortalCompletionPolicy.CONFIRMATION_MILLIS)
        assertNull(PortalCompletionPolicy.autoReturnDelay(PortalCompletion.Checking))
        assertNull(PortalCompletionPolicy.autoReturnDelay(PortalCompletion.Manual("仍未检测到外网，可在此页面手动登录。")))
    }

    private fun settled(decision: PortalDecision): PortalCompletion =
        (decision as? PortalDecision.Settle)?.completion
            ?: throw AssertionError("the watch must settle, but decided $decision")
}
