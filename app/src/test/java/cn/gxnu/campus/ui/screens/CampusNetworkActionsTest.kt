package cn.gxnu.campus.ui.screens

import cn.gxnu.campus.core.ConnectionStatus
import cn.gxnu.campus.core.PortalFailure
import cn.gxnu.campus.ui.CampusUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校园网主按钮说过的话和做过的事。
 *
 * 这次改版只允许精简页面上的文案，不允许改变按钮的动作，所以两条各钉一遍：文案逐条对，动作
 * 逐条对，而且两者必须说同一件事（按钮写着「设置校园账号」却去连网，是这一屏最坏的失败）。
 */
class CampusNetworkActionsTest {

    private fun state(status: ConnectionStatus, failure: PortalFailure? = null, initializing: Boolean = false) =
        CampusUiState(status = status, failure = failure, initializing = initializing)

    @Test
    fun beforeTheSavedSettingsAreReadTheButtonIsStillPreparing() {
        assertEquals("正在准备…", networkActionLabel(state(ConnectionStatus.READY, initializing = true)))
        assertEquals("正在准备…", networkActionLabel(state(ConnectionStatus.ONLINE, initializing = true)))
    }

    @Test
    fun everyStatusKeepsTheWordingItHadBeforeThisRedesign() {
        val expected = listOf(
            ConnectionStatus.NO_WIFI to "打开 Wi-Fi 设置",
            ConnectionStatus.OUTSIDE_CAMPUS to "打开 Wi-Fi 设置",
            ConnectionStatus.NEED_ACCOUNT to "设置校园账号",
            ConnectionStatus.NEED_PROVIDER to "选择运营商",
            ConnectionStatus.NEED_PERMISSION to "开启网络权限",
            ConnectionStatus.PREPARING to "正在识别网络…",
            ConnectionStatus.CHECKING to "正在检查网络…",
            ConnectionStatus.AUTHENTICATING to "正在认证账号…",
            ConnectionStatus.VERIFYING to "正在确认上网…",
            ConnectionStatus.ONLINE to "检查网络状态",
            ConnectionStatus.UNREACHABLE to "重试连接",
            ConnectionStatus.CANCELLED to "重新连接",
            ConnectionStatus.READY to "连接校园网"
        )
        expected.forEach { (status, label) ->
            assertEquals("$status 的按钮文案", label, networkActionLabel(state(status)))
        }
    }

    @Test
    fun anAccountFailureOffersToCheckTheAccountInsteadOfRetrying() {
        assertEquals(
            "检查账号",
            networkActionLabel(state(ConnectionStatus.AUTH_ERROR, PortalFailure.ACCOUNT))
        )
        assertEquals(
            "重新连接",
            networkActionLabel(state(ConnectionStatus.AUTH_ERROR, PortalFailure.TIMEOUT))
        )
    }

    @Test
    fun everyActionBranchIsStillTheSameAsBefore() {
        assertEquals(NetworkAction.WIFI_SETTINGS, networkAction(state(ConnectionStatus.NO_WIFI)))
        assertEquals(NetworkAction.WIFI_SETTINGS, networkAction(state(ConnectionStatus.OUTSIDE_CAMPUS)))
        assertEquals(NetworkAction.ACCOUNT, networkAction(state(ConnectionStatus.NEED_ACCOUNT)))
        assertEquals(NetworkAction.PROVIDER, networkAction(state(ConnectionStatus.NEED_PROVIDER)))
        assertEquals(NetworkAction.PERMISSIONS, networkAction(state(ConnectionStatus.NEED_PERMISSION)))
        assertEquals(NetworkAction.CONNECT, networkAction(state(ConnectionStatus.READY)))
        assertEquals(NetworkAction.CONNECT, networkAction(state(ConnectionStatus.ONLINE)))
        assertEquals(NetworkAction.CONNECT, networkAction(state(ConnectionStatus.PREPARING)))
        assertEquals(NetworkAction.CONNECT, networkAction(state(ConnectionStatus.CHECKING)))
        assertEquals(NetworkAction.CONNECT, networkAction(state(ConnectionStatus.AUTHENTICATING)))
        assertEquals(NetworkAction.CONNECT, networkAction(state(ConnectionStatus.VERIFYING)))
        assertEquals(NetworkAction.CONNECT, networkAction(state(ConnectionStatus.UNREACHABLE)))
        assertEquals(NetworkAction.CONNECT, networkAction(state(ConnectionStatus.CANCELLED)))
    }

    @Test
    fun theAccountFailureBranchOpensTheAccountPageNotTheRetryLoop() {
        assertEquals(NetworkAction.ACCOUNT, networkAction(state(ConnectionStatus.AUTH_ERROR, PortalFailure.ACCOUNT)))
        // 认证失败但不是账号问题时仍然是重连，和文案「重新连接」一致。
        assertEquals(NetworkAction.CONNECT, networkAction(state(ConnectionStatus.AUTH_ERROR, PortalFailure.TIMEOUT)))
    }

    @Test
    fun wheneverTheButtonOpensTheAccountPageTheLabelSaysSo() {
        listOf(
            state(ConnectionStatus.NEED_ACCOUNT),
            state(ConnectionStatus.AUTH_ERROR, PortalFailure.ACCOUNT)
        ).forEach { sample ->
            assertEquals(NetworkAction.ACCOUNT, networkAction(sample))
            assertTrue(
                "按钮要去账号页，文案就得说明白，而不是说「${networkActionLabel(sample)}」",
                networkActionLabel(sample) in setOf("设置校园账号", "检查账号")
            )
        }
    }
}
