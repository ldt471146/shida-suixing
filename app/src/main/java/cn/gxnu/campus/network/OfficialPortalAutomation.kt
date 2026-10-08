package cn.gxnu.campus.network

import cn.gxnu.campus.core.Credentials
import cn.gxnu.campus.core.Provider
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import org.json.JSONObject

enum class BridgeStage { WAITING, FILLED, SUBMITTED, REJECTED, MANUAL }

data class BridgeDecision(val stage: BridgeStage, val reason: String = "")

/** Injects data into the school's real form; no authentication URL is constructed here. */
object OfficialPortalAutomation {
    fun script(source: String, credentials: Credentials, provider: Provider): String {
        require(source.isNotBlank()) { "The official-page bridge asset is required" }
        val arguments = listOf(credentials.account, credentials.password, provider.suffix, provider.title)
            .joinToString(",") { value ->
                JSONObject.quote(value).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
            }
        return "(\n$source\n)($arguments)"
    }

    /** WebView JSON-encodes the string returned by evaluateJavascript one more time. */
    fun decision(evaluateResult: String?): BridgeDecision {
        if (evaluateResult == null || evaluateResult == "null") return BridgeDecision(BridgeStage.WAITING)
        if (evaluateResult.length > 4096) return BridgeDecision(BridgeStage.MANUAL, "PAGE")
        return try {
            val outer = parse(evaluateResult)
            val data = if (outer.isJsonPrimitive && outer.asJsonPrimitive.isString) parse(outer.asString) else outer
            if (!data.isJsonObject) return BridgeDecision(BridgeStage.MANUAL, "PAGE")
            val stage = when (string(data.asJsonObject.get("state"))) {
                "waiting" -> BridgeStage.WAITING
                // The form's fields exist and have been filled, but the submit preconditions are not
                // all met yet. This is progress, not a terminal state: the caller keeps polling.
                "filled" -> BridgeStage.FILLED
                "submitted" -> BridgeStage.SUBMITTED
                "rejected" -> BridgeStage.REJECTED
                "manual" -> BridgeStage.MANUAL
                else -> return BridgeDecision(BridgeStage.MANUAL, "PAGE")
            }
            val reason = if (stage == BridgeStage.WAITING || stage == BridgeStage.SUBMITTED || stage == BridgeStage.FILLED) "" else {
                // PROVIDER: 学校页面上找不到用户所选的运营商（本门户就没有「广电网络」这一项）。
                // 这要单独告诉用户去改选，而不是笼统地说「需要手动操作」。
                string(data.asJsonObject.get("reason")).takeIf { it in setOf("ACCOUNT", "CAPTCHA", "PAGE", "PROVIDER") } ?: "PAGE"
            }
            BridgeDecision(stage, reason)
        } catch (_: Exception) {
            BridgeDecision(BridgeStage.MANUAL, "PAGE")
        }
    }

    private fun string(element: JsonElement?): String? = element?.takeIf {
        it.isJsonPrimitive && it.asJsonPrimitive.isString
    }?.asString

    private fun parse(value: String): JsonElement = JsonReader(StringReader(value)).use { reader ->
        reader.strictness = Strictness.STRICT
        val parsed = JsonParser.parseReader(reader)
        require(reader.peek() == JsonToken.END_DOCUMENT)
        parsed
    }
}
