package cn.gxnu.campus.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cn.gxnu.campus.ui.CampusActions
import cn.gxnu.campus.ui.CampusUiState
import cn.gxnu.campus.ui.common.AutoConnectRow
import cn.gxnu.campus.ui.common.CampusCard
import cn.gxnu.campus.ui.common.CampusRow
import cn.gxnu.campus.ui.common.CampusRowDivider
import cn.gxnu.campus.ui.common.EntranceGate
import cn.gxnu.campus.ui.common.ThemeDialog
import cn.gxnu.campus.ui.common.entrance
import cn.gxnu.campus.ui.common.rememberPageEntrance
import cn.gxnu.campus.ui.theme.CampusSpace
import cn.gxnu.campus.ui.theme.LocalCampusPalette

/**
 * 我的：先说清「这是谁」，再把常用的三件事放在手边，最后一行进设置。
 *
 * 这一页不解释自己：能点的地方都长得像能点的样子，需要读的说明都在设置里的「连接帮助」。
 */
@Composable
fun ProfileScreen(
    state: CampusUiState,
    actions: CampusActions,
    gate: EntranceGate,
    onAccount: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showTheme by remember { mutableStateOf(false) }
    val entrance = rememberPageEntrance(gate, "profile", itemCount = 3)

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
        item(key = "identity") {
            Box(Modifier.entrance(entrance, 0)) { IdentityCard(state, onAccount) }
        }
        item(key = "common") {
            Box(Modifier.entrance(entrance, 1)) {
                CampusCard {
                    CampusRow(
                        title = "校园账号",
                        description = accountDescription(state),
                        leadingIcon = Icons.Outlined.PersonOutline,
                        trailingText = if (state.accountConfigured) "检查" else "设置",
                        onClick = onAccount,
                        enabled = !state.initializing && !state.accountSaving
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
        }
        item(key = "settings") {
            Box(Modifier.entrance(entrance, 2)) {
                CampusCard {
                    CampusRow(
                        title = "设置",
                        leadingIcon = Icons.Outlined.Settings,
                        onClick = onSettings
                    )
                }
            }
        }
    }

    if (showTheme) ThemeDialog(state.theme, onDismiss = { showTheme = false }) {
        actions.setTheme(it)
        showTheme = false
    }
}

/** 头像样式的标记加两行身份：这是谁、在哪个校区。 */
@Composable
private fun IdentityCard(state: CampusUiState, onAccount: () -> Unit) {
    val palette = LocalCampusPalette.current
    CampusCard {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onAccount)
                .padding(CampusSpace.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(Modifier.size(52.dp), shape = CircleShape, color = palette.accentWash) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.PersonOutline,
                        contentDescription = null,
                        modifier = Modifier.size(26.dp),
                        tint = palette.onAccentWash
                    )
                }
            }
            Spacer(Modifier.width(CampusSpace.lg))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (state.initializing) "正在读取…" else state.maskedAccount,
                    style = MaterialTheme.typography.titleLarge,
                    color = palette.textPrimary,
                    modifier = Modifier.semantics { heading() }
                )
                Text(
                    "广西师范大学 · 育才校区",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textTertiary
                )
            }
        }
    }
}

/** 一行说清这个账号现在是什么状态，不再多说一句。 */
private fun accountDescription(state: CampusUiState): String = when {
    state.initializing -> "正在读取…"
    !state.accountConfigured -> "未设置"
    state.accountSaved -> "已在此设备加密保存"
    else -> "仅本次使用"
}
