package com.y54895.matrixshop.core.text

import org.bukkit.ChatColor
import org.bukkit.command.CommandSender

object Texts {

    private const val PREFIX = "&8[&bMatrixShop&8] &7"

    fun color(text: String): String {
        return ChatColor.translateAlternateColorCodes('&', text)
    }

    fun prefixed(text: String): String {
        return color(PREFIX + text)
    }

    fun send(sender: CommandSender, text: String) {
        sender.sendMessage(prefixed(text))
    }

    fun sendRaw(sender: CommandSender, text: String) {
        sender.sendMessage(color(text))
    }

    fun apply(template: String, placeholders: Map<String, String>): String {
        var result = template
        placeholders.forEach { (key, value) ->
            result = result.replace("{$key}", value)
        }
        return color(result)
    }

    fun apply(lines: List<String>, placeholders: Map<String, String>): List<String> {
        return lines.map { apply(it, placeholders) }
    }
}
