package cn.gxnu.campus.network

import android.webkit.WebViewClient
import cn.gxnu.campus.core.PortalFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The embedded page is judged by what its own view and its pinned fetch report. The two questions
 * that decide what the screen shows are pinned here: which errors are the page failing, and whether
 * a load that finished is a page the user can actually use. A page that is only slow, and a page
 * that is never coming, arrive through the same callback and must not be confused.
 */
class PortalPageLoadPolicyTest {

    @Test
    fun aLoadThatFinishesWithNoFailureIsAReadyPage() {
        assertEquals(PortalPageState.Ready, PortalPageLoadPolicy.finished(PortalPageState.Loading(), null))
    }

    @Test
    fun aLoadThatFinishedAfterAFailureIsStillTheFailedPage() {
        // The view reports a completed load and a failed one through the same callback, so the
        // failure has to outlive the finish that follows it. Without this the screen would show an
        // empty document as a page that loaded.
        val failed = PortalPageLoadPolicy.failedPage(PortalFailure.UNREACHABLE)
        assertEquals(failed, PortalPageLoadPolicy.finished(PortalPageState.Loading(), PortalFailure.UNREACHABLE))
        assertEquals(failed, PortalPageLoadPolicy.finished(failed, null))
    }

    @Test
    fun aLateFinishCannotUndoAPageThatAlreadyArrived() {
        assertEquals(PortalPageState.Ready, PortalPageLoadPolicy.finished(PortalPageState.Ready, null))
    }

    @Test
    fun aFailedSubresourceNeverFailsThePage() {
        // A broken image, font or script leaves a page that still works on screen, and reporting it
        // as a broken page would hide a login form the user can still fill in.
        assertNull(PortalPageLoadPolicy.failureFor(isForMainFrame = false, errorCode = WebViewClient.ERROR_HOST_LOOKUP))
        assertNull(PortalPageLoadPolicy.failureFor(isForMainFrame = false, errorCode = WebViewClient.ERROR_CONNECT))
        assertEquals(
            PortalPageState.Ready,
            PortalPageLoadPolicy.finished(
                PortalPageState.Loading(),
                PortalPageLoadPolicy.failureFor(isForMainFrame = false, errorCode = WebViewClient.ERROR_TIMEOUT)
            )
        )
    }

    @Test
    fun aFailedMainFrameIsThePageFailing() {
        assertEquals(
            PortalFailure.UNREACHABLE,
            PortalPageLoadPolicy.failureFor(isForMainFrame = true, errorCode = WebViewClient.ERROR_HOST_LOOKUP)
        )
        assertTrue(
            PortalPageLoadPolicy.finished(
                PortalPageState.Loading(),
                PortalPageLoadPolicy.failureFor(isForMainFrame = true, errorCode = WebViewClient.ERROR_CONNECT)
            ) is PortalPageState.Failed
        )
    }

    @Test
    fun aFrameTheViewDoesNotIdentifyIsNotTreatedAsThePage() {
        // An unknown frame is not evidence about the page, and guessing would raise an error over a
        // page that is on screen and usable.
        assertNull(PortalPageLoadPolicy.failureFor(isForMainFrame = null, errorCode = WebViewClient.ERROR_UNKNOWN))
        assertNull(PortalPageLoadPolicy.failureFor(isForMainFrame = null, errorCode = null))
    }

    @Test
    fun aPageThatRanOutOfTimeIsNotReportedAsAnUnreachableOne() {
        assertEquals(
            PortalFailure.TIMEOUT,
            PortalPageLoadPolicy.failureFor(isForMainFrame = true, errorCode = WebViewClient.ERROR_TIMEOUT)
        )
    }

    @Test
    fun anUnsupportedOrInsecureLoadIsNotReportedAsAnUnreachableOne() {
        assertEquals(
            PortalFailure.UNSUPPORTED,
            PortalPageLoadPolicy.failureFor(isForMainFrame = true, errorCode = WebViewClient.ERROR_UNSUPPORTED_SCHEME)
        )
        assertEquals(
            PortalFailure.UNSUPPORTED,
            PortalPageLoadPolicy.failureFor(isForMainFrame = true, errorCode = WebViewClient.ERROR_FAILED_SSL_HANDSHAKE)
        )
    }

    @Test
    fun aClickedReloadStartsAFreshLoadWithNoProgressYet() {
        assertEquals(PortalPageState.Loading(), PortalPageLoadPolicy.started())
        assertEquals(PortalPageState.Loading(0), PortalPageLoadPolicy.started())
    }

    @Test
    fun progressOnlyRefinesARunningLoadAndStaysWithinItsRange() {
        assertEquals(PortalPageState.Loading(45), PortalPageLoadPolicy.progressed(45))
        assertEquals(PortalPageState.Loading(0), PortalPageLoadPolicy.progressed(-1))
        assertEquals(PortalPageState.Loading(100), PortalPageLoadPolicy.progressed(140))
    }

    @Test
    fun everyFailureToLoadHasItsOwnPlainReason() {
        val reasons = listOf(
            PortalFailure.TIMEOUT,
            PortalFailure.UNREACHABLE,
            PortalFailure.UNSUPPORTED,
            PortalFailure.INVALID_RESPONSE
        ).map(PortalPageLoadPolicy::reasonFor)

        assertEquals(reasons.distinct(), reasons)
        reasons.forEach { reason ->
            assertTrue(reason.isNotBlank())
            assertFalse("a failed page must never read as a success: $reason", reason.contains("成功"))
        }
        assertTrue(PortalPageLoadPolicy.reasonFor(PortalFailure.TIMEOUT).contains("超时"))
        assertTrue(PortalPageLoadPolicy.reasonFor(PortalFailure.UNREACHABLE).contains("校园 Wi-Fi"))
    }

    @Test
    fun aPageFailureNeverBlamesTheAccount() {
        // Nothing in a page load is an account verdict, and offering one would send the user to
        // re-check credentials that the school page never read.
        for (failure in PortalFailure.values()) {
            assertFalse(PortalPageLoadPolicy.reasonFor(failure).contains("账号"))
        }
    }
}
