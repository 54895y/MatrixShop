package com.y54895.matrixshop.core.util

import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.inventory.meta.SkullMeta

object BukkitCompat {

    fun resolveOfflinePlayer(name: String): OfflinePlayer? {
        val normalized = name.trim()
        if (normalized.isBlank()) {
            return null
        }
        onlinePlayer(normalized)?.let { return it }
        return Bukkit.getOfflinePlayers().firstOrNull { it.name?.equals(normalized, true) == true }
    }

    fun applySkullOwner(meta: SkullMeta, playerName: String) {
        resolveOfflinePlayer(playerName)?.let { owner ->
            val owningPlayerMethod = meta.javaClass.methods.firstOrNull { method ->
                method.name == "setOwningPlayer" &&
                    method.parameterTypes.size == 1 &&
                    OfflinePlayer::class.java.isAssignableFrom(method.parameterTypes[0])
            }
            if (owningPlayerMethod != null) {
                runCatching { owningPlayerMethod.invoke(meta, owner) }
                    .onSuccess { return }
            }
        }
        val legacyMethod = meta.javaClass.methods.firstOrNull { method ->
            method.name == "setOwner" &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes[0] == String::class.java
        }
        if (legacyMethod != null) {
            runCatching { legacyMethod.invoke(meta, playerName) }
        }
    }

    private fun onlinePlayer(name: String): Player? {
        return Bukkit.getPlayerExact(name)
            ?: Bukkit.getOnlinePlayers().firstOrNull { it.name.equals(name, true) }
    }
}
