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

    // 宿主已自带 Compose 与 SaltUI，仅编译期使用，不打进 .spmod。
    compileOnly("org.jetbrains.compose.runtime:runtime:1.12.1")
    compileOnly("org.jetbrains.compose.foundation:foundation:1.12.1")
    compileOnly("org.jetbrains.compose.ui:ui:1.12.1")
    compileOnly("io.github.moriafly:salt-ui:3.0.0-beta02")
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
