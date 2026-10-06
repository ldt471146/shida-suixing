package cn.gxnu.campus.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import cn.gxnu.campus.ui.UpdateActions
import cn.gxnu.campus.ui.UpdatePhase
import cn.gxnu.campus.ui.UpdateUiState
import cn.gxnu.campus.ui.theme.CampusSpace
import cn.gxnu.campus.ui.theme.LocalCampusPalette

/**
 * The update notice. It is a card in the app's settings-list language rather than a banner: one
 * accent action on the right, one muted dismiss icon, and nothing at all to see once there is no
 * release to offer.
 */
@Composable
fun UpdateNoticeCard(state: UpdateUiState, actions: UpdateActions, modifier: Modifier = Modifier) {
    val phase = state.phase
    if (phase is UpdatePhase.Hidden || phase is UpdatePhase.Checking) return
    CampusCard(modifier.testTag("update_notice")) {
        CampusRow(
            title = updateTitle(phase),
            description = updateDescription(phase),
            leadingIcon = updateIcon(phase),
            showChevron = false,
            onClick = updateAction(phase, actions),
            trailing = { UpdateTrailing(phase, actions) }
        )
        if (phase is UpdatePhase.Downloading) {
            UpdateProgress(
                phase,
                Modifier.padding(
                    start = CampusSpace.lg,
                    end = CampusSpace.lg,
                    bottom = CampusSpace.lg
                )
            )
        }
    }
}

/** The accent label is the row's action; the dismiss icon only exists before a transfer starts. */
@Composable
private fun UpdateTrailing(phase: UpdatePhase, actions: UpdateActions) {
    val palette = LocalCampusPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            updateActionLabel(phase),
            style = MaterialTheme.typography.labelLarge,
            color = palette.accent
        )
        if (phase is UpdatePhase.Available) {
            Spacer(Modifier.width(CampusSpace.xs))
            CampusIconButton(
                icon = Icons.Outlined.Close,
                contentDescription = "暂不提示这个版本",
                onClick = actions::dismissUpdate
            )
        }
    }
}

@Composable
private fun UpdateProgress(phase: UpdatePhase.Downloading, modifier: Modifier = Modifier) {
    val palette = LocalCampusPalette.current
    val track = modifier.fillMaxWidth()
    if (phase.totalBytes > 0) {
        LinearProgressIndicator(
            progress = { (phase.downloadedBytes.toFloat() / phase.totalBytes).coerceIn(0f, 1f) },
            modifier = track,
            color = palette.accent,
            trackColor = palette.muted
        )
    } else {
        LinearProgressIndicator(modifier = track, color = palette.accent, trackColor = palette.muted)
    }
}

private fun updateTitle(phase: UpdatePhase): String = when (phase) {
    is UpdatePhase.Available -> "发现新版本 ${phase.versionName}"
    is UpdatePhase.Downloading -> "正在下载 ${phase.versionName}"
    is UpdatePhase.Ready -> "新版本已就绪"
    is UpdatePhase.Failed -> "更新未完成"
    UpdatePhase.Hidden, UpdatePhase.Checking -> ""
}

private fun updateDescription(phase: UpdatePhase): String = when (phase) {
    is UpdatePhase.Available -> if (phase.apkBytes > 0) {
        "安装包 ${sizeText(phase.apkBytes)} · 下载后由系统确认安装"
    } else {
        "下载后由系统确认安装"
    }
    is UpdatePhase.Downloading -> if (phase.totalBytes > 0) {
        "已下载 ${(phase.downloadedBytes * 100 / phase.totalBytes).coerceIn(0L, 100L)}%"
    } else {
        "已下载 ${sizeText(phase.downloadedBytes)}"
    }
    is UpdatePhase.Ready -> if (phase.needsInstallPermission) {
        "请先允许本应用安装应用"
    } else {
        "点击安装，系统会再次确认"
    }
    is UpdatePhase.Failed -> phase.message
    UpdatePhase.Hidden, UpdatePhase.Checking -> ""
}

private fun updateActionLabel(phase: UpdatePhase): String = when (phase) {
    is UpdatePhase.Available -> "更新"
    is UpdatePhase.Downloading -> "取消"
    is UpdatePhase.Ready -> if (phase.needsInstallPermission) "去设置" else "安装"
    is UpdatePhase.Failed -> "重试"
    UpdatePhase.Hidden, UpdatePhase.Checking -> ""
}

private fun updateAction(phase: UpdatePhase, actions: UpdateActions): (() -> Unit)? = when (phase) {
    is UpdatePhase.Available, is UpdatePhase.Failed -> actions::downloadUpdate
    is UpdatePhase.Downloading -> actions::cancelDownload
    is UpdatePhase.Ready -> actions::installUpdate
    UpdatePhase.Hidden, UpdatePhase.Checking -> null
}

private fun updateIcon(phase: UpdatePhase): ImageVector = when (phase) {
    is UpdatePhase.Available -> Icons.Outlined.SystemUpdate
    is UpdatePhase.Downloading -> Icons.Outlined.Download
    is UpdatePhase.Ready -> Icons.Outlined.CheckCircle
    is UpdatePhase.Failed -> Icons.Outlined.ErrorOutline
    UpdatePhase.Hidden, UpdatePhase.Checking -> Icons.Outlined.SystemUpdate
}

/** Decimal-free size text: an update notice should read the same in every locale. */
private fun sizeText(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kilobytes = bytes / 1024
    if (kilobytes < 1024) return "$kilobytes KB"
    val tenths = bytes * 10 / (1024 * 1024)
    return "${tenths / 10}.${tenths % 10} MB"
}
