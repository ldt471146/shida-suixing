package cn.gxnu.campus.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.gxnu.campus.core.ConnectionStatus
import cn.gxnu.campus.ui.CampusActions
import cn.gxnu.campus.ui.CampusUiState
import cn.gxnu.campus.ui.theme.CampusRadius
import cn.gxnu.campus.ui.theme.CampusSpace
import cn.gxnu.campus.ui.theme.LocalCampusPalette

/** Raised surface: card fill, 1px border, radius 16, and only the light theme carries a shadow. */
@Composable
fun CampusCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalCampusPalette.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = palette.surface,
        shape = CampusRadius.lgShape,
        border = BorderStroke(1.dp, palette.border),
        shadowElevation = palette.cardShadow
    ) {
        Column(content = content)
    }
}

/**
 * Page title plus one muted subtitle line. [leading] carries the brand mark on the start page;
 * [trailing] stays reserved for a single icon button.
 */
@Composable
fun CampusPageHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val palette = LocalCampusPalette.current
    Row(
        modifier.fillMaxWidth().heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(CampusSpace.md))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                color = palette.textPrimary,
                modifier = Modifier.semantics { heading() }
            )
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = palette.textTertiary)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(CampusSpace.sm))
            trailing()
        }
    }
}

@Composable
fun SectionLabel(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        modifier = modifier.semantics { heading() },
        style = MaterialTheme.typography.labelMedium,
        color = LocalCampusPalette.current.textTertiary
    )
}

/** 36dp muted plate behind a 18dp stroke icon. The default leading mark for rows. */
@Composable
fun CampusIconPlate(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    plate: Color? = null
) {
    val palette = LocalCampusPalette.current
    Surface(
        modifier = modifier.size(36.dp),
        shape = CampusRadius.mdShape,
        color = plate ?: palette.muted
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = tint ?: palette.textSecondary)
        }
    }
}

@Composable
fun CampusRowDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier.padding(start = CampusSpace.lg),
        color = LocalCampusPalette.current.border
    )
}

/**
 * Settings / list row: optional stroke icon plate, title, one muted description line, and a
 * right-hand control. The right side is a value, a chevron, or a caller-supplied control.
 */
@Composable
fun CampusRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    leadingIcon: ImageVector? = null,
    leadingTint: Color? = null,
    leadingPlate: Color? = null,
    trailingText: String? = null,
    titleColor: Color? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    showChevron: Boolean = true,
    trailing: (@Composable () -> Unit)? = null
) {
    val palette = LocalCampusPalette.current
    val interactive = if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier
    Row(
        modifier.fillMaxWidth().then(interactive)
            .heightIn(min = 56.dp)
            .padding(horizontal = CampusSpace.lg, vertical = CampusSpace.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            CampusIconPlate(leadingIcon, tint = leadingTint, plate = leadingPlate)
            Spacer(Modifier.width(CampusSpace.md))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = titleColor ?: palette.textPrimary
            )
            if (description != null) {
                // One ellipsized line: a wrapping description would otherwise squeeze the
                // right-hand control out of the row at large font scales.
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (trailingText != null) {
            Spacer(Modifier.width(CampusSpace.md))
            Text(
                trailingText,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(CampusSpace.sm))
            trailing()
        }
        // The right slot carries one element: a value or a control already says the row leads
        // somewhere, and competing for that space starves the value at large font scales.
        if (showChevron && onClick != null && trailingText == null && trailing == null) {
            Spacer(Modifier.width(CampusSpace.xs))
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = palette.textTertiary
            )
        }
    }
}

/** Small status pill: muted tinted plate, 11-12sp medium label. */
@Composable
fun CampusPill(text: String, ink: Color, background: Color, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = background, contentColor = ink, shape = CampusRadius.pillShape) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun AutoConnectRow(state: CampusUiState, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    CampusRow(
        title = "自动连接",
        modifier = modifier,
        description = when {
            state.initializing -> "正在读取设置…"
            !state.autoConnect -> "连接 GXNU-YC 后自动认证"
            state.autoRunning -> "GXNU-YC · 后台服务运行中"
            else -> "GXNU-YC · 等待后台服务启动"
        },
        leadingIcon = Icons.Outlined.Autorenew,
        showChevron = false,
        trailing = {
            CampusSwitch(
                state.autoConnect,
                onCheckedChange,
                label = "自动连接",
                enabled = !state.initializing && !state.accountSaving
            )
        }
    )
}

@Composable
fun OfficialPortalLink(actions: CampusActions, modifier: Modifier = Modifier) {
    val palette = LocalCampusPalette.current
    Surface(
        modifier = modifier.heightIn(min = 44.dp),
        color = palette.surface,
        contentColor = palette.textSecondary,
        shape = CampusRadius.mdShape,
        border = BorderStroke(1.dp, palette.border)
    ) {
        Row(
            Modifier.clickable(role = Role.Button, onClick = actions::openOfficialPortal)
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = CampusSpace.lg),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("学校认证页面", style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
fun ConnectionHelpDialog(actions: CampusActions, onDismiss: () -> Unit) {
    val palette = LocalCampusPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        shape = CampusRadius.lgShape,
        title = { Text("连接帮助", style = MaterialTheme.typography.titleLarge, color = palette.textPrimary) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(CampusSpace.lg)
            ) {
                HelpParagraph("先连接校园 Wi-Fi", "在系统 Wi-Fi 设置中选择 GXNU-YC。校园账号只会用于这一个网络。")
                HelpParagraph("账号与运营商", "填写学校校园网账号和密码，并选择学校认证页面对应的运营商。账号有误时，请修改后再次连接。")
                HelpParagraph("自动连接如何运行", "开启后，App 会通过带持续通知的后台服务监听校园 Wi-Fi。强制停止、设备重启或权限撤销后，重新打开 App 恢复服务。")
                HelpParagraph("认证后仍不能上网", "App 会继续检查校园 Wi-Fi 的外网。学校入口异常或需要人工验证时，可打开学校认证页面处理。")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了", style = MaterialTheme.typography.labelLarge) }
        },
        dismissButton = {
            TextButton(onClick = actions::openOfficialPortal) { Text("学校认证页面", style = MaterialTheme.typography.labelLarge) }
        }
    )
}

@Composable
private fun HelpParagraph(title: String, text: String) {
    val palette = LocalCampusPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(CampusSpace.xs)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = palette.textPrimary)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = palette.textSecondary)
    }
}

@Composable
fun DeleteAccountDialog(onDismiss: () -> Unit, onDelete: () -> Unit) {
    val palette = LocalCampusPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        shape = CampusRadius.lgShape,
        title = { Text("删除校园账号？", style = MaterialTheme.typography.titleLarge, color = palette.textPrimary) },
        text = {
            Text(
                "会移除此设备上的账号和密码，并关闭自动连接。之后可以重新设置。",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary
            )
        },
        confirmButton = {
            TextButton(onClick = onDelete) {
                Text("删除账号", color = palette.danger, style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", style = MaterialTheme.typography.labelLarge) }
        }
    )
}

fun connectionStatusLabel(status: ConnectionStatus): String = when (status) {
    ConnectionStatus.NO_WIFI -> "未连接"
    ConnectionStatus.OUTSIDE_CAMPUS -> "其他网络"
    ConnectionStatus.NEED_ACCOUNT -> "待设置"
    ConnectionStatus.NEED_PROVIDER -> "待选择"
    ConnectionStatus.NEED_PERMISSION -> "需要权限"
    ConnectionStatus.READY -> "待认证"
    ConnectionStatus.PREPARING -> "识别网络"
    ConnectionStatus.CHECKING -> "检查网络"
    ConnectionStatus.AUTHENTICATING -> "认证中"
    ConnectionStatus.VERIFYING -> "验证中"
    ConnectionStatus.ONLINE -> "已连接"
    ConnectionStatus.AUTH_ERROR -> "认证失败"
    ConnectionStatus.UNREACHABLE -> "连接异常"
    ConnectionStatus.CANCELLED -> "已取消"
}

/** Muted chip used for the preview notice: a quiet label, not a full-width alert banner. */
@Composable
fun CampusNoticeStrip(text: String, modifier: Modifier = Modifier) {
    val palette = LocalCampusPalette.current
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            color = palette.muted,
            shape = CampusRadius.pillShape,
            border = BorderStroke(1.dp, palette.border)
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
                modifier = Modifier.padding(horizontal = CampusSpace.md, vertical = CampusSpace.xs)
            )
        }
    }
}
