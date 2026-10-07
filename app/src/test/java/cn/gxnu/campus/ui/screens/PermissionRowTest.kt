package cn.gxnu.campus.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 我的 permission row: the verdict pill and the hint about what tapping the row does. */
class PermissionRowTest {

    @Test fun theVerdictIsGrantedOnlyWhenEveryCheckPasses() {
        assertEquals("已授予", permissionRowLabel(true))
    }

    @Test fun anyMissingGrantWithholdsTheGrantedVerdict() {
        assertEquals("未授予", permissionRowLabel(false))
    }

    @Test fun theDescriptionStatesThePurposeOnceGranted() {
        assertEquals("用于识别校园 Wi-Fi 与后台通知", permissionRowDescription(true))
    }

    @Test fun theDescriptionWarnsThatTappingCanOpenSystemSettings() {
        val hint = permissionRowDescription(false)
        assertTrue(hint.startsWith("点按"))
        assertTrue(hint.contains("系统设置"))
    }
}
