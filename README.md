# SPW-Send

椒盐音乐（Salt Player）配套的局域网音乐库同步方案。

把电脑上的音乐文件夹**单向同步**到 Android 手机 / 平板。电脑端以 Salt Player 创意工坊模组的形式运行，在播放器内完成目录扫描、对比与推送；手机端负责接收落盘，也可主动发起对比与拉取。

> SPW-Send 是 [MySend](https://github.com/WinGua/MySend) 的定制分支。MySend 面向通用文件同步，同时编译 Windows 与 Android 两端；SPW-Send 只服务椒盐音乐场景，电脑端改为播放器模组。

## 组成

| 部分 | 位置 | 运行环境 |
| --- | --- | --- |
| Salt Player 模组 | `plugin/` | 椒盐音乐 Windows / Linux 桌面版 |
| Android 端 | 仓库根目录 | Android 7.0 及以上 |

## 当前状态

**功能已实现，构建走 GitHub Actions。**

- 协议 v2 已定稿：[docs/PROTOCOL.md](docs/PROTOCOL.md)
- 模组端：UDP 发现、HTTP 服务、目录对比、分片续传推送、Compose + SaltUI 内嵌面板
- Android 端：协议 v2 接收服务、映射管理、同步日志、前台保活，并支持由手机发起对比与拉取同步
- 云端构建：`android` 工作流产出 APK，`plugin` 工作流产出 `.spmod`，`release` 工作流在打 tag 时发布 Release

## 构建

代码推送后在 Actions 页面下载产物；也可在本地执行：

```bash
flutter pub get && flutter build apk --release   # Android
cd plugin && gradle plugin                       # 模组
```

发布前需在仓库 Secrets 中配置签名密钥：

| Secret | 说明 |
| --- | --- |
| `ANDROID_KEYSTORE_B64` | release keystore 的 Base64 编码 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 与 key 的密码 |

未配置时构建回退到 debug 签名，仅供测试安装。

## 同步规则

- **单向**：只有「电脑 → Android」，不支持反向或设备互传
- **只同步新增**：目标端不存在该相对路径才传输，已存在的文件直接跳过
- **不处理删除**：目标端多出的文件不会被自动清理
- **不比对内容**：不读取也不校验目标端已有文件
- **自动忽略**：任一路径段以 `.` 开头、以 `~$` 开头，或文件名为 `desktop.ini`、`thumbs.db`、`ehthumbs.db`

## 使用方式

1. Android 端安装 APK，为每组映射选择目标文件夹
2. 在椒盐音乐中打开模组面板（快捷键或配置页按钮）
3. 模组内选择电脑上的源文件夹，与手机端同名映射配对
4. 任一端点击「对比」查看新增文件，再点击「同步」；电脑端推送、手机端拉取均可

> 面板会跟随宿主的深色 / 浅色设置；无法识别宿主外观时回退系统主题。
>
> 同步仅在模组面板打开期间可用。关闭面板即停止局域网服务，不驻留后台。

## 技术栈

- **模组**：Kotlin / JVM、PF4J、Compose + SaltUI（宿主内嵌界面），通过 Salt Player Workshop API 加载
- **Android 端**：Flutter，单端 Android
- **传输**：UDP 广播发现 + 局域网 HTTP，明文，仅建议在可信网络内使用

## 协议

完整契约见 [docs/PROTOCOL.md](docs/PROTOCOL.md)，包含端口、握手、端点、续传规则与错误码。

## 许可与致谢

本项目沿用 **GNU General Public License v3.0**，与上游 MySend 保持一致。

- 前身：[WinGua/MySend](https://github.com/WinGua/MySend)
- 插件运行时：[Moriafly/spw-workshop-api](https://github.com/Moriafly/spw-workshop-api)
- 界面组件：[Moriafly/SaltUI](https://github.com/Moriafly/SaltUI)
- 产品参考：[LocalSend](https://github.com/localsend/localsend)

Built by Winter with DeepSeek.
