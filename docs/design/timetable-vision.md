# 课表与 AI 识图

2026-10-06 新增。用户上传课表图片，由视觉大模型识别成结构化课程，再在本机渲染成周课表。

## 为什么可以直接用 DeepSeek

DeepSeek 的 `deepseek-flash` 本身支持图片输入，不需要另外接一个视觉模型：[图像理解文档](https://api-docs.deepseek.com/zh-cn/guides/vision/) 说明历史模型名 `deepseek-v4-flash-vision-exp` 已下线，其请求同样由最新的 Flash 模型承接。接口是标准 OpenAI 兼容格式，`content` 为块数组而非字符串。

## 请求契约

```
POST https://api.deepseek.com/chat/completions
Authorization: Bearer <用户填写的 Key>
Content-Type: application/json

{"model":"deepseek-flash",
 "messages":[{"role":"system","content":"<纯文本规则 + JSON 示例>"},
             {"role":"user","content":[{"type":"text","text":"请识别这张课表图片…"},
                                       {"type":"image_url",
                                        "image_url":{"url":"data:image/jpeg;base64,…","detail":"high"}}]}],
 "response_format":{"type":"json_object"},
 "temperature":0,"max_tokens":4096,"stream":false}
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

图片在进入请求前会先解码边界、按 2 的幂次采样、缩放到长边 2048 再按质量阶梯压成 JPEG，因此不会触到单图 32 MiB / 8192 px 的上限。

## API Key

Key 由用户在课表页自行填写，用 AES/GCM 加密后存在本机，密钥由 Android Keystore 持有，使用独立的 Keystore 别名，删除其中一个密钥不会让另一个失效。Key 不写进源码、不打进 APK、不落日志，每次请求按需解密而不是长期缓存。这是刻意的取舍：把 Key 打包进 APK 意味着任何人反编译就能拿走并盗刷额度。

## 失败处理

以下情况都不会崩溃，也不会留下脏数据：未填 Key、Key 含换行、图片格式不支持 / 为空 / 超限（在本地拒绝，不上传）、400、401/403、402、413、429、3xx、5xx、超时、无网络、200 但内容为空、被截断、内容过滤、非 JSON、响应包结构异常、字段越界、课程冲突。请求被拒时，本机已保存的课表保持不变。

## 验证状态

代码、契约与失败路径由 71 项单元测试覆盖（模型与校验 16、识图客户端 14、响应解析 11、持久化 8、Key 保险库 6、控制器 13、图片处理 3），HTTP 层全部是假的。写这些测试时发现并修掉了三个自身缺陷：星期别名返回了内层下标、重复行未折叠、时段冲突被误报成字段非法。

**未验证的部分**（不要当成已验证）：

- **没有用真实 Key 调过接口**。wire format 是按官方文档写并对假 HTTP 层断言的，没有跑过真实的请求/响应往返。识别效果本身取决于模型，需要用户拿真实课表图试。
- `thinking` 与 `temperature` 两个字段取自文档，未实测；若线上拒绝，删掉 `HttpTimetableVisionClient.requestBody` 里的 `add("thinking", …)` 即可。
- 未在真机上确认渲染效果：网格布局数学有单测，但画面本身没在设备上比对过。
- 拍照入口仅在 API 29+ 提供，且只编译通过未实拍；相册选择是主路径，无需权限。
