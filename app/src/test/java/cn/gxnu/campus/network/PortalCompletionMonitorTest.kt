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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watch must reuse the verification the automatic attempt uses, must end on a real answer or on
 * its deadline, and must leave nothing running once the page is closed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PortalCompletionMonitorTest {

    /**
     * 用户报的故障，就钉在这里：手机还没登录，认证页自己就返回了。
     *
     * 这条测试原来断言 `Online` —— 而 `Online` 会让页面 1.5 秒后自动关掉。那是**错的**：第一次探测
     * 就是 ONLINE 只说明这张网「看起来能上网」（探测地址是强制门户检测用的，校园网墙园为了让手机
     * 弹出「需登录」而故意放行），**不说明本页发生过登录**。所以它必须停在 `AlreadyOnline`，
     * 页面保持打开，由用户自己决定。
     */
    @Test fun aPageOpenedWhileAlreadyOnlineStaysOpenInsteadOfClaimingALogin() = runTest {
        var probes = 0
        val monitor = monitorOf { probes++; true }
        monitor.start(backgroundScope)
        runCurrent()

        assertEquals(1, probes)
        assertEquals(PortalCompletion.AlreadyOnline, monitor.state.value)
        // 页面不许自己返回：这才是「我都检查不了、也点不了」的修复。
        assertNull(PortalCompletionPolicy.autoReturnDelay(monitor.state.value))
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
        assertTrue(unreachableReason.contains("校园 Wi-Fi"))
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

    /**
     * 反过来也要成立：页面开着的时候真的从「不通」变成「通」，就是这一页把登录做成了，
     * 该自动返回还是自动返回。修「首次即在线」不能顺手把正常路径也修坏。
     */
    @Test fun aWiFiThatComesOnlineWhileThePageIsOpenStillCountsAsALogin() = runTest {
        var probes = 0
        val monitor = monitorOf { probes++; probes >= 2 }
        monitor.start(backgroundScope)
        runCurrent()
        assertEquals(PortalCompletion.Checking, monitor.state.value)

        advanceTimeBy(PortalCompletionPolicy.POLL_INTERVAL_MILLIS + 1)
        runCurrent()

        assertEquals(2, probes)
        assertEquals(PortalCompletion.Online, monitor.state.value)
        assertEquals(
            PortalCompletionPolicy.CONFIRMATION_MILLIS,
            PortalCompletionPolicy.autoReturnDelay(monitor.state.value)
        )
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

    /**
     * 自动返回的机制本身。注意探测必须从「不通」变成「通」—— 首次即在线不再是登录成功
     * （见 [PortalCompletion.AlreadyOnline]），所以这条测试用一次真实转变来驱动它。
     */
    /**
     * 自动返回的机制本身：真实登录（探测从「不通」变成「通」）之后，页面会自己返回。
     *
     * 这里不去卡 1.5 秒窗口里某一毫秒的边界 —— 那是 `PortalCompletionPolicy.autoReturnDelay`
     * 的纯函数断言已经在钉的事，在协程调度器上用 `advanceTimeBy` 复现它只会变成对调度顺序的
     * 过度拟合。这条测试只管整条链走通：转变 → `Online` → 非空延迟 → 页面真的返回。
     */
    @Test fun successConfirmsItselfAndReturnsToTheAppWithoutATap() = runTest {
        var probes = 0
        val monitor = monitorOf { ++probes >= 2 }
        val watch = monitor.start(backgroundScope)
        var returned = false
        val page = launch {
            val settled = monitor.state.first { it !is PortalCompletion.Checking }
            val confirmation = PortalCompletionPolicy.autoReturnDelay(settled) ?: return@launch
            delay(confirmation)
            returned = true
        }
        // One full interval so the second probe answers online and the page settles.
        advanceTimeBy(PortalCompletionPolicy.POLL_INTERVAL_MILLIS + 1)
        runCurrent()

        assertEquals(PortalCompletion.Online, monitor.state.value)
        assertFalse("the confirmation has to be readable before the app returns", returned)

        advanceTimeBy(PortalCompletionPolicy.CONFIRMATION_MILLIS + 1)
        runCurrent()
        assertTrue("真实登录之后页面要自己返回", returned)

        watch.cancel()
        page.cancel()
    }

    @Test fun aProbeThatAnswersSuccessPastTheDeadlineStillReturnsTheUserToTheApp() = runTest {
        // The deadline bounds how long the page waits for an answer, not which real answer counts.
        // A probe the schedule already let out may come back online afterwards, and that is still a
        // success the user has to be returned for instead of being left on a page they cannot leave.
        // The first answer must be non-online so the later one reads as a real transition.
        val first = CompletableDeferred<Boolean>()
        val answer = CompletableDeferred<Boolean>()
        var probes = 0
        val monitor = monitorOf { if (++probes == 1) first.await() else answer.await() }
        val watch = monitor.start(backgroundScope)
        runCurrent()
        first.complete(false)
        runCurrent()
        advancePastTheDeadline()

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
        // 重新开始的一个 watch 会重新探一次；这一次是它的**首次**探测且立刻在线，
        // 所以按新语义只会得到 AlreadyOnline（页面不自动返回），而不是 Online。
        assertEquals(PortalCompletion.AlreadyOnline, monitor.state.value)
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
