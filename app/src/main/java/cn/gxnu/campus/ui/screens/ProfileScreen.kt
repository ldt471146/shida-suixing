package cn.gxnu.campus.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cn.gxnu.campus.BuildConfig
import cn.gxnu.campus.core.ConnectionStatus
import cn.gxnu.campus.network.WifiEnvironment
import cn.gxnu.campus.ui.CampusActions
import cn.gxnu.campus.ui.CampusUiState
import cn.gxnu.campus.ui.ThemeMode
import cn.gxnu.campus.ui.UpdateActions
import cn.gxnu.campus.ui.UpdatePhase
import cn.gxnu.campus.ui.UpdateUiState
import cn.gxnu.campus.ui.common.AutoConnectRow
import cn.gxnu.campus.ui.common.CampusCard
import cn.gxnu.campus.ui.common.CampusPageHeader
import cn.gxnu.campus.ui.common.CampusPill
import cn.gxnu.campus.ui.common.CampusRow
import cn.gxnu.campus.ui.common.CampusRowDivider
import cn.gxnu.campus.ui.common.ConnectionHelpDialog
import cn.gxnu.campus.ui.common.DeleteAccountDialog
import cn.gxnu.campus.ui.common.SectionLabel
import cn.gxnu.campus.ui.common.UpdateNoticeCard
import cn.gxnu.campus.ui.common.connectionStatusLabel
import cn.gxnu.campus.ui.theme.CampusRadius
import cn.gxnu.campus.ui.theme.CampusSpace
import cn.gxnu.campus.ui.theme.LocalCampusPalette

@Composable
fun ProfileScreen(
    state: CampusUiState,
    actions: CampusActions,
    onAccount: () -> Unit,
    updates: UpdateActions?,
    updateState: UpdateUiState,
    modifier: Modifier = Modifier
) {
    val palette = LocalCampusPalette.current
    val permissionsGranted = rememberCampusPermissionsGranted()
    var showTheme by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

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
        item { CampusPageHeader(title = "我的", subtitle = "连接设置与帮助") }
        item {
            CampusCard {
                CampusRow(
                    title = "校园账号",
                    description = when {
                        state.initializing -> "正在读取…"
                        !state.accountConfigured -> "未设置"
                        state.accountSaved -> "${state.maskedAccount} · 已在此设备加密保存"
                        else -> "${state.maskedAccount} · 仅本次使用"
                    },
                    leadingIcon = Icons.Outlined.PersonOutline,
                    onClick = onAccount
                )
                CampusRowDivider()
                AutoConnectRow(state, actions::setAutoConnect)
                CampusRowDivider()
                CampusRow(
                    title = "主题",
                    description = "跟随系统或固定深浅色",
                    leadingIcon = Icons.Outlined.DarkMode,
                    trailingText = state.theme.title,
                    onClick = { showTheme = true }
                )
            }
        }
        item {
            Text(
                "强制停止、重启设备或撤销权限后，重新打开 App 恢复自动连接。",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textTertiary
            )
        }
        item { SectionLabel("帮助与检查", Modifier.padding(start = CampusSpace.xs)) }
        if (updates != null) item { UpdateNoticeCard(updateState, updates) }
        item {
            CampusCard {
                CampusRow(
                    title = "连接帮助",
                    description = "账号、运营商与自动连接说明",
                    leadingIcon = Icons.Outlined.HelpOutline,
                    onClick = { showHelp = true }
                )
                CampusRowDivider()
                CampusRow(
                    title = "网络诊断",
                    description = "当前网络与脱敏配置",
                    leadingIcon = Icons.Outlined.MonitorHeart,
                    onClick = { showDiagnostics = true }
                )
                CampusRowDivider()
                CampusRow(
                    title = "网络与通知权限",
                    description = permissionRowDescription(permissionsGranted),
                    leadingIcon = Icons.Outlined.Notifications,
                    trailing = {
                        val (ink, wash) = if (permissionsGranted) palette.success to palette.successWash
                        else palette.warning to palette.warningWash
                        CampusPill(permissionRowLabel(permissionsGranted), ink, wash)
                    },
                    onClick = actions::requestPermissions
                )
                CampusRowDivider()
                CampusRow(
                    title = "学校认证页面",
                    description = "需要人工处理时使用学校入口",
                    leadingIcon = Icons.AutoMirrored.Outlined.OpenInNew,
                    onClick = actions::openOfficialPortal
                )
                if (updates != null) {
                    CampusRowDivider()
                    CampusRow(
                        title = "检查更新",
                        description = updateState.checkSummary ?: "当前版本 ${BuildConfig.VERSION_NAME}",
                        leadingIcon = Icons.Outlined.SystemUpdate,
                        trailingText = if (updateState.phase is UpdatePhase.Checking) "检查中…" else null,
                        onClick = updates::checkForUpdate,
                        enabled = updateState.phase !is UpdatePhase.Checking &&
                            updateState.phase !is UpdatePhase.Downloading
                    )
                }
            }
        }
        if (state.accountConfigured) {
            item {
                CampusCard {
                    CampusRow(
                        title = "删除校园账号",
                        description = "移除登录信息并关闭自动连接",
                        leadingIcon = Icons.Outlined.DeleteOutline,
                        leadingTint = palette.danger,
                        leadingPlate = palette.dangerWash,
                        titleColor = palette.danger,
                        onClick = { showDelete = true }
                    )
                }
            }
        }
        item {
            Text(
                "师大随行 ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textTertiary
            )
        }
    }

    if (showTheme) ThemeDialog(state.theme, onDismiss = { showTheme = false }) {
        actions.setTheme(it)
        showTheme = false
    }
    if (showHelp) ConnectionHelpDialog(actions, onDismiss = { showHelp = false })
    if (showDiagnostics) DiagnosticsDialog(state, onDismiss = { showDiagnostics = false })
    if (showDelete) DeleteAccountDialog(onDismiss = { showDelete = false }) {
        actions.deleteAccount()
        showDelete = false
    }
}

/**
 * The permission grants are platform state the runtime does not mirror, so the row reads the same
 * checks the activity requests: identifying 校园 Wi-Fi needs the location grants and the system
 * location switch, the background service needs the notification grant. Re-reading on resume
 * covers both the permission dialog result and the return from the app's system settings page.
 */
@Composable
private fun rememberCampusPermissionsGranted(): Boolean {
    val context = LocalContext.current
    var resumeEpoch by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeEpoch++ }
    return remember(resumeEpoch, context) {
        WifiEnvironment.hasNetworkPermissions(context) &&
            WifiEnvironment.isLocationEnabled(context) &&
            WifiEnvironment.hasNotificationPermission(context)
    }
}

/** The row reports one verdict for the pair it covers: both grants serve the same connection. */
internal fun permissionRowLabel(granted: Boolean): String = if (granted) "已授予" else "未授予"

/**
 * Granted states what the permissions are for. Missing warns that tapping is not always a dialog:
 * a grant that was permanently denied sends the user straight to the app's system settings page.
 */
internal fun permissionRowDescription(granted: Boolean): String = if (granted) {
    "用于识别校园 Wi-Fi 与后台通知"
} else {
    "点按授权，被拒绝时会跳转系统设置"
}

@Composable
private fun ThemeDialog(selected: ThemeMode, onDismiss: () -> Unit, onSelect: (ThemeMode) -> Unit) {
    val palette = LocalCampusPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        shape = CampusRadius.lgShape,
        title = { Text("选择主题", style = MaterialTheme.typography.titleLarge, color = palette.textPrimary) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                ThemeMode.entries.forEach { mode ->
                    ThemeOption(
                        icon = when (mode) {
                            ThemeMode.SYSTEM -> Icons.Outlined.BrightnessAuto
                            ThemeMode.LIGHT -> Icons.Outlined.LightMode
                            ThemeMode.DARK -> Icons.Outlined.DarkMode
                        },
                        title = mode.title,
                        description = when (mode) {
                            ThemeMode.SYSTEM -> "根据设备设置切换"
                            ThemeMode.LIGHT -> "浅色背景"
                            ThemeMode.DARK -> "深色背景"
                        },
                        selected = selected == mode,
                        onSelect = { onSelect(mode) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消", style = MaterialTheme.typography.labelLarge) }
        }
    )
}

@Composable
private fun ThemeOption(icon: ImageVector, title: String, description: String, selected: Boolean, onSelect: () -> Unit) {
    val palette = LocalCampusPalette.current
    CampusRow(
        title = title,
        description = description,
        leadingIcon = icon,
        leadingTint = if (selected) palette.onAccentWash else null,
        leadingPlate = if (selected) palette.accentWash else null,
        showChevron = false,
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        trailing = {
            RadioButton(
                selected = selected,
                onClick = null,
                colors = RadioButtonDefaults.colors(
                    selectedColor = palette.accent,
                    unselectedColor = palette.textTertiary
                )
            )
        }
    )
}

@Composable
private fun DiagnosticsDialog(state: CampusUiState, onDismiss: () -> Unit) {
    val palette = LocalCampusPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        shape = CampusRadius.lgShape,
        title = { Text("网络诊断", style = MaterialTheme.typography.titleLarge, color = palette.textPrimary) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(CampusSpace.lg)
            ) {
                DiagnosticItem("应用版本", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android ${android.os.Build.VERSION.RELEASE}")
                if (state.isPreview) {
                    CampusPill("界面预览 · 未执行校园认证", palette.textSecondary, palette.muted)
                }
                DiagnosticItem("连接状态", connectionStatusLabel(state.status))
                DiagnosticItem("当前 Wi-Fi", state.wifiName)
                DiagnosticItem("外网检查", when {
                    state.isPreview -> "预览场景，未执行网络检查"
                    state.status == ConnectionStatus.ONLINE -> "已通过目标 Wi-Fi 的网络验证"
                    else -> "尚未确认可用"
                })
                DiagnosticItem("运营商", state.selectedProvider?.title ?: "尚未选择")
                DiagnosticItem("校园账号", if (state.accountConfigured) state.maskedAccount else "尚未设置")
                DiagnosticItem("账号保存", when {
                    state.isPreview -> "预览数据，未保存至设备"
                    state.accountSaved -> "保存在此设备"
                    else -> "未保存到设备"
                })
                DiagnosticItem("自动连接", when {
                    state.isPreview -> "预览设置，不运行后台服务"
                    !state.autoConnect -> "已关闭"
                    state.autoRunning -> "已开启 · 后台服务运行中"
                    else -> "已开启 · 等待后台服务恢复"
                })
                DiagnosticItem("处理建议", state.message)
                if (state.diagnosticLines.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(CampusSpace.sm)) {
                        SectionLabel("本次连接")
                        state.diagnosticLines.forEach { line ->
                            Text(line, style = MaterialTheme.typography.bodyMedium, color = palette.textSecondary)
                        }
                    }
                }
                Text("此页只展示脱敏配置，不显示密码。", style = MaterialTheme.typography.bodySmall, color = palette.textTertiary)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭", style = MaterialTheme.typography.labelLarge) }
        }
    )
}

@Composable
private fun DiagnosticItem(label: String, value: String) {
    val palette = LocalCampusPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(CampusSpace.xs)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = palette.textTertiary, modifier = Modifier.semantics { heading() })
        Text(value, style = MaterialTheme.typography.bodyMedium, color = palette.textPrimary)
    }
}
