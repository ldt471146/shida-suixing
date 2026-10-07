package cn.gxnu.campus.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import cn.gxnu.campus.ui.ThemeMode
import cn.gxnu.campus.ui.theme.CampusRadius
import cn.gxnu.campus.ui.theme.LocalCampusPalette

/** 主题选择：我的和设置两处进的是同一个对话框，所以它只有一份。 */
@Composable
fun ThemeDialog(selected: ThemeMode, onDismiss: () -> Unit, onSelect: (ThemeMode) -> Unit) {
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
