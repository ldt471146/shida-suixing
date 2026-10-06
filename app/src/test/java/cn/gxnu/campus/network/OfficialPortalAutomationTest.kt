package cn.gxnu.campus.network

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class OfficialPortalAutomationTest {
    @Test fun webViewEncodedDecisionRetainsOnlyKnownErrorCategory() {
        val raw = """{"state":"rejected","reason":"ACCOUNT","message":"fixture-password https://private.test"}"""
        val result = OfficialPortalAutomation.decision(Gson().toJson(raw))
        assertEquals(BridgeDecision(BridgeStage.REJECTED, "ACCOUNT"), result)
        assertEquals("BridgeDecision(stage=REJECTED, reason=ACCOUNT)", result.toString())
    }

    @Test fun missingJavaScriptResultWaitsForSchoolLoading() {
        assertEquals(BridgeDecision(BridgeStage.WAITING), OfficialPortalAutomation.decision(null))
        assertEquals(BridgeDecision(BridgeStage.WAITING), OfficialPortalAutomation.decision("null"))
    }

    @Test fun malformedOrUnknownResponsesHaveOnlyFixedPageReason() {
        for (response in listOf(
            "not JSON fixture-password",
            """{"state":"unknown","reason":"fixture-password"}""",
            """{"state":"manual","reason":"https://private.test/fixture-password"}""",
            """{"state":"submitted"} extra code""",
            """{"state":{},"reason":"ACCOUNT"}""",
            "\"" + "x".repeat(5000) + "\""
        )) {
            assertEquals(BridgeDecision(BridgeStage.MANUAL, "PAGE"), OfficialPortalAutomation.decision(response))
        }
    }

    @Test fun normalStagesDoNotPropagatePayloadEvenWhenAnExtraReasonIsPresent() {
        assertEquals(BridgeDecision(BridgeStage.SUBMITTED), OfficialPortalAutomation.decision(
            """{"state":"submitted","reason":"fixture-password"}"""
        ))
        assertEquals(BridgeDecision(BridgeStage.WAITING), OfficialPortalAutomation.decision(
            """{"state":"waiting","reason":"fixture-password"}"""
        ))
        assertEquals(BridgeDecision(BridgeStage.MANUAL, "CAPTCHA"), OfficialPortalAutomation.decision(
            """{"state":"manual","reason":"CAPTCHA"}"""
        ))
    }
}
