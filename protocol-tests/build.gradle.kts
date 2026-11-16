plugins { kotlin("jvm") version "2.4.20" }
repositories {
    mavenCentral()
}
kotlin { jvmToolchain(25) }
sourceSets {
    main {
        kotlin.srcDir("../plugin/src/main/kotlin")
        kotlin.include("com/wingua/spwsend/core/**", "com/wingua/spwsend/net/**", "com/wingua/spwsend/sync/**")
    }
}
dependencies {
    implementation("org.bouncycastle:bcpkix-jdk18on:1.86")
    implementation("net.java.dev.jna:jna:5.18.1")
    testImplementation(kotlin("test-junit"))
}
tasks.test {
    systemProperty("user.home", layout.buildDirectory.dir("test-home").get().asFile.absolutePath)
    testLogging { events("passed", "failed", "skipped") }
}

tasks.register("writeRuntimeClasspath") {
    dependsOn(tasks.classes)
    doLast { layout.buildDirectory.file("runtime-classpath.txt").get().asFile.writeText(sourceSets.main.get().runtimeClasspath.asPath) }
}
