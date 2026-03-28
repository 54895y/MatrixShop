package com.y54895.matrixshop.core.config

import org.bukkit.configuration.file.YamlConfiguration
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.releaseResourceFile
import taboolib.common.platform.function.releaseResourceFolder
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

object ConfigFiles {

    private val resourceFiles = listOf("config.yml", "module.yml", "database.yml")
    private val bindingMigrations = listOf(
        BindingMigration(
            path = "Cart/settings.yml",
            registerStandalone = true,
            helpKey = "@commands.bindings.cart",
            hintKeys = linkedMapOf(
                "open" to "@commands.bindings.cart",
                "amount" to "@commands.hints.cart-amount"
            )
        ),
        BindingMigration(
            path = "Record/settings.yml",
            registerStandalone = true,
            helpKey = "@commands.bindings.record",
            hintKeys = linkedMapOf(
                "open" to "@commands.bindings.record"
            )
        )
    )

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
        releaseResourceFolder("Lang", false)
        releaseResourceFolder("Menu", false)
        releaseResourceFolder("PlayerShop", false)
        releaseResourceFolder("GlobalMarket", false)
        releaseResourceFolder("Auction", false)
        releaseResourceFolder("ChestShop", false)
        releaseResourceFolder("Transaction", false)
        releaseResourceFolder("Cart", false)
        releaseResourceFolder("Record", false)
        mergeBundledYamlDefaults("Lang/zh_CN.yml")
        mergeBundledYamlDefaults("Lang/en_US.yml")
        migrateLegacyBindingConfigs()
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

    fun setModuleEnabled(path: String, enabled: Boolean) {
        module.set("modules.$path", enabled)
        module.save(File(dataFolder(), "module.yml"))
    }

    fun defaultSystemCategory(): String {
        return config.getString("system-shop.default-category", "weapon").orEmpty().ifBlank { "weapon" }
    }

    private fun load(path: String): YamlConfiguration {
        return YamlConfiguration.loadConfiguration(File(dataFolder(), path))
    }

    private fun migrateLegacyBindingConfigs() {
        bindingMigrations.forEach { migration ->
            val file = File(dataFolder(), migration.path)
            if (!file.exists()) {
                return@forEach
            }
            val yaml = YamlConfiguration.loadConfiguration(file)
            val legacyHelpBlock = yaml.contains("Bindings.Commands.Help") && !yaml.contains("Bindings.Commands.Help-Key")
            var changed = false
            if (legacyHelpBlock) {
                yaml.set("Bindings.Commands.Help", null)
                changed = true
            }
            if (!yaml.contains("Bindings.Commands.Help-Key")) {
                yaml.set("Bindings.Commands.Help-Key", migration.helpKey)
                changed = true
            }
            if (!yaml.contains("Bindings.Commands.Register")) {
                yaml.set("Bindings.Commands.Register", migration.registerStandalone)
                changed = true
            } else if (legacyHelpBlock && migration.registerStandalone && !yaml.getBoolean("Bindings.Commands.Register", false)) {
                yaml.set("Bindings.Commands.Register", true)
                changed = true
            }
            migration.hintKeys.forEach { (key, value) ->
                val hintPath = "Bindings.Commands.Hint-Keys.$key"
                if (!yaml.contains(hintPath)) {
                    yaml.set(hintPath, value)
                    changed = true
                }
            }
            if (changed) {
                yaml.save(file)
            }
        }
    }

    private fun mergeBundledYamlDefaults(path: String) {
        val file = File(dataFolder(), path)
        if (!file.exists()) {
            return
        }
        val stream = ConfigFiles::class.java.classLoader.getResourceAsStream(path) ?: return
        stream.use { input ->
            InputStreamReader(input, StandardCharsets.UTF_8).use { reader ->
                val bundled = YamlConfiguration.loadConfiguration(reader)
                val current = YamlConfiguration.loadConfiguration(file)
                if (mergeMissing(bundled, current)) {
                    current.save(file)
                }
            }
        }
    }

    private fun mergeMissing(source: YamlConfiguration, target: YamlConfiguration): Boolean {
        var changed = false
        fun mergeSection(path: String) {
            val section = if (path.isBlank()) source else source.getConfigurationSection(path) ?: return
            section.getKeys(false).forEach { key ->
                val childPath = if (path.isBlank()) key else "$path.$key"
                if (source.isConfigurationSection(childPath)) {
                    mergeSection(childPath)
                } else if (!target.contains(childPath)) {
                    target.set(childPath, source.get(childPath))
                    changed = true
                }
            }
        }
        mergeSection("")
        return changed
    }
}

private data class BindingMigration(
    val path: String,
    val registerStandalone: Boolean,
    val helpKey: String,
    val hintKeys: Map<String, String>
)
