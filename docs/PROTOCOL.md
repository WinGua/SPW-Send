# SPW-Send / MySend 同步协议 v2

状态：本分支安全同步扩展，尚未发布。端点仍使用 v2 路径，v1.1 明文实现不兼容。

适用范围：SPW-Send 模组（PC）↔ MySend 安卓端

基准实现：MySend `lib/services/transfer/` 与 `lib/services/discovery_service.dart`

---

## 1. 设计目标

- 双向：任一端都可以发送或接收，文件方向由「哪一端存在新增」决定
- 只增不删：只传目标端不存在的相对路径，已存在文件绝不覆盖
- 可续传：发送与接收都从已写入位置继续，不重传整个文件
- 可共存：PC 与 Android 使用各自的本机端口，互不冲突
- 零常驻：PC 侧服务仅在模组面板打开期间存在

---

## 2. 常量

| 名称 | 值 |
| --- | --- |
| 协议版本 `proto` | `2` |
| UDP 发现端口 | `53317` |
| HTTPS 默认端口 | `53318` |
| 广播周期 | `2s` |
| 设备过期 | `12s` |
| 控制请求超时 | `10s` |
| 目录索引超时 | `60s` |
| 文件传输超时 | `max(60s, 大小 / 1MiB × 2s)`，上限 `10min` |
| 单设备并发同步任务 | `1` |
| 分片目录 | `.mysend-part` |
| 分片保留 | `7 天` |

HTTPS 端口被占用时允许改用系统分配的端口，但必须在发现报文的 `port` 中声明实际端口，对端以此为准。

---

## 3. 角色与端点归属

| 角色 | `role` | 本机映射根目录 |
| --- | --- | --- |
| PC（模组） | `pc` | 映射的 `sourcePath` |
| Android（MySend） | `android` | 映射的 `destPath` |

两端使用完全对称的端点集合，任意一端都能作为发送方或接收方：

| 端点 | PC | Android |
| --- | --- | --- |
| `GET /v2/hello` | 必须 | 必须 |
| `POST /v2/pair` | 必须 | 必须 |
| `POST /v2/sync/acquire` | 必须 | 必须 |
| `POST /v2/sync/renew` | 必须 | 必须 |
| `POST /v2/sync/release` | 必须 | 必须 |
| `POST /v2/sync/status` | 必须 | 必须 |
| `GET /v2/index` | 必须 | 必须 |
| `GET /v2/file`（下载本机文件） | 必须 | 必须 |
| `GET /v2/resume`（查询分片） | 必须 | 必须 |
| `POST /v2/resume/batch`（批量查询分片） | 必须 | 必须 |
| `POST /v2/upload`（接收文件） | 必须 | 必须 |
| `POST /v2/log` | 必须 | 必须 |
| `POST /v2/disconnect` | 必须 | 必须 |

同一映射名称在两端指向各自的映射根目录：PC 始终读写 `sourcePath`，Android 始终读写 `destPath`。

---

## 4. 设备发现

UDP 广播到 `255.255.255.255:53317`，并对每个网卡的定向广播地址各发一份；忽略回环接口。

报文（UTF-8 JSON，单行；指纹与版本为示意值）：

```json
{
  "t": "hello",
  "protocol": "https",
  "fingerprint": "<64 位小写十六进制 SHA-256>",
  "capabilities": ["sync-session", "sync-status"],
  "protocol": "https",
  "fingerprint": "<主动配对方证书 SHA-256>",
  "proto": 2,
  "id": "9f1c...",
  "name": "DESKTOP-8K2",
  "role": "pc",
  "form": "desktop",
  "port": 53318,
  "session": "4b7e...",
  "app": "spw-send-mod",
  "appVersion": "1.1"
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

- 强制 HTTPS，仅在 TLS 握手通过证书指纹及有效期校验后发送请求；不使用代理或重定向
- 使用 HTTP/1.1 连接复用，响应必须提供正确的 `Content-Length`；请求不支持 `Transfer-Encoding`
- 发现和配对报文新增 `protocol:"https"`、`fingerprint`（证书 DER 的 SHA-256 小写 hex）和能力字段
- 索引可在空闲时单独对比；文件和续传请求必须携带 `X-MySend-Sync:<任务 id>`，且与 token 所属设备和已取得的租约同时匹配
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
  "protocol": "https",
  "fingerprint": "<服务端证书 SHA-256>",
  "capabilities": ["sync-session", "sync-status"],
  "proto": 2,
  "app": "mysend",
  "version": "2.0.0",
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
  "protocol": "https",
  "fingerprint": "<主动配对方证书 SHA-256>",
  "proto": 2,
  "id": "9f1c...",
  "name": "DESKTOP-8K2",
  "role": "pc",
  "form": "desktop",
  "port": 53318,
  "session": "4b7e...",
  "app": "spw-send-mod",
  "appVersion": "1.1",
  "token": "c81d..."
}
```

`token` 可选：主动配对方附上自己签发的会话 token，供被连接方在本次会话中反向调用。两端都可以主动发起 `/v2/pair`，配对成功后双方各持有可调用对方的 token。

`200`：

```json
{
  "ok": true,
  "protocol": "https",
  "fingerprint": "<服务端证书 SHA-256>",
  "token": "a3f0...",
  "session": "4b7e...",
  "device": { "id": "9f1c...", "name": "DESKTOP-8K2", "role": "pc" }
}
```

- `proto != 2` → `426 PROTO_MISMATCH`
- 请求体 `role` 必须是对端角色（PC 收 `android`，Android 收 `pc`）
- 已有同步任务进行中 → `409 BUSY`
- 配对交换会话 token 与证书身份；后续同一目标的 HTTPS 连接可复用

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
- 同步进行中，缺少匹配的 `X-MySend-Sync` → `409 BUSY`

---

## 9. 差异计算

发起方同时扫描本机映射根与对端 `/v2/index`：

```
本机发送 = 本机索引 - 对端索引（按 p 精确匹配）
本机接收 = 对端索引 - 本机索引（按 p 精确匹配）
两端都有 = 跳过
```

- 待发送与待接收各自按 `p` 字典序排序，先发送、后接收
- 目标端已存在该路径 → 跳过，不读取、不校验
- 不处理任何一端多出的文件
- Windows 文件系统不区分大小写：接收前若发现仅大小写不同的同名文件，按已存在处理，不覆盖

---

## 10. 发送（本机 → 对端）

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

- 目标文件已存在 → `409 ALREADY_EXISTS`，不覆盖、不读取
- 客户端应把 `ALREADY_EXISTS` 视为「已存在，跳过」，不计为失败

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

## 11. 拉取（对端 → 本机）

任意一端都可以作为下载方，调用对端的 `GET /v2/file`：

```
GET /v2/file?name=<映射名>&path=<相对路径>
Range: bytes=<n>-
```

- 无 `Range`：`200`，返回完整文件
- 有 `Range`：`206`，响应头 `Content-Range: bytes <n>-<total-1>/<total>`
- 起点越界 → `416 RANGE_INVALID`
- 文件不存在 → `404 FILE_NOT_FOUND`
- 未知端点（旧版无此能力）→ `404 NOT_FOUND`
- 同步进行中，缺少匹配的 `X-MySend-Sync` → `409 BUSY`
- 客户端以本地分片长度作为 Range 起点，完成后按 10.3 的规则原子落盘
- 对端返回 `200` 而非 `206` 时，客户端必须从 0 覆盖分片，避免重复写入

---

## 12. 同步日志

```
POST /v2/log
Content-Type: application/json
```

```json
{
  "name": "音乐",
  "peer": "Pixel 8",
  "time": 1697000000000,
  "sent": { "c": 100, "b": 100000000, "f": [ "01 以父之名.flac" ] },
  "recv": { "c": 18, "b": 23456789, "f": [ "02 晴天.flac" ] },
  "count": 118,
  "bytes": 123456789,
  "files": [ "01 以父之名.flac", "02 晴天.flac" ]
}
```

- `name` 映射名，`peer` 发送方设备名
- `sent` / `recv` 为发送方视角的成功文件数、字节数与文件名（相对路径最后一段）
- `count` / `bytes` / `files` 为合计字段，仅供旧版对端读取
- 接收端保存日志时必须互换方向：对端的 `sent` 记为本机接收，对端的 `recv` 记为本机发送
- 旧版对端没有 `sent` / `recv` 时，按「本机发送」归档（旧版 MySend 只会拉取 PC 文件）
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
| 404 | `NOT_FOUND` | 未知端点（旧版对端缺少能力） |
| 409 | `BUSY` | 已有同步任务 |
| 409 | `MAPPING_NOT_CONFIGURED` | 映射存在但本机目录不可用 |
| 409 | `ALREADY_EXISTS` | 目标文件已存在，不覆盖 |
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

- 文件、索引与 token 均走 TLS；证书指纹在握手中核对，身份变化拒绝连接
- UDP 发现尚未认证，首次配对仍需可信网络；当前未实现双向 TLS 客户端证书认证
- 本机私钥保存在安装用户的 `~/.spw-send/identity.p12`，不打入模组、不上传；POSIX 系统只允许文件所有者读写
- 配对成功后按设备 id 持久化指纹；普通断开保留记录，确认设备重装后手动忘记身份
- 服务端必须做路径越界校验：规范化后必须位于映射根目录之内
- 拒绝符号链接逃逸；Android 端需解析真实路径后再次校验

---

## 16. 双向发起

**对比与同步可以由任一端发起，且发起方都会先发送本机新增、再接收对端新增**：

| 发起方 | 对比方式 | 同步方式 |
| --- | --- | --- |
| PC | 扫描本地 `sourcePath` + 调 Android `/v2/index` | 先 `POST /v2/upload` 推送，再 `GET /v2/file` 拉取 |
| Android | 调 PC `/v2/index` + 扫描本地 `destPath` | 先 `POST /v2/upload` 推送，再 `GET /v2/file` 拉取 |

- 发起方必须先完成 `/v2/pair`，并在后续请求携带有效 token
- 主动配对方在请求体附带自己签发的 `token`，被连接方据此可直接反向调用
- 同步完成后由发起方 `POST /v2/log` 给对端留存日志，报文包含发送与接收两个方向
- 同一时间同一设备只允许一个同步任务；服务端以 `409 BUSY` 拒绝并发上传/配对

---

## 17. 安全会话与双端状态

- 每次任务生成随机 id。双方均按设备 id 的字典顺序申请两个槽位，失败释放已获得的部分，避免同时发起形成交叉死锁。
- `POST /v2/sync/acquire?task=<id>`、`renew`、`release` 均要求 token 和空请求体。冲突返回 409。
- 每 10 秒续租，空闲租约 45 秒。请求处理和文件响应未退出前，过期或释放均不会开放新任务；迟到的旧任务释放不能释放新任务。
- 获得两端会话后重新扫描，避免旧计划导致重复传输。发送、接收各自最多四个不同文件并发，同一目标只允许一个上传写入者。
- `POST /v2/sync/status` 需要 token 与 `X-MySend-Sync`，JSON 请求体上限 16 KiB：

```json
{"phase":"start","name":"music","peer":"我的电脑","total":2}
{"phase":"plan","total":2}
{"phase":"end","sent":1,"received":1,"failed":0,"error":null}
```

`start` 在开始重新扫描前发送，`plan` 为锁内得到的新文件数；`end` 使用发起端视角，接收端交换 sent/received 显示。对端直接从自己的文件流统计进度，不需要逐文件发送进度 HTTP 请求。错误文本最多 2000 字符。异常释放或租约回收时，将尚未完成的窗口标记为中断；已完成结果不被覆盖。

## 18. 升级要求

v1.1 插件和旧 MySend 的明文传输不再兼容。两端都应更新到支持 HTTPS、指纹和同步会话的版本。仅有相同的 `/v2` 路径不代表兼容；禁止无声回退 HTTP。原版 MySend v2.0.0 安装包尚不具备这些扩展，需要本次更新后的配套构建。


### 取消同步

`POST /v2/sync/cancel?task=<task>` 使用已配对 token 和空请求体。任务由该对端发起时，服务端将租约标记取消并关闭对应的活动文件连接；任务由本机发起时，只接受当前配对对端与任务 ID 同时匹配的取消。未知或其他设备的任务返回 409。活动文件 I/O 退出前不会开放同步槽位。


### 配对身份校验

配对前 POST `/v2/pair/challenge` 提交设备 `id`、`role` 和 `fingerprint`，返回一次性 `challengeId`、`challenge`、服务端 `session` 与 `fingerprint`。挑战有效期 30 秒，每个服务最多保留 128 个，验证时立即消费。

POST `/v2/pair` 除设备描述与反向 token 外，还需提交 PEM `certificate`、`challengeId` 和 Base64 `proof`。证书 SHA-256 必须匹配设备指纹，签名使用持久身份私钥的 RSA PKCS#1 v1.5 / SHA-256。签名数据为 UTF-8 `MySend-Pair-v1\n`，之后依次编码服务端指纹、服务端 session、challengeId、challenge，以及请求中的 `id,fingerprint,role,form,name,port,session,token`；每项采用 ASCII 字节长度、冒号、UTF-8 内容。缺失 port 为 0，其他缺失项为空字符串。

签发 token 前验证证明；未知身份还需接收端人工确认。图标核对沿用 LocalSend：两端 SHA-256 指纹转大写、排序拼接后再取 SHA-256 的前 128 位，以固定的 260 图标字典转为 16 位编码，按四行四列显示。图标与文字模式用于首次配对时人工核对，两端均支持；图标核对不能替代私钥证明。双方需配套升级。
