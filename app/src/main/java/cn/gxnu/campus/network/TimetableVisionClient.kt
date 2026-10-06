package cn.gxnu.campus.network

import cn.gxnu.campus.BuildConfig
import cn.gxnu.campus.core.Timetable
import cn.gxnu.campus.core.TimetableException
import cn.gxnu.campus.core.TimetableFailure
import cn.gxnu.campus.core.TimetableCourseDraft
import cn.gxnu.campus.core.TimetableValidator
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * One downscaled, re-encoded image ready for upload. Not a data class: the bytes are large and
 * value equality on them would be meaningless.
 */
class TimetableImage(val bytes: ByteArray, val mimeType: String) {
    val byteCount: Int get() = bytes.size
    fun base64(): String = Base64.getEncoder().encodeToString(bytes)
    override fun toString() = "TimetableImage(bytes=$byteCount, mimeType=$mimeType)"
}

enum class TimetableVisionFailure {
    MISSING_KEY, INVALID_REQUEST, UNAUTHORIZED, QUOTA, RATE_LIMITED, TIMEOUT, NO_NETWORK,
    TOO_LARGE, SERVER, EMPTY_RESPONSE, MALFORMED_RESPONSE, NOT_A_TIMETABLE
}

class TimetableVisionException(
    val failure: TimetableVisionFailure,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/** Documented payload ceilings of the vision endpoint. Downscaling targets [TARGET_SIDE_PX]. */
/**
 * Documented endpoint ceilings: 8192 px per side, 32 MiB per base64 image and 48 MiB per request
 * body. Uploads target [TARGET_SIDE_PX], which is far below all three even after base64 inflation.
 */
object VisionImageLimits {
    const val TARGET_SIDE_PX = 2_048
    const val MAX_BASE64_BYTES = 32 * 1024 * 1024
    val ALLOWED_MIME_TYPES = setOf("image/jpeg", "image/png", "image/gif", "image/webp")

    data class ImageSize(val width: Int, val height: Int)

    /** Scales proportionally into the box and never enlarges; degenerate input maps to 1×1. */
    fun fitInside(width: Int, height: Int, maxSide: Int): ImageSize {
        if (width <= 0 || height <= 0 || maxSide <= 0) return ImageSize(1, 1)
        val longest = maxOf(width, height)
        if (longest <= maxSide) return ImageSize(width, height)
        val scale = maxSide.toDouble() / longest
        return ImageSize(
            maxOf(1, Math.round(width * scale).toInt()),
            maxOf(1, Math.round(height * scale).toInt())
        )
    }

    fun base64Length(byteCount: Int): Int = ((byteCount.toLong() + 2) / 3 * 4).toInt()

    /** Why the endpoint would refuse this payload, or null when it fits every documented limit. */
    fun rejectionReason(image: TimetableImage): String? = when {
        image.mimeType !in ALLOWED_MIME_TYPES -> "这张图片的格式无法上传，请换一张 JPG 或 PNG 照片。"
        image.bytes.isEmpty() -> "图片内容为空，请重新选择照片。"
        // The per-image ceiling is the binding limit; the 48 MiB body limit is looser than it.
        base64Length(image.bytes.size) > MAX_BASE64_BYTES -> "图片压缩后仍然过大，请换一张照片。"
        else -> null
    }
}

data class VisionHttpRequest(val url: String, val headers: Map<String, String>, val body: ByteArray) {
    // The body carries the API key's user data and the headers carry the key itself.
    override fun toString() = "VisionHttpRequest(url=$url, headerNames=${headers.keys})"
}

data class VisionHttpResponse(val status: Int, val body: String)

fun interface VisionHttpTransport {
    suspend fun post(request: VisionHttpRequest): VisionHttpResponse
}

/**
 * Where recognition requests go. The endpoint is compiled into the build so the shipped app needs
 * no setup, and the manual-key entry only exists for a build that ships without one.
 *
 * An API key is a bearer credential, so the request may only ever reach the one origin the build
 * was configured for: [chatCompletions] derives the only acceptable URL from a base that is itself
 * checked to be HTTPS, user-info-free and query-free, and [rejectionReason] then insists the request
 * URL is exactly that derived string. Anything else — a redirect, a tampered host, a plain-HTTP
 * base — is refused before the key is attached.
 */
object VisionEndpoint {
    const val CHAT_COMPLETIONS_PATH = "/chat/completions"

    /** Used only by a build whose own endpoint is blank; the manual-key path targets this. */
    const val FALLBACK_BASE_URL = "https://api.deepseek.com"

    /**
     * [baseUrl] plus the chat-completions path, or null when that base cannot be trusted with a key.
     * A trailing slash is normalised, so both `https://host/v1` and `https://host/v1/` are accepted.
     */
    fun chatCompletions(baseUrl: String): String? {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return null
        val url = try { URL(base) } catch (_: Exception) { null } ?: return null
        if (url.protocol != "https") return null
        if (url.userInfo != null) return null
        if (url.host.isNullOrBlank()) return null
        if (url.query != null || url.ref != null) return null
        return base + CHAT_COMPLETIONS_PATH
    }

    /** The endpoint this build is configured to use. */
    fun configuredBaseUrl(): String = BuildConfig.VISION_BASE_URL.trim().ifEmpty { FALLBACK_BASE_URL }

    /** Why [endpoint] must not receive the key, or null when it is the configured endpoint. */
    fun rejectionReason(endpoint: String, baseUrl: String): String? {
        val expected = chatCompletions(baseUrl) ?: return MISCONFIGURED
        return if (endpoint == expected) null else MISCONFIGURED
    }

    private const val MISCONFIGURED = "识别服务地址配置有误，请更新应用。"
}

/**
 * The credentials folded into the build. When both are present the recognition service works with
 * no setup at all and the key-entry screen is never shown; when either is blank the app falls back
 * to the encrypted on-device vault.
 */
object VisionBuiltInCredentials {
    val apiKey: String get() = BuildConfig.VISION_API_KEY.trim()
    val baseUrl: String get() = VisionEndpoint.configuredBaseUrl()
    val available: Boolean get() = apiKey.isNotEmpty() && VisionEndpoint.chatCompletions(baseUrl) != null
}

interface TimetableVisionClient {
    /** Reads the timetable out of [image], or throws [TimetableVisionException]. */
    suspend fun recognize(image: TimetableImage, apiKey: String): Timetable
}

class HttpTimetableVisionClient internal constructor(
    private val transport: VisionHttpTransport,
    private val baseUrl: String = VisionEndpoint.configuredBaseUrl(),
    private val now: () -> Long = System::currentTimeMillis
) : TimetableVisionClient {

    constructor() : this(HttpsVisionTransport())
    constructor(baseUrl: String) : this(HttpsVisionTransport(), baseUrl)

    private val endpoint: String = VisionEndpoint.chatCompletions(baseUrl).orEmpty()

    override suspend fun recognize(image: TimetableImage, apiKey: String): Timetable {
        val key = apiKey.trim()
        if (key.isEmpty()) throw TimetableVisionException(TimetableVisionFailure.MISSING_KEY, "请先填写 API Key，再识别课表。")
        // A key with line breaks would forge extra request headers.
        if (key.length > 512 || key.any { it == '\n' || it == '\r' }) {
            throw TimetableVisionException(TimetableVisionFailure.INVALID_REQUEST, "API Key 格式不正确，请重新复制。")
        }
        VisionImageLimits.rejectionReason(image)?.let {
            throw TimetableVisionException(TimetableVisionFailure.TOO_LARGE, it)
        }
        VisionEndpoint.rejectionReason(endpoint, baseUrl)?.let {
            throw TimetableVisionException(TimetableVisionFailure.INVALID_REQUEST, it)
        }
        val request = VisionHttpRequest(
            url = endpoint,
            headers = linkedMapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json",
                "Authorization" to "Bearer $key"
            ),
            body = requestBody(image)
        )
        val response = try {
            transport.post(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (timeout: SocketTimeoutException) {
            throw TimetableVisionException(TimetableVisionFailure.TIMEOUT, "识别请求超时，请检查网络后重试。", timeout)
        } catch (failure: IOException) {
            throw TimetableVisionException(TimetableVisionFailure.NO_NETWORK, "网络不可用，请连接网络后重试。", failure)
        } catch (failure: TimetableVisionException) {
            throw failure
        } catch (failure: Exception) {
            throw TimetableVisionException(TimetableVisionFailure.NO_NETWORK, "识别请求未能发出，请稍后重试。", failure)
        }
        if (response.status == 200) return TimetableResponseReader.read(response.body, now())
        throw failureFor(response.status)
    }

    private fun failureFor(status: Int): TimetableVisionException = when (status) {
        400 -> TimetableVisionException(TimetableVisionFailure.INVALID_REQUEST, "AI 拒绝了这次请求，请换一张课表照片重试。")
        401, 403 -> TimetableVisionException(TimetableVisionFailure.UNAUTHORIZED, "API Key 无效或已过期，请在设置中重新填写。")
        402 -> TimetableVisionException(TimetableVisionFailure.QUOTA, "API 账户余额不足，请充值后重试。")
        413 -> TimetableVisionException(TimetableVisionFailure.TOO_LARGE, "图片过大，AI 拒绝了这次请求，请换一张照片。")
        429 -> TimetableVisionException(TimetableVisionFailure.RATE_LIMITED, "请求过于频繁，请稍等片刻再试。")
        in 300..399 -> TimetableVisionException(TimetableVisionFailure.SERVER, "AI 服务返回了意外的跳转，请稍后重试。")
        in 500..599 -> TimetableVisionException(TimetableVisionFailure.SERVER, "AI 服务暂时不可用，请稍后重试。")
        else -> TimetableVisionException(TimetableVisionFailure.INVALID_REQUEST, "AI 服务返回 HTTP $status，请稍后重试。")
    }

    private fun requestBody(image: TimetableImage): ByteArray {
        val userContent = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", USER_PROMPT)
            })
            add(JsonObject().apply {
                addProperty("type", "image_url")
                add("image_url", JsonObject().apply {
                    addProperty("url", "data:${image.mimeType};base64,${image.base64()}")
                    // Timetables are dense text, so the original pixels are kept for the encoder.
                    addProperty("detail", "high")
                })
            })
        }
        val messages = JsonArray().apply {
            // Images are only accepted in user messages; a system message must stay text-only.
            add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", SYSTEM_PROMPT)
            })
            add(JsonObject().apply {
                addProperty("role", "user")
                add("content", userContent)
            })
        }
        val root = JsonObject().apply {
            addProperty("model", MODEL)
            // A dense timetable needs the model to actually look; a low effort budget misreads it.
            addProperty("reasoning_effort", REASONING_EFFORT)
            add("messages", messages)
            add("response_format", JsonObject().apply { addProperty("type", "json_object") })
            // Extraction wants the answer, not a chain of thought.
            addProperty("temperature", 0)
            addProperty("max_tokens", MAX_RESPONSE_TOKENS)
            addProperty("stream", false)
        }
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        const val MODEL = "deepseek-v4.1-flash"
        const val REASONING_EFFORT = "high"
        private const val MAX_RESPONSE_TOKENS = 8_192

        private const val USER_PROMPT = "请识别这张课表图片，并按上面的 json 格式输出。"

        // JSON mode requires the word "json" plus a concrete example of the wanted shape.
        private val SYSTEM_PROMPT = """
            你是课表识别助手。请阅读用户提供的图片，判断它是否为课程表，并只输出 json，不要输出解释或 Markdown 代码块。

            输出 json 格式（字段名与类型必须完全一致）：
            {
              "is_timetable": true,
              "term": "2025-2026学年第一学期",
              "courses": [
                {
                  "name": "高等数学",
                  "teacher": "张三",
                  "room": "文理楼301",
                  "weekday": 1,
                  "start_period": 1,
                  "end_period": 2,
                  "start_week": 1,
                  "end_week": 16,
                  "parity": "all"
                }
              ]
            }

            规则：
            1. weekday 是 1-7 的整数，1 表示周一，7 表示周日。
            2. start_period/end_period 是节次整数，start_week/end_week 是周次整数，一律使用阿拉伯数字。
            3. parity 表示单双周：每周都上课填 "all"，只在单周上课填 "odd"，只在双周上课填 "even"。
            4. 连堂（如第 1-2 节）只输出一条记录，用 start_period 与 end_period 表示范围。
            5. 同一门课在同一星期出现多次时，每次占用不同节次就各输出一条记录。
            6. 图片中没有的信息填空字符串，不要编造教师、教室或周次。
            7. 只输出课程表范围内的课程，忽略表头、上课时间、备注等非课程内容。
            8. 如果图片不是课程表，或完全看不清课程内容，输出 {"is_timetable": false, "term": "", "courses": []}。
        """.trimIndent()
    }
}

/**
 * Reads the chat-completion envelope and the model's json payload, and refuses anything that does
 * not satisfy the documented schema. The model's own words are never shown to the user.
 */
internal object TimetableResponseReader {

    fun read(body: String, recognizedAtMillis: Long): Timetable {
        val root = parseObject(body)
            ?: throw malformed("AI 返回的内容无法解析，请重试。")
        val choice = array(root, "choices")?.firstOrNull { it.isJsonObject }?.asJsonObject
            ?: throw malformed("AI 没有返回识别结果，请重试。")
        val finishReason = scalar(choice, "finish_reason")
        val content = objectOrNull(choice, "message")?.let { scalar(it, "content") }
        if (content.isNullOrBlank()) {
            // JSON mode is documented to return an empty content occasionally; retrying is the fix.
            throw TimetableVisionException(TimetableVisionFailure.EMPTY_RESPONSE, "AI 没有返回识别内容，请重试。")
        }
        when (finishReason) {
            "length" -> throw malformed("识别结果过长被截断，请换一张课表照片。")
            "content_filter" -> throw malformed("AI 拒绝识别这张图片，请换一张课表照片。")
        }
        val payload = parseObject(content.removeCodeFence())
            ?: throw malformed("AI 返回的课表 json 无法解析，请重试。")
        val courses = (array(payload, "courses") ?: JsonArray()).filter { it.isJsonObject }.map { course(it.asJsonObject) }
        val declared = boolean(payload.get("is_timetable"))
        if (declared == false || declared == null && courses.isEmpty()) throw notATimetable()
        val timetable = try {
            TimetableValidator.build(scalar(payload, "term"), courses, recognizedAtMillis)
        } catch (failure: TimetableException) {
            if (failure.failure == TimetableFailure.NO_COURSES) throw notATimetable()
            throw TimetableVisionException(TimetableVisionFailure.MALFORMED_RESPONSE, failure.message ?: "识别结果不可用，请重试。")
        }
        return timetable
    }

    private fun course(value: JsonObject) = TimetableCourseDraft(
        name = firstScalar(value, "name", "course", "course_name", "title"),
        teacher = firstScalar(value, "teacher", "instructor"),
        room = firstScalar(value, "room", "classroom", "location"),
        weekday = firstScalar(value, "weekday", "week_day", "day_of_week", "day"),
        startPeriod = firstScalar(value, "start_period", "period_start", "start_section"),
        endPeriod = firstScalar(value, "end_period", "period_end", "end_section"),
        startWeek = firstScalar(value, "start_week", "week_start"),
        endWeek = firstScalar(value, "end_week", "week_end"),
        parity = firstScalar(value, "parity", "week_parity", "week_type")
    )

    private fun firstScalar(value: JsonObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { scalar(value, it) }

    private fun scalar(value: JsonObject, key: String): String? =
        value.get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun objectOrNull(value: JsonObject, key: String): JsonObject? =
        value.get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun array(value: JsonObject, key: String): JsonArray? =
        value.get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun boolean(element: JsonElement?): Boolean? {
        val primitive = element?.takeIf { it.isJsonPrimitive } ?: return null
        return when (primitive.asString.trim().lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> null
        }
    }

    private fun parseObject(text: String): JsonObject? = try {
        JsonReader(StringReader(text.trim().removePrefix("\uFEFF"))).apply { strictness = Strictness.STRICT }.use { reader ->
            val parsed = JsonParser.parseReader(reader)
            if (reader.peek() != JsonToken.END_DOCUMENT) null
            else parsed.takeIf { it.isJsonObject }?.asJsonObject
        }
    } catch (_: Exception) {
        null
    }

    /** JSON mode still wraps output in a code fence now and then. */
    private fun String.removeCodeFence(): String {
        val trimmed = trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed.removePrefix("```").removePrefix("json").removePrefix("JSON")
            .removeSuffix("```").trim()
    }

    private fun malformed(message: String) =
        TimetableVisionException(TimetableVisionFailure.MALFORMED_RESPONSE, message)

    private fun notATimetable() =
        TimetableVisionException(TimetableVisionFailure.NOT_A_TIMETABLE, "这张图片里没有识别到课表，请选择一张课程表照片。")
}

/**
 * Blocking HTTPS off the main thread. The platform's own TLS stack is used, redirects are never
 * followed (the API key must not be replayed to another origin), and cancellation disconnects.
 */
internal class HttpsVisionTransport(
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 90_000
) : VisionHttpTransport {

    override suspend fun post(httpRequest: VisionHttpRequest): VisionHttpResponse =
        suspendCancellableCoroutine { continuation ->
            val active = AtomicReference<HttpsURLConnection?>(null)
            fun disconnectAsync() {
                val connection = active.getAndSet(null) ?: return
                Dispatchers.IO.dispatch(continuation.context, Runnable { disconnectSafely(connection) })
            }
            continuation.invokeOnCancellation { disconnectAsync() }
            Dispatchers.IO.dispatch(continuation.context, Runnable {
                var connection: HttpsURLConnection? = null
                try {
                    if (!continuation.isActive) return@Runnable
                    val url = URL(httpRequest.url)
                    if (url.protocol != "https") throw IOException("vision endpoint must use https")
                    connection = (url.openConnection() as HttpsURLConnection).apply {
                        requestMethod = "POST"
                        instanceFollowRedirects = false
                        connectTimeout = connectTimeoutMillis
                        readTimeout = readTimeoutMillis
                        useCaches = false
                        doInput = true
                        doOutput = true
                        httpRequest.headers.forEach { (name, value) -> setRequestProperty(name, value) }
                    }
                    val activeConnection = connection
                    active.set(activeConnection)
                    if (!continuation.isActive) {
                        disconnectAsync()
                        return@Runnable
                    }
                    activeConnection.outputStream.use { it.write(httpRequest.body) }
                    val status = activeConnection.responseCode
                    val stream = if (status in 200..299) activeConnection.inputStream else activeConnection.errorStream
                    val body = stream?.use { it.readBounded(MAX_RESPONSE_BYTES) } ?: ByteArray(0)
                    if (continuation.isActive) continuation.resume(VisionHttpResponse(status, String(body, Charsets.UTF_8)))
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                } finally {
                    connection?.let { if (active.compareAndSet(it, null)) disconnectSafely(it) }
                }
            })
        }

    private fun InputStream.readBounded(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) throw IOException("vision response exceeded $limit bytes")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun disconnectSafely(connection: HttpsURLConnection) {
        try { connection.disconnect() } catch (_: Exception) { }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 1_048_576
    }
}
