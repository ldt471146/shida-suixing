package cn.gxnu.campus.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Public
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cn.gxnu.campus.core.ConnectionStatus
import cn.gxnu.campus.core.PortalFailure
import cn.gxnu.campus.ui.CampusActions
import cn.gxnu.campus.ui.CampusUiState
import cn.gxnu.campus.ui.common.AutoConnectRow
import cn.gxnu.campus.ui.common.CampusCard
import cn.gxnu.campus.ui.common.CampusGhostButton
import cn.gxnu.campus.ui.common.CampusIconButton
import cn.gxnu.campus.ui.common.CampusPageHeader
import cn.gxnu.campus.ui.common.CampusPrimaryButton
import cn.gxnu.campus.ui.common.CampusRow
import cn.gxnu.campus.ui.common.CampusRowDivider
import cn.gxnu.campus.ui.common.ConnectionFeedback
import cn.gxnu.campus.ui.common.ConnectionHelpDialog
import cn.gxnu.campus.ui.common.EntranceGate
import cn.gxnu.campus.ui.common.OfficialPortalLink
import cn.gxnu.campus.ui.common.isConnecting
import cn.gxnu.campus.ui.common.entrance
import cn.gxnu.campus.ui.common.rememberPageEntrance
import cn.gxnu.campus.ui.theme.CampusSpace

/**
 * 校园网：状态、主按钮、账号、运营商、自动连接。
 *
 * 它原来是打开应用就看到的页面，现在是从「功能」里点进来的二级页 —— 也就是说，不认识校园网、
 * 只是来看课表的人不会再被它挡住。
 *
 * 连接动作一个字都没有改：状态卡说的仍是运行时的原话，主按钮的每个分支、恢复操作行的窄屏与
 * 大字号分支、「学校认证页面」的位置都和改动前一致。
 */
@Composable
fun CampusNetworkScreen(
    state: CampusUiState,
    actions: CampusActions,
    onBack: () -> Unit,
    onAccount: () -> Unit,
    onProvider: () -> Unit,
    gate: EntranceGate,
    modifier: Modifier = Modifier
) {
    var showHelp by remember { mutableStateOf(false) }
    val entrance = rememberPageEntrance(gate, "campus-network", itemCount = 4)
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = CampusSpace.lg,
            end = CampusSpace.lg,
            top = CampusSpace.md,
            bottom = CampusSpace.xxl
        ),
        verticalArrangement = Arrangement.spacedBy(CampusSpace.lg)
    ) {
        item(key = "header") {
            Box(Modifier.entrance(entrance, 0)) {
                CampusPageHeader(
                    title = "校园网",
                    subtitle = "",
                    leading = {
                        CampusIconButton(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "返回功能",
                            onClick = onBack
                        )
                    },
                    trailing = {
                        CampusIconButton(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = "连接帮助", onClick = { showHelp = true })
                    }
                )
            }
        }
        item(key = "status") {
            Box(Modifier.entrance(entrance, 1)) { NetworkCard(state, actions, onAccount, onProvider) }
        }
        item(key = "identity") {
            Box(Modifier.entrance(entrance, 2)) {
                CampusCard {
                    AccountSummary(state, onAccount)
                    CampusRowDivider()
                    ProviderSummary(state, onProvider)
                }
            }
        }
        item(key = "auto") {
            Box(Modifier.entrance(entrance, 3)) {
                CampusCard { AutoConnectRow(state, actions::setAutoConnect) }
            }
        }
    }
    if (showHelp) ConnectionHelpDialog(actions, onDismiss = { showHelp = false })
}

/** 校园网自己的开关行，和「我的」里那一行说的是同一件事。 */
@Composable
private fun NetworkCard(state: CampusUiState, actions: CampusActions, onAccount: () -> Unit, onProvider: () -> Unit) {
    val busy = state.initializing || state.status.isConnecting()
    val accountFailure = state.status == ConnectionStatus.AUTH_ERROR && state.failure == PortalFailure.ACCOUNT
    val fontScale = LocalDensity.current.fontScale
    CampusCard {
        Column(Modifier.padding(CampusSpace.lg), verticalArrangement = Arrangement.spacedBy(CampusSpace.lg)) {
            ConnectionFeedback(state)
            CampusPrimaryButton(
                title = networkActionLabel(state),
                onClick = {
                    when (networkAction(state)) {
                        NetworkAction.ACCOUNT -> onAccount()
                        NetworkAction.PROVIDER -> onProvider()
                        NetworkAction.PERMISSIONS -> actions.requestPermissions()
                        NetworkAction.WIFI_SETTINGS -> actions.openWifiSettings()
                        NetworkAction.CONNECT -> actions.connect()
                    }
                },
                enabled = !state.initializing && !state.accountSaving,
                busy = busy,
                showProgress = false,
                modifier = Modifier.testTag("primary_connect")
            )
            if (state.status.isConnecting() && !state.initializing) {
                CampusGhostButton(
                    title = "取消连接",
                    onClick = actions::cancelConnect,
                    modifier = Modifier.fillMaxWidth().testTag("cancel_connect")
                )
            } else if (state.status == ConnectionStatus.AUTH_ERROR || state.status == ConnectionStatus.UNREACHABLE) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth < 300.dp || fontScale > 1.3f) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CampusSpace.sm)) {
                            RecoveryAction(accountFailure, actions, onAccount, Modifier.fillMaxWidth())
                            OfficialPortalLink(actions, Modifier.fillMaxWidth())
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CampusSpace.sm)) {
                            RecoveryAction(accountFailure, actions, onAccount, Modifier.weight(1f))
                            OfficialPortalLink(actions, Modifier.weight(1f))
                        }
                    }
                }
            } else {
                OfficialPortalLink(actions, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun RecoveryAction(accountFailure: Boolean, actions: CampusActions, onAccount: () -> Unit, modifier: Modifier = Modifier) {
    CampusGhostButton(
        title = if (accountFailure) "重新连接" else "检查账号",
        onClick = if (accountFailure) actions::connect else onAccount,
        modifier = modifier
    )
}

@Composable
private fun AccountSummary(state: CampusUiState, onAccount: () -> Unit) {
    CampusRow(
        title = "校园账号",
        description = when {
            state.initializing -> "正在读取…"
            !state.accountConfigured -> "未设置"
            state.accountSaved -> "${state.maskedAccount} · 已保存"
            else -> "${state.maskedAccount} · 仅本次使用"
        },
        leadingIcon = Icons.Outlined.PersonOutline,
        trailingText = if (state.accountConfigured) "检查" else "设置",
        onClick = onAccount,
        enabled = !state.initializing && !state.accountSaving
    )
}

@Composable
private fun ProviderSummary(state: CampusUiState, onProvider: () -> Unit) {
    CampusRow(
        title = "运营商",
        description = "学校认证页面对应的供应商",
        leadingIcon = Icons.Outlined.Public,
        trailingText = if (state.initializing) "正在读取…" else state.selectedProvider?.title ?: "请选择",
        onClick = onProvider,
        enabled = !state.initializing && !state.accountSaving
    )
}

/**
 * 主按钮这一刻该说什么。分支和按钮真正执行的动作是一一对应的，所以这张表改了文案就等于改了
 * 行为 —— CampusNetworkActionsTest 把它逐条钉住。
 */
internal fun networkActionLabel(state: CampusUiState): String = if (state.initializing) "正在准备…" else when (state.status) {
    ConnectionStatus.NO_WIFI, ConnectionStatus.OUTSIDE_CAMPUS -> "打开 Wi-Fi 设置"
    ConnectionStatus.NEED_ACCOUNT -> "设置校园账号"
    ConnectionStatus.NEED_PROVIDER -> "选择运营商"
    ConnectionStatus.NEED_PERMISSION -> "开启网络权限"
    ConnectionStatus.PREPARING -> "正在识别网络…"
    ConnectionStatus.CHECKING -> "正在检查网络…"
    ConnectionStatus.AUTHENTICATING -> "正在认证账号…"
    ConnectionStatus.VERIFYING -> "正在确认上网…"
    ConnectionStatus.ONLINE -> "检查网络状态"
    ConnectionStatus.AUTH_ERROR -> if (state.failure == PortalFailure.ACCOUNT) "检查账号" else "重新连接"
    ConnectionStatus.UNREACHABLE -> "重试连接"
    ConnectionStatus.CANCELLED -> "重新连接"
    ConnectionStatus.READY -> "连接校园网"
}

/** 主按钮这一刻该做什么。文案与动作共用同一份状态判断，所以两者不可能各说各话。 */
internal enum class NetworkAction { ACCOUNT, PROVIDER, PERMISSIONS, WIFI_SETTINGS, CONNECT }

/**
 * 主按钮的动作分支。它原来内联在点击回调里，改一个分支只能靠读代码；抽出来之后
 * CampusNetworkActionsTest 可以逐条钉住 —— 这次改版只允许精简文案，不允许改变动作。
 */
internal fun networkAction(state: CampusUiState): NetworkAction {
    val accountFailure = state.status == ConnectionStatus.AUTH_ERROR && state.failure == PortalFailure.ACCOUNT
    return when {
        accountFailure -> NetworkAction.ACCOUNT
        state.status == ConnectionStatus.NEED_ACCOUNT -> NetworkAction.ACCOUNT
        state.status == ConnectionStatus.NEED_PROVIDER -> NetworkAction.PROVIDER
        state.status == ConnectionStatus.NEED_PERMISSION -> NetworkAction.PERMISSIONS
        state.status == ConnectionStatus.NO_WIFI || state.status == ConnectionStatus.OUTSIDE_CAMPUS -> NetworkAction.WIFI_SETTINGS
        else -> NetworkAction.CONNECT
    }
}
