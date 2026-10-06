package cn.gxnu.campus.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.gxnu.campus.core.ConnectionStatus
import cn.gxnu.campus.ui.theme.CampusRadius
import cn.gxnu.campus.ui.theme.CampusSpace
import cn.gxnu.campus.ui.theme.LocalCampusMotionEnabled
import cn.gxnu.campus.ui.theme.LocalCampusPalette

@Composable
fun CampusSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val palette = LocalCampusPalette.current
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier.size(width = 60.dp, height = 44.dp).semantics {
            contentDescription = label
            stateDescription = if (checked) "已开启" else "已关闭"
        },
        colors = SwitchDefaults.colors(
            checkedTrackColor = palette.accent,
            checkedThumbColor = palette.onAccent,
            checkedBorderColor = palette.accent,
            uncheckedTrackColor = if (palette.isDark) Color(0xFF4A505A) else Color(0xFFDFE1E6),
            uncheckedThumbColor = if (palette.isDark) Color(0xFFD7DBE2) else Color.White,
            uncheckedBorderColor = Color.Transparent
        )
    )
}

@Composable
fun CampusPrimaryButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    showProgress: Boolean = true
) {
    val palette = LocalCampusPalette.current
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        shape = CampusRadius.mdShape,
        elevation = null,
        colors = ButtonDefaults.buttonColors(
            containerColor = palette.accent,
            contentColor = palette.onAccent,
            disabledContainerColor = if (busy) palette.accent else palette.muted,
            disabledContentColor = if (busy) palette.onAccent else palette.textTertiary
        ),
        contentPadding = PaddingValues(horizontal = CampusSpace.lg, vertical = CampusSpace.md),
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (busy && showProgress && LocalCampusMotionEnabled.current) {
            Spacer(Modifier.width(CampusSpace.sm))
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = palette.onAccent,
                trackColor = palette.onAccent.copy(alpha = .24f),
                strokeWidth = 2.dp
            )
        }
    }
}

/** Ghost button: surface fill plus a 1px border. Used for every secondary action. */
@Composable
fun CampusGhostButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    val palette = LocalCampusPalette.current
    Surface(
        modifier = modifier.heightIn(min = 44.dp),
        color = palette.surface,
        contentColor = if (enabled) palette.textSecondary else palette.textTertiary,
        shape = CampusRadius.mdShape,
        border = BorderStroke(1.dp, palette.border)
    ) {
        Row(
            Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = CampusSpace.md),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Ghost icon button for page-header chrome. */
@Composable
fun CampusIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalCampusPalette.current
    Surface(
        modifier = modifier.size(40.dp),
        color = palette.surface,
        contentColor = palette.textSecondary,
        shape = CampusRadius.mdShape,
        border = BorderStroke(1.dp, palette.border)
    ) {
        Box(
            Modifier.clickable(role = Role.Button, onClick = onClick).fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun NetworkStatusPill(status: ConnectionStatus, modifier: Modifier = Modifier) {
    val (ink, background) = statusPillColors(status)
    CampusPill(connectionStatusLabel(status), ink, background, modifier)
}

/** One mapping shared by the pill and the headline status plate, so state colour never drifts. */
@Composable
internal fun statusPillColors(status: ConnectionStatus): Pair<Color, Color> {
    val palette = LocalCampusPalette.current
    return when (status) {
        ConnectionStatus.ONLINE -> palette.success to palette.successWash
        ConnectionStatus.AUTH_ERROR, ConnectionStatus.UNREACHABLE -> palette.danger to palette.dangerWash
        ConnectionStatus.NEED_PERMISSION -> palette.warning to palette.warningWash
        ConnectionStatus.NO_WIFI, ConnectionStatus.OUTSIDE_CAMPUS, ConnectionStatus.CANCELLED ->
            palette.textSecondary to palette.muted
        else -> palette.onAccentWash to palette.accentWash
    }
}
