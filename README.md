# SPW-Send

椒盐音乐（Salt Player）配套的局域网音乐库同步模组。

把电脑上的音乐文件夹**单向同步**到 Android 手机 / 平板。电脑端以 Salt Player 创意工坊模组的形式运行，在播放器内完成目录扫描、对比与推送；手机端直接使用 [MySend](https://github.com/WinGua/MySend) 安卓版接收落盘。

> SPW-Send 是 [MySend](https://github.com/WinGua/MySend) 的定制分支，只提供电脑端模组。
> 安卓端不再单独维护，统一使用 MySend 官方 APK；两端遵循同一套协议 v2。

## 组成

| 部分 | 位置 | 运行环境 |
| --- | --- | --- |
| Salt Player 模组（PC） | `plugin/` | 椒盐音乐 Windows / Linux 桌面版 |
| Android 端 | [WinGua/MySend](https://github.com/WinGua/MySend/releases) | Android 7.0 及以上 |

## 当前状态

**模组端功能已实现，构建走 GitHub Actions。**

- 运行时动态定位宿主的 Compose 调用点，适配 SPW 1.19.0 / 1.19.1 及后续重新混淆的版本
- 协议以 MySend v2 为基准：[docs/PROTOCOL.md](docs/PROTOCOL.md)
- 模组端：UDP 发现、HTTP 服务、目录对比、分片续传推送、SaltUI 内嵌面板
- 云端构建：`plugin` 工作流产出 `.spmod`，`release` 工作流在打 tag 时发布 Release

## 构建

代码推送后在 Actions 页面下载 `.spmod`；也可在本地执行：

```bash
cd plugin && gradle plugin
```

## 同步规则

- **单向**：只有「电脑 → Android」，不支持反向或设备互传
- **只同步新增**：目标端不存在该相对路径才传输，已存在的文件直接跳过
- **不处理删除**：目标端多出的文件不会被自动清理
- **不比对内容**：不读取也不校验目标端已有文件
- **自动忽略**：任一路径段以 `.` 开头、以 `~$` 开头，或文件名为 `desktop.ini`、`thumbs.db`、`ehthumbs.db`

## 使用方式

1. Android 端安装 [MySend 官方 APK](https://github.com/WinGua/MySend/releases)，为每组映射选择目标文件夹
2. 在椒盐音乐中打开 / 关闭模组面板（快捷键 `Ctrl+Alt+S` 或配置页按钮）
3. 模组内选择电脑上的源文件夹，与手机端创建同名映射
4. 任一端点击「对比」查看新增文件，再点击「同步」；电脑端推送、手机端拉取均可

## 技术栈

- **模组**：Kotlin / JVM、PF4J、SaltUI 3.0.0-beta02（随 `.spmod` 打包）、Compose，通过 Salt Player Workshop API 加载
- **Android 端**：MySend（Flutter）
- **传输**：UDP 广播发现 + 局域网 HTTP，明文，仅建议在可信网络内使用

## 协议

完整契约见 [docs/PROTOCOL.md](docs/PROTOCOL.md)，包含端口、握手、端点、续传规则与错误码。端口、请求头、字段与 MySend 安卓端保持一致。

## 许可与致谢

本项目沿用 **GNU General Public License v3.0**，与上游 MySend 保持一致。

- 上游 / 安卓端：[WinGua/MySend](https://github.com/WinGua/MySend)
- 插件运行时：[Moriafly/spw-workshop-api](https://github.com/Moriafly/spw-workshop-api)
- 产品参考：[LocalSend](https://github.com/localsend/localsend)

Built by Winter with DeepSeek.
