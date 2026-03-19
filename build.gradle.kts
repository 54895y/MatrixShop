import io.izzel.taboolib.gradle.*
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    java
    id("io.izzel.taboolib") version "2.0.30"
    kotlin("jvm") version "2.1.0"
}

taboolib {
    env {
        install(Basic, Bukkit, BukkitHook, CommandHelper)
        repoTabooLib = "https://repo.aeoliancloud.com/repository/releases"
        disableOnSkippedVersion = false
    }
    version {
        taboolib = "6.2.4-86dd2bf"
        coroutines = null
    }
}

repositories {
    maven("https://repo.aeoliancloud.com/repository/releases") { isAllowInsecureProtocol = true }
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/groups/public/")
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:1.12.2-R0.1-SNAPSHOT")
    compileOnly(kotlin("stdlib"))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget("1.8")
    }
}

configure<JavaPluginExtension> {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}
