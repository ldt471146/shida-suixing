package cn.gxnu.campus.network

import cn.gxnu.campus.core.NetworkSnapshot
import cn.gxnu.campus.core.PortalFailure
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.nio.charset.Charset
import java.security.Principal
import java.security.cert.Certificate
import java.util.zip.GZIPOutputStream
import javax.net.ssl.HttpsURLConnection
import org.junit.Assert.*
import org.junit.Test

class PinnedPortalResourcesTest {
    private val campus = NetworkSnapshot("pinned-fixture-42", "GXNU-YC", true)

    private class Reply(url: URL, private val status: Int = 200, private val body: ByteArray = "fixture".toByteArray(), private val headers: Map<String, List<String>> = emptyMap()) : HttpsURLConnection(url) {
        var disconnected = false
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun getHeaderField(name: String?) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
        override fun getHeaderFields(): Map<String, List<String>> = headers
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun connect() = Unit
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getPeerPrincipal(): Principal = Principal { "fixture" }
        override fun getLocalPrincipal(): Principal? = null
    }

    @Test fun prohibitedSchemesHostsMethodsAndLogoutNeverOpenTheNetwork() {
        val resources = PinnedPortalResources(campus, TargetConnectionFactory { _, _ -> throw AssertionError("Denied request opened a socket") })
        for ((url, method) in listOf(
            "http://yc.gxnu.edu.cn/" to "GET", "https://outside.invalid/login?user_password=fixture-sensitive" to "GET",
            "https://yc.gxnu.edu.cn.evil.invalid/" to "GET", "https://user@yc.gxnu.edu.cn/" to "GET",
            "https://yc.gxnu.edu.cn:801/" to "GET", "https://yc.gxnu.edu.cn/drcom/logout" to "GET",
            "https://yc.gxnu.edu.cn:802/eportal/portal/logout" to "GET", "https://yc.gxnu.edu.cn/" to "POST",
            "https://yc.gxnu.edu.cn/drcom/%6cogout" to "GET"
        )) {
            val result = resources.load(url, method)
            assertEquals(403, result.status)
            assertFalse(String(result.body).contains("fixture-sensitive"))
        }
    }

    @Test fun everyResourceUsesTheExactCampusSnapshotAndPreservesOriginalBytes() {
        val seen = mutableListOf<NetworkSnapshot>()
        val bytes = byteArrayOf(0x00, 0x7f, 0xff.toByte())
        val resources = PinnedPortalResources(campus, TargetConnectionFactory { network, url ->
            seen += network
            Reply(url, body = bytes, headers = mapOf("Content-Type" to listOf("image/png")))
        })
        val result = resources.load("https://yc.gxnu.edu.cn/icon.png")
        assertEquals(200, result.status)
        assertEquals(listOf(campus), seen)
        assertArrayEquals(bytes, result.body)
        assertEquals("image/png", result.mimeType)
    }

    @Test fun compressedGb18030JsonpKeepsDeclaredCharsetAndIsDecompressedOnce() {
        val text = "dr938({\"msg\":\"乗\"});"
        val compressed = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { it.write(text.toByteArray(Charset.forName("GB18030"))) }
        }.toByteArray()
        val resources = PinnedPortalResources(campus, TargetConnectionFactory { _, url -> Reply(url,
            body = compressed, headers = mapOf("Content-Type" to listOf("application/javascript; charset=GB18030"), "Content-Encoding" to listOf("gzip"), "Content-Length" to listOf(compressed.size.toString()))) })
        val result = resources.load("https://yc.gxnu.edu.cn:802/eportal/portal/page/loadConfig?callback=dr938")
        assertEquals(200, result.status)
        assertEquals(text, String(result.body, Charset.forName(result.encoding!!)))
        assertFalse(result.headers.keys.any { it.equals("Content-Encoding", true) || it.equals("Content-Length", true) })
    }

    @Test fun safeResourceRedirectReturnsTheFinalBodyInsteadOfAnUnsupportedWebview3xx() {
        val seen = mutableListOf<URL>()
        val resources = PinnedPortalResources(campus, TargetConnectionFactory { _, url ->
            seen += url
            if (url.path == "/old.js") Reply(url, 302, headers = mapOf("Location" to listOf("/new.js")))
            else Reply(url, body = "window.fixture=1;".toByteArray(), headers = mapOf("Content-Type" to listOf("text/javascript; charset=utf-8")))
        })
        val result = resources.load("https://yc.gxnu.edu.cn/old.js")
        assertEquals(200, result.status)
        assertEquals("window.fixture=1;", String(result.body))
        assertEquals(listOf("/old.js", "/new.js"), seen.map { it.path })
    }

    @Test fun redirectedMainDocumentPreservesTheSchoolsTerminalQueryInItsLocation() {
        val resources = PinnedPortalResources(campus, TargetConnectionFactory { _, url ->
            if (url.query == null) Reply(url, 302, headers = mapOf("Location" to listOf("/?wlanuserip=10.42.0.8&essid=GXNU-YC")))
            else Reply(url, body = "<html>fixture</html>".toByteArray())
        })
        val result = resources.load("https://yc.gxnu.edu.cn/", mainFrame = true)
        assertEquals(200, result.status)
        assertEquals("text/html", result.mimeType)
        assertTrue(String(result.body).contains("location.replace"))
        assertTrue(String(result.body).contains("wlanuserip=10.42.0.8"))
        assertTrue(String(result.body).contains("GXNU-YC"))
    }

    @Test fun redirectsCannotChangeOriginOrCarryCredentials() {
        for (location in listOf("https://outside.invalid/", "https://yc.gxnu.edu.cn:802/", "/?user_password=fixture-sensitive", "/drcom/logout")) {
            var opened = 0
            val resources = PinnedPortalResources(campus, TargetConnectionFactory { _, url ->
                opened++
                Reply(url, 302, headers = mapOf("Location" to listOf(location)))
            })
            val result = resources.load("https://yc.gxnu.edu.cn/", mainFrame = true)
            assertEquals(403, result.status)
            assertEquals(1, opened)
            assertFalse(String(result.body).contains("fixture-sensitive"))
        }
    }

    @Test fun serverCookiesStayInsideOneAttemptAndWebviewCookiesAreIgnored() {
        val connections = mutableListOf<Reply>()
        val factory = TargetConnectionFactory { _, url -> Reply(url, headers = if (url.path == "/start")
            mapOf("Set-Cookie" to listOf("campus_session=fixture-session; Path=/; Secure; HttpOnly")) else emptyMap()).also { connections += it } }
        val first = PinnedPortalResources(campus, factory)
        assertEquals(200, first.load("https://yc.gxnu.edu.cn/start").status)
        val next = first.load("https://yc.gxnu.edu.cn:802/eportal/portal/page/loadConfig", headers = mapOf("Cookie" to "untrusted=fixture-sensitive"))
        assertEquals(200, next.status)
        assertEquals("campus_session=fixture-session", connections[1].getRequestProperty("Cookie"))
        assertFalse(next.headers.keys.any { it.equals("Set-Cookie", true) })
        val second = PinnedPortalResources(campus, factory)
        second.load("https://yc.gxnu.edu.cn/after")
        assertNull(connections[2].getRequestProperty("Cookie"))
    }

    @Test fun foreignCookieDomainsAndSensitiveRefererQueriesAreNotForwarded() {
        val opened = mutableListOf<Reply>()
        val resources = PinnedPortalResources(campus, TargetConnectionFactory { _, url -> Reply(url,
            headers = mapOf("Set-Cookie" to listOf("bad=fixture-sensitive; Domain=outside.invalid; Path=/"))).also { opened += it } })
        assertEquals(200, resources.load("https://yc.gxnu.edu.cn/start").status)
        resources.load("https://yc.gxnu.edu.cn/resource.js", headers = mapOf("Referer" to "https://yc.gxnu.edu.cn/?user_password=fixture-sensitive"))
        assertNull(opened[1].getRequestProperty("Cookie"))
        assertEquals("https://yc.gxnu.edu.cn/", opened[1].getRequestProperty("Referer"))
    }

    @Test fun closingAnAttemptPreventsAnyNewSocketAndCompletedSocketsAreDisconnected() {
        var opened = 0
        lateinit var reply: Reply
        val resources = PinnedPortalResources(campus, TargetConnectionFactory { _, url -> opened++; Reply(url).also { reply = it } })
        assertEquals(200, resources.load("https://yc.gxnu.edu.cn/").status)
        assertTrue(reply.disconnected)
        resources.close()
        val blocked = resources.load("https://yc.gxnu.edu.cn/after")
        assertTrue(blocked.status >= 400)
        assertEquals(1, opened)
    }

    @Test fun oversizedResponsesFailWithoutExposingTheirContents() {
        val resources = PinnedPortalResources(campus, TargetConnectionFactory { _, url -> Reply(url,
            body = ByteArray(4 * 1024 * 1024 + 1) { 'x'.code.toByte() }) })
        val result = resources.load("https://yc.gxnu.edu.cn/oversized")
        assertTrue(result.status >= 400)
        assertEquals(PortalFailure.INVALID_RESPONSE, result.failure)
        assertTrue(result.body.size < 1024)
    }
}
