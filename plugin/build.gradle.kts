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
version = "0.1.1"

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

    // 宿主插件运行时由 spw-workshop-api 提供 SaltUI 2.9.0-alpha02 与 Compose 1.12.0，
    // 版本必须与其保持一致，否则会在宿主中出现 NoSuchFieldError / NoSuchMethodError。
    compileOnly("org.jetbrains.compose.runtime:runtime:1.12.0")
    compileOnly("org.jetbrains.compose.foundation:foundation:1.12.0")
    compileOnly("org.jetbrains.compose.ui:ui:1.12.0")
    compileOnly("io.github.moriafly:salt-ui:2.9.0-alpha02")
}

spmod {
    PluginClass = "com.wingua.spwsend.SendPlugin"
    PluginId = "com.wingua.spwsend"
    PluginName = "SPW-Send"
    PluginDescription = "把电脑上的音乐文件夹单向同步到 Android 设备"
    PluginVersion = project.version.toString()
    PluginProvider = "WinGua"
    PluginHasConfig = true
    PluginPermissions = listOf(
        PluginPermission.KEY_BINDINGS,
        PluginPermission.CLASS_TRANSFORM
    )
    PluginOpenSourceUrl = "https://github.com/WinGua/SPW-Send"
}
