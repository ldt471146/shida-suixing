# 应用内更新与 GitHub 分发

2026-10-06 新增。0.4.0 起不再手动传安装包，改为应用启动时检查 GitHub Releases，发现新版本后一键下载并在系统确认后安装。

## 分发链路

发布靠打标签，不靠手工上传：

```powershell
git tag v0.4.1
git push origin v0.4.1
```

[发布工作流](../../.github/workflows/android-release.yml) 在 `v*` 标签推送或手动触发时运行，runner 上依次执行 `:app:testDebugUnitTest :app:lintDebug :app:assembleRelease`，然后把两个资产发到对应的 Release：

- `shida-suixing-<versionName>.apk` —— 签名安装包。名字必须是 ASCII，非 ASCII 文件名在 runner 的 shell 里会被破坏。
- `version.json` —— `{"versionCode":7,"versionName":"0.4.0"}`，应用据此判断是否需要更新。

签名材料不在版本库里：密钥通过仓库 Secrets 提供（`SIGNING_KEYSTORE_BASE64`、`SIGNING_STORE_PASSWORD`、`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD`），CI 解码到 `$RUNNER_TEMP` 后写进 `local.properties`。`local.properties` 与 `*.jks` 都在 `.gitignore` 里。识别端点的 Key 同理，走 `VISION_API_KEY` Secret 与 `VISION_BASE_URL` 变量。

注意 `gradlew` 的可执行位：Windows 上的 git 不跟踪该位，CI 首次运行会以 `Permission denied`（退出码 126）失败。仓库已用 `git update-index --chmod=+x gradlew` 记录为 `100755`，工作流里另有一道 `chmod +x gradlew` 兜底。

## 检查

`ReleaseUpdater` 的检查部分是纯函数，网络只是注入的 `ReleaseTransport`。

1. `GET {api}/repos/{owner}/{repo}/releases/latest`，带 `Accept: application/vnd.github+json` 与显式 `User-Agent`（GitHub 对没有 UA 的请求返回 403）。
2. 状态映射：404 → `NoRelease`，403/429 → `RateLimited`，其他非 200 → `Unreachable`，200 但 `assets` 不可解析 → `Unreadable`。
3. 取 `version.json`，用它的 `browser_download_url`（`github.com`，不计入 API 限额）而不是 API 资产地址。**因此一次检查只消耗 1 个请求**，未认证限额是每小时 60 次。
4. `versionCode` 严格大于 `BuildConfig.VERSION_CODE` 才是更新；否则 `UpToDate`；更新但找不到可用 APK → `MissingApk`。

`versionName` 被约束为 `[A-Za-z0-9][A-Za-z0-9._+-]{0,63}`，因为它会进入缓存文件名。

自动检查每个进程只跑一次 —— `UpdateController` 是进程级单例，Activity 重建不会重复消耗限额。「我的 → 检查更新」是手动路径，不受此限制。

## 下载与安装

- 分块（64 KB）流式写入 `cacheDir/updates/shida-suixing-<version>.apk.part`，带进度回调。
- 首块校验 PK 魔数，声明的长度和实际字节都强制 128 MB 上限，非 2xx 视为网络失败。
- 只有完整且看起来合理的响应体才会重命名为 `.apk`。
- 取消 = 取消 job + 断开 socket + 删除半成品文件；取消永远不产生结果值。
- 安装前查 `canRequestPackageInstalls()`：为假时跳到本应用的「安装未知应用」设置页，`onResume` 重新检查并自动继续；为真时通过 FileProvider 的 content URI 发出 `ACTION_VIEW` + `application/vnd.android.package-archive` + `FLAG_GRANT_READ_URI_PERMISSION`。

**Android 不允许应用静默安装自己。** 流程止于下载完成后由系统弹出安装确认，这一步无法省略；界面文案也照实写成「下载后由系统确认安装」，不假装全自动。

## 界面状态

首页最后一项与「我的 → 帮助与检查」共用同一张卡片，没有可更新的版本时完全不显示：

| 状态 | 标题 | 说明 | 操作 |
| --- | --- | --- | --- |
| `Available` | 发现新版本 0.4.1 | 安装包 8.7 MB · 下载后由系统确认安装 | 更新 / ✕ 暂不提示 |
| `Downloading` | 正在下载 0.4.1 | 已下载 N% + 进度条 | 取消 |
| `Ready` | 新版本已就绪 | 点击安装，系统会再次确认（或「请先允许本应用安装应用」） | 安装 / 去设置 |
| `Failed` | 更新未完成 | 具体原因 | 重试 |

「检查更新」行会报告 `已是最新版本`、`暂无发布版本`、`更新服务请求过于频繁，请稍后再试`、`新版本缺少安装包，请稍后再试`、`更新信息暂时无法读取`、`暂时无法连接更新服务` 或 `发现新版本 0.4.1`。卡片用 `CampusCard` + `CampusRow`，单一强调色，只用描边图标。

## 验证状态

33 项单元测试覆盖纯逻辑，全部用假 transport，不开 socket：新旧版本比较、残缺 JSON、版本元数据缺失/类型错误/负数、版本名路径转义、资产缺失与回退、多余的 APK 候选、404/403/429/5xx/301、传输异常、第二次请求失败、PK 魔数、下载与进度、非 zip 响应体、空响应体、大小上限、下载中途 404、取消后不留半成品。

测试写就时测试源集编译不过（另一路课表改动在飞），因此用变异验证证明它们确实会失败：把比较改成 `>=`、把 `looksLikeApk` 放宽为「非空」后，恰好产生预期的 4 个失败，随后还原。

**未验证的部分**：

- 真实的 403 限流分支只做过模拟；`/rate_limit` 确认未认证额度是每小时 60 次，但没有把额度真的用光来跑这条路径。
- 系统的安装确认弹窗与 FileProvider 的 URI 授权需要真机确认；模拟器上的安装确认与真机行为可能不同。
- 端到端必须有一个已发布的 Release 才能跑。本次工作结束时仓库标签数为 0，所以应用当前会显示「暂无发布版本」；打第一个 `v*` 标签后才能走完整链路。
