package cn.gxnu.campus.ui.screens

/** 设置页的分组，顺序就是显示顺序。 */
internal enum class SettingsGroup(val title: String) {
    APPEARANCE("外观"),
    CONNECTION("连接"),
    UPDATE("更新"),
    ACCOUNT("账号")
}

/**
 * 设置页上的每一条。它是一张清单而不是一堆 if：哪些条目在什么条件下出现，由
 * [settingsEntries] 一个纯函数回答，界面只负责把清单画出来 —— 于是「删除账号只在有账号时出现」
 * 这种规则第一次可以被单测钉住。
 */
internal enum class SettingsEntry(val group: SettingsGroup, val title: String) {
    THEME(SettingsGroup.APPEARANCE, "主题"),
    AUTO_CONNECT(SettingsGroup.APPEARANCE, "自动连接"),
    PERMISSIONS(SettingsGroup.CONNECTION, "网络与通知权限"),
    DIAGNOSTICS(SettingsGroup.CONNECTION, "网络诊断"),
    HELP(SettingsGroup.CONNECTION, "连接帮助"),
    OFFICIAL_PORTAL(SettingsGroup.CONNECTION, "学校认证页面"),
    CHECK_UPDATE(SettingsGroup.UPDATE, "检查更新"),
    ABOUT(SettingsGroup.UPDATE, "关于"),
    DELETE_ACCOUNT(SettingsGroup.ACCOUNT, "删除校园账号")
}

/**
 * 这一刻设置页上真正存在的条目，按分组排好序。
 *
 * @param accountConfigured 没有校园账号时就没有账号可删。
 * @param updatesAvailable 预览构建里没有更新通道，那一组就不出现。
 */
internal fun settingsEntries(accountConfigured: Boolean, updatesAvailable: Boolean): List<SettingsEntry> =
    SettingsEntry.entries.filter { entry ->
        when (entry) {
            SettingsEntry.DELETE_ACCOUNT -> accountConfigured
            SettingsEntry.CHECK_UPDATE -> updatesAvailable
            else -> true
        }
    }

/** 清单按分组切好，空的分组不会留下一个只有标题的空壳。 */
internal fun settingsGroups(entries: List<SettingsEntry>): List<Pair<SettingsGroup, List<SettingsEntry>>> =
    SettingsGroup.entries.mapNotNull { group ->
        entries.filter { it.group == group }.takeIf { it.isNotEmpty() }?.let { group to it }
    }
