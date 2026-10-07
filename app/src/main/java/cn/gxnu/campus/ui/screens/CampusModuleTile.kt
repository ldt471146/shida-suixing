package cn.gxnu.campus.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.gxnu.campus.core.CampusRoute
import cn.gxnu.campus.ui.common.pressFeedback
import cn.gxnu.campus.ui.theme.CampusRadius
import cn.gxnu.campus.ui.theme.CampusSpace
import cn.gxnu.campus.ui.theme.LocalCampusPalette

/** 一个模块画哪一个图标。core 不认识 Compose，所以这个映射留在界面层。 */
internal fun moduleIcon(route: CampusRoute): ImageVector = when (route) {
    CampusRoute.CAMPUS_NETWORK -> Icons.Outlined.Wifi
    CampusRoute.TIMETABLE -> Icons.Outlined.CalendarMonth
}

/**
 * 功能页与首页共用的模块块：一块 40dp 的图标台加一个名字，没有第二行文字。
 *
 * 「功能」页的全部内容就是它的名字能说清的事情，所以这里刻意不留 description 的位置 ——
 * 有一个位置，迟早会有人填上。
 */
@Composable
internal fun CampusModuleTile(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalCampusPalette.current
    val interaction = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .pressFeedback(interaction),
        color = palette.surface,
        contentColor = palette.textPrimary,
        shape = CampusRadius.lgShape,
        border = BorderStroke(1.dp, palette.border),
        shadowElevation = palette.cardShadow
    ) {
        Column(
            Modifier
                .clickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    role = Role.Button,
                    onClick = onClick
                )
                .fillMaxWidth()
                .padding(CampusSpace.lg),
            verticalArrangement = Arrangement.spacedBy(CampusSpace.md)
        ) {
            Surface(Modifier.size(40.dp), shape = CampusRadius.mdShape, color = palette.accentWash) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = palette.onAccentWash)
                }
            }
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
