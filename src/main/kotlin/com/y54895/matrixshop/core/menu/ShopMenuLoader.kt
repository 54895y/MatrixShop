package com.y54895.matrixshop.core.menu

import com.y54895.matrixshop.core.config.ConfigFiles
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.LinkedHashMap

data class ShopMenuSelection(
    val id: String,
    val definition: MenuDefinition
)

object ShopMenuLoader {

    fun load(moduleFolder: String, legacyMainUiFile: String): LinkedHashMap<String, MenuDefinition> {
        val result = LinkedHashMap<String, MenuDefinition>()
        val folder = File(ConfigFiles.dataFolder(), "$moduleFolder/shops")
        folder.mkdirs()
        folder.listFiles { file -> file.isFile && file.extension.equals("yml", true) }
            ?.sortedBy { it.nameWithoutExtension }
            ?.forEach { file ->
                val yaml = YamlConfiguration.loadConfiguration(file)
                val id = yaml.getString("id", file.nameWithoutExtension).orEmpty().ifBlank { file.nameWithoutExtension }.lowercase()
                result[id] = MenuLoader.load(file)
            }
        if (result.isEmpty()) {
            val legacy = File(ConfigFiles.dataFolder(), "$moduleFolder/ui/$legacyMainUiFile")
            result["default"] = MenuLoader.load(legacy)
        }
        return result
    }

    fun resolve(menus: Map<String, MenuDefinition>, requestedId: String?): ShopMenuSelection {
        val normalized = requestedId?.trim()?.takeIf(String::isNotBlank)?.lowercase()
        if (normalized != null) {
            menus[normalized]?.let { return ShopMenuSelection(normalized, it) }
        }
        menus["default"]?.let { return ShopMenuSelection("default", it) }
        val first = menus.entries.first()
        return ShopMenuSelection(first.key, first.value)
    }

    fun contains(menus: Map<String, MenuDefinition>, shopId: String?): Boolean {
        val normalized = shopId?.trim()?.takeIf(String::isNotBlank)?.lowercase() ?: return false
        return menus.containsKey(normalized)
    }
}
