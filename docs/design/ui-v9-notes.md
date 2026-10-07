# UI v9（0.9.0）界面与动效说明

本文只记录这次改版的设计与动效决策，发布说明由 Lead 统一撰写。

## 1. 信息架构

底部导航 = **首页 / 功能 / 我的**（最右为「我的」）。

| 页面 | 内容 | 从哪进 |
| --- | --- | --- |
| 首页 | 标题 + 今日课程 + 两个极简入口（校园网、课表） | 启动即落地 |
| 功能 | 两列模块网格，图标 + 名称，无介绍文字 | 底栏 |
| 校园网 | 状态卡 / 主按钮 / 账号 / 运营商 / 自动连接 | 功能 → 校园网 |
| 课表 | 周次切换 + 周课表网格；管理动作收进右上角弹层 | 功能 → 课表 / 首页入口 |
| 我的 | 身份区 + 常用项（校园账号 / 自动连接 / 主题）+ 设置入口 | 底栏 |
| 设置 | 外观 / 连接 / 更新 / 账号 四组，检查更新与版本在这里 | 我的 → 设置 |

打开应用不再直接落在校园网页面；校园网和课表并列成为功能里的一个模块。

## 2. 设计系统（来自 ui-ux-pro-max `--design-system`）

- 查询：`campus student utility app minimal tool productivity`，`--variance 3 --motion 3 --density 4`。
- 采用其输出的**风格**「Exaggerated Minimalism / 大声留白、高对比、极少装饰」的方向与**密度档 standard**；
  配色**没有**采用它给的 teal + orange（那是给营销页的），继续沿用仓库既有的 App Mode 调色板
  （soft-gray canvas + 白色 raised card + 单一 accent，见 `ui/theme/CampusTokens.kt`），
  以免同一台设备上出现两套品牌色。
- 字体沿用系统中文字体（`FontFamily.SansSerif`），不引入 Plus Jakarta Sans：中文界面下它只影响
  拉丁字母，换字体只会让中英混排更不一致。
- 采纳其 UX 规则：按压反馈必须有（≥44dp 触控目标，行高 56dp）、动画 150–300ms、`prefers-reduced-motion`
  等价物（Android 的动画时长缩放）、列表用稳定 key、昂贵派生用 `remember` / `derivedStateOf`。
- 未采纳：GSAP 动效预设（Web 专用）、App Store 落地页模式、Hero 大标题 clamp 排版。

参考链接见 §4。

## 3. 动效清单

所有时长/缓动都取 Material 3 token，参数集中在 `ui/theme/CampusTokens.kt` 的 `CampusMotion` / `CampusEasing`。

| 动效 | 驱动方式 | 参数 | 位置 |
| --- | --- | --- | --- |
| tab 切换方向性位移 | `AnimatedContent` + `slideIn/OutHorizontally` + `fade` | 进入 250ms（medium1）emphasized-decelerate；退出 200ms（short4）emphasized-accelerate；位移 ±屏宽/5，方向由 tab 下标决定 | `ui/CampusNavigation.kt` `tabTransform()` |
| 进入二级页 | 同上 | 进入 400ms（medium4）emphasized-decelerate，整屏推入；父页退出 200ms 向左 1/4 屏 | `forwardTransform()` |
| 返回上一层 | 同上 | 二级页 200ms 向右整屏滑出；父页 400ms 从左 1/4 屏回来 | `backTransform()` |
| 底栏指示器 | `animateDpAsState` + `Modifier.offset { }` | `spring(dampingRatio = 0.8f, stiffness = 400f)`；位移在布局阶段求值，不触发重新测量 | `CampusNavigation.kt` `CampusNavigationBar()` |
| 底栏文字/图标颜色 | `animateColorAsState` | 200ms（short4）standard | `CampusNavigation.kt` `NavItem()` |
| 按压缩放 + 变暗 | `animateFloatAsState` + `Modifier.graphicsLayer{}` | `spring(dampingRatio = 0.8f, stiffness = 1500f)`；缩放 0.97、alpha −0.08；只在绘制阶段 | `ui/common/CampusInteractions.kt` `pressFeedback()` |
| 列表错峰入场 | 页面级 `Animatable` 时钟 + 每项 `graphicsLayer{}` 取自己的窗口 | 每项 300ms（medium2）emphasized-decelerate，上移 14dp，间隔 35ms，整页一个时钟 | `CampusInteractions.kt` `rememberCampusEntrance()` / `entrance()` |
| 权限胶囊颜色 | `animateColorAsState` | 200ms standard | `ui/screens/SettingsScreen.kt` `PermissionPill()` |
| 连接状态色/标题 | 既有实现（`Crossfade` + `animateColorAsState`） | 200ms | `ui/common/ConnectionFeedback.kt`（未改） |

入场动画只在**每页第一次出现**时播放：`EntranceGate`（普通容器，非快照状态）按页面 key 记账，
已播过的页面再次进入时直接停在终值。滚动、重组、被 Lazy 布局回收再放回来都不会重播。

系统把动画时长缩放为 0 时：`LocalCampusMotionEnabled` 关掉按压与入场；`AnimatedContent` 的转场
在 `MotionDurationScale.scaleFactor = 0` 时于下一帧直接结束（Compose 内建行为）。

## 4. 参考链接

- M3 duration/easing token 表：https://m3.material.io/styles/motion/easing-and-duration/tokens-specs
- 「哪种转场配哪条曲线、多长」（进入 decelerate 400ms / 退出 accelerate 200ms）：
  https://m3.material.io/styles/motion/easing-and-duration/applying-easing-and-duration
- M3 转场模式（forward/backward、top level）：
  https://m3.material.io/styles/motion/transitions/transition-patterns
- M3 navigation bar 指示器：https://m3.material.io/components/navigation-bar/guidelines
- Compose 动画总览/最佳实践：https://developer.android.com/develop/ui/compose/animation/quick-guide
- Compose 动画自定义（dampingRatio / stiffness / Easing）：
  https://developer.android.com/develop/ui/compose/animation/customize
- Compose 性能（推迟状态读取、lambda 版 modifier、key）：
  https://developer.android.com/develop/ui/compose/performance/bestpractices
- Compose Lazy 列表（key / contentType）：
  https://developer.android.com/develop/ui/compose/lists
- `MotionDurationScale`（scaleFactor = 0 时下一帧结束）：
  https://developer.android.com/reference/kotlin/androidx/compose/ui/MotionDurationScale

## 5. 性能取舍

**做了：**

- 所有逐帧动画只写绘制阶段或布局放置阶段：按压/入场用 `Modifier.graphicsLayer{}`，指示器用
  `Modifier.offset { }`；没有动画 width/height/padding，也没有 `animateContentSize`。
- 入场动画是「一个时钟 + 每项取自己的窗口」，不是每项一条动画；条目被回收不会重播。
- 转场中两个页面同时存在是 `AnimatedContent` 的固有代价，因此每页内容都保持轻量（首页 = 标题 + 一张卡 + 两个块）。
- `LazyColumn` / `LazyVerticalGrid` / `LazyRow` 全部带稳定 key；模块网格另加 `contentType`。
- 昂贵派生（今日课程、课表 slot 分配、按周网格、日期）都走 `remember(key)` 或纯函数。
- 页面级状态（今日课程的选取）抽成纯函数 `homeToday()`，可单测且不在组合里重算。
- 列表项 lambda 直接引用 `actions` 的方法引用或稳定 lambda；没有在 item 内 new 状态对象。

**没有做 / 未知：**

- 课表网格不是 Lazy 布局（固定 5–7 列 + 横向滚动的 `Row`），所以那里没有 key 可用；改成 Lazy 会动到
  16 条像素级测试钉住的列宽算法，这次不动。
- 帧率数字见验收报告，口径是模拟器（`ro.hardware.egl=emulation`，软件渲染），**不能**当作真机结论。
