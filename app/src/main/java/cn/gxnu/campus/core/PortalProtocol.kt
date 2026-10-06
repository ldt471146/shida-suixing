package cn.gxnu.campus.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.Strictness
import com.google.gson.reflect.TypeToken
import java.net.URLEncoder
import java.util.concurrent.ThreadLocalRandom

object PortalProtocol {
    const val LOGIN_ENDPOINT = "https://yc.gxnu.edu.cn:802/eportal/portal/login"
    private val gson = GsonBuilder().setStrictness(Strictness.STRICT).create()
    private val responseType = object : TypeToken<Map<String, JsonElement>>() {}.type
    private val jsonp = Regex("""[A-Za-z_$][A-Za-z0-9_$]*\s*\(\s*(\{[\s\S]*\})\s*\)\s*;?""")

    fun loginParameters(credentials: Credentials, provider: Provider, context: PortalContext): Map<String, String> {
        var account = credentials.account.trim()
        val knownSuffixes = Provider.entries.map { it.suffix }.filter { it.isNotEmpty() }
        while (true) {
            val suffix = knownSuffixes.firstOrNull { account.endsWith(it, ignoreCase = true) } ?: break
            account = account.dropLast(suffix.length).trimEnd()
        }
        if (account.isEmpty()) {
            throw PortalException(PortalFailure.ACCOUNT, "请先填写校园网账号。")
        }
        return linkedMapOf(
            "login_method" to "1",
            "user_account" to ",1,$account${provider.suffix}",
            "user_password" to credentials.password,
            "wlan_user_ip" to context.ipv4,
            "wlan_user_ipv6" to context.ipv6,
            "wlan_user_mac" to context.mac,
            "wlan_ac_ip" to context.acIp,
            "wlan_ac_name" to context.acName,
            "jsVersion" to context.jsVersion,
            "terminal_type" to "2",
            "lang" to "zh-cn",
            "callback" to "dr1003",
            "v" to ThreadLocalRandom.current().nextInt(10_000).toString()
        )
    }

    fun encodeQuery(parameters: Map<String, String>): String = parameters.entries.joinToString("&") {
        "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
    }

    fun loginUrl(credentials: Credentials, provider: Provider, context: PortalContext): String =
        "$LOGIN_ENDPOINT?${encodeQuery(loginParameters(credentials, provider, context))}"

    /** Accept only a whole JSON object or a single data-only JSONP callback. Never evaluate JS. */
    fun parseResponse(jsonOrJsonp: String): Boolean = isAccepted(parseData(jsonOrJsonp))

    /** School error codes are data; no response text or URL is executed or logged. */
    fun acceptedOrFailure(jsonOrJsonp: String, credentials: Credentials): Boolean {
        val data = parseData(jsonOrJsonp)
        if (isAccepted(data)) return true
        val code = data["ret_code"]?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull()
        // The page documents this as an already-online terminal. The coordinator must still
        // validate this exact Wi-Fi, rather than treating the code alone as Internet access.
        if (code == 2) return true
        val raw = data["msg"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
        val message = safeSchoolMessage(raw, credentials)
        if (message.contains("Mac, IP, NASip, PORT err(6)", true) ||
            message.contains("error bind vid", true) || message.contains("首次登录") || message.contains("修改密码")) {
            throw PortalException(PortalFailure.UNSUPPORTED, "学校要求先修改密码，请打开学校认证页面完成后重试。")
        }
        val (reason, fallback) = when (code) {
            1, 4, 7 -> PortalFailure.ACCOUNT to "学校未通过账号认证，请检查账号、密码和供应商。"
            3 -> PortalFailure.UNREACHABLE to "学校认证系统繁忙，请稍后重试。"
            6, 8, 10 -> PortalFailure.TIMEOUT to "学校认证服务器响应超时，请稍后重试。"
            998 -> PortalFailure.UNSUPPORTED to "学校所需的网络参数未完整识别，请打开学校认证页面或查看诊断。"
            else -> if (Regex("账号|账户|密码|password|account", RegexOption.IGNORE_CASE).containsMatchIn(message)) {
                PortalFailure.ACCOUNT to "学校未通过账号认证，请检查账号、密码和供应商。"
            } else PortalFailure.UNREACHABLE to "学校未完成网络认证，请重试或打开学校认证页面。"
        }
        // Timeout/busy/parameter codes have documented local messages. Other rejections retain
        // the school's readable reason after removing possible echoed credentials and links.
        val detail = if (code in listOf(3, 6, 8, 10, 998) || message.isBlank()) fallback else message
        throw PortalException(reason, detail)
    }

    private fun safeSchoolMessage(value: String, credentials: Credentials): String {
        var text = value
        for (secret in listOf(credentials.password, credentials.account, credentials.account.trim())
            .filter { it.isNotEmpty() }.distinct().sortedByDescending(String::length)) {
            text = text.replace(secret, "•••", ignoreCase = false)
            text = text.replace(URLEncoder.encode(secret, "UTF-8"), "•••", ignoreCase = true)
        }
        return text.replace(Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE), "[学校链接]")
            .replace(Regex("<[^>]*>"), "")
            .replace(Regex("[\\p{Cntrl}\\s]+"), " ")
            .trim().take(240)
    }

    private fun parseData(jsonOrJsonp: String): Map<String, JsonElement> {
        if (jsonOrJsonp.length > 128 * 1024) throw invalidResponse()
        val response = jsonOrJsonp.trim()
        val payload = if (response.startsWith("{")) response else {
            jsonp.matchEntire(response)?.groupValues?.get(1) ?: throw invalidResponse()
        }
        // Gson's map adapter rejects duplicate top-level keys instead of silently overwriting result.
        val data = try {
            gson.fromJson<Map<String, JsonElement>>(payload, responseType) ?: throw invalidResponse()
        } catch (_: Exception) {
            throw invalidResponse()
        }
        if (data["result"]?.isJsonPrimitive != true) throw invalidResponse()
        return data
    }

    private fun isAccepted(data: Map<String, JsonElement>): Boolean {
        val result = data["result"] ?: throw invalidResponse()
        if (!result.isJsonPrimitive) throw invalidResponse()
        val value = result.asJsonPrimitive
        return when {
            value.isNumber -> value.asString == "1"
            value.isString -> value.asString == "1" || value.asString == "ok"
            else -> false
        }
    }

    private fun invalidResponse() =
        PortalException(PortalFailure.INVALID_RESPONSE, "学校认证响应无法识别，请打开学校认证页面检查。")
}
