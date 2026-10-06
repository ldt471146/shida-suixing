package cn.gxnu.campus.ui.common

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import cn.gxnu.campus.core.ConnectionStatus
import cn.gxnu.campus.core.PortalFailure
import cn.gxnu.campus.ui.CampusUiState
import cn.gxnu.campus.ui.theme.CampusMotion
import cn.gxnu.campus.ui.theme.CampusRadius
import cn.gxnu.campus.ui.theme.CampusSpace
import cn.gxnu.campus.ui.theme.LocalCampusMotionEnabled
import cn.gxnu.campus.ui.theme.LocalCampusPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

fun ConnectionStatus.isConnecting(): Boolean = when (this) {
    ConnectionStatus.PREPARING, ConnectionStatus.CHECKING,
    ConnectionStatus.AUTHENTICATING, ConnectionStatus.VERIFYING -> true
    else -> false
}

fun connectionHeadline(state: CampusUiState): String = if (state.initializing) {
    "正在读取设置"
} else when (state.status) {
    ConnectionStatus.NO_WIFI -> "先连接校园 Wi-Fi"
    ConnectionStatus.OUTSIDE_CAMPUS -> "请切换校园 Wi-Fi"
    ConnectionStatus.NEED_ACCOUNT -> "设置校园账号"
    ConnectionStatus.NEED_PROVIDER -> "请选择运营商"
    ConnectionStatus.NEED_PERMISSION -> "需要网络权限"
    ConnectionStatus.READY -> "可以连接校园网"
    ConnectionStatus.PREPARING -> "正在识别网络"
    ConnectionStatus.CHECKING -> "正在检查网络"
    ConnectionStatus.AUTHENTICATING -> "正在认证账号"
    ConnectionStatus.VERIFYING -> "正在确认上网"
    ConnectionStatus.ONLINE -> "已连接校园网"
    ConnectionStatus.CANCELLED -> "已取消连接"
    ConnectionStatus.AUTH_ERROR -> "账号认证失败"
    ConnectionStatus.UNREACHABLE -> when (state.failure) {
        PortalFailure.TIMEOUT -> "连接超时"
        PortalFailure.VERIFICATION -> "暂时无法上网"
        else -> "暂时无法连接"
    }
}

/**
 * The primary status surface: what the network is doing right now, why, and - while an attempt
 * runs - which stage it reached. Read as one live region so a screen reader announces the change
 * once instead of piece by piece.
 */
@Composable
fun ConnectionFeedback(state: CampusUiState, modifier: Modifier = Modifier) {
    val palette = LocalCampusPalette.current
    val motionDuration = if (LocalCampusMotionEnabled.current) CampusMotion.DURATION_MS else 0
    val title = connectionHeadline(state)
    val message = if (state.initializing) "正在读取已保存的设置，请稍候。" else state.message
    val networkLabel = if (state.initializing) "校园网" else "校园网 · ${state.wifiName}"
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CampusSpace.md)) {
        Column(
            Modifier.fillMaxWidth().clearAndSetSemantics {
                liveRegion = LiveRegionMode.Polite
                heading()
                contentDescription = "$networkLabel。$title。$message"
            },
            verticalArrangement = Arrangement.spacedBy(CampusSpace.sm)
        ) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CampusSpace.xs)) {
                    Text(
                        networkLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.textTertiary
                    )
                    Crossfade(
                        targetState = title,
                        animationSpec = tween(motionDuration),
                        label = "connection_title"
                    ) { visibleTitle ->
                        Text(visibleTitle, style = MaterialTheme.typography.headlineMedium, color = palette.textPrimary)
                    }
                }
                Spacer(Modifier.width(CampusSpace.lg))
                ConnectionStatusVisual(state.status, state.initializing)
            }
            Crossfade(
                targetState = message,
                animationSpec = tween(motionDuration),
                label = "connection_reason"
            ) { visibleMessage ->
                Text(visibleMessage, style = MaterialTheme.typography.bodyMedium, color = palette.textSecondary)
            }
        }
        if (state.status.isConnecting() && !state.initializing) {
            ConnectionStages(state.status)
            ConnectionElapsedTime(state.attemptId, state.connectionStartedAtMillis)
        }
    }
}

/**
 * A 44dp stroke plate in the state colour, ringed by a sweep while an attempt is running. The
 * colour transition is the only motion; nothing bounces.
 */
@Composable
private fun ConnectionStatusVisual(status: ConnectionStatus, initializing: Boolean) {
    val palette = LocalCampusPalette.current
    val motionEnabled = LocalCampusMotionEnabled.current
    val busy = initializing || status.isConnecting()
    val (targetInk, targetContainer) = if (initializing) {
        palette.accent to palette.accentWash
    } else {
        statusPillColors(status)
    }
    val ink by animateColorAsState(
        targetInk,
        tween(if (motionEnabled) CampusMotion.DURATION_MS else 0),
        label = "connection_ink"
    )
    val container by animateColorAsState(
        targetContainer,
        tween(if (motionEnabled) CampusMotion.DURATION_MS else 0),
        label = "connection_container"
    )
    val rotation: State<Float> = if (busy && motionEnabled) {
        val transition = rememberInfiniteTransition(label = "connection_activity")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
            label = "connection_activity_rotation"
        )
    } else rememberUpdatedState(0f)

    Box(Modifier.size(52.dp).testTag("connection_activity").clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        Surface(Modifier.size(44.dp), shape = CircleShape, color = container) {
            Box(contentAlignment = Alignment.Center) {
                Icon(statusIcon(status, initializing), contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
            }
        }
        if (busy) {
            Canvas(Modifier.matchParentSize()) {
                val stroke = 1.5.dp.toPx()
                val inset = stroke / 2f
                val bounds = Size(size.width - stroke, size.height - stroke)
                drawArc(
                    color = ink,
                    startAngle = if (motionEnabled) rotation.value - 90f else -90f,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = bounds,
                    style = Stroke(stroke, cap = StrokeCap.Round)
                )
            }
        }
    }
}

private fun statusIcon(status: ConnectionStatus, initializing: Boolean): ImageVector = when {
    initializing -> Icons.Outlined.Wifi
    status == ConnectionStatus.ONLINE -> Icons.Outlined.Check
    status == ConnectionStatus.AUTH_ERROR || status == ConnectionStatus.UNREACHABLE -> Icons.Outlined.ErrorOutline
    status == ConnectionStatus.NO_WIFI || status == ConnectionStatus.OUTSIDE_CAMPUS -> Icons.Outlined.WifiOff
    else -> Icons.Outlined.Wifi
}

/**
 * Three quiet step chips. The pre-authentication internet check belongs to identifying the
 * network, and an already-online network can skip authentication entirely.
 */
@Composable
private fun ConnectionStages(status: ConnectionStatus) {
    val current = when (status) {
        ConnectionStatus.AUTHENTICATING -> 1
        ConnectionStatus.VERIFYING -> 2
        else -> 0
    }
    val labels = listOf("校园 Wi-Fi", "账号认证", "确认上网")
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 300.dp || fontScale > 1.3f) {
            Column(verticalArrangement = Arrangement.spacedBy(CampusSpace.sm)) {
                labels.forEachIndexed { index, label -> ConnectionStage(label, index, current) }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CampusSpace.sm)) {
                labels.forEachIndexed { index, label -> ConnectionStage(label, index, current, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ConnectionStage(label: String, index: Int, current: Int, modifier: Modifier = Modifier) {
    val palette = LocalCampusPalette.current
    val motionEnabled = LocalCampusMotionEnabled.current
    val targetInk = when {
        index < current -> palette.success
        index == current -> palette.onAccentWash
        else -> palette.textTertiary
    }
    val targetPlate = when {
        index < current -> palette.successWash
        index == current -> palette.accentWash
        else -> palette.muted
    }
    val ink by animateColorAsState(targetInk, tween(if (motionEnabled) CampusMotion.DURATION_MS else 0), label = "connection_stage_ink")
    val plate by animateColorAsState(targetPlate, tween(if (motionEnabled) CampusMotion.DURATION_MS else 0), label = "connection_stage_plate")
    Surface(
        modifier = modifier.semantics(mergeDescendants = true) {
            stateDescription = when {
                index < current -> "已完成"
                index == current -> "进行中"
                else -> "尚未进行"
            }
        },
        shape = CampusRadius.pillShape,
        color = plate,
        contentColor = ink
    ) {
        Row(
            Modifier.padding(horizontal = CampusSpace.md, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (index < current) {
                Icon(Icons.Outlined.Check, contentDescription = null, tint = ink, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(CampusSpace.xs))
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = ink)
        }
    }
}

@Composable
private fun ConnectionElapsedTime(attemptId: Long, startedAtMillis: Long?) {
    var elapsed by remember(attemptId, startedAtMillis) { mutableLongStateOf(elapsedSeconds(startedAtMillis)) }
    LaunchedEffect(attemptId, startedAtMillis) {
        if (startedAtMillis != null) {
            while (isActive) {
                delay(1000)
                elapsed = elapsedSeconds(startedAtMillis)
            }
        }
    }
    Text(
        if (startedAtMillis == null) "连接中，请稍候。" else "已等待 $elapsed 秒",
        style = MaterialTheme.typography.labelMedium,
        color = LocalCampusPalette.current.textTertiary,
        modifier = Modifier.testTag("connection_elapsed").clearAndSetSemantics { }
    )
}

private fun elapsedSeconds(startedAtMillis: Long?): Long =
    if (startedAtMillis == null) 0L else ((System.nanoTime() / 1_000_000 - startedAtMillis).coerceAtLeast(0L) / 1000)
