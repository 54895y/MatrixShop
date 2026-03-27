package com.y54895.matrixshop.core.text

import com.y54895.matrixshop.core.config.ConfigFiles
import org.bukkit.configuration.file.YamlConfiguration
import taboolib.common.platform.function.warning
import java.io.File

object MatrixI18n {

    private var activeCode: String = "zh_CN"
    private var fallbackCode: String = "zh_CN"
    private var activeBundle: YamlConfiguration = YamlConfiguration()
    private var fallbackBundle: YamlConfiguration = YamlConfiguration()

    fun reload() {
        val configured = ConfigFiles.config.getString("language.default", "zh_CN").orEmpty().ifBlank { "zh_CN" }
        val fallback = ConfigFiles.config.getString("language.fallback", "zh_CN").orEmpty().ifBlank { "zh_CN" }
        activeCode = configured
        fallbackCode = fallback
        activeBundle = loadBundle(configured)
        fallbackBundle = if (fallback.equals(configured, true)) activeBundle else loadBundle(fallback)
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
        return activeBundle.getString(path)
            ?: fallbackBundle.getString(path)
    }

    private fun loadBundle(code: String): YamlConfiguration {
        val file = File(ConfigFiles.dataFolder(), "Lang/$code.yml")
        if (!file.exists()) {
            warning("MatrixShop i18n file not found: Lang/$code.yml")
            return YamlConfiguration()
        }
        return YamlConfiguration.loadConfiguration(file)
    }
}
