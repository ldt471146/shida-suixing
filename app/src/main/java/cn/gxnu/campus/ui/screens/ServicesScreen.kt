package cn.gxnu.campus.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cn.gxnu.campus.core.CampusModules
import cn.gxnu.campus.core.CampusRoute
import cn.gxnu.campus.ui.common.CampusPageHeader
import cn.gxnu.campus.ui.common.EntranceGate
import cn.gxnu.campus.ui.common.entrance
import cn.gxnu.campus.ui.common.rememberPageEntrance
import cn.gxnu.campus.ui.theme.CampusSpace

/**
 * 功能：一个两列的模块网格，每块只有图标和名字。
 *
 * 这里没有一句介绍、没有「后续计划」、没有页脚说明 —— 一页介绍会让「挑一件事做」变成「读一页
 * 说明」。点开才是校园网，所以不认识校园网的人可以直接绕开。
 */
@Composable
fun ServicesScreen(
    gate: EntranceGate,
    onRoute: (CampusRoute) -> Unit,
    modifier: Modifier = Modifier
) {
    val modules = CampusModules.available
    val entrance = rememberPageEntrance(gate, "services", itemCount = 1)
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier,
        contentPadding = PaddingValues(
            start = CampusSpace.lg,
            end = CampusSpace.lg,
            top = CampusSpace.md,
            bottom = CampusSpace.xxl
        ),
        horizontalArrangement = Arrangement.spacedBy(CampusSpace.md),
        verticalArrangement = Arrangement.spacedBy(CampusSpace.md)
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
            CampusPageHeader(title = "功能", subtitle = "")
        }
        itemsIndexed(
            items = modules,
            key = { _, module -> module.id },
            contentType = { _, _ -> "module" }
        ) { index, module ->
            CampusModuleTile(
                title = module.title,
                icon = moduleIcon(module.route),
                onClick = { onRoute(module.route) },
                // 入场的错峰只属于网格本身；标题不参与，免得页面一进来就先动一下标题。
                modifier = Modifier.entrance(entrance, index)
            )
        }
    }
}
