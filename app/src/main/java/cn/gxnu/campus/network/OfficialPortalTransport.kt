package cn.gxnu.campus.network

import cn.gxnu.campus.core.CampusNetworkPolicy
import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.view.View
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import cn.gxnu.campus.core.ConnectionTransport
import cn.gxnu.campus.core.Credentials
import cn.gxnu.campus.core.NetworkSnapshot
import cn.gxnu.campus.core.PortalException
import cn.gxnu.campus.core.PortalFailure
import cn.gxnu.campus.core.Provider
import java.io.ByteArrayInputStream
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The school page owns the login request; this class only fills its form and checks this Wi-Fi. */
class OfficialPortalTransport(context: Context, private val wifi: WifiEnvironment) : ConnectionTransport {
    private val context = context.applicationContext
    private val internet = PortalClient(wifi)

    override suspend fun verifyInternet(network: NetworkSnapshot): Boolean = internet.verifyInternet(network)

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun authenticate(network: NetworkSnapshot, credentials: Credentials, provider: Provider): Boolean {
        CampusNetworkPolicy.refusal(network)?.let { throw PortalException(PortalFailure.UNREACHABLE, it) }
        val resources = PinnedPortalResources(network, TargetConnectionFactory { snapshot, url -> wifi.openConnection(snapshot, url) })
        val pageFailure = AtomicReference<PortalFailure?>(null)
        var view: WebView? = null
        var submitted = false
        var automation = ""
        try {
            val source = withContext(Dispatchers.IO) { context.assets.open("portal-bridge.js").bufferedReader(Charsets.UTF_8).use { it.readText() } }
            automation = OfficialPortalAutomation.script(source, credentials, provider)
            withContext(Dispatchers.Main.immediate) {
                WebView.setWebContentsDebuggingEnabled(false)
                WebStorage.getInstance().deleteAllData()
                ServiceWorkerGuard.apply()
                val browser = WebView(context)
                view = browser
                browser.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    mediaPlaybackRequiresUserGesture = true
                }
                // A headless WebView still needs a viewport for the original form's visibility checks.
                val width = context.resources.displayMetrics.widthPixels.coerceAtLeast(360)
                val height = context.resources.displayMetrics.heightPixels.coerceAtLeast(640)
                browser.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                browser.layout(0, 0, width, height)
                browser.onResume()
                browser.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(webView: WebView?, request: WebResourceRequest?): WebResourceResponse {
                        val reply = if (request == null) resources.load("", "GET") else resources.load(
                            request.url.toString(), request.method, request.requestHeaders, request.isForMainFrame
                        )
                        if (request?.isForMainFrame == true && reply.failure != null) pageFailure.compareAndSet(null, reply.failure)
                        return WebResourceResponse(reply.mimeType, reply.encoding, reply.status, reply.reason, reply.headers, ByteArrayInputStream(reply.body))
                    }

                    override fun shouldOverrideUrlLoading(webView: WebView?, request: WebResourceRequest?): Boolean {
                        val target = request?.url?.toString().orEmpty()
                        val current = webView?.url
                        val originMatches = current == null || !PinnedPortalResources.permits(current) || try {
                            PinnedPortalResources.sameOrigin(URL(current), URL(target))
                        } catch (_: Exception) { false }
                        val blocked = request?.method != "GET" || !PinnedPortalResources.permits(target) || !originMatches
                        if (blocked && request?.isForMainFrame == true) pageFailure.compareAndSet(null, PortalFailure.UNSUPPORTED)
                        return blocked
                    }

                    override fun onReceivedSslError(webView: WebView?, handler: SslErrorHandler?, error: SslError?) {
                        handler?.cancel()
                        pageFailure.compareAndSet(null, PortalFailure.UNREACHABLE)
                    }

                    override fun onReceivedError(webView: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        if (request?.isForMainFrame == true) pageFailure.compareAndSet(null, PortalFailure.UNREACHABLE)
                    }
                }
                browser.loadUrl(PortalClient.SCHOOL_ENTRY)
            }

            val online = withTimeoutOrNull(25_000) {
                coroutineScope {
                    // Existing online pages can legitimately expose no login form. Only one verifier runs,
                    // during loading and after submission, always on this same Wi-Fi.
                    val verification: Deferred<Boolean> = async {
                        while (currentCoroutineContext().isActive) {
                            val reachable = try { internet.verifyInternet(network) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: PortalException) { false }
                            if (reachable) return@async true
                            delay(400)
                        }
                        false
                    }
                    while (currentCoroutineContext().isActive) {
                        if (verification.isCompleted && verification.await()) return@coroutineScope true
                        if (!submitted) pageFailure.get()?.let { throw pageException(it) }
                        // Once submitted, subsequent documents may report a rejection but must not click again.
                        val script = if (submitted) "window.__campusOfficialSubmitted=true;\n$automation" else automation
                        val raw = withContext(Dispatchers.Main.immediate) { evaluate(view ?: throw pageException(PortalFailure.UNREACHABLE), script) }
                        val decision = OfficialPortalAutomation.decision(raw)
                        when (decision.stage) {
                            // The headless attempt only cares that the form is ready to submit; a form
                            // it has just filled keeps it polling, exactly like one that is not up yet.
                            BridgeStage.WAITING, BridgeStage.FILLED -> Unit
                            BridgeStage.SUBMITTED -> submitted = true
                            BridgeStage.REJECTED -> throw when (decision.reason) {
                                "ACCOUNT" -> PortalException(PortalFailure.ACCOUNT, "学校未通过认证，请检查账号、密码和供应商。")
                                "CAPTCHA" -> PortalException(PortalFailure.UNSUPPORTED, "学校要求验证码，请打开学校原网页完成认证。")
                                else -> PortalException(PortalFailure.INVALID_RESPONSE, "学校网页未完成认证，请重试或打开学校原网页。")
                            }
                            BridgeStage.MANUAL -> throw when (decision.reason) {
                                "CAPTCHA" -> PortalException(PortalFailure.UNSUPPORTED, "学校要求验证码，请打开学校原网页完成认证。")
                                // 运营商是用户自己选的，学校页面上没有这一项时必须让他去改，
                                // 而不是含糊地说「需要手动操作」—— 那样他不知道该动哪里。
                                "PROVIDER" -> PortalException(PortalFailure.UNSUPPORTED, "学校认证页面上没有你所选的运营商，请回到「校园网」改选运营商后重试。")
                                else -> PortalException(PortalFailure.UNSUPPORTED, "学校认证页面需要手动操作，请打开学校原网页。")
                            }
                        }
                        delay(350)
                    }
                    false
                }
            }
            if (online == true) return true
            throw if (submitted) PortalException(PortalFailure.VERIFICATION, "学校网页已提交认证，但校园 Wi-Fi 仍无法访问外网，请重试。")
                else PortalException(PortalFailure.TIMEOUT, "学校认证页面加载超时，请重试或打开学校原网页。")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: PortalException) {
            throw failure
        } catch (_: Exception) {
            throw pageException(PortalFailure.UNREACHABLE)
        } finally {
            automation = ""
            withContext(NonCancellable) {
                val closing = launch(Dispatchers.IO) { resources.close() }
                withContext(Dispatchers.Main.immediate) {
                    view?.let { browser ->
                        try { browser.stopLoading() } catch (_: Exception) { }
                        try { browser.onPause() } catch (_: Exception) { }
                        try { browser.clearHistory() } catch (_: Exception) { }
                        try { browser.clearCache(true) } catch (_: Exception) { }
                        try { browser.destroy() } catch (_: Exception) { }
                    }
                    view = null
                    try { WebStorage.getInstance().deleteAllData() } catch (_: Exception) { }
                }
                closing.join()
            }
        }
    }

    private suspend fun evaluate(view: WebView, script: String): String? = suspendCancellableCoroutine { continuation ->
        view.evaluateJavascript(script) { value -> if (continuation.isActive) continuation.resume(value) }
    }

    private fun pageException(reason: PortalFailure): PortalException = when (reason) {
        PortalFailure.TIMEOUT -> PortalException(reason, "学校认证页面请求超时，请重试。")
        PortalFailure.UNSUPPORTED -> PortalException(reason, "学校认证页面要求其他操作，请打开学校原网页。")
        PortalFailure.INVALID_RESPONSE -> PortalException(reason, "学校认证页面响应异常，请重试或打开学校原网页。")
        else -> PortalException(PortalFailure.UNREACHABLE, "学校认证页面暂时无法访问，请检查校园 Wi-Fi 后重试。")
    }
}
