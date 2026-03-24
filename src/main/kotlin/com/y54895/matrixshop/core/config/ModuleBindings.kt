package com.y54895.matrixshop.core.config

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

data class ModuleCommandBinding(
    val keys: List<String>,
    val registerStandalone: Boolean,
    val showInHelp: Boolean,
    val priority: Int,
    val condition: String? = null,
    val helpLines: List<String> = emptyList()
)

object ModuleBindings {

    private val settingsPaths = mapOf(
        "menu" to "Menu/settings.yml",
        "system-shop" to "SystemShop/settings.yml",
        "player-shop" to "PlayerShop/settings.yml",
        "global-market" to "GlobalMarket/settings.yml",
        "auction" to "Auction/settings.yml",
        "chestshop" to "ChestShop/settings.yml",
        "transaction" to "Transaction/settings.yml",
        "cart" to "Cart/settings.yml",
        "record" to "Record/settings.yml"
    )

    private val defaults = linkedMapOf(
        "menu" to ModuleCommandBinding(listOf("menu", "menus"), registerStandalone = false, showInHelp = true, priority = 110),
        "system-shop" to ModuleCommandBinding(listOf("system", "systemshop"), registerStandalone = false, showInHelp = true, priority = 100),
        "player-shop" to ModuleCommandBinding(listOf("player_shop", "playershop", "pshop"), registerStandalone = false, showInHelp = true, priority = 90),
        "global-market" to ModuleCommandBinding(listOf("global_market", "globalmarket", "market", "gm"), registerStandalone = false, showInHelp = true, priority = 80),
        "auction" to ModuleCommandBinding(listOf("auction", "ah"), registerStandalone = true, showInHelp = true, priority = 70),
        "chestshop" to ModuleCommandBinding(listOf("chestshop", "cshop"), registerStandalone = true, showInHelp = true, priority = 60),
        "transaction" to ModuleCommandBinding(listOf("trade", "tm"), registerStandalone = true, showInHelp = true, priority = 50),
        "cart" to ModuleCommandBinding(listOf("cart"), registerStandalone = false, showInHelp = true, priority = 40),
        "record" to ModuleCommandBinding(listOf("record", "records"), registerStandalone = false, showInHelp = true, priority = 30)
    )

    fun binding(moduleId: String): ModuleCommandBinding {
        return load(moduleId)
    }

    fun primary(moduleId: String): String {
        return load(moduleId).keys.firstOrNull() ?: moduleId
    }

    fun keys(moduleId: String): List<String> {
        return load(moduleId).keys
    }

    fun registerStandalone(moduleId: String): Boolean {
        return load(moduleId).registerStandalone
    }

    fun showInHelp(moduleId: String): Boolean {
        return load(moduleId).showInHelp
    }

    fun condition(moduleId: String): String? {
        return load(moduleId).condition
    }

    fun helpLines(moduleId: String): List<String> {
        return load(moduleId).helpLines
    }

    fun matches(moduleId: String, token: String): Boolean {
        return load(moduleId).keys.contains(token.trim().lowercase())
    }

    fun resolveModule(token: String): String? {
        val normalized = token.trim().lowercase()
        return defaults.keys
            .map { it to load(it) }
            .filter { (_, binding) -> binding.keys.contains(normalized) }
            .sortedByDescending { (_, binding) -> binding.priority }
            .firstOrNull()
            ?.first
    }

    private fun load(moduleId: String): ModuleCommandBinding {
        val fallback = defaults[moduleId] ?: ModuleCommandBinding(listOf(moduleId), registerStandalone = false, showInHelp = true, priority = 0)
        val relativePath = settingsPaths[moduleId] ?: return fallback
        val file = File(ConfigFiles.dataFolder(), relativePath)
        if (!file.exists()) {
            return fallback
        }
        val yaml = YamlConfiguration.loadConfiguration(file)
        val keys = yaml.getStringList("Bindings.Commands.Bindings")
            .mapNotNull { it?.trim()?.takeIf(String::isNotBlank)?.lowercase() }
            .distinct()
            .ifEmpty { fallback.keys }
        return ModuleCommandBinding(
            keys = keys,
            registerStandalone = yaml.getBoolean("Bindings.Commands.Register", fallback.registerStandalone),
            showInHelp = yaml.getBoolean("Bindings.Commands.Show-In-Help", fallback.showInHelp),
            priority = yaml.getInt("Bindings.Commands.Priority", fallback.priority),
            condition = yaml.getString("Bindings.Commands.Condition")?.trim()?.ifBlank { fallback.condition },
            helpLines = readHelpLines(yaml, "Bindings.Commands.Help").ifEmpty { fallback.helpLines }
        )
    }

    private fun readHelpLines(yaml: YamlConfiguration, path: String): List<String> {
        val value = yaml.get(path) ?: return emptyList()
        return when (value) {
            is String -> value.lines().map { it.trimEnd() }
            is List<*> -> value.map { it?.toString().orEmpty() }
            else -> emptyList()
        }
    }
}
