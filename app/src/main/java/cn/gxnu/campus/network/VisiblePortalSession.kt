package cn.gxnu.campus.network

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import cn.gxnu.campus.core.Credentials
import cn.gxnu.campus.core.NetworkSnapshot
import cn.gxnu.campus.core.PortalFailure
import cn.gxnu.campus.core.Provider
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** What the visible page needs to keep its cookies and its requests on the same campus Wi-Fi. */
data class VisiblePortalTarget(val network: NetworkSnapshot, val credentials: Credentials, val provider: Provider)

/**
 * The embedded official page. It shares PinnedPortalResources and the form bridge with the
 * automatic attempt, so a manual login stays on the target Wi-Fi and never reaches the
 * default network, an external browser, or a different host.
 */
class VisiblePortalSession(context: Context, private val wifi: WifiEnvironment, private val target: VisiblePortalTarget) {
    private val context = context.applicationContext
    private val resources = PinnedPortalResources(target.network, TargetConnectionFactory { snapshot, url -> wifi.openConnection(snapshot, url) })
    private val internet = PortalClient(wifi)
    private var browser: WebView? = null

    /**
     * What the page itself is doing. It lives here rather than in the screen because the main frame
     * is served from [PinnedPortalResources]: a school page that never arrived reaches the view as
     * a valid but empty 502 document, which the view reports as a finished page. Only the reply and
     * the load callbacks together can tell a slow page from one that is not coming.
     */
    private val mutablePage = MutableStateFlow<PortalPageState>(PortalPageState.Loading())
    internal val page: StateFlow<PortalPageState> = mutablePage.asStateFlow()

    /**
     * The first failure of the current load. It is held until that load finishes because
     * `onPageFinished` reports a failed load and a good one through the same callback, so the
     * callback order must not decide it. A load this screen starts clears it.
     */
    private val pendingFailure = AtomicReference<PortalFailure?>(null)

    /**
     * The completion watch the screen drives. The school page reporting success proves nothing on
     * its own, so the page is only finished once the same HTTPS 204 probe the automatic attempt
     * uses answers for this exact Wi-Fi.
     */
    internal val completion = PortalCompletionMonitor(verify = { internet.verifyInternet(target.network) })
    private val automation: String by lazy {
        val source = context.assets.open("portal-bridge.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
        OfficialPortalAutomation.script(source, target.credentials, target.provider)
    }

    /** Builds the view on the calling (main) thread. The caller attaches it to the Compose host. */
    @SuppressLint("SetJavaScriptEnabled")
    fun createView(): WebView {
        browser?.let { return it }
        WebStorage.getInstance().deleteAllData()
        ServiceWorkerGuard.apply()
        val view = WebView(context)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(webView: WebView?, request: WebResourceRequest?): WebResourceResponse {
                val reply = if (request == null) resources.load("", "GET") else resources.load(
                    request.url.toString(), request.method, request.requestHeaders, request.isForMainFrame
                )
                // The main frame is fetched from here, so a dead school page is handed to the view
                // as a well-formed but empty 502 document and never as a load error. This reply is
                // the only place that knows the page did not arrive.
                if (request?.isForMainFrame == true) reply.failure?.let(::notePageFailure)
                return WebResourceResponse(reply.mimeType, reply.encoding, reply.status, reply.reason, reply.headers, ByteArrayInputStream(reply.body))
            }

            override fun shouldOverrideUrlLoading(webView: WebView?, request: WebResourceRequest?): Boolean {
                val targetUrl = request?.url?.toString().orEmpty()
                val current = webView?.url
                val originMatches = current == null || !PinnedPortalResources.permits(current) || try {
                    PinnedPortalResources.sameOrigin(java.net.URL(current), java.net.URL(targetUrl))
                } catch (_: Exception) { false }
                // Off-site links, logoff pages and non-GET navigations stay inside no browser. A
                // blocked navigation is not a page failure here: the page the user is on stays
                // usable, so this screen says nothing about it.
                return request?.method != "GET" || !PinnedPortalResources.permits(targetUrl) || !originMatches
            }

            override fun onPageStarted(webView: WebView?, url: String?, favicon: Bitmap?) {
                mutablePage.value = PortalPageLoadPolicy.started()
            }

            override fun onPageFinished(webView: WebView?, url: String?) {
                mutablePage.value = PortalPageLoadPolicy.finished(mutablePage.value, pendingFailure.getAndSet(null))
            }

            override fun onReceivedSslError(webView: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.cancel()
                // Cancelling alone leaves a load that will never finish, so a refused certificate is
                // reported as the page failure it is instead of as a page that is still coming.
                notePageFailure(PortalFailure.UNREACHABLE)
            }

            override fun onReceivedError(webView: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                // A failed image, font or script leaves the page standing; only the main frame is
                // the page, so only the main frame may fail it.
                PortalPageLoadPolicy.failureFor(request?.isForMainFrame, error?.errorCode)?.let(::notePageFailure)
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(webView: WebView?, newProgress: Int) {
                // Progress only ever refines a load that is still running; it cannot revive a page
                // that already finished or failed.
                if (mutablePage.value is PortalPageState.Loading) {
                    mutablePage.value = PortalPageLoadPolicy.progressed(newProgress)
                }
            }

            // The page kept its own script behaviour while no chrome client rendered dialogs for
            // it, and these keep it exactly as inert now that one can.
            override fun onJsAlert(webView: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                result?.cancel()
                return true
            }

            override fun onJsConfirm(webView: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                result?.cancel()
                return true
            }

            override fun onJsPrompt(webView: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
                result?.cancel()
                return true
            }
        }
        browser = view
        beginLoad()
        view.loadUrl(PortalClient.SCHOOL_ENTRY)
        return view
    }

    /** A load started from this screen: a failure that belonged to the previous page is spent. */
    private fun beginLoad() {
        pendingFailure.set(null)
        mutablePage.value = PortalPageLoadPolicy.started()
    }

    /**
     * Records the first failure of the current load and shows it at once. The screen has to be able
     * to answer "did this page arrive" without waiting for a callback that a failed load may never
     * send, and the latch is what keeps `onPageFinished` from calling that page usable.
     */
    private fun notePageFailure(failure: PortalFailure) {
        if (pendingFailure.compareAndSet(null, failure)) {
            mutablePage.value = PortalPageLoadPolicy.failedPage(failure)
        }
    }

    /** Fills the school's form and clicks its original login button once. False while the page is not ready. */
    suspend fun autoFill(): Boolean {
        val view = browser ?: return false
        val raw = suspendCancellableCoroutine<String?> { continuation ->
            view.evaluateJavascript(automation) { value -> if (continuation.isActive) continuation.resume(value) }
        }
        return OfficialPortalAutomation.decision(raw).stage == BridgeStage.SUBMITTED
    }

    fun reload() {
        val view = browser ?: return
        // A retry loads the page again, so the failure of the page it replaces no longer applies.
        beginLoad()
        view.loadUrl(PortalClient.SCHOOL_ENTRY)
    }

    fun destroy() {
        // Stop watching before the early return: a page closed before its view was built still
        // has a watch to end, and no probe may outlive the WebView it was started for.
        completion.stop()
        val view = browser ?: return
        browser = null
        try { view.stopLoading() } catch (_: Exception) { }
        try { view.onPause() } catch (_: Exception) { }
        try { view.clearHistory() } catch (_: Exception) { }
        try { view.clearCache(true) } catch (_: Exception) { }
        try { view.destroy() } catch (_: Exception) { }
        resources.close()
        try { WebStorage.getInstance().deleteAllData() } catch (_: Exception) { }
    }
}

/** What the embedded page is doing, as its own view and its pinned fetch report it. */
internal sealed interface PortalPageState {
    /** A load is in flight. [progress] is the view's own 0-100 report, 0 before it has one. */
    data class Loading(val progress: Int = 0) : PortalPageState
    /** The load finished with a document on screen. */
    data object Ready : PortalPageState
    /** The load produced no usable page, and [reason] is what the user is told about it. */
    data class Failed(val reason: String) : PortalPageState
}

/**
 * The embedded page's own state machine. It is kept pure so the one distinction this screen rests
 * on - a page that failed against a page that is still coming - is settled by a test rather than by
 * a WebView, which reports both through the same `onPageFinished` callback.
 */
internal object PortalPageLoadPolicy {

    fun started(): PortalPageState = PortalPageState.Loading()

    /** Progress can only refine a load that is still running, and never leaves the 0-100 range. */
    fun progressed(progress: Int): PortalPageState = PortalPageState.Loading(progress.coerceIn(0, 100))

    fun failedPage(failure: PortalFailure): PortalPageState = PortalPageState.Failed(reasonFor(failure))

    /**
     * What the page is once a load finishes. The failure is consulted first: the view reports a
     * failed load and a good one through the same callback, so only a load that recorded no failure
     * is a page the user can use, and a finished load that had already failed stays failed.
     */
    fun finished(current: PortalPageState, failure: PortalFailure?): PortalPageState = when {
        failure != null -> failedPage(failure)
        current is PortalPageState.Loading -> PortalPageState.Ready
        else -> current
    }

    /**
     * Whether a view-level error is the page failing. Only the main frame is the page: a failed
     * image, font or script leaves a page that still works on screen, so a subresource error must
     * never be reported as a broken page.
     */
    fun failureFor(isForMainFrame: Boolean?, errorCode: Int?): PortalFailure? =
        if (isForMainFrame != true) null else when (errorCode) {
            WebViewClient.ERROR_TIMEOUT -> PortalFailure.TIMEOUT
            WebViewClient.ERROR_UNSUPPORTED_SCHEME,
            WebViewClient.ERROR_FAILED_SSL_HANDSHAKE,
            WebViewClient.ERROR_REDIRECT_LOOP -> PortalFailure.UNSUPPORTED
            else -> PortalFailure.UNREACHABLE
        }

    /** Plain words for a page that did not arrive, saying what to check and what to do next. */
    fun reasonFor(failure: PortalFailure): String = when (failure) {
        PortalFailure.TIMEOUT -> "学校认证页响应超时，请确认仍连接校园 Wi-Fi 后重试。"
        PortalFailure.UNSUPPORTED -> "学校认证页跳转到了不允许的地址，请重新加载重试。"
        PortalFailure.INVALID_RESPONSE -> "学校认证页返回的内容无法识别，请重新加载重试。"
        else -> "无法连接学校认证页，请确认已连接校园 Wi-Fi 后重试。"
    }
}

/** The school page loads no workers; a retained worker could otherwise bypass request interception. */
internal object ServiceWorkerGuard {
    fun apply() {
        try {
            android.webkit.ServiceWorkerController.getInstance().serviceWorkerWebSettings.apply {
                blockNetworkLoads = true
                allowContentAccess = false
                allowFileAccess = false
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
        } catch (_: Exception) { }
    }
}

/** One result of the same-Wi-Fi verification. A probe that never reached the network is no answer. */
internal enum class PortalProbe { ONLINE, OFFLINE, UNREACHABLE }

/** What the visible page shows while the watch runs and after it settles. */
internal sealed interface PortalCompletion {
    data object Checking : PortalCompletion
    data object Online : PortalCompletion
    data class Manual(val reason: String) : PortalCompletion
}

/** The next thing the watch does: probe after a delay, or settle on a state the page renders. */
internal sealed interface PortalDecision {
    data class Probe(val delayMillis: Long) : PortalDecision
    data class Settle(val completion: PortalCompletion) : PortalDecision
}

/**
 * When the embedded page is finished. A page that displays online templates, hides its login
 * button or says it succeeded is not evidence, so the only accepted success signal here is the
 * same HTTPS 204 probe the automatic attempt uses. The decision is a pure function of the time
 * spent watching and the last answer, which keeps the schedule and the give-up reason testable.
 */
internal object PortalCompletionPolicy {
    /** The probe costs a round trip, so polling faster would only add traffic, not speed. */
    const val POLL_INTERVAL_MILLIS = 2_000L
    /** A user may still have to type a captcha by hand; a minute is long enough to do that. */
    const val POLL_DEADLINE_MILLIS = 60_000L
    /** Long enough to read the confirmation, short enough that nobody taps around it. */
    const val CONFIRMATION_MILLIS = 1_500L

    fun next(elapsedMillis: Long, probe: PortalProbe?): PortalDecision = when {
        probe == PortalProbe.ONLINE -> PortalDecision.Settle(PortalCompletion.Online)
        // The page can be opened while the Wi-Fi is already online, so the first probe is now.
        probe == null -> PortalDecision.Probe(0L)
        // Another probe needs a whole interval inside the deadline, so none lands past it.
        POLL_DEADLINE_MILLIS - elapsedMillis <= POLL_INTERVAL_MILLIS ->
            PortalDecision.Settle(PortalCompletion.Manual(fallbackReason(probe)))
        else -> PortalDecision.Probe(POLL_INTERVAL_MILLIS)
    }

    /** How long the success state stands before the app returns by itself; null keeps the page open. */
    fun autoReturnDelay(completion: PortalCompletion): Long? =
        if (completion == PortalCompletion.Online) CONFIRMATION_MILLIS else null

    private fun fallbackReason(probe: PortalProbe): String = when (probe) {
        // The probe could not reach this Wi-Fi at all, so the page may still be logged in by hand.
        PortalProbe.UNREACHABLE -> "校园 Wi-Fi 检测暂时不可用，请确认仍连接校园 Wi-Fi，可在此页面手动登录。"
        else -> "仍未检测到外网，可在此页面手动登录，完成后返回首页。"
    }
}

/**
 * Runs [PortalCompletionPolicy] against the visible page's own verifier. The caller owns the
 * scope, so closing the page cancels the polling with it and nothing keeps probing afterwards.
 */
internal class PortalCompletionMonitor(
    private val verify: suspend () -> Boolean,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private val mutableState = MutableStateFlow<PortalCompletion>(PortalCompletion.Checking)
    val state: StateFlow<PortalCompletion> = mutableState.asStateFlow()
    private var watch: Job? = null

    /** Watches from now; reloading the page restarts the deadline instead of reusing an old one. */
    fun start(scope: CoroutineScope): Job {
        watch?.cancel()
        mutableState.value = PortalCompletion.Checking
        return scope.launch {
            val startedAt = now()
            var probe: PortalProbe? = null
            while (true) {
                when (val decision = PortalCompletionPolicy.next(now() - startedAt, probe)) {
                    is PortalDecision.Probe -> {
                        if (decision.delayMillis > 0) delay(decision.delayMillis)
                        probe = probeOnce()
                    }
                    is PortalDecision.Settle -> {
                        // stop() can land while a probe is still unwinding, and nothing here may
                        // assume how the caller's verifier treats that cancellation. A closed page
                        // never publishes: the watch settles only while it is still the live watch.
                        if (!isActive) return@launch
                        mutableState.value = decision.completion
                        return@launch
                    }
                }
            }
        }.also { watch = it }
    }

    fun stop() {
        watch?.cancel()
        watch = null
    }

    private suspend fun probeOnce(): PortalProbe = try {
        if (verify()) PortalProbe.ONLINE else PortalProbe.OFFLINE
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // A failure to even reach the probe leaves the page usable; it is reported as such.
        PortalProbe.UNREACHABLE
    }
}
