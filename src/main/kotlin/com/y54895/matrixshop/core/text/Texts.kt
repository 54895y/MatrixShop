package com.y54895.matrixshop.core.text

import com.y54895.matrixlib.api.text.MatrixText
import org.bukkit.command.CommandSender

object Texts {

    private val branding = MatrixShopBranding.value

    fun color(text: String): String {
        return MatrixText.color(text)
    }

    fun prefixed(text: String): String {
        return MatrixText.prefixed(branding, text)
    }

    fun send(sender: CommandSender, text: String) {
        MatrixText.send(sender, branding, text)
    }

    fun sendRaw(sender: CommandSender, text: String) {
        MatrixText.sendRaw(sender, text)
    }

    fun apply(template: String, placeholders: Map<String, String>): String {
        return MatrixText.raw(MatrixText.apply(template, placeholders))
    }

    fun apply(lines: List<String>, placeholders: Map<String, String>): List<String> {
        return lines.map { apply(it, placeholders) }
    }
}
