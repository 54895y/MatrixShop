import io.izzel.taboolib.gradle.*
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    java
    id("io.izzel.taboolib") version "2.0.36"
    kotlin("jvm") version "2.3.0"
}

val matrixLibApiVersion = providers.gradleProperty("matrixlibApiVersion").orElse("1.0.0").get()

taboolib {
    env {
        install(Basic, Bukkit, BukkitHook, CommandHelper)
        repoTabooLib = "https://repo.tabooproject.org/repository/releases"
        disableOnSkippedVersion = false
    }
    description {
        name = "MatrixShop"
        bukkitApi("1.12")
        dependencies {
            name("MatrixLib")
        }
    }
    version {
        taboolib = "6.2.4-99fb800"
        coroutines = null
    }
}

repositories {
    maven("https://repo.tabooproject.org/repository/releases")
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/groups/public/")
    maven(url = uri("../_publish/matrix-api"))
    maven(url = uri("../../_publish/matrix-api"))
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:1.12.2-R0.1-SNAPSHOT")
    compileOnly(kotlin("stdlib"))
    compileOnly("com.y54895.matrixlib:matrixlib-api:$matrixLibApiVersion")
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
