# SPW-Send 同步协议 v2

状态：已定稿，实现中  
适用范围：SPW-Send 模组（PC）↔ SPW-Send Android 接收端  
兼容性：与 MySend 协议 v1 **不兼容**，但可与 MySend 在同一局域网共存

---

## 1. 设计目标

- 单向：文件只从 PC 流向 Android
- 只增不删：只传目标端不存在的相对路径
- 可续传：中断后从已写入位置继续，不重传整个文件
- 可校验：传入文件可按 SHA-256 验收
- 可共存：与 MySend 使用不同端口，互不干扰
- 零常驻：PC 侧服务仅在模组面板打开期间存在

---

## 2. 常量

| 名称 | 值 |
| --- | --- |
| 协议版本 `proto` | `2` |
| UDP 发现端口 | `54317` |
| HTTP 默认端口 | `54318` |
| 广播周期 | `2s` |
| 设备过期 | `12s` |
| 控制请求超时 | `10s` |
| 文件传输超时 | `max(60s, 大小 / 1MiB × 2s)`，上限 `10min` |
| 单设备并发同步任务 | `1` |
| 分片保留 | `7 天` |

HTTP 端口被占用时允许改用其他端口，但必须在 `hello` 中声明实际 `port`，对端以此为准。

---

## 3. 角色与端点归属

| 角色 | `role` | 持有 |
| --- | --- | --- |
| PC（模组） | `pc` | 源文件、源目录映射 |
| Android（接收端） | `android` | 目标目录映射 |

两端都必须实现：`/v2/hello`、`/v2/pair`、`/v2/index`、`/v2/log`、`/v2/disconnect`

| 端点 | PC | Android |
| --- | --- | --- |
| `/v2/file`（下载自己的文件） | 必须 | 可选 |
| `/v2/upload`（接收文件） | 可选 | 必须 |
| `/v2/resume`（查询分片） | 可选 | 必须 |

---

## 4. 设备发现

UDP 广播到 `255.255.255.255:54317`，并对每个网卡的定向广播地址各发一份；忽略回环接口。

报文（UTF-8 JSON，单行）：

```json
{
  "t": "hello",
  "proto": 2,
  "id": "9f1c...",
  "name": "DESKTOP-8K2",
  "role": "pc",
  "app": "spw-send-mod",
  "appVersion": "0.1.0",
  "port": 54318,
  "session": "4b7e..."
}
```

规则：

- `id`：设备首次启动生成的 UUID，持久化保存
- `session`：本次会话标识；PC 在面板打开时生成，Android 在接收服务启动时生成
- 只展示 `role` 为对端的设备，不展示同角色
- 收到广播即可直接发起配对，无需额外握手包
- 超过 `12s` 未收到广播视为离线；收到 `/v2/disconnect` 立即移除

---

## 5. 通用 HTTP 约定

- 每个请求和响应都带 `Connection: close`，不复用连接
- 请求头 `X-SpwSend-Proto: 2`（`/v2/hello` 可省略）
- 鉴权头 `X-SpwSend-Token: <hex>`
- 查询参数按 UTF-8 百分号编码
- 相对路径统一用 `/` 分隔；禁止绝对路径、`..`、空段、含 `:` 的段
- 除文件下载外，响应体一律为 JSON

**Token**：服务端在 `/v2/pair` 成功时生成 128 位随机十六进制串，有效期至断开或进程退出。除 `/v2/hello` 和 `/v2/pair` 外，缺失或错误的 token 一律返回 `401 UNAUTHORIZED`。

---

## 6. 握手

```
GET /v2/hello
```

`200`：

```json
{
  "proto": 2,
  "app": "spw-send-android",
  "version": "1.0.0",
  "minPeer": 1,
  "session": "4b7e...",
  "device": { "id": "...", "name": "Pixel 8", "role": "android" }
}
```

- `proto != 2` → `426 PROTO_MISMATCH`
- 对端 `version` 低于本地 `minPeer` → `426 APP_TOO_OLD`
- 版本号格式 `主.次.修订`，逐段按数值比较

---

## 7. 配对

```
POST /v2/pair
Content-Type: application/json
```

```json
{
  "id": "9f1c...",
  "name": "DESKTOP-8K2",
  "role": "pc",
  "app": "spw-send-mod",
  "appVersion": "0.1.0",
  "proto": 2
}
```

`200`：

```json
{
  "ok": true,
  "token": "a3f0...",
  "session": "4b7e...",
  "device": { "id": "9f1c...", "name": "DESKTOP-8K2", "role": "pc" }
}
```

- Android 首次收到未知设备 `id`：弹窗确认，同意后写入白名单，之后自动接受
- 配对只建立本次会话，不保持长连接
- 已有同步任务进行中 → `409 BUSY`

---

## 8. 索引

```
GET /v2/index?name=<映射名>
```

`200`：

```json
{
  "ok": true,
  "name": "音乐",
  "files": [
    { "p": "周杰伦/叶惠美/01 以父之名.flac", "s": 12345678, "m": 1697000000000 }
  ]
}
```

- `p` 相对路径，`/` 分隔，大小写敏感，不做 Unicode 归一化
- `s` 字节数；`m` epoch 毫秒
- `s` 与 `m` **仅用于显示**，差异比对只看 `p`
- 服务端必须执行同一套过滤规则：任一路径段以 `.` 或 `~$` 开头，或文件名为 `desktop.ini`、`thumbs.db`、`ehthumbs.db`
- 不返回目录项，空目录不同步
- 映射不存在 → `404 MAPPING_NOT_FOUND`

---

## 9. 差异计算

```
待传 = 源索引 - 目标索引（按 p 精确匹配）
```

- 按 `p` 字典序排序，逐个顺序传输
- 目标端已存在该路径 → 跳过，不读取、不校验
- 不处理目标端多出的文件

---

## 10. 推送（PC → Android）

### 10.1 查询续传点

```
GET /v2/resume?name=<映射名>&path=<相对路径>&size=<总字节>
```

`200`：

```json
{ "ok": true, "offset": 0 }
```

- 无分片返回 `0`
- 分片记录的总大小与 `size` 不符时，服务端删除分片并返回 `0`

### 10.2 上传

```
POST /v2/upload?name=<映射名>&path=<相对路径>
```

请求头：

| 头 | 必填 | 说明 |
| --- | --- | --- |
| `X-Offset` | 是 | 本次数据在文件中的起始偏移 |
| `X-File-Size` | 是 | 文件总字节数 |
| `X-File-Mtime` | 是 | 源文件修改时间，epoch 毫秒 |
| `X-Content-SHA256` | 否 | 整个文件的小写十六进制摘要，推荐携带 |
| `Content-Length` | 是 | 本次请求体的字节数 |

`200`：

```json
{ "ok": true, "size": 12345678 }
```

服务端写入流程：

1. 分片路径：`<目标根>/.spw-send-part/<sha256(相对路径)>.part`
2. 同目录 `<...>.meta` 保存 `{p, size, mtime, sha256}`
3. `X-Offset` 必须等于当前分片长度，否则 `416 RANGE_INVALID`
4. 数据不足 → `409 INCOMPLETE`，保留分片
5. 摘要不符 → 删除分片，`409 CHECKSUM_MISMATCH`
6. 校验通过 → 自动创建父目录，`ATOMIC_MOVE` 到目标路径，删除 meta
7. 空间不足 → `507 NO_SPACE`

### 10.3 分片清理

接收端启动时删除 `.spw-send-part` 下超过 7 天的 `.part` 与 `.meta`。

---

## 11. 拉取（Android → PC）

```
GET /v2/file?name=<映射名>&path=<相对路径>
Range: bytes=<n>-
```

- 无 `Range`：`200`，返回完整文件
- 有 `Range`：`206`，响应头 `Content-Range: bytes <n>-<total-1>/<total>`
- 起点越界 → `416 RANGE_INVALID`
- 文件不存在 → `404 FILE_NOT_FOUND`
- 客户端以本地分片长度作为 Range 起点，其余写入规则与 10.2 一致

---

## 12. 同步日志

```
POST /v2/log
```

```json
{
  "task": "音乐",
  "startedAt": 1697000000000,
  "finishedAt": 1697000010000,
  "total": 120,
  "success": 118,
  "failed": 2,
  "bytes": 123456789,
  "failures": [ { "p": "a/b.flac", "reason": "TIMEOUT" } ]
}
```

- 两端各自本地保存，互不依赖
- 接收端保留 7 天
- 失败原因使用错误码，随附可读文本

---

## 13. 断开

```
POST /v2/disconnect
```

```json
{ "reason": "user" }
```

- 收到后立即移除该设备、清空会话与 token
- 返回 `204` 或 `{"ok":true}` 均可

---

## 14. 错误码

| HTTP | code | 含义 |
| --- | --- | --- |
| 400 | `BAD_REQUEST` | 参数缺失或 JSON 非法 |
| 400 | `PATH_INVALID` | 路径非法或越界 |
| 401 | `UNAUTHORIZED` | token 缺失或错误 |
| 404 | `MAPPING_NOT_FOUND` | 映射不存在 |
| 404 | `FILE_NOT_FOUND` | 文件不存在 |
| 409 | `BUSY` | 已有同步任务 |
| 409 | `INCOMPLETE` | 字节数不足，保留分片 |
| 409 | `CHECKSUM_MISMATCH` | 摘要不符，已删除分片 |
| 416 | `RANGE_INVALID` | 续传偏移非法 |
| 426 | `PROTO_MISMATCH` | 协议版本不一致 |
| 426 | `APP_TOO_OLD` | 对端版本过低 |
| 500 | `IO_ERROR` | 读写失败 |
| 507 | `NO_SPACE` | 目标空间不足 |

统一错误响应：

```json
{ "ok": false, "code": "MAPPING_NOT_FOUND", "message": "映射「音乐」不存在" }
```

---

## 15. 安全边界

- 明文 HTTP，无 TLS，仅限可信局域网
- token 只用于阻止误连与同网随手请求，不能抵御抓包
- 服务端必须做路径越界校验：规范化后必须位于映射根目录之内
- 拒绝符号链接逃逸；Android 端需解析真实路径后再次校验

---

## 16. 与 MySend v1 的差异

| 项目 | v1 | v2 |
| --- | --- | --- |
| 端口 | 53317 / 53318 | 54317 / 54318 |
| 鉴权 | 无 | 会话 token |
| 续传 | 不支持 | `.part` + 偏移续传 |
| 完整性 | 仅比对大小 | 可选 SHA-256 校验 |
| 协议版本 | 无显式握手 | `/v2/hello` + `minPeer` |
| 配对 | 无条件接受 | 首次确认 + 白名单 |
| 分片清理 | 无 | 7 天自动清理 |
