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
            description = "导入教务系统的 Word 课表，或拍照识别生成周课表。",
            route = CampusRoute.TIMETABLE
        )
    )
}
