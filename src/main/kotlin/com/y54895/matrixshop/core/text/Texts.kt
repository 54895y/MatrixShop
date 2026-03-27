package com.y54895.matrixshop.core.text

import com.y54895.matrixlib.api.text.MatrixText
import org.bukkit.command.CommandSender

object Texts {

    private val branding = MatrixShopBranding.value

    fun color(text: String): String {
        return MatrixText.color(resolve(text))
    }

    fun colorKey(key: String, placeholders: Map<String, String> = emptyMap()): String {
        return MatrixText.color(tr(key, placeholders))
    }

    fun prefixed(text: String): String {
        return MatrixText.prefixed(branding, resolve(text))
    }

    fun prefixedKey(key: String, placeholders: Map<String, String> = emptyMap()): String {
        return MatrixText.prefixed(branding, tr(key, placeholders))
    }

    fun send(sender: CommandSender, text: String) {
        MatrixText.send(sender, branding, resolve(text))
    }

    fun sendKey(sender: CommandSender, key: String, placeholders: Map<String, String> = emptyMap()) {
        MatrixText.send(sender, branding, tr(key, placeholders))
    }

    fun sendRaw(sender: CommandSender, text: String) {
        MatrixText.sendRaw(sender, resolve(text))
    }

    fun sendRawKey(sender: CommandSender, key: String, placeholders: Map<String, String> = emptyMap()) {
        MatrixText.sendRaw(sender, tr(key, placeholders))
    }

    fun tr(key: String, placeholders: Map<String, String> = emptyMap()): String {
        return MatrixText.raw(MatrixText.apply(resolve(key), placeholders))
    }

    fun apply(template: String, placeholders: Map<String, String>): String {
        return MatrixText.raw(MatrixText.apply(resolve(template), placeholders))
    }

    fun apply(lines: List<String>, placeholders: Map<String, String>): List<String> {
        return lines.map { apply(it, placeholders) }
    }

    private fun resolve(text: String): String {
        return MatrixI18n.resolve(text)
    }
}
