package com.y54895.matrixshop.module.transaction

import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import taboolib.common.platform.Awake
import taboolib.common.platform.event.SubscribeEvent

@Awake
object TransactionListener {

    @SubscribeEvent
    fun onTradeClick(event: InventoryClickEvent) {
        val holder = event.inventory.holder as? TransactionTradeHolder ?: return
        val player = event.whoClicked as? Player ?: return
        TransactionModule.handleTradeClick(player, holder, event)
    }

    @SubscribeEvent
    fun onTradeDrag(event: InventoryDragEvent) {
        TransactionModule.handleTradeDrag(event)
    }

    @SubscribeEvent
    fun onQuit(event: PlayerQuitEvent) {
        TransactionModule.handleQuit(event.player)
    }

    @SubscribeEvent
    fun onDeath(event: PlayerDeathEvent) {
        TransactionModule.handleDeath(event.entity)
    }

    @SubscribeEvent
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        TransactionModule.handleWorldChange(event.player)
    }

    @SubscribeEvent
    fun onDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        TransactionModule.handleDamage(player)
    }
}
