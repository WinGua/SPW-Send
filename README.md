# SPW-Send

椒盐音乐（Salt Player）配套的局域网音乐库同步方案。

把电脑上的音乐文件夹**单向同步**到 Android 手机 / 平板。电脑端以 Salt Player 创意工坊模组的形式运行，在播放器内完成目录扫描、对比与推送；手机端是专用接收器，只负责接收与落盘。

> SPW-Send 是 [MySend](https://github.com/WinGua/MySend) 的定制分支。MySend 面向通用文件同步，同时编译 Windows 与 Android 两端；SPW-Send 只服务椒盐音乐场景，电脑端改为播放器模组。

## 组成

| 部分 | 位置 | 运行环境 |
| --- | --- | --- |
| Salt Player 模组 | `plugin/` | 椒盐音乐 Windows / Linux 桌面版 |
| Android 接收端 | 仓库根目录 | Android 7.0 及以上 |

## 当前状态

**开发中，暂无可用构建。**

- 协议 v2 已定稿：[docs/PROTOCOL.md](docs/PROTOCOL.md)
- 模组工程骨架已建立，功能未实现
- Android 接收端尚未移植

## 同步规则

- **单向**：只有「电脑 → Android」，不支持反向或设备互传
- **只同步新增**：目标端不存在该相对路径才传输，已存在的文件直接跳过
- **不处理删除**：目标端多出的文件不会被自动清理
- **不比对内容**：不读取也不校验目标端已有文件
- **自动忽略**：任一路径段以 `.` 开头、以 `~$` 开头，或文件名为 `desktop.ini`、`thumbs.db`、`ehthumbs.db`

## 使用方式（规划）

1. Android 端安装 APK，为每组映射选择目标文件夹
2. 在椒盐音乐中打开模组面板（快捷键或配置页按钮）
3. 模组内选择电脑上的源文件夹，与手机端同名映射配对
4. 点击「对比」查看新增文件，再点击「同步」

> 同步仅在模组面板打开期间可用。关闭面板即停止局域网服务，不驻留后台。

## 技术栈

- **模组**：Kotlin / JVM、PF4J、Swing（第一阶段界面），通过 Salt Player Workshop API 加载
- **接收端**：Flutter，单端 Android
- **传输**：UDP 广播发现 + 局域网 HTTP，明文，仅建议在可信网络内使用

## 协议

完整契约见 [docs/PROTOCOL.md](docs/PROTOCOL.md)，包含端口、握手、端点、续传规则与错误码。

## 许可与致谢

本项目沿用 **GNU General Public License v3.0**，与上游 MySend 保持一致。

- 前身：[WinGua/MySend](https://github.com/WinGua/MySend)
- 插件运行时：[Moriafly/spw-workshop-api](https://github.com/Moriafly/spw-workshop-api)
- 产品参考：[LocalSend](https://github.com/localsend/localsend)

Built by Winter with DeepSeek.
