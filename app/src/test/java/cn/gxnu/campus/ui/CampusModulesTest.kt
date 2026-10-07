package cn.gxnu.campus.ui

import cn.gxnu.campus.core.CampusModules
import cn.gxnu.campus.core.CampusRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 功能页的模块清单。
 *
 * 校园网现在和课表并列在这张清单里，所以「清单里有什么」本身就是信息架构的一部分：少一条，
 * 校园网就再也进不去。
 */
class CampusModulesTest {

    @Test
    fun theServicesPageOffersCampusNetworkAndTimetableSideBySide() {
        assertEquals(listOf("campus-network", "timetable"), CampusModules.available.map { it.id })
        assertEquals(listOf("校园网", "课表"), CampusModules.available.map { it.title })
        assertEquals(
            listOf(CampusRoute.CAMPUS_NETWORK, CampusRoute.TIMETABLE),
            CampusModules.available.map { it.route }
        )
    }

    @Test
    fun everyModuleHasAStableUniqueIdBecauseItIsTheListKey() {
        val ids = CampusModules.available.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.none { it.isBlank() })
    }

    @Test
    fun noModuleCarriesAnIntroductionLine() {
        // 功能页现在是图标 + 名字；这条断言防的是「介绍文字」从别处悄悄回来。
        // 数据结构里已经没有 description 字段，所以这里确认的是清单本身只声明了这四件事。
        val properties = cn.gxnu.campus.core.CampusModule::class.java.declaredFields
            .map { it.name }
            .filterNot { it.startsWith("$") }
            .toSet()
        assertEquals(setOf("id", "title", "route"), properties)
    }
}
