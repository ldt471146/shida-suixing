package cn.gxnu.campus.core

enum class CampusRoute { CAMPUS_NETWORK, TIMETABLE }

data class CampusModule(
    val id: String,
    val title: String,
    val description: String,
    val route: CampusRoute
)

object CampusModules {
    val available: List<CampusModule> = listOf(
        CampusModule(
            id = "campus-network",
            title = "校园网",
            description = "选择供应商，连接校园 Wi-Fi。",
            route = CampusRoute.CAMPUS_NETWORK
        ),
        CampusModule(
            id = "timetable",
            title = "课表",
            description = "登录研究生系统，把课表取回本机。",
            route = CampusRoute.TIMETABLE
        )
    )
}
