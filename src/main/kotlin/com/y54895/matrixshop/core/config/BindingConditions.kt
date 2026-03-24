package com.y54895.matrixshop.core.config

import com.y54895.matrixshop.core.permission.Permissions
import org.bukkit.entity.Player
import taboolib.common.platform.function.warning
import taboolib.module.kether.KetherShell
import taboolib.module.kether.ScriptOptions
import java.util.concurrent.TimeUnit

object BindingConditions {

    fun test(player: Player, condition: String?, placeholders: Map<String, String> = emptyMap()): Boolean {
        val source = condition?.trim()?.takeIf(String::isNotBlank) ?: return true
        val rendered = replacePlaceholders(source, placeholders)
        return runCatching {
            val result = KetherShell.eval(rendered, ScriptOptions.new {
                sender(player)
                vars(
                    placeholders + mapOf(
                        "player_name" to player.name,
                        "player_uuid" to player.uniqueId.toString()
                    )
                )
                sandbox(true)
            }).get(3, TimeUnit.SECONDS)
            coerceBoolean(result)
        }.onFailure {
            warning("Failed to evaluate binding condition '$rendered': ${it.message}")
        }.getOrDefault(false)
    }

    fun require(player: Player, condition: String?, placeholders: Map<String, String> = emptyMap()): Boolean {
        if (test(player, condition, placeholders)) {
            return true
        }
        return Permissions.deny(player)
    }

    private fun replacePlaceholders(source: String, placeholders: Map<String, String>): String {
        var rendered = source
        placeholders.forEach { (key, value) ->
            rendered = rendered.replace("{$key}", value)
        }
        return rendered
    }

    private fun coerceBoolean(result: Any?): Boolean {
        return when (result) {
            null -> false
            is Boolean -> result
            is Number -> result.toDouble() != 0.0
            is String -> when (result.trim().lowercase()) {
                "true", "yes", "y", "1", "pass", "allow" -> true
                "false", "no", "n", "0", "deny", "null", "" -> false
                else -> result.isNotBlank()
            }
            else -> true
        }
    }
}
