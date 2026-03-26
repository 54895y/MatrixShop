package com.y54895.matrixshop.core.text

import org.bukkit.command.CommandSender

object Texts {

    fun color(text: String): String {
        return MatrixShopRuntime.color(text)
    }

    fun prefixed(text: String): String {
        return MatrixShopRuntime.prefixed(text)
    }

    fun send(sender: CommandSender, text: String, placeholders: Map<String, String> = emptyMap()) {
        MatrixShopRuntime.send(sender, text, placeholders)
    }

    fun sendRaw(sender: CommandSender, text: String, placeholders: Map<String, String> = emptyMap()) {
        MatrixShopRuntime.sendRaw(sender, text, placeholders)
    }

    fun raw(text: String, placeholders: Map<String, String> = emptyMap()): String {
        return MatrixShopRuntime.raw(text, placeholders)
    }

    fun apply(template: String, placeholders: Map<String, String> = emptyMap()): String {
        return MatrixShopRuntime.apply(template, placeholders)
    }

    fun apply(lines: List<String>, placeholders: Map<String, String> = emptyMap()): List<String> {
        return MatrixShopRuntime.apply(lines, placeholders)
    }
}
