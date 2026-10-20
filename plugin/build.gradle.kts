import com.xuncorp.spw.workshop.gradle.PluginPermission
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java-library")
    kotlin("jvm") version "2.4.20"
    kotlin("kapt") version "2.4.20"
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
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

dependencies {
    compileOnly(kotlin("stdlib"))
    compileOnly(libs.spw.workshop.api)
    kapt(libs.spw.workshop.api)
}

spmod {
    PluginClass = "com.wingua.spwsend.SendPlugin"
    PluginId = "com.wingua.spwsend"
    PluginName = "SPW-Send"
    PluginDescription = "把电脑上的音乐文件夹单向同步到 Android 设备"
    PluginVersion = project.version.toString()
    PluginProvider = "Winter"
    PluginHasConfig = true
    PluginPermissions = listOf(PluginPermission.KEY_BINDINGS)
    PluginOpenSourceUrl = "https://github.com/WinGua/SPW-Send"
}
