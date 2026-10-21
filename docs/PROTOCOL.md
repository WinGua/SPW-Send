# SPW-Send / MySend 同步协议 v2

状态：已定稿，实现中

适用范围：SPW-Send 模组（PC）↔ MySend 安卓端

基准实现：MySend `lib/services/transfer/` 与 `lib/services/discovery_service.dart`

---

## 1. 设计目标

- 单向：文件只从 PC 流向 Android
- 只增不删：只传目标端不存在的相对路径
- 可续传：中断后从已写入位置继续，不重传整个文件
- 可共存：PC 与 Android 使用各自的本机端口，互不冲突
- 零常驻：PC 侧服务仅在模组面板打开期间存在

---

## 2. 常量

| 名称 | 值 |
| --- | --- |
| 协议版本 `proto` | `2` |
| UDP 发现端口 | `53317` |
| HTTP 默认端口 | `53318` |
| 广播周期 | `2s` |
| 设备过期 | `12s` |
| 控制请求超时 | `10s` |
| 目录索引超时 | `60s` |
| 文件传输超时 | `max(60s, 大小 / 1MiB × 2s)`，上限 `10min` |
| 单设备并发同步任务 | `1` |
| 分片目录 | `.mysend-part` |
| 分片保留 | `7 天` |

HTTP 端口被占用时允许改用系统分配的端口，但必须在 `hello` 中声明实际 `port`，对端以此为准。

---

## 3. 角色与端点归属

| 角色 | `role` | 持有 |
| --- | --- | --- |
| PC（模组） | `pc` | 源文件、源目录映射 |
| Android（MySend） | `android` | 目标目录映射 |

| 端点 | PC | Android |
| --- | --- | --- |
| `GET /v2/hello` | 必须 | 必须 |
| `POST /v2/pair` | 必须 | 必须 |
| `GET /v2/index` | 必须 | 必须 |
| `GET /v2/file`（下载 PC 文件） | 必须 | 不使用 |
| `GET /v2/resume`（查询分片） | 不使用 | 必须 |
| `POST /v2/resume/batch`（批量查询分片） | 不使用 | 必须 |
| `POST /v2/upload`（接收文件） | 不使用 | 必须 |
| `POST /v2/log` | 必须 | 必须 |
| `POST /v2/disconnect` | 必须 | 必须 |

---

## 4. 设备发现

UDP 广播到 `255.255.255.255:53317`，并对每个网卡的定向广播地址各发一份；忽略回环接口。

报文（UTF-8 JSON，单行）：

```json
{
  "t": "hello",
  "proto": 2,
  "id": "9f1c...",
  "name": "DESKTOP-8K2",
  "role": "pc",
  "form": "desktop",
  "port": 53318,
  "session": "4b7e...",
  "app": "spw-send-mod",
  "appVersion": "0.1.2"
}
```

规则：

- `id`：设备首次启动生成的 UUID，持久化保存
- `session`：本次会话标识；PC 在面板打开时生成，Android 在接收服务启动时生成
- 只展示 `role` 为对端的设备，不展示同角色
- 只接受 `proto == 2` 的报文；`app` 字段仅用于标识，不参与过滤
- 收到广播即可直接发起配对，无需额外握手包
- 超过 `12s` 未收到广播视为离线
- 对端 `session` 变化说明其服务已重启，旧 token 立即失效

---

## 5. 通用 HTTP 约定

- 每个请求和响应都带 `Connection: close`，不复用连接
- 请求头 `X-MySend-Proto: 2`（`/v2/hello` 可省略）
- 鉴权头 `X-MySend-Token: <hex>`
- 查询参数按 UTF-8 百分号编码
- 相对路径统一用 `/` 分隔；禁止绝对路径、`..`、空段、含 `:` 的段
- 除文件下载外，响应体一律为 JSON
- 服务端可在客户端声明 `Accept-Encoding: gzip` 时压缩较大的 JSON 响应

**Token**：服务端在 `/v2/pair` 成功时生成 128 位随机十六进制串，有效期至断开、对端重新配对或服务退出。除 `/v2/hello` 和 `/v2/pair` 外，缺失或错误的 token 一律返回 `401 UNAUTHORIZED`。同一设备只保留最新一个 token。

---

## 6. 握手

```
GET /v2/hello
```

`200`：

```json
{
  "proto": 2,
  "app": "mysend",
  "version": "1.0.0",
  "minPeer": 1,
  "session": "4b7e...",
  "device": { "id": "...", "name": "Pixel 8", "role": "android" }
}
```

- `proto != 2` → `426 PROTO_MISMATCH`
- `minPeer` 为对端可接受的最低主版本，当前未强制校验

---

## 7. 配对

```
POST /v2/pair
Content-Type: application/json
```

```json
{
  "proto": 2,
  "id": "9f1c...",
  "name": "DESKTOP-8K2",
  "role": "pc",
  "form": "desktop",
  "port": 53318,
  "session": "4b7e...",
  "app": "spw-send-mod",
  "appVersion": "0.1.2",
  "token": "c81d..."
}
```

`token` 可选：主动配对方附上自己签发的会话 token，供被连接方在本次会话中反向调用。两端都可以主动发起 `/v2/pair`，配对成功后双方各持有可调用对方的 token。

`200`：

```json
{
  "ok": true,
  "token": "a3f0...",
  "session": "4b7e...",
  "device": { "id": "9f1c...", "name": "DESKTOP-8K2", "role": "pc" }
}
```

- `proto != 2` → `426 PROTO_MISMATCH`
- 请求体 `role` 必须是对端角色（PC 收 `android`，Android 收 `pc`）
- 已有同步任务进行中 → `409 BUSY`
- 配对只建立本次会话，不保持长连接

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
- `s` 与 `m` **仅用于续传校验与显示**，差异比对只看 `p`
- 服务端必须执行同一套过滤规则：任一路径段以 `.` 或 `~$` 开头，或文件名为 `desktop.ini`、`thumbs.db`、`ehthumbs.db`
- 不返回目录项，空目录不同步
- 映射不存在 → `404 MAPPING_NOT_FOUND`
- 同步进行中 → `409 BUSY`

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
GET /v2/resume?name=<映射名>&path=<相对路径>&size=<总字节>&mtime=<毫秒>
```

`200`：

```json
{ "ok": true, "offset": 0 }
```

- 无分片返回 `0`
- 分片记录的总大小或修改时间与请求不符时，服务端删除分片并返回 `0`

### 10.2 批量查询续传点

```
POST /v2/resume/batch
Content-Type: application/json
```

```json
{
  "name": "音乐",
  "items": [
    { "p": "a/b.flac", "s": 12345678, "m": 1697000000000 }
  ]
}
```

`200`：

```json
{ "ok": true, "offsets": { "a/b.flac": 0 } }
```

- 单次最多 `5000` 项
- 对端未实现时客户端回退为逐文件 `/v2/resume`

### 10.3 上传

```
POST /v2/upload?name=<映射名>&path=<相对路径>&size=<总字节>&mtime=<毫秒>
```

请求头：

| 头 | 必填 | 说明 |
| --- | --- | --- |
| `X-Offset` | 是 | 本次数据在文件中的起始偏移 |
| `Content-Length` | 是 | 本次请求体的字节数，必须等于 `size - X-Offset` |

`200`：

```json
{ "ok": true, "size": 12345678 }
```

服务端写入流程：

1. 分片路径：`<目标根>/.mysend-part/<sha256(相对路径)>.part`
2. 同目录 `<...>.meta` 保存 `{p, size, mtime}`
3. `X-Offset` 必须等于当前分片长度，否则 `416 RANGE_INVALID`
4. 数据不足 → `409 INCOMPLETE`，保留分片
5. 校验通过 → 自动创建父目录，原子改名到目标路径，删除 meta
6. 空间不足 → `507 NO_SPACE`

### 10.4 分片清理

接收端启动时删除 `.mysend-part` 下超过 7 天的 `.part` 与 `.meta`。

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
- 同步进行中 → `409 BUSY`
- 客户端以本地分片长度作为 Range 起点，完成后按 10.3 的规则原子落盘

---

## 12. 同步日志

```
POST /v2/log
Content-Type: application/json
```

```json
{
  "name": "音乐",
  "count": 118,
  "bytes": 123456789,
  "files": [ "01 以父之名.flac", "02 晴天.flac" ]
}
```

- `name` 映射名，`count` 成功文件数，`bytes` 成功传输总字节，`files` 成功文件名（相对路径最后一段）
- 只有实际成功同步了文件才发送；两端各自本地保存，互不依赖
- 接收端保留 7 天

---

## 13. 断开

```
POST /v2/disconnect
```

- 收到后立即移除该设备、清空会话与 token
- 返回 `{"ok":true}`

---

## 14. 错误码

| HTTP | code | 含义 |
| --- | --- | --- |
| 400 | `BAD_REQUEST` | 参数缺失、方法不支持或 JSON 非法 |
| 400 | `PATH_INVALID` | 路径非法或越界 |
| 401 | `UNAUTHORIZED` | token 缺失或错误 |
| 404 | `MAPPING_NOT_FOUND` | 映射不存在 |
| 404 | `FILE_NOT_FOUND` | 文件不存在 |
| 409 | `BUSY` | 已有同步任务 |
| 409 | `INCOMPLETE` | 字节数不足，保留分片 |
| 416 | `RANGE_INVALID` | 续传偏移或 Range 非法 |
| 426 | `PROTO_MISMATCH` | 协议版本不一致 |
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

## 16. 双向发起

文件方向固定为 PC → Android，但**对比与同步可以由任一端发起**：

| 发起方 | 对比方式 | 同步方式 |
| --- | --- | --- |
| PC | 扫描本地源目录 + 调 Android `/v2/index` | 逐文件 `POST /v2/upload` 推送 |
| Android | 调 PC `/v2/index` 取源索引 + 扫描本地目标目录 | 逐文件 `GET /v2/file` 拉取（支持 Range 续传） |

- 发起方必须先完成 `/v2/pair`，并在后续请求携带有效 token
- 主动配对方在请求体附带自己签发的 `token`，被连接方据此可直接反向调用
- 任一方向完成后都由发起方 `POST /v2/log` 给对端留存日志
- 同一时间同一设备只允许一个同步任务；服务端以 `409 BUSY` 拒绝并发上传/配对

---

## 17. 与旧版 SPW-Send 安卓端的兼容性

旧版 SPW-Send 安卓端使用 `54317 / 54318` 端口与 `X-SpwSend-*` 请求头，**不再兼容**。
本模组仅对接 MySend 安卓端，端口与请求头均与 MySend 一致。
