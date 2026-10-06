package cn.gxnu.campus.network

import cn.gxnu.campus.core.ConnectionTransport
import cn.gxnu.campus.core.Credentials
import cn.gxnu.campus.core.NetworkSnapshot
import cn.gxnu.campus.core.PortalContext
import cn.gxnu.campus.core.PortalException
import cn.gxnu.campus.core.PortalFailure
import cn.gxnu.campus.core.PortalProtocol
import cn.gxnu.campus.core.Provider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine

internal fun interface TargetConnectionFactory {
    fun open(network: NetworkSnapshot, url: URL): HttpsURLConnection
}

/** Every HTTP connection is created by the supplied, exact Wi-Fi Network. */
class PortalClient internal constructor(private val connections: TargetConnectionFactory) : ConnectionTransport {
    constructor(wifi: WifiEnvironment) : this(TargetConnectionFactory { network, url -> wifi.openConnection(network, url) })

    override suspend fun authenticate(network: NetworkSnapshot, credentials: Credentials, provider: Provider): Boolean {
        requireCampus(network)
        val entry = discoverEntry(network)
        val html = entry.reply.decode(GB18030)
        val discovered = PortalDiscovery.discoverContext(html, entry.url, allowMissingAddress = true)
        val scriptUrl = PortalDiscovery.configurationScript(html, entry.url)
        val script = schoolGet(network, scriptUrl).decode(GB18030)
        var context = discovered.copy(jsVersion = PortalDiscovery.jsVersion(script))
        val needsAddress = context.ipv4.isEmpty() && context.ipv6.isEmpty()
        if (needsAddress) context = readTerminalStatus(network, context, required = true)
        PortalDiscovery.requireAddress(context)
        val configUrl = URL(CONFIG_ENDPOINT + "?" + PortalProtocol.encodeQuery(PortalDiscovery.configParameters(html, context, entry.url)))
        val config = schoolGet(network, configUrl).decode(Charsets.UTF_8)
        PortalDiscovery.validateConfiguration(config, script)
        if (!needsAddress && context.mac.isEmpty()) context = readTerminalStatus(network, context, required = false)
        val loginUrl = URL(PortalProtocol.loginUrl(credentials, provider, context))
        if (loginUrl.protocol != "https" || loginUrl.host != SCHOOL_HOST || loginUrl.port != 802 ||
            loginUrl.path != "/eportal/portal/login") unsupported()
        val result = schoolGet(network, loginUrl).decode(Charsets.UTF_8)
        return PortalProtocol.acceptedOrFailure(result, credentials)
    }

    private suspend fun readTerminalStatus(network: NetworkSnapshot, context: PortalContext, required: Boolean): PortalContext {
        // The school's a41.js fills missing terminal fields from this read-only kernel endpoint.
        val url = URL(SCHOOL_ENTRY + "drcom/chkstatus?" + PortalProtocol.encodeQuery(linkedMapOf(
            "callback" to "dr1002", "jsVersion" to context.jsVersion,
            "lang" to "zh-cn",
            "v" to java.util.concurrent.ThreadLocalRandom.current().nextInt(10_000).toString()
        )))
        return try { PortalDiscovery.mergeStatus(context, schoolGet(network, url).decode(Charsets.UTF_8)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: PortalException) {
            if (required) throw failure
            // An absent MAC alone is optional in the current school portal flow.
            context
        }
    }

    override suspend fun verifyInternet(network: NetworkSnapshot): Boolean {
        requireCampus(network)
        // Each endpoint is a trusted HTTPS 204 probe on this exact Wi-Fi. Try the next
        // endpoint when one is filtered; a school login page or redirect never counts.
        for (endpoint in INTERNET_CHECKS) {
            val result = try { request(network, URL(endpoint), 4_000, readBody = false) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: PortalException) { continue }
            if (result.status == 204 && result.location == null) return true
        }
        return false
    }

    private suspend fun schoolGet(network: NetworkSnapshot, url: URL): HttpReply {
        requireSchoolUrl(url)
        val reply = request(network, url, 5_000, readBody = true)
        if (reply.status in 300..399 || reply.location != null) unsupported()
        if (reply.status != 200) throw PortalException(PortalFailure.UNREACHABLE, "学校认证入口返回 HTTP ${reply.status}，请稍后重试或查看网络诊断。")
        return reply
    }

    private data class DiscoveredEntry(val url: URL, val reply: HttpReply)

    /** Only the credential-free discovery page may follow redirects, within the school's HTTPS origin. */
    private suspend fun discoverEntry(network: NetworkSnapshot): DiscoveredEntry {
        var url = URL(SCHOOL_ENTRY)
        repeat(4) { hop ->
            requireSchoolUrl(url)
            if (Regex("(?:^|&)(?:user_account|user_password|upass|DDDDD)=", RegexOption.IGNORE_CASE)
                    .containsMatchIn(url.query.orEmpty())) unsupported()
            val reply = request(network, url, 5_000, readBody = true)
            if (reply.status in listOf(301, 302, 303, 307, 308)) {
                if (hop == 3 || reply.location.isNullOrBlank()) unsupported()
                url = try { URL(url, reply.location) } catch (_: Exception) { unsupported() }
            } else {
                if (reply.location != null) unsupported()
                if (reply.status != 200) throw PortalException(PortalFailure.UNREACHABLE, "学校认证入口暂时不可达，请稍后重试。")
                return DiscoveredEntry(url, reply)
            }
        }
        unsupported()
    }

    private fun requireSchoolUrl(url: URL) {
        if (url.protocol != "https" || url.host != SCHOOL_HOST || url.port !in listOf(-1, 443, 802) ||
            url.userInfo != null) unsupported()
    }

    private suspend fun request(network: NetworkSnapshot, url: URL, timeout: Int, readBody: Boolean): HttpReply =
        suspendCancellableCoroutine { continuation ->
            val active = AtomicReference<HttpsURLConnection?>(null)
            fun disconnectAsync() {
                val connection = active.getAndSet(null) ?: return
                Dispatchers.IO.dispatch(continuation.context, Runnable { disconnectSafely(connection) })
            }
            continuation.invokeOnCancellation { disconnectAsync() }
            // Blocking DNS, reads and disconnects stay on IO. Cancellation can return immediately
            // even while a platform socket is still unwinding.
            Dispatchers.IO.dispatch(continuation.context, Runnable {
                var connection: HttpsURLConnection? = null
                try {
                    if (!continuation.isActive) return@Runnable
                    requireCampus(network)
                    connection = connections.open(network, url)
                    val activeConnection = connection
                    active.set(activeConnection)
                    if (!continuation.isActive) {
                        disconnectAsync()
                        return@Runnable
                    }
                    activeConnection.requestMethod = "GET"
                    activeConnection.instanceFollowRedirects = false
                    activeConnection.connectTimeout = timeout
                    activeConnection.readTimeout = timeout
                    activeConnection.useCaches = false
                    activeConnection.setRequestProperty("Cache-Control", "no-cache, no-store")
                    activeConnection.setRequestProperty("Accept-Encoding", "gzip")
                    activeConnection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) CampusConnect/0.2")
                    if (!continuation.isActive) return@Runnable
                    val status = activeConnection.responseCode
                    val location = activeConnection.getHeaderField("Location")
                    val bytes = if (readBody && status == 200) {
                        val stream = if (activeConnection.getHeaderField("Content-Encoding")?.equals("gzip", true) == true)
                            GZIPInputStream(activeConnection.inputStream) else activeConnection.inputStream
                        stream.use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (!continuation.isActive) throw CancellationException()
                                if (output.size() + count > 1_048_576) throw PortalException(PortalFailure.INVALID_RESPONSE, "学校响应格式异常，请使用原网页。")
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                    } else ByteArray(0)
                    if (continuation.isActive) continuation.resume(HttpReply(status, location, bytes))
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(safeFailure(failure))
                } finally {
                    connection?.let { if (active.compareAndSet(it, null)) disconnectSafely(it) }
                }
            })
        }

    private fun disconnectSafely(connection: HttpsURLConnection) {
        try { connection.disconnect() } catch (_: Exception) { }
    }

    private fun safeFailure(failure: Exception): Exception = when (failure) {
        is CancellationException -> failure
        is PortalException -> failure
        is SocketTimeoutException -> PortalException(PortalFailure.TIMEOUT, "学校请求超时，请稍后重试。")
        is SSLException -> PortalException(PortalFailure.UNREACHABLE, "学校安全连接失败，请检查系统时间或使用原网页。")
        else -> PortalException(PortalFailure.UNREACHABLE, "目标校园 Wi-Fi 或学校入口暂时不可达。")
    }

    private fun requireCampus(network: NetworkSnapshot) {
        if (!network.isCampus) throw PortalException(PortalFailure.UNREACHABLE, "请先连接 GXNU-YC 校园 Wi-Fi。")
    }

    private data class HttpReply(val status: Int, val location: String?, val body: ByteArray) {
        fun decode(charset: Charset): String = String(body, charset)
        override fun toString() = "HttpReply(status=$status)"
    }

    companion object {
        const val SCHOOL_ENTRY = "https://yc.gxnu.edu.cn/"
        private const val SCHOOL_HOST = "yc.gxnu.edu.cn"
        private const val CONFIG_ENDPOINT = "https://yc.gxnu.edu.cn:802/eportal/portal/page/loadConfig"
        private val GB18030 = Charset.forName("GB18030")
        private val INTERNET_CHECKS = listOf(
            "https://connectivitycheck.platform.hicloud.com/generate_204",
            "https://cp.cloudflare.com/generate_204"
        )
    }
}

/** Reads data literals and query fields; it never evaluates school JavaScript. */
internal object PortalDiscovery {
    fun discoverContext(html: String, finalUrl: URL? = null, allowMissingAddress: Boolean = false): PortalContext {
        fun query(keys: List<String>) = queryValue(finalUrl?.toExternalForm().orEmpty(), keys).ifBlank { queryValue(html, keys) }
        val v4Aliases = listOf("ip", "wlanuserip", "wlan_user_ip", "userip", "user-ip", "client_ip", "UserIP", "uip", "station_ip")
        val primary = query(v4Aliases).ifBlank {
            literal(html, listOf("v46ip", "ss5", "v4ip", "lip"))
        }.ifBlank {
            literal(html, listOf("ss3")).let(::hexIpv4)
        }
        val ipv4 = if (isIpv4(primary)) normalizeIpv4(primary) else ""
        val ipv6 = query(listOf("UserV6IP", "wlan_user_ipv6", "user_ipv6")).ifBlank {
            literal(html, listOf("v6ip", "ipv6"))
        }.ifBlank { primary.takeIf { it.contains(':') } ?: "" }.let {
            if (isIpv6(it)) it else ""
        }
        if (!allowMissingAddress && ipv4.isEmpty() && ipv6.isEmpty()) missingAddress()
        val mac = query(listOf("mac", "usermac", "user-mac", "wlanusermac", "wlan_user_mac", "umac", "client_mac", "station_mac"))
            .ifBlank { literal(html, listOf("ss4", "olmac")) }.let(::normalizeMac)
        val acIp = query(listOf("wlanacip", "wlan_ac_ip", "acip", "switchip", "nasip", "nas-ip"))
            .ifBlank { literal(html, listOf("wlanacip", "wlan_ac_ip")) }
            .takeIf(::isIpv4)?.let(::normalizeIpv4) ?: ""
        val acName = query(listOf("wlanacname", "wlan_ac_name", "acname", "sysname", "nasname", "nas-name"))
            .ifBlank { literal(html, listOf("wlanacname", "wlan_ac_name")) }
        return PortalContext(ipv4, ipv6, mac, acIp, acName)
    }

    fun requireAddress(context: PortalContext) {
        if (!isIpv4(context.ipv4) && !isIpv6(context.ipv6)) missingAddress()
    }

    fun mergeStatus(context: PortalContext, jsonp: String): PortalContext {
        val data = parseData(jsonp, "终端状态")
        if (scalar(data, "result") !in listOf("0", "1")) return context
        val mac = context.mac.ifBlank { scalar(data, "ss4").orEmpty().let(::normalizeMac) }
        val statusIp = listOf("ss5", "v46ip", "v4ip").firstNotNullOfOrNull { field ->
            scalar(data, field)?.takeIf(::isIpv4)?.let(::normalizeIpv4)
        }.orEmpty().ifBlank { scalar(data, "ss3").orEmpty().let(::hexIpv4).takeIf(::isIpv4).orEmpty() }
        val ipv4 = context.ipv4.ifBlank { statusIp }
        val ipv6 = context.ipv6.ifBlank {
            listOf("wlan_user_ipv6", "user_ipv6", "v6ip", "v46ip", "ss5").firstNotNullOfOrNull { field ->
                scalar(data, field)?.takeIf(::isIpv6)
            }.orEmpty()
        }
        val acIp = context.acIp.ifBlank {
            scalar(data, "wlan_ac_ip")?.takeIf(::isIpv4)?.let(::normalizeIpv4).orEmpty()
        }
        return context.copy(ipv4 = ipv4, ipv6 = ipv6, mac = mac, acIp = acIp)
    }

    fun configurationScript(html: String, finalUrl: URL? = null): URL {
        val sources = Regex("<script\\b[^>]*\\bsrc\\s*=\\s*['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE)
            .findAll(html).map { it.groupValues[1].replace("&amp;", "&") }.toList()
        for (source in sources) {
            val url = try { URL(finalUrl ?: URL(PortalClient.SCHOOL_ENTRY), source) } catch (_: Exception) { continue }
            if (url.protocol == "https" && url.host == "yc.gxnu.edu.cn" && url.port in listOf(-1, 443) &&
                url.path in listOf("/a40.js", "/a41.js")) return url
        }
        unsupported()
    }

    fun configParameters(html: String, context: PortalContext, finalUrl: URL? = null): Map<String, String> {
        fun base64(value: String) = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
        fun query(vararg keys: String) = queryValue(finalUrl?.toExternalForm().orEmpty(), keys.toList())
            .ifBlank { queryValue(html, keys.toList()) }
        return linkedMapOf(
            "program_index" to query("program_index"),
            "wlan_vlan_id" to query("wlan_vlan_id", "vlan", "vlanid")
                .ifBlank { literal(html, listOf("vlanid")) }.ifBlank { "1" },
            "wlan_user_ip" to base64(context.ipv4),
            "wlan_user_ipv6" to base64(context.ipv6),
            "wlan_user_ssid" to query("ssid", "wlan_user_ssid", "essid"),
            "wlan_user_areaid" to query("areaID", "wlan_user_areaid"),
            "wlan_ac_ip" to base64(context.acIp),
            "wlan_ap_mac" to query("wlanapmac", "wlan_ap_mac", "apmac", "ap_mac")
                .replace(Regex("[-:]"), "").ifBlank { "000000000000" },
            "gw_id" to query("gw_id", "gw_mac").replace(Regex("[-:]"), "").lowercase().ifBlank { "000000000000" },
            "callback" to "dr938",
            "jsVersion" to context.jsVersion,
            "lang" to "zh-cn",
            "v" to java.util.concurrent.ThreadLocalRandom.current().nextInt(10_000).toString()
        )
    }

    fun validateConfiguration(jsonp: String, script: String) {
        val root = parseData(jsonp, "认证配置")
        val data = root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: unsupported()
        if (scalar(root, "code") != "1") unsupported()
        if (scalar(data, "login_method") != "1" || scalar(data, "enable_r3") != "0" || scalar(data, "en_md5") != "0") unsupported()
        if (literal(script, listOf("page_data_encrypt")) != "0") unsupported()
        if (data.has("page_data_encrypt") && scalar(data, "page_data_encrypt") != "0") unsupported()
        if (scalar(data, "account_prefix") != "1" || scalar(data, "io_mode") != "0") unsupported()
        // These absent fields use the documented defaults in the school's public a41.js.
        for (field in listOf("custom_perceive", "en_perceive", "enable_alias", "password_cut", "enbale_eduroam_verify", "enable_captcha", "captcha_enable", "captcha")) {
            val value = scalar(data, field)
            if (data.has(field) && value == null) unsupported()
            if (value != null && value != "0" && value != "false" && value.isNotEmpty()) unsupported()
        }
    }

    fun jsVersion(script: String): String = literal(script, listOf("jsVersion"))
        .takeIf { it.matches(Regex("[0-9A-Za-z.]{1,16}")) } ?: "4.2.2"

    private fun parseData(value: String, source: String): JsonObject {
        try {
            val trimmed = value.trim().removePrefix("\uFEFF")
            val body = if (trimmed.startsWith('{')) trimmed else {
                Regex("[A-Za-z_$][A-Za-z0-9_$]*\\s*\\(\\s*(\\{.*})\\s*\\)\\s*;?", RegexOption.DOT_MATCHES_ALL)
                    .matchEntire(trimmed)?.groupValues?.get(1) ?: unsupported()
            }
            val reader = JsonReader(StringReader(body)).apply { strictness = Strictness.STRICT }
            return reader.use {
                val parsed = JsonParser.parseReader(it)
                if (it.peek() != JsonToken.END_DOCUMENT) unsupported()
                parsed.takeIf { element -> element.isJsonObject }?.asJsonObject ?: unsupported()
            }
        } catch (failure: PortalException) {
            throw PortalException(failure.reason, "读取${source}失败：${failure.message}")
        } catch (failure: Exception) {
            // Keep only parser coordinates. Exception messages can contain response values.
            val position = listOfNotNull(failure.message, failure.cause?.message)
                .firstNotNullOfOrNull { Regex("line\\s+(\\d{1,7})\\s+column\\s+(\\d{1,7})\\b").find(it) }
            val location = position?.let { "（第 ${it.groupValues[1]} 行，第 ${it.groupValues[2]} 列）" }.orEmpty()
            throw PortalException(PortalFailure.INVALID_RESPONSE, "学校${source}响应无法解析${location}，请重试或查看网络诊断。")
        }
    }

    private fun scalar(objectValue: JsonObject, key: String): String? = objectValue.get(key)
        ?.takeIf { it.isJsonPrimitive }?.asString

    private fun queryValue(text: String, keys: List<String>): String {
        val decodedHtml = text.replace("&amp;", "&")
        for (key in keys) {
            val match = Regex("[?&]" + Regex.escape(key) + "=([^&'\"<>\\s]*)", RegexOption.IGNORE_CASE).find(decodedHtml)
            if (match != null) return try { URLDecoder.decode(match.groupValues[1], "UTF-8") } catch (_: Exception) { "" }
        }
        return ""
    }

    private fun literal(text: String, keys: List<String>): String {
        for (key in keys) {
            val quoted = Regex("(?<![A-Za-z0-9_$])['\"]?" + Regex.escape(key) + "['\"]?\\s*[:=]\\s*['\"]([^'\"\\r\\n]*)['\"]")
                .find(text)?.groupValues?.get(1)
            if (!quoted.isNullOrBlank()) return quoted.trim()
            val numeric = Regex("(?<![A-Za-z0-9_$])['\"]?" + Regex.escape(key) + "['\"]?\\s*[:=]\\s*([0-9]+)").find(text)?.groupValues?.get(1)
            if (numeric != null) return numeric
        }
        return ""
    }

    private fun isIpv4(value: String): Boolean {
        val components = value.split('.')
        return components.size == 4 && components.all { it.isNotEmpty() && it.all(Char::isDigit) && (it.toIntOrNull() ?: -1) in 0..255 } &&
            components.any { it.toInt() != 0 }
    }
    private fun normalizeIpv4(value: String) = value.split('.').joinToString(".") { it.toInt().toString() }
    private fun isIpv6(value: String): Boolean {
        if (value.length !in 2..45 || !value.contains(':') || !value.matches(Regex("[0-9a-fA-F:.]+"))) return false
        // A colon-containing, numeric-only literal cannot trigger a DNS lookup.
        return try { java.net.InetAddress.getByName(value); value != "::" } catch (_: Exception) { false }
    }
    private fun missingAddress(): Nothing = throw PortalException(PortalFailure.UNSUPPORTED, "无法确认当前 Wi-Fi 的终端地址，请打开学校认证页面或查看诊断。")
    private fun normalizeMac(value: String): String = value.replace(Regex("[-:]"), "")
        .takeIf { it.matches(Regex("[0-9a-fA-F]{12}")) && it !in listOf("000000000000", "111111111111", "123456789012") }
        ?.lowercase().orEmpty()
    private fun hexIpv4(value: String): String = if (value.matches(Regex("[0-9a-fA-F]{8}")))
        value.chunked(2).joinToString(".") { it.toInt(16).toString() } else ""
}

private fun unsupported(): Nothing = throw PortalException(PortalFailure.UNSUPPORTED, "学校认证配置需要人工操作，请打开学校原网页。")
