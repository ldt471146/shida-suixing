package cn.gxnu.campus.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cn.gxnu.campus.core.CampusModules
import cn.gxnu.campus.core.CampusRoute
import cn.gxnu.campus.ui.CampusUiState
import cn.gxnu.campus.ui.common.CampusCard
import cn.gxnu.campus.ui.common.CampusPageHeader
import cn.gxnu.campus.ui.common.CampusPill
import cn.gxnu.campus.ui.common.CampusRow
import cn.gxnu.campus.ui.common.CampusRowDivider
import cn.gxnu.campus.ui.common.NetworkStatusPill
import cn.gxnu.campus.ui.common.SectionLabel
import cn.gxnu.campus.ui.common.connectionHeadline
import cn.gxnu.campus.ui.theme.CampusSpace
import cn.gxnu.campus.ui.theme.LocalCampusPalette

@Composable
fun ServicesScreen(
    state: CampusUiState,
    onCampusNetwork: () -> Unit,
    onTimetable: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalCampusPalette.current
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
        item {
            CampusPageHeader(title = "校园服务", subtitle = "广西师范大学 · 育才校区")
        }
        item { SectionLabel("已上线", Modifier.padding(start = CampusSpace.xs)) }
        item {
            CampusCard {
                CampusModules.available.forEachIndexed { index, module ->
                    CampusRow(
                        title = module.title,
                        description = module.description,
                        leadingIcon = if (module.route == CampusRoute.CAMPUS_NETWORK) Icons.Outlined.Wifi else Icons.Outlined.Schedule,
                        leadingTint = if (module.route == CampusRoute.CAMPUS_NETWORK) palette.onAccentWash else null,
                        leadingPlate = if (module.route == CampusRoute.CAMPUS_NETWORK) palette.accentWash else null,
                        // The campus-network status belongs to the campus-network row only.
                        trailing = if (module.route == CampusRoute.CAMPUS_NETWORK) {
                            {
                                // Until the runtime has read the saved settings there is no status to
                                // report: state.status is still its NO_WIFI default, so a pill would
                                // claim 未连接. 首页 answers the same state with the read-in-progress
                                // headline, so this row borrows it instead of stating a verdict.
                                if (state.initializing) {
                                    CampusPill(connectionHeadline(state), palette.onAccentWash, palette.accentWash)
                                } else {
                                    NetworkStatusPill(state.status)
                                }
                            }
                        } else null,
                        onClick = {
                            when (module.route) {
                                CampusRoute.CAMPUS_NETWORK -> onCampusNetwork()
                                CampusRoute.TIMETABLE -> onTimetable()
                            }
                        }
                    )
                    if (index < CampusModules.available.lastIndex) CampusRowDivider()
                }
            }
        }
        item { SectionLabel("后续计划", Modifier.padding(start = CampusSpace.xs)) }
        item {
            CampusCard {
                CampusRow(
                    title = "校历、校园办事",
                    description = "以上服务尚未开放。",
                    leadingIcon = Icons.Outlined.Schedule,
                    showChevron = false
                )
            }
        }
        item {
            Text(
                "当前支持 Android，Windows 和 iPhone 后续提供。",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textTertiary
            )
        }
    }
}
