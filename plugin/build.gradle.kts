import com.xuncorp.spw.workshop.gradle.PluginPermission
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java-library")
    kotlin("jvm") version "2.4.20"
    kotlin("kapt") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("com.xuncorp.spw.workshop") version "0.1.0-dev22"
}

group = "com.wingua.spwsend"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    jvmToolchain(25)

    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
    }
}

dependencies {
    compileOnly(kotlin("stdlib"))
    compileOnly(libs.spw.workshop.api)
    kapt(libs.spw.workshop.api)

    // 只依赖宿主插件运行时实际暴露的 Compose 1.12.0 基础构件。
    // SaltUI 未向插件类加载器暴露（连 SaltConfigs 都找不到），因此面板全部自绘，
    // 不再引用 com.moriafly.salt.ui 下的任何类。
    compileOnly("org.jetbrains.compose.runtime:runtime:1.12.0")
    compileOnly("org.jetbrains.compose.foundation:foundation:1.12.0")
    compileOnly("org.jetbrains.compose.ui:ui:1.12.0")
    compileOnly("org.jetbrains.compose.animation:animation:1.12.0")
}

spmod {
    PluginClass = "com.wingua.spwsend.SendPlugin"
    PluginId = "com.wingua.spwsend"
    PluginName = "SPW-Send"
    PluginDescription = "把电脑上的音乐文件夹单向同步到 MySend 安卓端"
    PluginVersion = project.version.toString()
    PluginProvider = "WinGua"
    PluginHasConfig = true
    PluginPermissions = listOf(
        PluginPermission.KEY_BINDINGS,
        PluginPermission.CLASS_TRANSFORM
    )
    PluginOpenSourceUrl = "https://github.com/WinGua/SPW-Send"
}
