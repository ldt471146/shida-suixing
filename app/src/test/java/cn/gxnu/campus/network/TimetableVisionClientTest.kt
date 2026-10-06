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

    private fun client(transport: VisionHttpTransport) =
        HttpTimetableVisionClient(transport, baseUrl = BASE_URL, now = { FIXED_TIME })

    private fun client(status: Int, body: String = "{}") = client(RecordingTransport { VisionHttpResponse(status, body) })

    private suspend fun refusal(client: TimetableVisionClient, apiKey: String = KEY): TimetableVisionException =
        try {
            client.recognize(image(), apiKey)
            throw AssertionError("expected the request to be refused")
        } catch (failure: TimetableVisionException) {
            failure
        }

    private fun image(bytes: ByteArray = IMAGE_BYTES, mimeType: String = "image/jpeg") = TimetableImage(bytes, mimeType)

    @Test fun requestMatchesTheDocumentedContract() = runTest {
        val transport = RecordingTransport { VisionHttpResponse(200, envelope(VALID_PAYLOAD)) }
        val timetable = client(transport).recognize(image(), KEY)

        val request = requireNotNull(transport.request)
        // The endpoint is the configured base plus the chat-completions path.
        assertEquals(ENDPOINT, request.url)
        assertEquals("Bearer $KEY", request.headers["Authorization"])
        assertEquals("application/json", request.headers["Content-Type"])

        val body = JsonParser.parseString(String(request.body, Charsets.UTF_8)).asJsonObject
        assertEquals("deepseek-v4.1-flash", body.get("model").asString)
        assertEquals("high", body.get("reasoning_effort").asString)
        assertEquals("json_object", body.getAsJsonObject("response_format").get("type").asString)
        assertEquals(0, body.get("temperature").asInt)
        assertEquals(8192, body.get("max_tokens").asInt)
        assertFalse("streaming is not used", body.get("stream").asBoolean)

        val messages = body.getAsJsonArray("messages")
        assertEquals(2, messages.size())
        val system = messages[0].asJsonObject
        assertEquals("system", system.get("role").asString)
        // Images are only legal in user messages, so the system message carries plain text.
        assertTrue(system.get("content").isJsonPrimitive)
        val systemPrompt = system.get("content").asString
        assertTrue("json mode needs the word json in the prompt", systemPrompt.contains("json"))
        assertTrue("the example must show every field it asks for", systemPrompt.contains("parity"))

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

    @Test fun theEndpointIsDerivedFromTheBaseUrl() {
        assertEquals(ENDPOINT, VisionEndpoint.chatCompletions(BASE_URL))
        // A trailing slash is the same address.
        assertEquals(ENDPOINT, VisionEndpoint.chatCompletions("$BASE_URL/"))
        assertEquals(ENDPOINT, VisionEndpoint.chatCompletions("  $BASE_URL  "))
        // The documented legacy shape, with no path of its own, still resolves.
        assertEquals(
            "https://api.deepseek.com/chat/completions",
            VisionEndpoint.chatCompletions(VisionEndpoint.FALLBACK_BASE_URL)
        )
        assertNull(VisionEndpoint.chatCompletions(""))
        assertNull(VisionEndpoint.chatCompletions("   "))
    }

    @Test fun aBaseThatCouldNotHoldTheKeyIsRefusedBeforeAnyRequestIsSent() = runTest {
        val unusable = listOf(
            // Plain HTTP would put the bearer key on the wire in the clear.
            "http://vision.example.test/v1",
            // User info in the URL is a credential-smuggling shape, never a real endpoint.
            "https://user:secret@vision.example.test/v1",
            // A query changes what is addressed and is not part of the documented contract.
            "https://vision.example.test/v1?target=elsewhere",
            "not a url at all",
            ""
        )
        for (base in unusable) {
            val transport = RecordingTransport { VisionHttpResponse(200, envelope(VALID_PAYLOAD)) }
            val failure = refusal(HttpTimetableVisionClient(transport, baseUrl = base, now = { FIXED_TIME }))
            assertEquals("base should be refused: $base", TimetableVisionFailure.INVALID_REQUEST, failure.failure)
            assertNull("no request may be sent for: $base", transport.request)
        }
    }

    @Test fun anEndpointThatIsNotTheConfiguredOneIsRefused() {
        assertNull(VisionEndpoint.rejectionReason(ENDPOINT, BASE_URL))
        // Any other host or path could receive the key, so it is refused outright.
        assertTrue(VisionEndpoint.rejectionReason("https://elsewhere.test/v1/chat/completions", BASE_URL) != null)
        assertTrue(VisionEndpoint.rejectionReason("https://vision.example.test/v2/chat/completions", BASE_URL) != null)
        assertTrue(VisionEndpoint.rejectionReason("", BASE_URL) != null)
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
        val failure = refusal(client(transport), apiKey = "   ")
        assertEquals(TimetableVisionFailure.MISSING_KEY, failure.failure)
        assertNull(transport.request)
    }

    @Test fun aKeyWithLineBreaksIsRefused() = runTest {
        val transport = RecordingTransport { VisionHttpResponse(200, envelope(VALID_PAYLOAD)) }
        val failure = refusal(client(transport), apiKey = "$KEY\r\nX-Injected: 1")
        assertEquals(TimetableVisionFailure.INVALID_REQUEST, failure.failure)
        assertNull(transport.request)
    }

    @Test fun anUnauthorisedKeyIsReportedAsSuch() = runTest {
        assertEquals(TimetableVisionFailure.UNAUTHORIZED, refusal(client(401)).failure)
        assertEquals(TimetableVisionFailure.UNAUTHORIZED, refusal(client(403)).failure)
        assertTrue(refusal(client(401)).message!!.contains("API Key"))
    }

    @Test fun everyErrorStatusMapsToItsOwnFailure() = runTest {
        assertEquals(TimetableVisionFailure.INVALID_REQUEST, refusal(client(400)).failure)
        assertEquals(TimetableVisionFailure.QUOTA, refusal(client(402)).failure)
        assertEquals(TimetableVisionFailure.TOO_LARGE, refusal(client(413)).failure)
        assertEquals(TimetableVisionFailure.RATE_LIMITED, refusal(client(429)).failure)
        assertEquals(TimetableVisionFailure.SERVER, refusal(client(500)).failure)
        assertEquals(TimetableVisionFailure.SERVER, refusal(client(503)).failure)
        // A redirect would replay the key at another origin, so it is refused outright.
        assertEquals(TimetableVisionFailure.SERVER, refusal(client(302)).failure)
        assertEquals(TimetableVisionFailure.INVALID_REQUEST, refusal(client(418)).failure)
    }

    @Test fun aTimeoutIsReportedAsATimeout() = runTest {
        val failure = refusal(client(VisionHttpTransport { throw SocketTimeoutException("fixture timeout") }))
        assertEquals(TimetableVisionFailure.TIMEOUT, failure.failure)
    }

    @Test fun noNetworkIsReportedAsNoNetwork() = runTest {
        val failure = refusal(client(VisionHttpTransport { throw IOException("fixture offline") }))
        assertEquals(TimetableVisionFailure.NO_NETWORK, failure.failure)
    }

    @Test fun anImageTheEndpointWouldRefuseIsNeverUploaded() = runTest {
        val transport = RecordingTransport { VisionHttpResponse(200, envelope(VALID_PAYLOAD)) }
        val failure = refusalWith(client(transport), image(mimeType = "image/bmp"))
        assertEquals(TimetableVisionFailure.TOO_LARGE, failure.failure)
        assertNull(transport.request)
    }

    private suspend fun refusalWith(client: TimetableVisionClient, image: TimetableImage): TimetableVisionException =
        try {
            client.recognize(image, KEY)
            throw AssertionError("expected the image to be refused")
        } catch (failure: TimetableVisionException) {
            failure
        }

    @Test fun anOversizedImageIsRefusedLocally() = runTest {
        val transport = RecordingTransport { VisionHttpResponse(200, envelope(VALID_PAYLOAD)) }
        // Just over the 32 MiB base64 ceiling, so the upload must not even start.
        val failure = refusalWith(client(transport), image(bytes = ByteArray(26 * 1024 * 1024)))
        assertEquals(TimetableVisionFailure.TOO_LARGE, failure.failure)
        assertNull(transport.request)
    }

    @Test fun anEmptyImageIsRefused() = runTest {
        assertEquals(TimetableVisionFailure.TOO_LARGE, refusalWith(client(200), image(bytes = ByteArray(0))).failure)
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
        assertEquals(TimetableVisionFailure.MALFORMED_RESPONSE, refusal(client(200, envelope(payload))).failure)
    }

    @Test fun aConflictingCourseListIsRefused() = runTest {
        val payload = """
            {"is_timetable":true,"term":"","courses":[
              {"name":"A-fixture","weekday":2,"start_period":1,"end_period":2,"start_week":1,"end_week":16},
              {"name":"B-fixture","weekday":2,"start_period":2,"end_period":3,"start_week":1,"end_week":16}
            ]}
        """.trimIndent()
        assertEquals(TimetableVisionFailure.MALFORMED_RESPONSE, refusal(client(200, envelope(payload))).failure)
    }

    private companion object {
        const val KEY = "sk-fixture-key"
        const val MODEL = "deepseek-v4.1-flash"
        const val FIXED_TIME = 1_700_000_000_000L
        const val BASE_URL = "https://vision.example.test/v1"
        const val ENDPOINT = "$BASE_URL/chat/completions"
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
