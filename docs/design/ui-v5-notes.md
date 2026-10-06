# 界面重做 v5（桌面工具风）

2026-10-06。用户反馈「软件还是太丑了，一点没有软件的样子」，要求重构界面。方向确认为：采用桌面工具风格的视觉语言，但保留手机上的底部导航。

## 设计语言

来源为 `app-shell-ui` skill 的 App Mode 规则，落到 Compose 上：

- **画布与浮起层级**：软灰画布 → 白色浮起卡片。深色主题是炭灰画布配提亮的炭灰卡片，不用纯黑页面配纯白卡片，卡片始终比画布更亮才读得出「浮起」。
- **单一主色**：全应用只有一个品牌色，用于主按钮、开关打开、选中态与链接。深色下用同色相的更亮版本。
- **文字三级**：`textPrimary` / `textSecondary` / `textTertiary`，两套主题各自取值。
- **层级靠 1px 描边**：阴影只作可选补充，浅色 1dp、深色 0dp。
- **圆角阶梯**：8 / 12 / 16 / 999，两套主题共用。
- **间距 4dp 网格**：`CampusSpace` 的 xs…xxl。
- **动效**：只做颜色与背景变化，180ms。
- **图标克制**：统一描边图标，不用 emoji；状态用小胶囊和弱化文字，不用整宽横幅。
- **列表行语言**：描边图标（可带柔和底板）+ 标题 + 弱化说明 + 右侧控件或状态。

## 令牌

`ui/theme/CampusTokens.kt` 是颜色的唯一来源，组件只读这些字段，不再直接写十六进制，因此换色能一次落到全局。核心取值：

| 令牌 | 浅色 | 深色 |
| --- | --- | --- |
| canvas | `#F4F5F7` | `#1B1E24` |
| surface | `#FFFFFF` | `#262A31` |
| border | `#E6E8EC` | `#333841` |
| textPrimary | `#1C1C1E` | `#F2F4F7` |
| textTertiary | `#8A9099` | `#767D89` |
| accent | `#325FA4` | `#5B93DA` |

深色的 accent 承载深色墨迹（`onAccent = #10233A`），所以同一个主色既能当实心按钮底色，也能在深色画布上当小号彩色文字。

## 改动的文件

`ui/theme/CampusTokens.kt`（新增）、`ui/theme/CampusTheme.kt`、`ui/common/CampusComponents.kt`、`ui/common/CampusControls.kt`、`ui/common/CampusArtwork.kt`、`ui/common/ConnectionFeedback.kt`、`ui/CampusApp.kt`、`ui/screens/HomeScreen.kt`、`ui/screens/ProfileScreen.kt`、`ui/screens/ServicesScreen.kt`、`ui/screens/AccountScreen.kt`（收尾统一头部）。

底部导航保留三个页签，选中态是图标外围的柔和色块而非整列高亮——整宽高亮会让三分之一条栏在喊。

## 实际画面

| 场景 | 证据 |
| --- | --- |
| 首页浅色 | [v5-home-light](screenshots/v5-home-light.png) |
| 首页深色 | [v5-home-dark](screenshots/v5-home-dark.png) |
| 首页离线 | [v5-home-offline](screenshots/v5-home-offline.png) |
| 服务 | [v5-services-light](screenshots/v5-services-light.png) |
| 我的浅色 | [v5-profile-light](screenshots/v5-profile-light.png) |
| 我的深色 | [v5-profile-dark](screenshots/v5-profile-dark.png) |

## 已知缺口

`ui/screens/OfficialPortalScreen.kt`（内嵌学校登录页）仍沿用旧的字号与间距写法，只有外层容器继承了新卡片样式。该页是嵌第三方网页的容器，视觉比重低，未在本轮统一。
