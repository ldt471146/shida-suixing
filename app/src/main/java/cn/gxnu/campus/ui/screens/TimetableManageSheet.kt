package cn.gxnu.campus.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cn.gxnu.campus.ui.common.CampusPrimaryButton
import cn.gxnu.campus.ui.theme.CampusRadius
import cn.gxnu.campus.ui.theme.CampusSpace
import cn.gxnu.campus.ui.theme.LocalCampusPalette

/**
 * 课表管理：更新、添加课程、设置开学日期、退出登录、删除课表。
 *
 * 这些动作原来铺在课表页最下面一整张卡片里，于是每个只想看课的人都得先经过它。现在它们只在
 * 点开右上角那一个图标时出现 —— 动作一个没少，只是不再占着页面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimetableManageSheet(
    account: String,
    signingIn: Boolean,
    onRefresh: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onDelete: () -> Unit,
    onAddCourse: () -> Unit,
    onSetTermStart: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalCampusPalette.current
    val signedIn = account.isNotBlank()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = CampusRadius.lgShape,
        containerColor = palette.surface,
        contentColor = palette.textPrimary
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = CampusSpace.lg)
                .padding(bottom = CampusSpace.xxl),
            verticalArrangement = Arrangement.spacedBy(CampusSpace.md)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "课表管理",
                    style = MaterialTheme.typography.titleLarge,
                    color = palette.textPrimary,
                    modifier = Modifier.semantics { heading() }
                )
                Text(
                    // 退出登录 之后课表还在，所以这一行说清楚现在这份课表是谁的、还能不能更新。
                    if (signedIn) "已登录 $account" else "未登录 · 正在用本机保存的课表",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textTertiary
                )
            }
            // 更新课表 is the one action that keeps this page in step with the 研究生系统, so it leads.
            CampusPrimaryButton(
                title = when {
                    signingIn -> "正在更新…"
                    signedIn -> "更新课表"
                    else -> "登录研究生系统"
                },
                onClick = if (signedIn) onRefresh else onSignIn,
                busy = signingIn,
                showProgress = false
            )
            // A fetched timetable is allowed to be wrong, so the manual route comes before everything
            // else: typing a missing 上课地点 must never require another round trip to the server.
            OutlinedActionButton("添加课程", Icons.Outlined.Add, onAddCourse, Modifier.fillMaxWidth(), enabled = !signingIn)
            Text(
                "取回来的课表可以在本机手动补正，不影响其他课程。",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textTertiary
            )
            OutlinedActionButton("设置开学日期", Icons.Outlined.CalendarMonth, onSetTermStart, Modifier.fillMaxWidth(), enabled = !signingIn)
            OutlinedActionButton(
                "退出登录",
                Icons.AutoMirrored.Outlined.Logout,
                onSignOut,
                Modifier.fillMaxWidth(),
                enabled = signedIn && !signingIn
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDelete, enabled = !signingIn, modifier = Modifier.heightIn(min = 44.dp)) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = palette.danger
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("删除课表", color = palette.danger, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
