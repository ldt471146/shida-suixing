package cn.gxnu.campus.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 广西师范大学研究生系统（SmartGmis）的课表接口。
 *
 * 这套接口是直接从系统自己的前端里读出来的，不是猜的：
 *
 * - 登录 `POST {base}/login`，JSON 体 `{"userId":"学号","password":"<MD5 小写十六进制>",
 *   "ip":"","cityname":""}`。成功时 `zt == "1"` 并返回一枚 JWT（`token`）。
 * - 课表 `GET {base}/xskb/xh`，认证头是 **`GmisToken: <token>`**（不是 `Authorization`）。
 *   返回一个数组，每条一门课。
 *
 * 密码只做一次 MD5 —— 这是系统前端的做法，不是本应用的选择；HTTPS 才是它真正的保护。
 */
object CampusTimetableApi {

    /** 研究生系统的根，写成常量是因为它就是这一所学校的一个部署。 */
    const val BASE_URL = "https://yjsapp.gxnu.edu.cn/SmartGmis5_0"

    private const val CONNECT_TIMEOUT_MILLIS = 10_000
    private const val READ_TIMEOUT_MILLIS = 20_000

    /** 一节课的上下课时间，由系统自己的课表页给出；本表是全校统一的作息。 */
    val PERIOD_CLOCKS: List<String> = listOf(
        "08:30-09:10", "09:15-09:55", "10:05-10:45", "10:55-11:35", "11:40-12:20",
        "14:00-14:40", "14:45-15:25", "15:35-16:15", "16:20-17:00",
        "18:20-19:00", "19:00-19:40", "19:45-20:25", "20:30-21:10"
    )

    /** 一次登录的结果：成功带 token，失败带一句能直接显示给用户的中文。 */
    sealed interface SignIn {
        data class Ok(val token: String) : SignIn
        data class Failed(val reason: String) : SignIn
    }

    sealed interface Fetch {
        data class Ok(val courses: List<CampusCourse>) : Fetch

        /** [expired] 为真时说明 token 过期了，调用方应该重新登录一次再试。 */
        data class Failed(val reason: String, val expired: Boolean = false) : Fetch
    }

    /** 接口里的一行课，字段名保持系统自己的叫法，便于对着原始 JSON 复核。 */
    data class CampusCourse(
        val name: String,
        val teacher: String,
        val room: String,
        /** 1 = 周一 … 7 = 周日。 */
        val weekday: Int,
        val startPeriod: Int,
        val endPeriod: Int,
        val weeks: String,
        val startClock: String,
        val endClock: String
    )

    fun signIn(userId: String, password: String): SignIn {
        val id = userId.trim()
        if (id.isEmpty()) return SignIn.Failed("请输入学号。")
        if (password.isEmpty()) return SignIn.Failed("请输入密码。")

        val body = JsonObject().apply {
            addProperty("userId", id)
            addProperty("password", md5Hex(password))
            addProperty("ip", "")
            addProperty("cityname", "")
        }.toString()

        val response = try {
            request("POST", "$BASE_URL/login", body, token = null)
        } catch (_: Exception) {
            return SignIn.Failed("连不上研究生系统，请确认已连上校园网后重试。")
        } ?: return SignIn.Failed("连不上研究生系统，请确认已连上校园网后重试。")

        if (response.code != HttpURLConnection.HTTP_OK) {
            return SignIn.Failed("研究生系统暂时没有响应（${response.code}），请稍后重试。")
        }
        return parseSignIn(response.text)
    }

    /**
     * 登录响应 → 会话。网络之外的那一半单独放在这里，测试直接拿真实响应体跑，不需要校园网。
     */
    fun parseSignIn(json: String): SignIn {
        val root = jsonObjectOrNull(json)
            ?: return SignIn.Failed("研究生系统返回了无法识别的内容，请稍后重试。")
        if (root.string("zt") != "1") {
            val message = root.string("msg").orEmpty()
            return SignIn.Failed(if (message.isBlank()) "学号或密码不正确。" else "登录失败：$message")
        }
        val token = root.string("token").orEmpty()
        if (token.isBlank()) return SignIn.Failed("登录成功但没有拿到凭证，请稍后重试。")
        // The response's own userId is null in practice; the caller already knows which id it sent.
        return SignIn.Ok(token)
    }

    fun fetchTimetable(token: String): Fetch {
        val response = try {
            request("GET", "$BASE_URL/xskb/xh", body = null, token = token)
        } catch (_: Exception) {
            return Fetch.Failed("连不上研究生系统，请确认已连上校园网后重试。")
        } ?: return Fetch.Failed("连不上研究生系统，请确认已连上校园网后重试。")

        if (response.code == HttpURLConnection.HTTP_UNAUTHORIZED || response.code == 401) {
            return Fetch.Failed("登录状态已过期，请重新登录。", expired = true)
        }
        if (response.code != HttpURLConnection.HTTP_OK) {
            return Fetch.Failed("课表暂时取不到（${response.code}），请稍后重试。")
        }
        val courses = parseCourses(response.text)
            ?: return Fetch.Failed("研究生系统返回了无法识别的课表，请稍后重试。")
        if (courses.isEmpty()) return Fetch.Failed("这个学期还没有排课。")
        return Fetch.Ok(courses)
    }

    /** `/xskb/xh` 的响应体 → 课表行。无法解析时返回 null；「无节次」的行在这里被丢掉。 */
    fun parseCourses(json: String): List<CampusCourse>? = jsonArrayOrNull(json)
        ?.mapNotNull { it.takeIf { element -> element.isJsonObject }?.asJsonObject }
        ?.mapNotNull(::parseCourse)

    /**
     * 一行 JSON 变一门课。**节次取自 `ksjcmc` / `jsjcmc` 末尾的数字**（`上午2` → 第 2 节，
     * `晚上13` → 第 13 节），不是 `ksjc` / `jsjc` —— 后者在这份系统里不是节次号（同一节里
     * 出现了 42/46/47/52/53/58，与任何节次编号都对不上），而课表页左侧显示的就是 1..13。
     *
     * `ksjc == 99` 是「无节次」的哨兵值：那是学校给还没排时间的课留的行，**不导入**，
     * 所以课表里不会出现第 0 节。
     */
    private fun parseCourse(row: JsonObject): CampusCourse? {
        val name = row.string("bjmc").orEmpty().ifBlank { row.string("kcmc").orEmpty() }
        if (name.isBlank()) return null
        if (row.string("ksjc") == UNNUMBERED_SENTINEL) return null

        val startPeriod = trailingNumber(row.string("ksjcmc")) ?: return null
        val endPeriod = trailingNumber(row.string("jsjcmc")) ?: startPeriod
        if (startPeriod !in 1..TIMETABLE_MAX_PERIODS) return null
        val weekday = row.int("zh") ?: return null
        if (weekday !in 1..7) return null

        return CampusCourse(
            name = name,
            teacher = row.string("zjjsxm").orEmpty(),
            room = row.string("skdd").orEmpty(),
            weekday = weekday,
            startPeriod = startPeriod,
            endPeriod = endPeriod.coerceIn(startPeriod, TIMETABLE_MAX_PERIODS),
            weeks = row.string("sksj").orEmpty(),
            startClock = row.string("kssj").orEmpty(),
            endClock = row.string("jssj").orEmpty()
        )
    }

    /** `上午2` → 2，`晚上13` → 13。没带数字的标签（无节次）返回 null。 */
    private fun trailingNumber(label: String?): Int? =
        label?.let { Regex("(\\d{1,2})\\s*$").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    /** 系统前端用的就是这一种：小写十六进制 MD5。 */
    fun md5Hex(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.int(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asInt

    private class Response(val code: Int, val text: String)

    private fun jsonObjectOrNull(text: String): JsonObject? = runCatching { JsonParser.parseString(text) }
        .getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject

    private fun jsonArrayOrNull(text: String): com.google.gson.JsonArray? = runCatching { JsonParser.parseString(text) }
        .getOrNull()?.takeIf { it.isJsonArray }?.asJsonArray

    private fun request(method: String, url: String, body: String?, token: String?): Response? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Content-Type", "application/json;charset=utf-8")
            // The system's own header name; Authorization is rejected with 401.
            if (!token.isNullOrBlank()) setRequestProperty("GmisToken", token)
            if (body != null) doOutput = true
        }
        return try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            Response(code, text)
        } finally {
            connection.disconnect()
        }
    }

    /** 无节次的哨兵：这一行是「还没排时间」，不是第 0 节。 */
    private const val UNNUMBERED_SENTINEL = "99"
}
