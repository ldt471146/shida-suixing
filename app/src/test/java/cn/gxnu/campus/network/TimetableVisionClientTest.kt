package cn.gxnu.campus.network

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fictional fixtures only. The HTTP layer is faked, so nothing here reaches the network. */
class TimetableVisionClientTest {

    private class RecordingTransport(private val response: () -> VisionHttpResponse) : VisionHttpTransport {
        var request: VisionHttpRequest? = null
        override suspend fun post(request: VisionHttpRequest): VisionHttpResponse {
            this.request = request
            return response()
        }
    }

    private fun envelope(content: String, finishReason: String = "stop"): String = JsonObject().apply {
        addProperty("id", "fixture-completion")
        addProperty("object", "chat.completion")
        addProperty("model", MODEL)
        add("choices", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("index", 0)
                addProperty("finish_reason", finishReason)
                add("message", JsonObject().apply {
                    addProperty("role", "assistant")
                    addProperty("content", content)
                })
            })
        })
    }.toString()

    private fun client(transport: VisionHttpTransport) = HttpTimetableVisionClient(transport, now = { FIXED_TIME })

    private fun client(status: Int, body: String = "{}") = client(RecordingTransport { VisionHttpResponse(status, body) })

    private suspend fun refusalFrom(status: Int, body: String = "{}"): TimetableVisionException {
        val client = client(status, body)
        return try {
            client.recognize(image(), KEY)
            throw AssertionError("expected HTTP $status to be refused")
        } catch (failure: TimetableVisionException) {
            failure
        }
    }

    private fun image(bytes: ByteArray = IMAGE_BYTES, mimeType: String = "image/jpeg") = TimetableImage(bytes, mimeType)

    @Test fun requestMatchesTheDocumentedContract() = runTest {
        val transport = RecordingTransport { VisionHttpResponse(200, envelope(VALID_PAYLOAD)) }
        val timetable = client(transport).recognize(image(), KEY)

        val request = requireNotNull(transport.request)
        assertEquals("https://api.deepseek.com/chat/completions", request.url)
        assertEquals("Bearer $KEY", request.headers["Authorization"])
        assertEquals("application/json", request.headers["Content-Type"])

        val body = JsonParser.parseString(String(request.body, Charsets.UTF_8)).asJsonObject
        assertEquals("deepseek-flash", body.get("model").asString)
        assertEquals("json_object", body.getAsJsonObject("response_format").get("type").asString)
        assertFalse("streaming is not used", body.get("stream").asBoolean)

        val messages = body.getAsJsonArray("messages")
        assertEquals(2, messages.size())
        val system = messages[0].asJsonObject
        assertEquals("system", system.get("role").asString)
        // Images are only legal in user messages, so the system message carries plain text.
        assertTrue(system.get("content").isJsonPrimitive)
        assertTrue("json mode needs the word json in the prompt", system.get("content").asString.contains("json"))

        val user = messages[1].asJsonObject
        assertEquals("user", user.get("role").asString)
        val blocks = user.getAsJsonArray("content")
        assertEquals("text", blocks[0].asJsonObject.get("type").asString)
        val imageBlock = blocks[1].asJsonObject
        assertEquals("image_url", imageBlock.get("type").asString)
        val url = imageBlock.getAsJsonObject("image_url").get("url").asString
        assertEquals("high", imageBlock.getAsJsonObject("image_url").get("detail").asString)
        assertEquals("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(IMAGE_BYTES), url)

        assertEquals("2025-2026学年第一学期", timetable.term)
        assertEquals(2, timetable.courseCount)
        assertEquals(FIXED_TIME, timetable.recognizedAtMillis)
        assertEquals(1, timetable.courses.first().weekday)
        assertEquals(3, timetable.courses.last().weekday)
    }

    @Test fun theApiKeyTravelsOnlyInTheAuthorizationHeader() = runTest {
        val transport = RecordingTransport { VisionHttpResponse(200, envelope(VALID_PAYLOAD)) }
        client(transport).recognize(image(), "  $KEY  ")
        val request = requireNotNull(transport.request)
        assertEquals("Bearer $KEY", request.headers["Authorization"])
        assertFalse(String(request.body, Charsets.UTF_8).contains(KEY))
        // The request's own description must stay safe to log.
        assertFalse(request.toString().contains(KEY))
        assertFalse(request.toString().contains("base64"))
    }

    @Test fun aMissingKeyIsRefusedBeforeAnyRequestIsSent() = runTest {
        val transport = RecordingTransport { VisionHttpResponse(200, envelope(VALID_PAYLOAD)) }
        val failure = try {
            client(transport).recognize(image(), "   ")
            throw AssertionError("expected a missing key to be refused")
        } catch (failure: TimetableVisionException) {
            failure
        }
        assertEquals(TimetableVisionFailure.MISSING_KEY, failure.failure)
        assertNull(transport.request)
    }

    @Test fun aKeyWithLineBreaksIsRefused() = runTest {
        val transport = RecordingTransport { VisionHttpResponse(200, envelope(VALID_PAYLOAD)) }
        val failure = try {
            client(transport).recognize(image(), "$KEY\r\nX-Injected: 1")
            throw AssertionError("expected a forged header to be refused")
        } catch (failure: TimetableVisionException) {
            failure
        }
        assertEquals(TimetableVisionFailure.INVALID_REQUEST, failure.failure)
        assertNull(transport.request)
    }

    @Test fun anUnauthorisedKeyIsReportedAsSuch() = runTest {
        assertEquals(TimetableVisionFailure.UNAUTHORIZED, refusalFrom(401).failure)
        assertEquals(TimetableVisionFailure.UNAUTHORIZED, refusalFrom(403).failure)
        assertTrue(refusalFrom(401).message!!.contains("API Key"))
    }

    @Test fun everyErrorStatusMapsToItsOwnFailure() = runTest {
        assertEquals(TimetableVisionFailure.INVALID_REQUEST, refusalFrom(400).failure)
        assertEquals(TimetableVisionFailure.QUOTA, refusalFrom(402).failure)
        assertEquals(TimetableVisionFailure.TOO_LARGE, refusalFrom(413).failure)
        assertEquals(TimetableVisionFailure.RATE_LIMITED, refusalFrom(429).failure)
        assertEquals(TimetableVisionFailure.SERVER, refusalFrom(500).failure)
        assertEquals(TimetableVisionFailure.SERVER, refusalFrom(503).failure)
        // A redirect would replay the key at another origin, so it is refused outright.
        assertEquals(TimetableVisionFailure.SERVER, refusalFrom(302).failure)
        assertEquals(TimetableVisionFailure.INVALID_REQUEST, refusalFrom(418).failure)
    }

    @Test fun aTimeoutIsReportedAsATimeout() = runTest {
        val failure = try {
            client(VisionHttpTransport { throw SocketTimeoutException("fixture timeout") }).recognize(image(), KEY)
            throw AssertionError("expected a timeout")
        } catch (failure: TimetableVisionException) {
            failure
        }
        assertEquals(TimetableVisionFailure.TIMEOUT, failure.failure)
    }

    @Test fun noNetworkIsReportedAsNoNetwork() = runTest {
        val failure = try {
            client(VisionHttpTransport { throw IOException("fixture offline") }).recognize(image(), KEY)
            throw AssertionError("expected a network failure")
        } catch (failure: TimetableVisionException) {
            failure
        }
        assertEquals(TimetableVisionFailure.NO_NETWORK, failure.failure)
    }

    @Test fun anImageTheEndpointWouldRefuseIsNeverUploaded() = runTest {
        val transport = RecordingTransport { VisionHttpResponse(200, envelope(VALID_PAYLOAD)) }
        val failure = try {
            client(transport).recognize(image(mimeType = "image/bmp"), KEY)
            throw AssertionError("expected an unsupported format to be refused")
        } catch (failure: TimetableVisionException) {
            failure
        }
        assertEquals(TimetableVisionFailure.TOO_LARGE, failure.failure)
        assertNull(transport.request)
    }

    @Test fun anOversizedImageIsRefusedLocally() = runTest {
        val transport = RecordingTransport { VisionHttpResponse(200, envelope(VALID_PAYLOAD)) }
        val failure = try {
            // Just over the 32 MiB base64 ceiling, so the upload must not even start.
            client(transport).recognize(image(bytes = ByteArray(26 * 1024 * 1024)), KEY)
            throw AssertionError("expected an oversized image to be refused")
        } catch (failure: TimetableVisionException) {
            failure
        }
        assertEquals(TimetableVisionFailure.TOO_LARGE, failure.failure)
        assertNull(transport.request)
    }

    @Test fun anEmptyImageIsRefused() = runTest {
        val failure = try {
            client(200).recognize(image(bytes = ByteArray(0)), KEY)
            throw AssertionError("expected an empty image to be refused")
        } catch (failure: TimetableVisionException) {
            failure
        }
        assertEquals(TimetableVisionFailure.TOO_LARGE, failure.failure)
    }

    @Test fun downscalingHonoursTheLongSideLimit() {
        // Nothing is enlarged, and the long side never exceeds the requested box.
        assertEquals(VisionImageLimits.ImageSize(1200, 900), VisionImageLimits.fitInside(1200, 900, 8192))
        assertEquals(VisionImageLimits.ImageSize(8192, 6144), VisionImageLimits.fitInside(12000, 9000, 8192))
        assertEquals(VisionImageLimits.ImageSize(2048, 1536), VisionImageLimits.fitInside(12000, 9000, 2048))
        assertEquals(VisionImageLimits.ImageSize(1536, 2048), VisionImageLimits.fitInside(1536, 2048, 2048))
        assertEquals(VisionImageLimits.ImageSize(1, 1), VisionImageLimits.fitInside(0, 900, 2048))
        assertEquals(VisionImageLimits.ImageSize(8192, 1), VisionImageLimits.fitInside(8192, 1, 8192))
        // 24 MiB of bytes is exactly the documented 32 MiB base64 ceiling.
        assertEquals(33554432, VisionImageLimits.base64Length(25165824))
    }

    @Test fun aResponseWithAnUnusableCourseIsRefused() = runTest {
        val payload = """
            {"is_timetable":true,"term":"","courses":[
              {"name":"高等数学-fixture","teacher":"教师甲","room":"文理楼201",
               "weekday":19,"start_period":1,"end_period":2,"start_week":1,"end_week":16}
            ]}
        """.trimIndent()
        assertEquals(TimetableVisionFailure.MALFORMED_RESPONSE, refusalFrom(200, envelope(payload)).failure)
    }

    @Test fun aConflictingCourseListIsRefused() = runTest {
        val payload = """
            {"is_timetable":true,"term":"","courses":[
              {"name":"A-fixture","weekday":2,"start_period":1,"end_period":2,"start_week":1,"end_week":16},
              {"name":"B-fixture","weekday":2,"start_period":2,"end_period":3,"start_week":1,"end_week":16}
            ]}
        """.trimIndent()
        assertEquals(TimetableVisionFailure.MALFORMED_RESPONSE, refusalFrom(200, envelope(payload)).failure)
    }

    private companion object {
        const val KEY = "sk-fixture-key"
        const val MODEL = "deepseek-flash"
        const val FIXED_TIME = 1_700_000_000_000L
        val IMAGE_BYTES = ByteArray(64) { index -> (index % 7).toByte() }
        val VALID_PAYLOAD = """
            {"is_timetable":true,"term":"2025-2026学年第一学期","courses":[
              {"name":"高等数学-fixture","teacher":"教师甲","room":"文理楼201",
               "weekday":1,"start_period":1,"end_period":2,"start_week":1,"end_week":16},
              {"name":"大学英语-fixture","teacher":"教师乙","room":"外语楼305",
               "weekday":3,"start_period":3,"end_period":4,"start_week":2,"end_week":16}
            ]}
        """.trimIndent()
    }
}
