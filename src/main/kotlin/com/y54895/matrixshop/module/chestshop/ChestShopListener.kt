package com.y54895.matrixshop.module.chestshop

import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEvent
import taboolib.common.platform.Awake
import taboolib.common.platform.event.SubscribeEvent

@Awake
object ChestShopListener {

    @SubscribeEvent
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) {
            return
        }
        val player = event.player
        val block = event.clickedBlock ?: return
        if (handle(player, block)) {
            event.isCancelled = true
        }
    }

    @SubscribeEvent
    fun onBreak(event: BlockBreakEvent) {
        if (!ChestShopModule.canBreakProtectedBlock(event.player, event.block)) {
            event.isCancelled = true
        }
    }

    private fun handle(player: Player, block: Block): Boolean {
        return ChestShopModule.handleChestInteract(player, block) || ChestShopModule.handleSignInteract(player, block)
    }
}
