package cn.gxnu.campus.network

import cn.gxnu.campus.BuildConfig
import cn.gxnu.campus.core.TIMETABLE_MAX_PERIODS
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
import kotlinx.coroutines.withTimeoutOrNull

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
    /** Reported from measurement: the model scores 100% at a photo's native 1700 px and 68% at 1024 px. */
    const val TARGET_SIDE_PX = 4_096

    /** The endpoint refuses anything longer than this on either side, so nothing may exceed it. */
    const val MAX_SIDE_PX = 8_192
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
        var refusal: TimetableVisionException? = null
        for (model in MODELS) {
            try {
                // Each model gets its own slice of the caller's budget. Without one, a model that hangs
                // holds the ladder until the outer watchdog fires and the fallback never gets to answer
                // at all — exactly the slow case a second model exists for.
                return withTimeoutOrNull(ATTEMPT_TIMEOUT_MILLIS) { request(model, image, key) }
                    ?: throw TimetableVisionException(
                        TimetableVisionFailure.TIMEOUT, "识别请求超时，请检查网络后重试。"
                    )
            } catch (failure: TimetableVisionException) {
                // A second model only helps when the first never delivered a verdict. A model that
                // looked at the photo and answered — even "this is not a timetable" — is believed.
                if (failure.failure !in WORTH_ANOTHER_MODEL) throw failure
                refusal = failure
            }
        }
        throw refusal ?: TimetableVisionException(TimetableVisionFailure.SERVER, "AI 服务暂时不可用，请稍后重试。")
    }

    private suspend fun request(model: String, image: TimetableImage, key: String): Timetable {
        val httpRequest = VisionHttpRequest(
            url = endpoint,
            headers = linkedMapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json",
                "Authorization" to "Bearer $key"
            ),
            body = requestBody(model, image)
        )
        val response = try {
            transport.post(httpRequest)
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

    private fun requestBody(model: String, image: TimetableImage): ByteArray {
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
            addProperty("model", model)
            // Only the deepseek route advertises a reasoning budget; the default model answers directly.
            if (model in REASONING_MODELS) addProperty("reasoning_effort", REASONING_EFFORT)
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
        const val MODEL = "glm-5v-turbo"
        const val REASONING_EFFORT = "high"

        /**
         * Tried in order. Recorded against one real GXNU timetable photo: the default route answers in
         * 11-26 s with `finish_reason=stop` and zero reasoning tokens, while the deepseek route spends
         * the entire output budget thinking (`completion_thinking_tokens=8192`) and returns empty
         * content at every `reasoning_effort` — so it is kept only as a second chance for a request the
         * default route could not complete at all.
         */
        val MODELS = listOf("glm-5v-turbo", "deepseek-v4.1-flash")
        private val REASONING_MODELS = setOf("deepseek-v4.1-flash")

        /**
         * Failures that mean "no verdict was reached", so a different model is worth one more upload.
         * A truncated or malformed answer is deliberately absent: the model did read the photo and
         * answered badly, and swapping models is not a fix for that. Neither is a rate limit, which
         * the retry would meet again.
         */
        private val WORTH_ANOTHER_MODEL = setOf(
            TimetableVisionFailure.EMPTY_RESPONSE,
            TimetableVisionFailure.TIMEOUT,
            TimetableVisionFailure.SERVER
        )

        /**
         * Per model, so two attempts fit inside the caller's watchdog. The endpoint's own gateway
         * drops a request at 60 s, so an attempt that reaches this bound has already failed.
         */
        private const val ATTEMPT_TIMEOUT_MILLIS = 40_000L
        private const val MAX_RESPONSE_TOKENS = 8_192

        private const val USER_PROMPT = "请识别这张课表图片，并按上面的 json 格式输出。"

        /**
         * JSON mode requires the word "json" plus a concrete example of the wanted shape.
         *
         * The three reading steps are what carry the accuracy: naming the day columns and the period
         * rows before extracting anything forces both axes to be read, and the row labels are spelled
         * out because GXNU numbers its periods 上午1-5 / 下午6-9 / 晚上10-13 rather than 1-13, which a
         * model otherwise renumbers from 1. Measured on a real timetable: naming them took the same
         * model from 83% to 100% field accuracy. `day_headers` and `period_labels` are asked for as a
         * priming transcript; the reader ignores them.
         */
        private val SYSTEM_PROMPT = """
            你是课表识别助手。请阅读用户提供的图片，判断它是否为课程表，并只输出 json，不要输出解释或 Markdown 代码块。

            输出 json 格式（字段名与类型必须完全一致）：
            {
              "is_timetable": true,
              "term": "2026-2027秋学期",
              "day_headers": ["星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"],
              "period_labels": ["无节次", "上午1", "上午2", "上午3", "上午4", "上午5", "下午6", "下午7", "下午8", "下午9", "晚上10", "晚上11", "晚上12", "晚上13"],
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

            按下面三步来读，不要跳步：

            第一步，先数清表格有几列、每列是什么。从左到右念出每个星期列的表头文字，原样写进 day_headers 数组。表头可能写成「星期一 [Monday]」这样的中英文两行，也可能因为字小而换行。这种课表常常有 6 到 7 个星期列（含最右边的星期六、星期日），不要默认只有 5 列，漏掉最右边的列会直接导致后面判错列。

            第二步，从上到下念出最左边一列的节次行标签，原样写进 period_labels 数组。典型顺序是：无节次、上午1、上午2、上午3、上午4、上午5、下午6、下午7、下午8、下午9、晚上10、晚上11、晚上12、晚上13。**节次只能是 1 到 13 的整数，不要写 0**。节次编号一律取标签末尾的阿拉伯数字：「上午3」就是第 3 节，「下午6」就是第 6 节，「晚上10」就是第 10 节，与上午/下午/晚上无关，不要重新从 1 数。最上面那一行「无节次」不写节次号，落在这一行的课 start_period 和 end_period 都填 1，也就是从第 1 节开始。

            第三步，逐个课程色块清点，每个色块出一条记录：
            - weekday 取它所在的星期列在 day_headers 里的位置（第 1 列是 1，第 7 列是 7）。
            - start_period / end_period 用它上下边缘盖住的行标签来定：从色块顶边所在的那一行，到色块底边所在的那一行。色块是纵向合并的，常常连盖 3 到 4 行——尤其下午和晚上那些高格子，例如从「下午6」一直盖到「下午9」就要输出 6-9，从「晚上10」盖到「晚上13」就要输出 10-13。先看清底边压在哪一行，再写 end_period，不要只按色块第一行写成两行。
            - 周次读方括号：课程文字形如「课程名 [2-18周] 教师名」，[2-18周] 就是 start_week 2、end_week 18；方括号后面的中文人名是 teacher。name 只保留课程名本身，不要把周次括号和人名写进 name。周的后面如果再跟着一对空的方括号，那只是空教室，room 填空字符串。
            - 同一门课在不同星期或不同节次各算一条记录；一个色块覆盖多个节次也只出一条记录，用 start_period 与 end_period 表示范围。
            - parity 表示单双周：每周都上课填 "all"，只在单周上课填 "odd"，只在双周上课填 "even"。
            - room 没有写就填空字符串，不要编造教师、教室或周次。

            只输出课程表范围内的课程，忽略学期选择框、查询/打印按钮、表头和备注等非课程内容。如果图片不是课程表，或完全看不清课程内容，输出 {"is_timetable": false, "term": "", "day_headers": [], "period_labels": [], "courses": []}。
        """.trimIndent()
    }
}

/**
 * Reads the chat-completion envelope and the model's json payload, and refuses anything that does
 * not satisfy the documented schema. The model's own words are never shown to the user.
 *
 * The model is not a typed producer: it writes a number as either a json number or a string, spells
 * weekdays out in words, packs a whole 第3-4节 range into a single key, and copies a blank table cell
 * out as a bare bracket pair. Everything it might say is normalised here, so [TimetableValidator]
 * only ever sees the documented shape. A period the model reads as 0 — the unnumbered 无节次 row of a
 * printed sheet — is carried through as 0 and becomes 第1节 in the validator, which is the one place
 * that owns the 1..13 range.
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
        val parsed = (array(payload, "courses") ?: JsonArray()).filter { it.isJsonObject }
            .map { course(it.asJsonObject) }
        val courses = resolveUnnumbered(parsed)
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

    private fun course(value: JsonObject): ParsedCourse {
        val rawStart = firstText(value, "start_period", "period_start", "start_section")
        val rawEnd = firstText(value, "end_period", "period_end", "end_section")
        val startWeek = firstText(value, "start_week", "week_start")
        val endWeek = firstText(value, "end_week", "week_end")
        val periods = span(periodText(rawStart), periodText(rawEnd))
        val weeks = span(startWeek, endWeek)
        return ParsedCourse(
            draft = TimetableCourseDraft(
                name = firstText(value, "name", "course", "course_name", "title"),
                teacher = firstCleanText(value, "teacher", "instructor"),
                room = firstCleanText(value, "room", "classroom", "location"),
                weekday = firstText(value, "weekday", "week_day", "day_of_week", "day")?.let(::normalizeWeekday),
                startPeriod = periods.first,
                endPeriod = periods.second,
                startWeek = weeks.first,
                endWeek = weeks.second,
                // An explicit 单双周 field wins; otherwise the only place it was stated is the week range
                // itself, which [span] may just have taken apart.
                parity = firstText(value, "parity", "week_parity", "week_type")?.let(::normalizeParity)
                    ?: parityPrintedIn(startWeek, endWeek)
            ),
            unnumbered = saysNothingAboutAPeriod(rawStart) && saysNothingAboutAPeriod(rawEnd)
        )
    }

    /**
     * A course from the sheet's unnumbered row starts at the first period — unless that weekday's
     * 上午1 slot is already taken, in which case writing a second course over it would make
     * [TimetableValidator] reject the whole timetable. Losing every course is far worse than moving
     * one, so the first period nothing else on that day occupies is used instead, and the course is
     * still there for the user to place by hand.
     */
    private fun resolveUnnumbered(parsed: List<ParsedCourse>): List<TimetableCourseDraft> {
        val taken = parsed.filterNot { it.unnumbered }.mapNotNull { entry ->
            val day = entry.draft.weekday?.let(::normalizeWeekday)?.toIntOrNull() ?: return@mapNotNull null
            val range = occupiedRange(entry.draft) ?: return@mapNotNull null
            day to range
        }
        return parsed.map { entry ->
            if (!entry.unnumbered) return@map entry.draft
            val day = entry.draft.weekday?.let(::normalizeWeekday)?.toIntOrNull()
            val free = if (day == null) FIRST_PERIOD else firstFreePeriod(day, taken).toString()
            entry.draft.copy(startPeriod = free, endPeriod = free)
        }
    }

    /** The period range one already-numbered draft holds, in the validator's own 1..20 terms. */
    private fun occupiedRange(draft: TimetableCourseDraft): IntRange? {
        val ends = span(draft.startPeriod, draft.endPeriod) ?: return null
        val first = ends.first?.toIntOrNull()?.coerceIn(1, TIMETABLE_MAX_PERIODS) ?: return null
        val last = ends.second?.toIntOrNull()?.coerceIn(1, TIMETABLE_MAX_PERIODS) ?: return null
        return if (first <= last) first..last else last..first
    }

    private fun firstFreePeriod(day: Int, taken: List<Pair<Int, IntRange>>): Int {
        val busy = taken.filter { it.first == day }.flatMap { (_, range) -> range.toList() }.toSet()
        return (1..TIMETABLE_MAX_PERIODS).firstOrNull { it !in busy } ?: FIRST_PERIOD.toInt()
    }

    /**
     * True when the model stated no usable period number: it left the field out, copied the sheet's own
     * 无节次 label out, or wrote the 0 that row carries. All three mean the same thing here.
     */
    private fun saysNothingAboutAPeriod(raw: String?): Boolean {
        val text = raw?.takeIf { it.isNotBlank() } ?: return true
        val digits = normalizeDigits(text)
        if (!NUMBER.containsMatchIn(digits)) return true
        return digits.filter(Char::isDigit).toIntOrNull() == 0
    }

    private data class ParsedCourse(val draft: TimetableCourseDraft, val unnumbered: Boolean)

    /**
     * The first spelling of the field that actually says something; an empty string says nothing.
     */
    private fun firstText(value: JsonObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { scalar(value, it)?.takeIf { text -> text.isNotBlank() } }

    /**
     * A period field the model answered with the row's own label rather than a number. The school's
     * unnumbered top row is labelled 无节次, and a course that sits in it starts at the first period —
     * an unreadable label must not cost the user the whole timetable.
     */
    private fun periodText(value: String?): String? {
        val text = value?.takeIf { it.isNotBlank() } ?: return null
        return if (NUMBER.containsMatchIn(normalizeDigits(text))) text else FIRST_PERIOD
    }

    /** [firstText] plus the empty-cell cleanup, for the fields that are only ever shown to the user. */
    private fun firstCleanText(value: JsonObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { scalar(value, it)?.let(::cleanedText) }

    /**
     * The model copies a blank table cell out as "[]", "（）" or "[ ]" and pads a missing one with a
     * dash or 无, and either would reach the user verbatim. Only the display-only fields are cleaned:
     * a blank course name is a real defect that must keep failing validation.
     */
    private fun cleanedText(value: String): String? {
        val text = value.trim()
        if (text.isEmpty()) return null
        if (text.lowercase() in EMPTY_CELL_WORDS) return null
        if (text.all { it.isWhitespace() || it in BRACKET_CHARS }) return null
        return text
    }

    /**
     * The two ends of a period or week span. A range written into a single key ("第3-4节", "2-18周")
     * is split into its ends, but only when the sibling key states nothing, so a properly separated
     * pair is never second-guessed. A lone end is mirrored: a course taught "in these weeks" is
     * better read as a one-point span than dropped. A 0 is carried through rather than treated as
     * missing — [TimetableValidator] owns what the period range means, and it turns 0 into 第1节.
     */
    private fun span(start: String?, end: String?): Pair<String?, String?> {
        if (end == null) rangeEnds(start)?.let { return it }
        if (start == null) rangeEnds(end)?.let { return it }
        val first = start?.let { numberStatedBy(it, atEnd = false) }
        val last = end?.let { numberStatedBy(it, atEnd = true) }
        return when {
            first != null -> first to (last ?: first)
            last != null -> last to last
            else -> null to null
        }
    }

    private fun rangeEnds(text: String?): Pair<String, String>? =
        RANGE.find(normalizeDigits(text.orEmpty()))?.let { it.groupValues[1] to it.groupValues[2] }

    /** The number one side states: the far end of a range for an end field, its near end otherwise. */
    private fun numberStatedBy(text: String, atEnd: Boolean): String? {
        val digits = normalizeDigits(text)
        RANGE.find(digits)?.let { return if (atEnd) it.groupValues[2] else it.groupValues[1] }
        return NUMBER.find(digits)?.value
    }

    /** Numbers are kept as they are; 星期一 / 周一 / 礼拜天 / Monday / Sun map onto the 1-7 contract. */
    private fun normalizeWeekday(value: String): String? {
        val text = normalizeDigits(value.trim())
        CHINESE_WEEKDAY.find(text)?.let { match -> return WEEKDAY_CHARS[match.groupValues[1]]?.toString() }
        val lower = text.lowercase()
        val english = WEEKDAY_PREFIXES.indexOfFirst { lower.contains(it) }
        if (english >= 0) return (english + 1).toString()
        return NUMBER.find(text)?.value
    }

    /** 每周 / 全周 / 每周都上 all mean every week; an unknown word is left to [parityPrintedIn]. */
    private fun normalizeParity(value: String): String? {
        val text = value.trim().lowercase()
        return when {
            text.any { it in ODD_MARKERS } || text.contains("odd") || text.contains("single") -> "odd"
            text.any { it in EVEN_MARKERS } || text.contains("even") || text.contains("double") -> "even"
            text.any { it in ALL_MARKERS } || text.contains("all") || text.contains("every") ||
                text.contains("each") -> "all"
            else -> null
        }
    }

    /** 单双周 is usually printed inside the week range on a Chinese timetable. */
    private fun parityPrintedIn(start: String?, end: String?): String? {
        val text = start.orEmpty() + end.orEmpty()
        return when {
            text.any { it in ODD_MARKERS } -> "odd"
            text.any { it in EVEN_MARKERS } -> "even"
            else -> null
        }
    }

    /** Full-width digits come out of Chinese text often enough to be worth folding. */
    private fun normalizeDigits(text: String): String =
        text.map { symbol -> if (symbol in '０'..'９') '0' + (symbol - '０') else symbol }.joinToString("")

    private const val BRACKET_CHARS = "[](){}<>（）【】〔〕「」『』〈〉《》〖〗［］｛｝＜＞"

    /** What an unreadable period label means: the school's unnumbered row is its first period. */
    private const val FIRST_PERIOD = "1"

    private val EMPTY_CELL_WORDS = setOf(
        "-", "--", "—", "——", "–", "－", "/", "／", "、", "?", "？",
        "无", "暂无", "没有", "未知", "空", "null", "none", "n/a", "unknown"
    )

    private val ODD_MARKERS = "单奇".toCharArray()
    private val EVEN_MARKERS = "双偶".toCharArray()
    private val ALL_MARKERS = "每全都无".toCharArray()

    private val NUMBER = Regex("\\d{1,3}")
    private val RANGE = Regex("(\\d{1,3})\\s*[-~～/至到—–]\\s*(\\d{1,3})")
    private val CHINESE_WEEKDAY = Regex("(?:星期|周|礼拜)\\s*([一二三四五六日天1-7])")
    private val WEEKDAY_PREFIXES = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
    private val WEEKDAY_CHARS = mapOf(
        "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5, "六" to 6, "日" to 7, "天" to 7,
        "1" to 1, "2" to 2, "3" to 3, "4" to 4, "5" to 5, "6" to 6, "7" to 7
    )

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
