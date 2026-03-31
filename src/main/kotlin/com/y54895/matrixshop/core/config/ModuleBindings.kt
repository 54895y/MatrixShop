package com.y54895.matrixshop.core.config

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

data class ModuleCommandBinding(
    val keys: List<String>,
    val registerStandalone: Boolean,
    val showInHelp: Boolean,
    val priority: Int,
    val helpKey: String? = null,
    val hintKeys: Map<String, String> = emptyMap(),
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
        "cart" to ModuleCommandBinding(listOf("cart"), registerStandalone = true, showInHelp = true, priority = 40),
        "record" to ModuleCommandBinding(listOf("record", "records"), registerStandalone = true, showInHelp = true, priority = 30)
    )
    private val cache = ConcurrentHashMap<String, ModuleCommandBinding>()

    fun clearCache() {
        cache.clear()
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

    fun helpKey(moduleId: String): String? {
        return load(moduleId).helpKey
    }

    fun hintKey(moduleId: String, key: String): String? {
        return load(moduleId).hintKeys[key.lowercase()]
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
        cache[moduleId]?.let { return it }
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
            helpKey = yaml.getString("Bindings.Commands.Help-Key")?.trim()?.ifBlank { null } ?: fallback.helpKey,
            hintKeys = loadHintKeys(yaml, "Bindings.Commands.Hint-Keys") + fallback.hintKeys,
            helpLines = loadHelpLines(yaml, "Bindings.Commands.Help")
        ).also { cache[moduleId] = it }
    }

    private fun loadHintKeys(yaml: YamlConfiguration, path: String): Map<String, String> {
        val section = yaml.getConfigurationSection(path) ?: return emptyMap()
        return section.getKeys(false).associateNotNull { key ->
            section.getString(key)?.trim()?.takeIf(String::isNotBlank)?.let { key.lowercase() to it }
        }
    }

    private fun loadHelpLines(yaml: YamlConfiguration, path: String): List<String> {
        if (yaml.isList(path)) {
            return yaml.getStringList(path)
        }
        val text = yaml.getString(path) ?: return emptyList()
        return text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    }
}

private inline fun <K, V> Iterable<K>.associateNotNull(transform: (K) -> Pair<K, V>?): Map<K, V> {
    val result = LinkedHashMap<K, V>()
    for (element in this) {
        val pair = transform(element) ?: continue
        result[pair.first] = pair.second
    }
    return result
}
