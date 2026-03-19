package com.y54895.matrixshop.core.config

import org.bukkit.configuration.file.YamlConfiguration
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.releaseResourceFile
import taboolib.common.platform.function.releaseResourceFolder
import java.io.File

object ConfigFiles {

    private val resourceFiles = listOf("config.yml", "module.yml", "database.yml")

    lateinit var config: YamlConfiguration
        private set
    lateinit var module: YamlConfiguration
        private set
    lateinit var database: YamlConfiguration
        private set

    fun ensureDefaults() {
        getDataFolder().mkdirs()
        resourceFiles.forEach { releaseResourceFile(it, false) }
        releaseResourceFolder("SystemShop", false)
        releaseResourceFolder("Auction", false)
        releaseResourceFolder("ChestShop", false)
        releaseResourceFolder("Transaction", false)
        releaseResourceFolder("Cart", false)
        releaseResourceFolder("Record", false)
    }

    fun reload() {
        config = load("config.yml")
        module = load("module.yml")
        database = load("database.yml")
    }

    fun dataFolder(): File {
        return getDataFolder()
    }

    fun isDebug(): Boolean {
        return config.getBoolean("debug", false)
    }

    fun isModuleEnabled(path: String, defaultValue: Boolean = true): Boolean {
        return module.getBoolean("modules.$path", defaultValue)
    }

    fun defaultSystemCategory(): String {
        return config.getString("system-shop.default-category", "weapon").orEmpty().ifBlank { "weapon" }
    }

    private fun load(path: String): YamlConfiguration {
        return YamlConfiguration.loadConfiguration(File(dataFolder(), path))
    }
}
