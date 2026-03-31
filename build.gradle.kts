import io.izzel.taboolib.gradle.*
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    java
    id("com.gradleup.shadow") version "9.3.1"
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
        contributors {
            name("54895y")
        }
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
}

dependencies {
    implementation("org.bstats:bstats-bukkit:3.2.1")
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

tasks.named<ShadowJar>("shadowJar") {
    val runtimeJar = tasks.named<Jar>("jar").get()
    dependsOn(runtimeJar)
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(zipTree(runtimeJar.archiveFile))
    configurations = listOf(project.configurations.runtimeClasspath.get())

    dependencies {
        exclude { dependency ->
            dependency.moduleGroup != "org.bstats"
        }
    }

    relocate("org.bstats", "${project.group}.libs.bstats")
}

tasks.named("build") {
    dependsOn(tasks.named("shadowJar"))
}
