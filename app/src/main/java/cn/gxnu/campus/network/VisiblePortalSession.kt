package cn.gxnu.campus.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import cn.gxnu.campus.core.Credentials
import cn.gxnu.campus.core.NetworkSnapshot
import cn.gxnu.campus.core.Provider
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
                return WebResourceResponse(reply.mimeType, reply.encoding, reply.status, reply.reason, reply.headers, ByteArrayInputStream(reply.body))
            }

            override fun shouldOverrideUrlLoading(webView: WebView?, request: WebResourceRequest?): Boolean {
                val targetUrl = request?.url?.toString().orEmpty()
                val current = webView?.url
                val originMatches = current == null || !PinnedPortalResources.permits(current) || try {
                    PinnedPortalResources.sameOrigin(java.net.URL(current), java.net.URL(targetUrl))
                } catch (_: Exception) { false }
                // Off-site links, logoff pages and non-GET navigations stay inside no browser.
                return request?.method != "GET" || !PinnedPortalResources.permits(targetUrl) || !originMatches
            }

            override fun onReceivedSslError(webView: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.cancel()
            }

            override fun onReceivedError(webView: WebView?, request: WebResourceRequest?, error: WebResourceError?) = Unit
        }
        browser = view
        view.loadUrl(PortalClient.SCHOOL_ENTRY)
        return view
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
        browser?.loadUrl(PortalClient.SCHOOL_ENTRY)
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
        PortalProbe.UNREACHABLE -> "校园 Wi-Fi 检测暂时不可用，请确认仍连接 GXNU-YC，可在此页面手动登录。"
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
