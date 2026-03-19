package com.y54895.matrixshop.core.action

import com.y54895.matrixshop.core.text.Texts
import org.bukkit.Sound
import org.bukkit.entity.Player

data class ActionContext(
    val player: Player,
    val placeholders: Map<String, String>,
    val backAction: (() -> Unit)? = null
)

object ActionExecutor {

    fun execute(context: ActionContext, actions: List<String>) {
        actions.forEach { executeSingle(context, it) }
    }

    private fun executeSingle(context: ActionContext, rawAction: String) {
        val action = Texts.apply(rawAction, context.placeholders)
        when {
            action.equals("close", true) -> context.player.closeInventory()
            action.equals("back", true) -> context.backAction?.invoke() ?: context.player.closeInventory()
            action.startsWith("tell:", true) -> context.player.sendMessage(Texts.color(action.substringAfter(':').trim()))
            action.startsWith("sound:", true) -> playSound(context.player, action.substringAfter(':').trim())
            action.isNotBlank() -> context.player.performCommand(action.removePrefix("/"))
        }
    }

    private fun playSound(player: Player, raw: String) {
        val split = raw.split('-')
        val soundName = split.getOrNull(0)?.trim()?.uppercase() ?: return
        val volume = split.getOrNull(1)?.toFloatOrNull() ?: 1f
        val pitch = split.getOrNull(2)?.toFloatOrNull() ?: 1f
        val sound = runCatching { Sound.valueOf(soundName) }.getOrNull() ?: return
        player.playSound(player.location, sound, volume, pitch)
    }
}
