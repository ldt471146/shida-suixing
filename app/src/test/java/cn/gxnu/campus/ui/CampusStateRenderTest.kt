package cn.gxnu.campus.ui

import cn.gxnu.campus.core.ConnectionState
import cn.gxnu.campus.core.ConnectionStatus
import cn.gxnu.campus.core.Credentials
import cn.gxnu.campus.core.PortalFailure
import cn.gxnu.campus.core.Provider
import cn.gxnu.campus.runtime.ManualIntentStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「运行时字段 → 界面状态」的推导。这段逻辑原来埋在 `CampusRuntime` 的私有方法里，零覆盖；
 * 抽出来之后这里第一次钉住它。重点是那些**容易被后续改动弄丢的细节**：手动连接阶段压过协调器
 * 状态、计时归属、账号打码、以及「忙」的两个来源。
 */
class CampusStateRenderTest {

    private fun inputs(
        connection: ConnectionState = ConnectionState(),
        manualStage: ManualIntentStage = ManualIntentStage.NONE,
        initializing: Boolean = false,
        preparingStartedAt: Long? = null,
        wifiName: String? = "GXNU-YC",
        credentials: Credentials? = null,
        accountRemembered: Boolean = false,
        accountChanging: Boolean = false,
        accountChanges: Int = 0,
        selectedProvider: Provider? = null,
        autoConnect: Boolean = false,
        autoRunning: Boolean = false,
        theme: ThemeMode = ThemeMode.LIGHT,
        feedbackMessage: String? = null,
        feedbackId: Long = 0,
        diagnosticLines: List<String> = emptyList(),
        permissionMessage: String = "需要权限",
        connectionMessage: String = "协调器说"
    ) = CampusRenderInputs(
        connection, manualStage, initializing, preparingStartedAt, wifiName, credentials,
        accountRemembered, accountChanging, accountChanges, selectedProvider, autoConnect,
        autoRunning, theme, feedbackMessage, feedbackId, diagnosticLines,
        permissionMessage, connectionMessage
    )

    /** 没在手动连接时，界面显示的就是协调器的状态与消息。 */
    @Test
    fun withoutAManualAttemptTheCoordinatorSpeaks() {
        val state = campusUiState(
            inputs(
                connection = ConnectionState(status = ConnectionStatus.ONLINE, message = "校园 Wi-Fi 已连接，可以上网。", attemptId = 7),
                connectionMessage = "校园 Wi-Fi 已连接，可以上网。"
            )
        )
        assertEquals(ConnectionStatus.ONLINE, state.status)
        assertEquals("校园 Wi-Fi 已连接，可以上网。", state.message)
        assertEquals(7L, state.attemptId)
    }

    /** 用户刚点过按钮，界面该说这一步，而不是协调器还没来得及改的旧状态。 */
    @Test
    fun aManualAttemptOverridesTheCoordinatorStatus() {
        val preparing = campusUiState(
            inputs(connection = ConnectionState(status = ConnectionStatus.READY), manualStage = ManualIntentStage.PREPARING)
        )
        assertEquals(ConnectionStatus.PREPARING, preparing.status)
        assertNull("手动进行中不该显示上一次的失败", preparing.failure)

        val waiting = campusUiState(inputs(manualStage = ManualIntentStage.WAITING_PERMISSION))
        assertEquals(ConnectionStatus.NEED_PERMISSION, waiting.status)
        assertEquals("需要权限", waiting.message)

        val failed = campusUiState(inputs(manualStage = ManualIntentStage.FAILED))
        assertEquals(ConnectionStatus.UNREACHABLE, failed.status)
        assertEquals(PortalFailure.UNREACHABLE, failed.failure)
    }

    /** 初始化还没跑完时，「正在识别」得说清是在恢复账号，否则用户会以为卡住了。 */
    @Test
    fun thePreparingMessageSaysWhichPreparingItIs() {
        val restoring = campusUiState(inputs(manualStage = ManualIntentStage.PREPARING, initializing = true))
        assertEquals("正在恢复账号并识别校园 Wi-Fi…", restoring.message)

        val connecting = campusUiState(inputs(manualStage = ManualIntentStage.PREPARING, initializing = false))
        assertEquals("正在识别当前 Wi-Fi 并准备连接…", connecting.message)
    }

    /** 手动连接刚起步或刚失败时，计时属于那一次尝试；其余时候属于协调器。 */
    @Test
    fun theElapsedTimerFollowsTheAttemptThatOwnsIt() {
        val connection = ConnectionState(connectionStartedAtMillis = 111L)
        assertEquals(
            222L,
            campusUiState(inputs(connection = connection, manualStage = ManualIntentStage.PREPARING, preparingStartedAt = 222L))
                .connectionStartedAtMillis
        )
        assertEquals(
            222L,
            campusUiState(inputs(connection = connection, manualStage = ManualIntentStage.FAILED, preparingStartedAt = 222L))
                .connectionStartedAtMillis
        )
        assertEquals(
            111L,
            campusUiState(inputs(connection = connection, manualStage = ManualIntentStage.NONE, preparingStartedAt = 222L))
                .connectionStartedAtMillis
        )
    }

    /** Wi-Fi 名读不出来时给一句人话，而不是留空。 */
    @Test
    fun anUnreadableWifiNameBecomesPlainWords() {
        assertEquals("GXNU-YC", campusUiState(inputs(wifiName = "GXNU-YC")).wifiName)
        assertEquals("未连接 Wi-Fi", campusUiState(inputs(wifiName = null)).wifiName)
    }

    /**
     * 账号打码：界面状态是会被截屏的东西，完整账号不许进去。
     */
    @Test
    fun theAccountIsNeverCarriedInFull() {
        assertEquals("尚未设置", campusUiState(inputs(credentials = null)).maskedAccount)

        val long = campusUiState(inputs(credentials = Credentials("2026010039", "pw")))
        assertEquals("20••••39", long.maskedAccount)
        assertFalse("完整学号不许出现在界面状态里", long.maskedAccount.contains("2026010039"))

        assertEquals("5•••", campusUiState(inputs(credentials = Credentials("5123", "pw"))).maskedAccount)
    }

    /** 「忙」有两个来源：本运行时里的账号变更数，和凭据仓自己。任一为真都要禁用按钮。 */
    @Test
    fun accountSavingIsBusyFromEitherSource() {
        assertFalse(campusUiState(inputs()).accountSaving)
        assertTrue(campusUiState(inputs(accountChanges = 1)).accountSaving)
        assertTrue(campusUiState(inputs(accountChanging = true)).accountSaving)
    }

    @Test
    fun theRemainingFieldsAreCarriedStraightThrough() {
        val state = campusUiState(
            inputs(
                credentials = Credentials("2026010039", "pw"),
                accountRemembered = true,
                selectedProvider = Provider.CAMPUS,
                autoConnect = true,
                autoRunning = true,
                theme = ThemeMode.DARK,
                feedbackMessage = "已保存",
                feedbackId = 9,
                diagnosticLines = listOf("line"),
                initializing = true
            )
        )
        assertTrue(state.accountConfigured)
        assertTrue(state.accountSaved)
        assertEquals(Provider.CAMPUS, state.selectedProvider)
        assertTrue(state.autoConnect)
        assertTrue(state.autoRunning)
        assertEquals(ThemeMode.DARK, state.theme)
        assertEquals("已保存", state.feedback)
        assertEquals(9L, state.feedbackId)
        assertEquals(listOf("line"), state.diagnosticLines)
        assertTrue(state.initializing)
    }
}
