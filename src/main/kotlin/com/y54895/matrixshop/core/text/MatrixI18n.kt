package com.y54895.matrixshop.core.text

import com.y54895.matrixshop.core.config.ConfigFiles
import org.bukkit.configuration.file.YamlConfiguration
import taboolib.common.platform.function.warning
import java.io.File
import java.util.LinkedHashMap

object MatrixI18n {

    private var activeCode: String = "zh_CN"
    private var fallbackCode: String = "zh_CN"
    private var activeValues: Map<String, String> = emptyMap()
    private var fallbackValues: Map<String, String> = emptyMap()

    fun reload() {
        val configured = ConfigFiles.config.getString("language.default", "zh_CN").orEmpty().ifBlank { "zh_CN" }
        val fallback = ConfigFiles.config.getString("language.fallback", "zh_CN").orEmpty().ifBlank { "zh_CN" }
        activeCode = configured
        fallbackCode = fallback
        activeValues = loadBundle(configured)
        fallbackValues = if (fallback.equals(configured, true)) activeValues else loadBundle(fallback)
    }

    fun code(): String {
        return activeCode
    }

    fun resolve(input: String): String {
        val token = input.trim()
        if (!token.startsWith("@")) {
            return input
        }
        val key = token.substring(1).trim()
        if (key.isBlank()) {
            return input
        }
        return find(key) ?: "&c[Missing i18n key: $key]"
    }

    private fun find(path: String): String? {
        return activeValues[path]
            ?: fallbackValues[path]
    }

    private fun loadBundle(code: String): Map<String, String> {
        val file = File(ConfigFiles.dataFolder(), "Lang/$code.yml")
        if (!file.exists()) {
            warning("MatrixShop i18n file not found: Lang/$code.yml")
            return emptyMap()
        }
        return flatten(YamlConfiguration.loadConfiguration(file))
    }

    private fun flatten(yaml: YamlConfiguration): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        fun walk(path: String) {
            val section = if (path.isBlank()) yaml else yaml.getConfigurationSection(path) ?: return
            section.getKeys(false).forEach { key ->
                val child = if (path.isBlank()) key else "$path.$key"
                if (yaml.isConfigurationSection(child)) {
                    walk(child)
                } else {
                    yaml.getString(child)?.let { result[child] = it }
                }
            }
        }
        walk("")
        return result
    }
}
