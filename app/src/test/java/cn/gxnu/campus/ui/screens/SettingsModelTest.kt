package cn.gxnu.campus.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页的清单：哪些条目在什么条件下出现、落在哪一组。
 *
 * 「有什么」现在是一个纯函数，所以「没有账号就不该有删除账号」这类规则不靠读界面来确认。
 */
class SettingsModelTest {

    private val everything = settingsEntries(accountConfigured = true, updatesAvailable = true)

    @Test
    fun everySettingsEntryBelongsToAGroupAndIsReachable() {
        val listed = everything.toSet()
        SettingsEntry.entries.forEach { entry ->
            assertTrue("$entry 没有出现在清单里", entry in listed)
        }
    }

    @Test
    fun theOrderKeepsEachGroupTogether() {
        // 分组是照着枚举声明顺序排的，所以清单里的分组序号必须单调不减。
        val ordinals = everything.map { it.group.ordinal }
        assertEquals(ordinals.sorted(), ordinals)
    }

    @Test
    fun deletingAnAccountOnlyExistsWhenThereIsOne() {
        assertTrue(SettingsEntry.DELETE_ACCOUNT in everything)
        assertFalse(
            "没有校园账号就没有账号可删",
            SettingsEntry.DELETE_ACCOUNT in settingsEntries(accountConfigured = false, updatesAvailable = true)
        )
    }

    @Test
    fun checkingForUpdatesOnlyExistsWhenThereIsAnUpdateChannel() {
        assertTrue(SettingsEntry.CHECK_UPDATE in everything)
        assertFalse(
            "预览构建里没有更新通道",
            SettingsEntry.CHECK_UPDATE in settingsEntries(accountConfigured = true, updatesAvailable = false)
        )
    }

    @Test
    fun theRestIsAlwaysThere() {
        val minimal = settingsEntries(accountConfigured = false, updatesAvailable = false)
        listOf(
            SettingsEntry.THEME,
            SettingsEntry.AUTO_CONNECT,
            SettingsEntry.PERMISSIONS,
            SettingsEntry.DIAGNOSTICS,
            SettingsEntry.HELP,
            SettingsEntry.OFFICIAL_PORTAL,
            SettingsEntry.ABOUT
        ).forEach { assertTrue("$it 应该一直在", it in minimal) }
    }

    @Test
    fun groupsCarryTheirOwnTitlesAndNeverAppearEmpty() {
        val groups = settingsGroups(everything)
        assertEquals(listOf("外观", "连接", "更新", "账号"), groups.map { it.first.title })
        groups.forEach { (group, items) ->
            assertTrue("$group 不该是空组", items.isNotEmpty())
            assertTrue(items.all { it.group == group })
        }
    }

    @Test
    fun aGroupWithoutSurvivingEntriesDisappearsEntirely() {
        val groups = settingsGroups(settingsEntries(accountConfigured = false, updatesAvailable = false))
        assertEquals(listOf("外观", "连接", "更新"), groups.map { it.first.title })
    }

    @Test
    fun theUpdateGroupStillExistsWithoutACheckRowBecauseAboutLivesThere() {
        val entries = settingsEntries(accountConfigured = false, updatesAvailable = false)
        assertTrue(entries.contains(SettingsEntry.ABOUT))
        assertEquals(SettingsGroup.UPDATE, SettingsEntry.ABOUT.group)
    }
}
