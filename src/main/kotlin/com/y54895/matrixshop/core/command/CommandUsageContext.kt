package com.y54895.matrixshop.core.command

import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object CommandUsageContext {

    private val mainRoots = ConcurrentHashMap<UUID, String>()
    private val moduleRoots = ConcurrentHashMap<UUID, ConcurrentHashMap<String, String>>()

    fun rememberMain(player: Player, root: String) {
        mainRoots[player.uniqueId] = root.lowercase()
    }

    fun rememberModule(player: Player, moduleId: String, prefix: String) {
        moduleRoots.computeIfAbsent(player.uniqueId) { ConcurrentHashMap() }[moduleId.lowercase()] = prefix
    }

    fun clear(playerId: UUID) {
        mainRoots.remove(playerId)
        moduleRoots.remove(playerId)
    }

    fun mainRoot(player: Player, fallback: String = "matrixshop"): String {
        return mainRoots[player.uniqueId] ?: fallback
    }

    fun modulePrefix(player: Player, moduleId: String, fallback: String): String {
        return moduleRoots[player.uniqueId]?.get(moduleId.lowercase()) ?: fallback
    }

    fun placeholders(player: Player, moduleId: String, fallback: String): Map<String, String> {
        return mapOf("command" to modulePrefix(player, moduleId, fallback))
    }
}
