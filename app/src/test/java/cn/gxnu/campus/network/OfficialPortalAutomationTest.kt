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

    /**
     * 用户报的「我填了东西，去认证的时候不给我填好」。
     *
     * 桥脚本原来只有 waiting / submitted / rejected / manual 四种回答，而「字段已经填好、但还没有
     * 满足点击条件」只能报成 waiting —— 界面于是显示「正在准备学校登录页」，用户看到的却是一个
     * 空表单，像是应用什么都没做。现在这种中间态是独立的 `filled`：它是进展，不是终态，
     * 调用方继续轮询，界面说清「账号已自动填好」。
     */
    @Test fun aFilledButUnsubmittableFormReportsProgressInsteadOfWaiting() {
        assertEquals(BridgeDecision(BridgeStage.FILLED), OfficialPortalAutomation.decision(
            """{"state":"filled"}"""
        ))
        // Same rule as the other non-error states: no payload may ride along.
        assertEquals(BridgeDecision(BridgeStage.FILLED), OfficialPortalAutomation.decision(
            """{"state":"filled","reason":"fixture-password"}"""
        ))
    }
}
