# 课表与 AI 识图

2026-10-06 新增。用户上传课表图片，由视觉大模型识别成结构化课程，再在本机渲染成周课表。

## 端点与模型

0.4.0 起识别端点内置进构建，正常使用无需填 Key。默认走 `https://ai.123312.xyz/v1` 上的 `deepseek-v4.1-flash`，`reasoning_effort: "high"`、`temperature: 0`、`response_format: json_object`。

端点与 Key 由 `local.properties` 的 `vision.baseUrl` / `vision.apiKey` 在构建时注入 `BuildConfig`，`local.properties` 已被忽略，因此版本库里没有 Key。课表页的高级设置可以覆盖端点、模型或填自己的 Key。

这是一个明确的取舍：**Key 打进 APK 意味着任何拿到 APK 的人都能提取并盗刷额度**。当前是自用分发，接受这一点；若要公开分发，应改为用户自带 Key 或走服务端转发。用户自带 Key 的路径仍然保留，用 Android Keystore AES/GCM 加密存在本机，使用独立别名。

## 请求契约

```
POST {vision.baseUrl}/chat/completions
Authorization: Bearer <BuildConfig.VISION_API_KEY 或用户填写的 Key>
Content-Type: application/json

{"model":"deepseek-v4.1-flash",
 "messages":[{"role":"system","content":"<纯文本规则 + JSON 示例>"},
             {"role":"user","content":[{"type":"text","text":"请识别这张课表图片…"},
                                       {"type":"image_url",
                                        "image_url":{"url":"data:image/jpeg;base64,…","detail":"high"}}]}],
 "response_format":{"type":"json_object"},
 "temperature":0,"max_tokens":8192,"stream":false}
```

约束与实现要点：

- **图片只能出现在 user 消息里**。system/assistant 带图会返回 400，因此 system 保持纯文本。
- JSON 模式要求提示里包含 `json` 字样与完整示例，两者都已写入提示。
- 端点的主机与路径在发请求前校验，Key 不可能被发到其他源；不跟随重定向。
- Key 会先 trim，含 CR/LF 直接拒绝（防请求头注入）；Key 不出现在请求体，也不出现在 `toString()`。

## 响应与渲染

模型返回 `choices[0].message.content`，期望形如：

```json
{"is_timetable":true,"term":"…",
 "courses":[{"name":"…","teacher":"…","room":"…",
             "weekday":1,"start_period":1,"end_period":2,
             "start_week":1,"end_week":16}]}
```

解析层容忍代码块围栏、字段别名（`day`、`course_name`、`instructor`、`classroom`、`start_section`…）与中文写法（`星期三`、`第1-2节`、`1-16周(单)`）。校验层会拒绝越界字段、折叠重复行，并把同一时段不同课程的冲突单独报出。`is_timetable:false` 或课程列表为空时，界面明说没识别到课表且不保存任何内容，模型返回的自由文本原因不会展示给用户。

**单双周是一个独立字段**（`parity`：`ALL` / `ODD` / `EVEN`），不是靠周次范围推断的。同一时段一门单周课加一门双周课是合法课表，不算冲突；只有共用同一奇偶性的重叠才算冲突。

渲染分三层：今日课程列表、按周查看的课表网格、点开单门课看教师与地点。「按周查看」需要开学日期才能算当前周，未设置时按第 1 周显示并提示设置；设置后卡片显示当前周与对应日期区间。

图片在进入请求前会先解码边界、按 2 的幂次采样、缩放到长边 2048 再按质量阶梯压成 JPEG，因此不会触到单图 32 MiB / 8192 px 的上限。

## API Key

内置端点的 Key 在构建时注入 `BuildConfig`（见上）。用户自带 Key 时用 AES/GCM 加密后存在本机，密钥由 Android Keystore 持有，使用独立的 Keystore 别名，删除其中一个不会让另一个失效；不落日志，每次请求按需解密而不是长期缓存。

## 失败处理

以下情况都不会崩溃，也不会留下脏数据：未填 Key、Key 含换行、图片格式不支持 / 为空 / 超限（在本地拒绝，不上传）、400、401/403、402、413、429、3xx、5xx、超时、无网络、200 但内容为空、被截断、内容过滤、非 JSON、响应包结构异常、字段越界、课程冲突。请求被拒时，本机已保存的课表保持不变。

## 验证状态

代码、契约与失败路径由 104 项课表单元测试覆盖（模型与校验、识图客户端、响应解析、持久化、Key 保险库、控制器、图片处理），HTTP 层全部是假的。写这些测试时发现并修掉了三个自身缺陷：星期别名返回了内层下标（`星期三`→2、`sunday`→8）、重复行未折叠、时段冲突被误报成字段非法；单双周最初被静默丢弃，补上 `parity` 字段后才正确。

**已端到端验证**（2026-10-06，模拟器 + 真实接口）：用一张合成的中文课表图片（含 13 门课、单双周标注、合并单元格）走完整流程 —— 选图、上传、识别、渲染、落盘、重装后重新读取。结果 12 门课程全部识别，教师与教室正确（`大学英语 / Sarah Chen / 外语楼205`、`高等数学 / 李国强 / 文理楼301`、`毛泽东思想和中国特色社会主义理论 / 黄凯 / 文科楼202`）。周次筛选经核对正确：双周限定的课在第 1 周不出现，`9-16周` 的课在第 1 周不出现。

**未验证的部分**（不要当成已验证）：

- 拍照入口仅在 API 29+ 提供，只编译通过，未实拍；相册选择是主路径，无需权限。
- 模型识别准确率取决于图片质量与模型本身，一张图通过不代表所有课表都能读对。界面已写明「看不清的内容不会凭空补全」。
- 极窄屏幕（< 360dp）与超大字体的网格排版未逐一比对。
