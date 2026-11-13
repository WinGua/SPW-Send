# 上游依赖

状态：随依赖变动更新

适用范围：SPW-Send 模组（`plugin/`）

本模组建立在两个上游仓库之上。升级、排障或改动宿主适配前，先读这一页。

---

## 1. 上游一览

| 上游 | 作用 | 仓库 | 当前版本 | 获取方式 |
| --- | --- | --- | --- | --- |
| spw-workshop-api | 模组 Gradle 插件与编译期 API：插件加载、宿主 Compose 调用点、`spmod` 打包 | <https://github.com/Moriafly/spw-workshop-api> | `0.1.0-dev23` | JitPack：`com.github.Moriafly.spw-workshop-api` |
| SaltUI | 模组面板的 UI 组件，随 `.spmod` 打进 `lib/` | <https://github.com/Moriafly/SaltUI> | `3.0.0-beta02` | Maven Central：`io.github.moriafly:salt-ui-desktop` / `salt-core-desktop` |

当前适配基线：SPW `1.19.1` · Workshop API `0.1.0-dev23` · Compose `1.12.1` · Kotlin `2.4.20` · JVM `25` · SaltUI `3.0.0-beta02`。

## 2. 本地落点

| 要改的内容 | 文件 |
| --- | --- |
| 版本号与 Maven 坐标 | `plugin/gradle/libs.versions.toml` |
| 插件 id、SaltUI 依赖、`spmod` 元数据 | `plugin/build.gradle.kts` |
| JitPack 仓库与 `useModule` 映射 | `plugin/settings.gradle.kts` |
| 宿主 Compose 调用点动态定位 | `plugin/src/main/kotlin/com/wingua/spwsend/ui/HostUiTargets.kt` |
| 外观与字体兜底 | `plugin/src/main/kotlin/com/wingua/spwsend/ui/HostAppearance.kt`、`HostFont.kt` |
| 面板入口与生命周期 | `plugin/src/main/kotlin/com/wingua/spwsend/SendPlugin.kt`、`ui/ComposePanel.kt` |

## 3. 何时必须回看上游

- 升级宿主 SPW、Workshop API、Compose 或 SaltUI 之前。
- 宿主改版后出现以下症状：面板打不开、样式或配色错乱、`HostUiTargets` 定位失败、Compose 版本冲突。
- 发布 `.spmod` 前，对照一次第 1 节的基线。
- 上游 Release 出现 breaking change 或 dev 版本号变化时。

## 4. 约束

- 版本只从上游 Release / JitPack 获取，不猜坐标、不改 `group` 名。
- SaltUI 保持 `isTransitive = false`，不假设宿主自带 SaltUI。
- Compose 由宿主提供，一律 `compileOnly`，不打进 `.spmod`。
- SaltUI 以 Apache-2.0 随包分发，许可声明见 `NOTICE`。
- 升级完成后同步更新本文件与 README 的「技术栈与协议」。
