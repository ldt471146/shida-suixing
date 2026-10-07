package cn.gxnu.campus.core

enum class CampusRoute { CAMPUS_NETWORK, TIMETABLE }

/**
 * 功能页上的一个模块：它是什么、叫什么、点开去哪里。
 *
 * 这里刻意没有 description —— 一句话介绍就足以把一个「挑一件事做」的页面变回需要阅读的页面。
 * 图标也不在这里，因为 core 不依赖 Compose；界面层按 [route] 决定画哪一个（见 ui.screens.moduleIcon）。
 */
data class CampusModule(
    val id: String,
    val title: String,
    val route: CampusRoute
)

object CampusModules {
    val available: List<CampusModule> = listOf(
        CampusModule(
            id = "campus-network",
            title = "校园网",
            route = CampusRoute.CAMPUS_NETWORK
        ),
        CampusModule(
            id = "timetable",
            title = "课表",
            route = CampusRoute.TIMETABLE
        )
    )
}
