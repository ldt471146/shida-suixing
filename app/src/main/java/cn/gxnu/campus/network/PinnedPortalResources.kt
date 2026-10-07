package cn.gxnu.campus.network

import cn.gxnu.campus.core.CampusNetworkPolicy
import cn.gxnu.campus.core.NetworkSnapshot
import cn.gxnu.campus.core.PortalFailure
import com.google.gson.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.net.HttpCookie
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection

internal data class PinnedPortalReply(
    val status: Int,
    val reason: String,
    val mimeType: String,
    val encoding: String?,
    val headers: Map<String, String>,
    val body: ByteArray,
    val failure: PortalFailure? = null
) {
    override fun toString() = "PinnedPortalReply(status=$status)"
}

internal class PinnedPortalResources(
    private val network: NetworkSnapshot,
    private val connections: TargetConnectionFactory
) {
    private val closed = AtomicBoolean(false)
    private val active = ConcurrentHashMap.newKeySet<HttpsURLConnection>()
    private val receivedBytes = AtomicLong(0)
    private val cookies = linkedMapOf<String, HttpCookie>()

    /** Called from WebView's interception thread; never falls back to the default network. */
    fun load(url: String, method: String = "GET", headers: Map<String, String> = emptyMap(), mainFrame: Boolean = false): PinnedPortalReply {
        // The name is deliberately not part of this check: the interceptor exists to keep requests on
        // the network we are authenticating on, and CampusNetworkPolicy is the one place that decides
        // which networks those are.
        if (method != "GET" || !CampusNetworkPolicy.allows(network) || !permits(url)) return denied()
        if (closed.get()) return failed(PortalFailure.UNREACHABLE)
        var current = URL(url)
        try {
            repeat(5) { hop ->
                if (closed.get()) return failed(PortalFailure.UNREACHABLE)
                val result = request(current, headers, mainFrame)
                if (result.status in listOf(301, 302, 303, 307, 308)) {
                    if (hop == 4) return failed(PortalFailure.INVALID_RESPONSE)
                    val location = result.headers.entries.firstOrNull { it.key.equals("Location", true) }?.value ?: return denied()
                    val next = URL(current, location)
                    if (!permits(next.toExternalForm()) || !sameOrigin(current, next) || sensitiveQuery(next)) return denied()
                    current = next
                } else {
                    // WebResourceResponse rejects every 3xx, including cache-only 304.
                    if (result.status in 300..399) return failed(PortalFailure.INVALID_RESPONSE)
                    if (mainFrame && current.toExternalForm() != url && result.status == 200) return redirectDocument(current)
                    return result.copy(headers = result.headers.filterKeys { !it.equals("Location", true) })
                }
            }
        } catch (_: SocketTimeoutException) {
            return failed(PortalFailure.TIMEOUT)
        } catch (_: Exception) {
            return failed(PortalFailure.UNREACHABLE)
        }
        return failed(PortalFailure.INVALID_RESPONSE)
    }

    private fun request(url: URL, headers: Map<String, String>, mainFrame: Boolean): PinnedPortalReply {
        val connection = connections.open(network, url)
        active += connection
        try {
            if (closed.get()) return failed(PortalFailure.UNREACHABLE)
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.useCaches = false
            connection.setRequestProperty("Cache-Control", "no-cache, no-store")
            connection.setRequestProperty("Accept-Encoding", "gzip")
            for (name in listOf("User-Agent", "Accept", "Accept-Language")) {
                headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.takeIf(::safeHeaderValue)?.let {
                    connection.setRequestProperty(name, it)
                }
            }
            val suppliedReferer = headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value
            connection.setRequestProperty("Referer", safeReferer(suppliedReferer))
            synchronized(cookies) {
                cookies.entries.removeAll { it.value.hasExpired() }
                val path = url.path.ifEmpty { "/" }
                val matching = cookies.values.filter { pathMatches(path, it.path ?: "/") }
                if (matching.isNotEmpty()) connection.setRequestProperty("Cookie", matching.joinToString("; ") { "${it.name}=${it.value}" })
            }
            val status = connection.responseCode
            if (closed.get()) return failed(PortalFailure.UNREACHABLE)
            receiveCookies(connection, url)
            val responseHeaders = linkedMapOf<String, String>()
            connection.headerFields?.forEach { (name, values) ->
                if (name != null && values != null && !name.equals("Set-Cookie", true) &&
                    !name.equals("Content-Length", true) && !name.equals("Content-Encoding", true) &&
                    !name.equals("Transfer-Encoding", true) && !name.equals("Connection", true)) {
                    values.firstOrNull()?.takeIf(::safeHeaderValue)?.let { responseHeaders[name] = it }
                }
            }
            responseHeaders.keys.removeAll { it.equals("Cache-Control", true) }
            responseHeaders["Cache-Control"] = "no-store"
            if (status in 300..399) return PinnedPortalReply(status, "Redirect", "text/plain", "UTF-8", responseHeaders, ByteArray(0))
            if (status !in 200..299) return PinnedPortalReply(status.takeIf { it in 400..599 } ?: 502, "School Response", "text/plain", "UTF-8", mapOf("Cache-Control" to "no-store"), ByteArray(0), PortalFailure.UNREACHABLE)

            val contentType = connection.getHeaderField("Content-Type").orEmpty()
            val mimeType = contentType.substringBefore(';').trim().lowercase(Locale.ROOT)
                .takeIf { it.matches(Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")) } ?: inferredMime(url, mainFrame)
            val declared = Regex("charset\\s*=\\s*['\"]?([^;\\s'\"]+)", RegexOption.IGNORE_CASE)
                .find(contentType)?.groupValues?.get(1)
            val encoding = declared?.let { if (Charset.isSupported(it)) Charset.forName(it).name() else return failed(PortalFailure.INVALID_RESPONSE) }
                ?: defaultEncoding(url, mimeType)
            val contentEncoding = connection.getHeaderField("Content-Encoding")?.trim().orEmpty()
            if (contentEncoding.isNotEmpty() && !contentEncoding.equals("gzip", true) && !contentEncoding.equals("identity", true)) return failed(PortalFailure.INVALID_RESPONSE)
            val stream = if (contentEncoding.equals("gzip", true)) GZIPInputStream(connection.inputStream) else connection.inputStream
            val output = ByteArrayOutputStream()
            stream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    if (closed.get()) return failed(PortalFailure.UNREACHABLE)
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_BODY_BYTES || receivedBytes.addAndGet(count.toLong()) > MAX_ATTEMPT_BYTES) return failed(PortalFailure.INVALID_RESPONSE)
                    output.write(buffer, 0, count)
                }
            }
            return PinnedPortalReply(status, "OK", mimeType, encoding, responseHeaders, output.toByteArray())
        } finally {
            active.remove(connection)
            try { connection.disconnect() } catch (_: Exception) { }
        }
    }

    private fun receiveCookies(connection: HttpsURLConnection, url: URL) {
        connection.headerFields?.entries?.filter { it.key?.equals("Set-Cookie", true) == true }?.forEach { (_, values) ->
            values?.forEach { line ->
                val parsed = try { HttpCookie.parse(line) } catch (_: IllegalArgumentException) { emptyList() }
                synchronized(cookies) {
                    for (cookie in parsed) {
                        val domain = cookie.domain?.removePrefix(".")
                        if (domain != null && !domain.equals(SCHOOL_HOST, true)) continue
                        if (!safeHeaderValue(cookie.name) || !safeHeaderValue(cookie.value)) continue
                        if (cookie.path.isNullOrEmpty()) cookie.path = url.path.substringBeforeLast('/', "").ifEmpty { "/" }
                        val key = cookie.name + "\u0000" + cookie.path
                        if (cookie.hasExpired()) cookies.remove(key) else cookies[key] = cookie
                    }
                }
            }
        }
    }

    /** Run on IO while the WebView is stopped and destroyed on Main. */
    fun close() {
        closed.set(true)
        synchronized(cookies) { cookies.clear() }
        active.toList().forEach { connection -> try { connection.disconnect() } catch (_: Exception) { } }
        active.clear()
    }

    private fun redirectDocument(url: URL): PinnedPortalReply {
        val literal = JsonPrimitive(url.toExternalForm()).toString().replace("<", "\\u003C").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        val html = "<!doctype html><meta charset=\"utf-8\"><script>location.replace($literal);</script>"
        return PinnedPortalReply(200, "OK", "text/html", "UTF-8", mapOf("Cache-Control" to "no-store"), html.toByteArray(Charsets.UTF_8))
    }

    private fun denied() = PinnedPortalReply(403, "Blocked", "text/plain", "UTF-8", mapOf("Cache-Control" to "no-store"), ByteArray(0), PortalFailure.UNSUPPORTED)
    private fun failed(reason: PortalFailure) = PinnedPortalReply(502, "Unavailable", "text/plain", "UTF-8", mapOf("Cache-Control" to "no-store"), ByteArray(0), reason)

    companion object {
        private const val SCHOOL_HOST = "yc.gxnu.edu.cn"
        private const val MAX_BODY_BYTES = 4 * 1024 * 1024
        private const val MAX_ATTEMPT_BYTES = 16L * 1024 * 1024

        fun permits(url: String): Boolean {
            val parsed = try { URL(url) } catch (_: Exception) { return false }
            val path = try { URLDecoder.decode(parsed.path, "UTF-8").lowercase(Locale.ROOT) } catch (_: Exception) { return false }
            return parsed.protocol == "https" && parsed.host.equals(SCHOOL_HOST, true) &&
                parsed.port in listOf(-1, 443, 802) && parsed.userInfo == null &&
                !path.contains("logout") && !path.contains("logoff")
        }

        fun sameOrigin(first: URL, second: URL): Boolean = first.protocol == second.protocol &&
            first.host.equals(second.host, true) && first.port.let { if (it < 0) first.defaultPort else it } ==
            second.port.let { if (it < 0) second.defaultPort else it }

        private fun sensitiveQuery(url: URL): Boolean = url.query.orEmpty().split('&').any {
            val key = try { URLDecoder.decode(it.substringBefore('='), "UTF-8") } catch (_: Exception) { return@any true }
            key.lowercase(Locale.ROOT) in listOf("user_account", "user_password", "upass", "ddddd")
        }

        private fun safeHeaderValue(value: String): Boolean = value.none { it == '\r' || it == '\n' || it == '\u0000' }

        private fun safeReferer(value: String?): String {
            if (value == null || !permits(value)) return PortalClient.SCHOOL_ENTRY
            val original = URL(value)
            return URL(original.protocol, original.host, original.port, original.path.ifEmpty { "/" }).toExternalForm()
        }

        private fun pathMatches(path: String, cookiePath: String): Boolean = path == cookiePath ||
            (path.startsWith(cookiePath) && (cookiePath.endsWith('/') || path.getOrNull(cookiePath.length) == '/'))

        private fun inferredMime(url: URL, mainFrame: Boolean): String = when {
            mainFrame -> "text/html"
            url.path.endsWith(".js", true) -> "application/javascript"
            url.path.endsWith(".css", true) -> "text/css"
            url.path.startsWith("/drcom/") || url.path.startsWith("/eportal/portal/") -> "application/javascript"
            else -> "application/octet-stream"
        }

        private fun defaultEncoding(url: URL, mime: String): String? {
            if (url.path.startsWith("/drcom/") || url.path.startsWith("/eportal/portal/")) return "UTF-8"
            // School HTML and its static JS were verified as legacy GB18030 bytes.
            return if (mime.startsWith("text/") || mime.contains("javascript")) "GB18030" else null
        }
    }
}
