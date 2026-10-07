package cn.gxnu.campus.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cn.gxnu.campus.BuildConfig
import cn.gxnu.campus.core.ConnectionStatus
import cn.gxnu.campus.network.WifiEnvironment
import cn.gxnu.campus.ui.CampusActions
import cn.gxnu.campus.ui.CampusUiState
import cn.gxnu.campus.ui.UpdateActions
import cn.gxnu.campus.ui.UpdatePhase
import cn.gxnu.campus.ui.UpdateUiState
import cn.gxnu.campus.ui.common.AutoConnectRow
import cn.gxnu.campus.ui.common.CampusCard
import cn.gxnu.campus.ui.common.CampusIconButton
import cn.gxnu.campus.ui.common.CampusPageHeader
import cn.gxnu.campus.ui.common.CampusPill
import cn.gxnu.campus.ui.common.CampusRow
import cn.gxnu.campus.ui.common.CampusRowDivider
import cn.gxnu.campus.ui.common.ConnectionHelpDialog
import cn.gxnu.campus.ui.common.DeleteAccountDialog
import cn.gxnu.campus.ui.common.EntranceGate
import cn.gxnu.campus.ui.common.SectionLabel
import cn.gxnu.campus.ui.common.ThemeDialog
import cn.gxnu.campus.ui.common.UpdateNoticeCard
import cn.gxnu.campus.ui.common.connectionStatusLabel
import cn.gxnu.campus.ui.common.entrance
import cn.gxnu.campus.ui.common.rememberPageEntrance
import cn.gxnu.campus.ui.theme.CampusEasing
import cn.gxnu.campus.ui.theme.CampusMotion
import cn.gxnu.campus.ui.theme.CampusRadius
import cn.gxnu.campus.ui.theme.CampusSpace
import cn.gxnu.campus.ui.theme.LocalCampusPalette

/**
 * 设置：外观、连接、更新、账号四组。
 *
 * 页面由 [settingsEntries] 那份清单长出来，而不是一串写死的行 —— 「有什么」是一个可以单测的
 * 纯函数，「点击做什么」才留在界面里。
 */
@Composable
fun SettingsScreen(
    state: CampusUiState,
    actions: CampusActions,
    updates: UpdateActions?,
    updateState: UpdateUiState,
    gate: EntranceGate,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val permissionsGranted = rememberCampusPermissionsGranted()
    var showTheme by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    val entries = settingsEntries(
        accountConfigured = state.accountConfigured,
        updatesAvailable = updates != null
    )
    val groups = settingsGroups(entries)
    val entrance = rememberPageEntrance(gate, "settings", itemCount = groups.size + 1)

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
                    title = "设置",
                    subtitle = "",
                    leading = {
                        CampusIconButton(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "返回我的",
                            onClick = onBack
                        )
                    }
                )
            }
        }
        groups.forEachIndexed { index, (group, items) ->
            item(key = group.name) {
                Column(
                    Modifier.entrance(entrance, index + 1),
                    verticalArrangement = Arrangement.spacedBy(CampusSpace.sm)
                ) {
                    SectionLabel(group.title, Modifier.padding(start = CampusSpace.xs))
                    CampusCard {
                        items.forEachIndexed { position, entry ->
                            SettingsRow(
                                entry = entry,
                                state = state,
                                actions = actions,
                                updates = updates,
                                updateState = updateState,
                                permissionsGranted = permissionsGranted,
                                onTheme = { showTheme = true },
                                onDiagnostics = { showDiagnostics = true },
                                onHelp = { showHelp = true },
                                onDelete = { showDelete = true },
                                onAbout = { showAbout = true }
                            )
                            if (position < items.lastIndex) CampusRowDivider()
                        }
                    }
                    // 有版本可更新时它紧跟在「检查更新」那一组下面；没有的话这一块什么都不占。
                    if (group == SettingsGroup.UPDATE && updates != null) {
                        UpdateNoticeCard(updateState, updates)
                    }
                }
            }
        }
    }

    if (showTheme) ThemeDialog(state.theme, onDismiss = { showTheme = false }) {
        actions.setTheme(it)
        showTheme = false
    }
    if (showHelp) ConnectionHelpDialog(actions, onDismiss = { showHelp = false })
    if (showDiagnostics) DiagnosticsDialog(state, onDismiss = { showDiagnostics = false })
    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
    if (showDelete) DeleteAccountDialog(onDismiss = { showDelete = false }) {
        actions.deleteAccount()
        showDelete = false
    }
}

@Composable
private fun SettingsRow(
    entry: SettingsEntry,
    state: CampusUiState,
    actions: CampusActions,
    updates: UpdateActions?,
    updateState: UpdateUiState,
    permissionsGranted: Boolean,
    onTheme: () -> Unit,
    onDiagnostics: () -> Unit,
    onHelp: () -> Unit,
    onDelete: () -> Unit,
    onAbout: () -> Unit
) {
    val palette = LocalCampusPalette.current
    when (entry) {
        SettingsEntry.THEME -> CampusRow(
            title = entry.title,
            leadingIcon = Icons.Outlined.DarkMode,
            trailingText = state.theme.title,
            onClick = onTheme
        )

        SettingsEntry.AUTO_CONNECT -> AutoConnectRow(state, actions::setAutoConnect)

        SettingsEntry.PERMISSIONS -> CampusRow(
            title = entry.title,
            description = permissionRowDescription(permissionsGranted),
            leadingIcon = Icons.Outlined.Notifications,
            trailing = { PermissionPill(permissionsGranted) },
            onClick = actions::requestPermissions
        )

        SettingsEntry.DIAGNOSTICS -> CampusRow(
            title = entry.title,
            leadingIcon = Icons.Outlined.MonitorHeart,
            onClick = onDiagnostics
        )

        SettingsEntry.HELP -> CampusRow(
            title = entry.title,
            leadingIcon = Icons.AutoMirrored.Outlined.HelpOutline,
            onClick = onHelp
        )

        SettingsEntry.OFFICIAL_PORTAL -> CampusRow(
            title = entry.title,
            leadingIcon = Icons.AutoMirrored.Outlined.OpenInNew,
            onClick = actions::openOfficialPortal
        )

        SettingsEntry.CHECK_UPDATE -> CampusRow(
            title = entry.title,
            description = updateState.checkSummary ?: "当前版本 ${BuildConfig.VERSION_NAME}",
            leadingIcon = Icons.Outlined.SystemUpdate,
            trailingText = if (updateState.phase is UpdatePhase.Checking) "检查中…" else null,
            onClick = { updates?.checkForUpdate() },
            enabled = updates != null &&
                updateState.phase !is UpdatePhase.Checking &&
                updateState.phase !is UpdatePhase.Downloading
        )

        SettingsEntry.ABOUT -> CampusRow(
            title = entry.title,
            leadingIcon = Icons.Outlined.Info,
            trailingText = "师大随行 ${BuildConfig.VERSION_NAME}",
            onClick = onAbout
        )

        SettingsEntry.DELETE_ACCOUNT -> CampusRow(
            title = entry.title,
            leadingIcon = Icons.Outlined.DeleteOutline,
            leadingTint = palette.danger,
            leadingPlate = palette.dangerWash,
            titleColor = palette.danger,
            onClick = onDelete
        )
    }
}

/** 权限状态胶囊：颜色跟着授权状态过渡，不是换一个颜色值。 */
@Composable
private fun PermissionPill(granted: Boolean) {
    val palette = LocalCampusPalette.current
    val (targetInk, targetWash) = if (granted) palette.success to palette.successWash else palette.warning to palette.warningWash
    val ink: Color by animateColorAsState(
        targetValue = targetInk,
        animationSpec = tween(CampusMotion.DURATION_MS, easing = CampusEasing.standard),
        label = "permission_ink"
    )
    val wash: Color by animateColorAsState(
        targetValue = targetWash,
        animationSpec = tween(CampusMotion.DURATION_MS, easing = CampusEasing.standard),
        label = "permission_wash"
    )
    CampusPill(permissionRowLabel(granted), ink, wash)
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val palette = LocalCampusPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        shape = CampusRadius.lgShape,
        title = { Text("师大随行", style = MaterialTheme.typography.titleLarge, color = palette.textPrimary) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(CampusSpace.sm)
            ) {
                Text(
                    "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textPrimary
                )
                Text(
                    "广西师范大学 · 育才校区",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary
                )
                Text(
                    "当前支持 Android，Windows 和 iPhone 后续提供。",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textTertiary
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭", style = MaterialTheme.typography.labelLarge) }
        }
    )
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
