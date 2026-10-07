package cn.gxnu.campus.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watch must reuse the verification the automatic attempt uses, must end on a real answer or on
 * its deadline, and must leave nothing running once the page is closed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PortalCompletionMonitorTest {

    @Test fun aPageOpenedWhileAlreadyOnlineIsConfirmedByTheFirstProbe() = runTest {
        var probes = 0
        val monitor = monitorOf { probes++; true }
        monitor.start(backgroundScope)
        runCurrent()

        assertEquals(1, probes)
        assertEquals(PortalCompletion.Online, monitor.state.value)
    }

    @Test fun aPageThatLooksFinishedIsNotConfirmedUntilTheProbeAnswers() = runTest {
        var probes = 0
        val monitor = monitorOf { probes++; probes >= 3 }
        monitor.start(backgroundScope)
        runCurrent()
        assertEquals(PortalCompletion.Checking, monitor.state.value)

        advanceTimeBy(PortalCompletionPolicy.POLL_INTERVAL_MILLIS + 1)
        runCurrent()
        assertEquals(2, probes)
        assertEquals(PortalCompletion.Checking, monitor.state.value)

        advanceTimeBy(PortalCompletionPolicy.POLL_INTERVAL_MILLIS + 1)
        runCurrent()
        assertEquals(3, probes)
        assertEquals(PortalCompletion.Online, monitor.state.value)
    }

    @Test fun aPageThatNeverReachesTheInternetGivesUpAtTheDeadlineAndStaysOpen() = runTest {
        var probes = 0
        val monitor = monitorOf { probes++; false }
        val watch = monitor.start(backgroundScope)
        advancePastTheDeadline()

        val settled = monitor.state.value
        assertTrue(settled is PortalCompletion.Manual)
        assertTrue((settled as PortalCompletion.Manual).reason.contains("手动登录"))
        assertEquals((PortalCompletionPolicy.POLL_DEADLINE_MILLIS / PortalCompletionPolicy.POLL_INTERVAL_MILLIS).toInt(), probes)
        assertTrue(watch.isCompleted)

        // Nothing may keep polling once the watch gave up; the page belongs to the user by now.
        val spent = probes
        advanceTimeBy(10 * PortalCompletionPolicy.POLL_INTERVAL_MILLIS)
        runCurrent()
        assertEquals(spent, probes)
        assertEquals(settled, monitor.state.value)
    }

    @Test fun aProbeThatCannotReachTheWiFiReportsItsOwnReason() = runTest {
        val unreachable = monitorOf { throw IllegalStateException("the target Wi-Fi is gone") }
        unreachable.start(backgroundScope)
        advancePastTheDeadline()
        val offline = monitorOf { false }
        offline.start(backgroundScope)
        advancePastTheDeadline()

        val unreachableReason = (unreachable.state.value as PortalCompletion.Manual).reason
        val offlineReason = (offline.state.value as PortalCompletion.Manual).reason
        assertTrue(unreachableReason.contains("GXNU-YC"))
        assertNotEquals(offlineReason, unreachableReason)
    }

    @Test fun closingThePageMidWatchCancelsThePollingWithNoLingeringCoroutine() = runTest {
        var probes = 0
        val monitor = monitorOf { probes++; false }
        val watch = monitor.start(backgroundScope)
        runCurrent()
        assertEquals(1, probes)

        monitor.stop()
        runCurrent()
        assertTrue(watch.isCancelled)

        advancePastTheDeadline()
        assertEquals(1, probes)
        assertEquals(PortalCompletion.Checking, monitor.state.value)
    }

    @Test fun aPageAbandonedWithoutACloseTapStopsWithItsOwnScope() = runTest {
        var probes = 0
        val monitor = monitorOf { probes++; false }
        val page = CoroutineScope(coroutineContext + Job())
        val watch = monitor.start(page)
        runCurrent()
        assertEquals(1, probes)

        // Leaving composition cancels the scope the screen started the watch in.
        page.cancel()
        runCurrent()
        assertTrue(watch.isCancelled)

        advancePastTheDeadline()
        assertEquals(1, probes)
    }

    @Test fun aProbeStillOnTheWireWhenThePageClosesPublishesNothing() = runTest {
        val answer = CompletableDeferred<Boolean>()
        var probes = 0
        val monitor = monitorOf { probes++; answer.await() }
        val watch = monitor.start(backgroundScope)
        runCurrent()
        assertEquals(1, probes)
        assertEquals(PortalCompletion.Checking, monitor.state.value)

        // The page is closed while its first probe is still waiting for the network.
        monitor.stop()
        runCurrent()
        assertTrue(watch.isCancelled)

        // The answer that arrives afterwards belongs to a page that is already gone.
        answer.complete(true)
        runCurrent()
        assertEquals(PortalCompletion.Checking, monitor.state.value)

        advancePastTheDeadline()
        assertEquals(1, probes)
        assertEquals(PortalCompletion.Checking, monitor.state.value)
    }

    @Test fun aVerifierThatIgnoresCancellationCannotPublishAfterThePageClosed() = runTest {
        // The watch cannot assume how its verifier treats cancellation, and this is the shape that
        // would otherwise let a torn-down page publish the success it can no longer show.
        val monitor = monitorOf { try { CompletableDeferred<Boolean>().await() } catch (_: CancellationException) { true } }
        val watch = monitor.start(backgroundScope)
        runCurrent()
        assertEquals(PortalCompletion.Checking, monitor.state.value)

        monitor.stop()
        runCurrent()
        assertTrue(watch.isCancelled)

        advancePastTheDeadline()
        assertEquals(PortalCompletion.Checking, monitor.state.value)
    }

    @Test fun successConfirmsItselfAndReturnsToTheAppWithoutATap() = runTest {
        val monitor = monitorOf { true }
        val watch = monitor.start(backgroundScope)
        var returned = false
        val page = launch {
            val settled = monitor.state.first { it !is PortalCompletion.Checking }
            val confirmation = PortalCompletionPolicy.autoReturnDelay(settled) ?: return@launch
            delay(confirmation)
            returned = true
        }
        runCurrent()

        assertEquals(PortalCompletion.Online, monitor.state.value)
        assertFalse("the confirmation has to be readable before the app returns", returned)
        advanceTimeBy(PortalCompletionPolicy.CONFIRMATION_MILLIS - 1)
        runCurrent()
        assertFalse(returned)
        advanceTimeBy(2)
        runCurrent()
        assertTrue(returned)

        watch.cancel()
        page.cancel()
    }

    @Test fun aProbeThatAnswersSuccessPastTheDeadlineStillReturnsTheUserToTheApp() = runTest {
        // The deadline bounds how long the page waits for an answer, not which real answer counts.
        // A probe the schedule already let out may come back online afterwards, and that is still a
        // success the user has to be returned for instead of being left on a page they cannot leave.
        val answer = CompletableDeferred<Boolean>()
        val monitor = monitorOf { answer.await() }
        val watch = monitor.start(backgroundScope)
        runCurrent()
        advancePastTheDeadline()
        assertEquals(PortalCompletion.Checking, monitor.state.value)

        answer.complete(true)
        runCurrent()

        assertEquals(PortalCompletion.Online, monitor.state.value)
        assertTrue(watch.isCompleted)
    }

    @Test fun aPageThatGaveUpIsNeverReturnedFromByItself() = runTest {
        val monitor = monitorOf { false }
        val watch = monitor.start(backgroundScope)
        var returned = false
        val page = launch {
            val settled = monitor.state.first { it !is PortalCompletion.Checking }
            returned = PortalCompletionPolicy.autoReturnDelay(settled) != null
        }
        advancePastTheDeadline()
        runCurrent()

        assertTrue(monitor.state.value is PortalCompletion.Manual)
        assertFalse(returned)

        watch.cancel()
        page.cancel()
    }

    @Test fun reloadingAfterTheDeadlineWatchesAgainWithAFreshDeadline() = runTest {
        var probes = 0
        var online = false
        val monitor = monitorOf { probes++; online }
        monitor.start(backgroundScope)
        advancePastTheDeadline()
        assertTrue(monitor.state.value is PortalCompletion.Manual)

        val spent = probes
        online = true
        monitor.start(backgroundScope)
        runCurrent()
        assertEquals(PortalCompletion.Online, monitor.state.value)
        assertEquals(spent + 1, probes)
    }

    @Test fun restartingTheWatchReplacesTheRunningOneInsteadOfDoublingProbes() = runTest {
        var probes = 0
        val monitor = monitorOf { probes++; false }
        monitor.start(backgroundScope)
        runCurrent()
        monitor.start(backgroundScope)
        runCurrent()
        assertEquals(2, probes)

        advanceTimeBy(PortalCompletionPolicy.POLL_INTERVAL_MILLIS + 1)
        runCurrent()
        // One probe per interval: a replaced watch must not keep its own schedule alive.
        assertEquals(3, probes)
    }

    /**
     * The watch is background work, which advanceUntilIdle leaves untouched, so the clock has to be
     * driven past the deadline here for the give-up path to be reached at all. One interval of
     * slack guarantees the last probe of the bounded schedule has had its turn.
     */
    private fun TestScope.advancePastTheDeadline() {
        advanceTimeBy(PortalCompletionPolicy.POLL_DEADLINE_MILLIS + PortalCompletionPolicy.POLL_INTERVAL_MILLIS)
        runCurrent()
    }

    private fun TestScope.monitorOf(verify: suspend () -> Boolean): PortalCompletionMonitor =
        PortalCompletionMonitor(verify = verify, now = { testScheduler.currentTime })
}
