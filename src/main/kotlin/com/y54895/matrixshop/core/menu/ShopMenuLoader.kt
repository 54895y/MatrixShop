package com.y54895.matrixshop.core.menu

import com.y54895.matrixshop.core.config.ConfigFiles
import com.y54895.matrixshop.core.config.ModuleCommandBinding
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.LinkedHashMap

data class ConfiguredShopMenu(
    val id: String,
    val definition: MenuDefinition,
    val bindings: ModuleCommandBinding = ModuleCommandBinding(emptyList(), false, false, 0)
)

data class ShopMenuSelection(
    val id: String,
    val definition: MenuDefinition,
    val bindings: ModuleCommandBinding
)

object ShopMenuLoader {

    fun load(moduleFolder: String, legacyMainUiFile: String): LinkedHashMap<String, ConfiguredShopMenu> {
        val result = LinkedHashMap<String, ConfiguredShopMenu>()
        val folder = File(ConfigFiles.dataFolder(), "$moduleFolder/shops")
        folder.mkdirs()
        folder.listFiles { file -> file.isFile && file.extension.equals("yml", true) }
            ?.sortedBy { it.nameWithoutExtension }
            ?.forEach { file ->
                val yaml = YamlConfiguration.loadConfiguration(file)
                val id = file.nameWithoutExtension.trim().ifBlank { "default" }
                result[id] = ConfiguredShopMenu(
                    id = id,
                    definition = MenuLoader.load(file),
                    bindings = loadBindings(yaml)
                )
            }
        if (result.isEmpty()) {
            val legacy = File(ConfigFiles.dataFolder(), "$moduleFolder/ui/$legacyMainUiFile")
            result["default"] = ConfiguredShopMenu(
                id = "default",
                definition = MenuLoader.load(legacy)
            )
        }
        return result
    }

    fun resolve(menus: Map<String, ConfiguredShopMenu>, requestedId: String?): ShopMenuSelection {
        val normalized = normalizeShopId(requestedId)
        if (normalized != null) {
            menus.entries.firstOrNull { normalizeShopId(it.key) == normalized }?.value
                ?.let { return ShopMenuSelection(it.id, it.definition, it.bindings) }
        }
        menus.entries.firstOrNull { normalizeShopId(it.key) == "default" }?.value
            ?.let { return ShopMenuSelection(it.id, it.definition, it.bindings) }
        val first = menus.entries.first()
        return ShopMenuSelection(first.value.id, first.value.definition, first.value.bindings)
    }

    fun contains(menus: Map<String, ConfiguredShopMenu>, shopId: String?): Boolean {
        val normalized = normalizeShopId(shopId) ?: return false
        return menus.keys.any { normalizeShopId(it) == normalized }
    }

    fun resolveByBinding(menus: Map<String, ConfiguredShopMenu>, token: String?): ShopMenuSelection? {
        val normalized = token?.trim()?.takeIf(String::isNotBlank)?.lowercase() ?: return null
        return menus.values
            .filter { it.bindings.keys.contains(normalized) }
            .sortedByDescending { it.bindings.priority }
            .firstOrNull()
            ?.let { ShopMenuSelection(it.id, it.definition, it.bindings) }
    }

    fun helpEntries(menus: Map<String, ConfiguredShopMenu>): List<ShopMenuSelection> {
        return menus.values
            .filter { it.bindings.showInHelp && it.bindings.keys.isNotEmpty() }
            .sortedByDescending { it.bindings.priority }
            .map { ShopMenuSelection(it.id, it.definition, it.bindings) }
    }

    fun allEntries(menus: Map<String, ConfiguredShopMenu>): List<ShopMenuSelection> {
        return menus.values
            .sortedByDescending { it.bindings.priority }
            .map { ShopMenuSelection(it.id, it.definition, it.bindings) }
    }

    fun standaloneEntries(menus: Map<String, ConfiguredShopMenu>): List<ShopMenuSelection> {
        return menus.values
            .filter { it.bindings.registerStandalone && it.bindings.keys.isNotEmpty() }
            .sortedByDescending { it.bindings.priority }
            .map { ShopMenuSelection(it.id, it.definition, it.bindings) }
    }

    private fun loadBindings(yaml: YamlConfiguration): ModuleCommandBinding {
        val keys = yaml.getStringList("Bindings.Commands.Bindings")
            .mapNotNull { it?.trim()?.takeIf(String::isNotBlank)?.lowercase() }
            .distinct()
        return ModuleCommandBinding(
            keys = keys,
            registerStandalone = yaml.getBoolean("Bindings.Commands.Register", false),
            showInHelp = yaml.getBoolean("Bindings.Commands.Show-In-Help", false),
            priority = yaml.getInt("Bindings.Commands.Priority", 0),
            helpKey = yaml.getString("Bindings.Commands.Help-Key")?.trim()?.ifBlank { null }
        )
    }

    private fun normalizeShopId(value: String?): String? {
        return value?.trim()?.takeIf(String::isNotBlank)?.lowercase()
    }
}
