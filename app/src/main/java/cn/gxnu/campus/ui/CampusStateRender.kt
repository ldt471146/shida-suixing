package cn.gxnu.campus.ui

import cn.gxnu.campus.core.ConnectionState
import cn.gxnu.campus.core.ConnectionStatus
import cn.gxnu.campus.core.Credentials
import cn.gxnu.campus.core.PortalFailure
import cn.gxnu.campus.core.Provider
import cn.gxnu.campus.runtime.ManualIntentStage

/**
 * 「运行时那一堆字段」→「界面状态」的推导，单独放在这里。
 *
 * 它原来是 `CampusRuntime` 里的一个私有方法，后果是：这个映射既没法单测（要构造整个运行时），
 * 又让那个类同时背着「协调连接」和「决定界面显示什么」两件事。抽成纯函数之后，这一层第一次
 * 有了测试，`CampusRuntime` 也只剩它真正该管的：协调。
 *
 * 全部输入显式列出来是有意的 —— 隐藏的输入正是这类映射最容易出错的地方。
 */
internal data class CampusRenderInputs(
    val connection: ConnectionState,
    /** 手动连接的进行阶段；它压过协调器的状态，因为用户刚点过按钮，界面该说这一步。 */
    val manualStage: ManualIntentStage,
    val initializing: Boolean,
    val preparingStartedAt: Long?,
    /** 当前 Wi-Fi 名；读不出来时传 null，由这里给出「未连接 Wi-Fi」。 */
    val wifiName: String?,
    val credentials: Credentials?,
    val accountRemembered: Boolean,
    /** 凭据仓自己正在忙（例如正在写 Keystore）。 */
    val accountChanging: Boolean,
    /** 本运行时里正在进行的账号变更数。 */
    val accountChanges: Int,
    val selectedProvider: Provider?,
    val autoConnect: Boolean,
    val autoRunning: Boolean,
    val theme: ThemeMode,
    val feedbackMessage: String?,
    val feedbackId: Long,
    val diagnosticLines: List<String>,
    /** 权限不足时要说的话，由调用方决定（它知道缺的是哪个权限）。 */
    val permissionMessage: String,
    /** 协调器的消息，已经过 `connectionMessage()` 的兜底。 */
    val connectionMessage: String
)

internal fun campusUiState(input: CampusRenderInputs): CampusUiState = CampusUiState(
    status = when (input.manualStage) {
        ManualIntentStage.PREPARING -> ConnectionStatus.PREPARING
        ManualIntentStage.WAITING_PERMISSION -> ConnectionStatus.NEED_PERMISSION
        ManualIntentStage.FAILED -> ConnectionStatus.UNREACHABLE
        ManualIntentStage.NONE -> input.connection.status
    },
    failure = when (input.manualStage) {
        ManualIntentStage.NONE -> input.connection.failure
        ManualIntentStage.FAILED -> PortalFailure.UNREACHABLE
        else -> null
    },
    attemptId = input.connection.attemptId,
    // 手动连接刚起步或刚失败时，计时属于那一次尝试；其余时候属于协调器。
    connectionStartedAtMillis = if (input.manualStage in MANUAL_TIMED_STAGES) input.preparingStartedAt
    else input.connection.connectionStartedAtMillis,
    message = when (input.manualStage) {
        ManualIntentStage.PREPARING ->
            if (input.initializing) "正在恢复账号并识别校园 Wi-Fi…" else "正在识别当前 Wi-Fi 并准备连接…"
        ManualIntentStage.WAITING_PERMISSION -> input.permissionMessage
        ManualIntentStage.FAILED -> "校园 Wi-Fi 识别失败，请确认网络后重试。"
        ManualIntentStage.NONE -> input.connectionMessage
    },
    wifiName = input.wifiName ?: "未连接 Wi-Fi",
    accountConfigured = input.credentials != null,
    maskedAccount = maskAccount(input.credentials?.account),
    accountSaved = input.accountRemembered,
    selectedProvider = input.selectedProvider,
    autoConnect = input.autoConnect,
    autoRunning = input.autoRunning,
    theme = input.theme,
    feedback = input.feedbackMessage,
    feedbackId = input.feedbackId,
    initializing = input.initializing,
    accountSaving = input.accountChanges > 0 || input.accountChanging,
    diagnosticLines = input.diagnosticLines
)

/**
 * 账号打码：只留头两位与末两位。太短的账号连头尾都留不下，就只留第一位 —— 无论如何都不能把
 * 完整账号放进界面状态里，那是会被截屏的东西。
 */
internal fun maskAccount(value: String?): String = when {
    value.isNullOrEmpty() -> "尚未设置"
    value.length <= 4 -> value.take(1) + "•••"
    else -> value.take(2) + "••••" + value.takeLast(2)
}

private val MANUAL_TIMED_STAGES = setOf(ManualIntentStage.PREPARING, ManualIntentStage.FAILED)
