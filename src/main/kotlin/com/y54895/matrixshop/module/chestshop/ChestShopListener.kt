package com.y54895.matrixshop.module.chestshop

import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import taboolib.common.platform.Awake
import taboolib.common.platform.event.SubscribeEvent

@Awake
object ChestShopListener {

    @SubscribeEvent
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) {
            return
        }
        val interactionKind = when (event.action) {
            Action.LEFT_CLICK_BLOCK -> if (event.player.isSneaking) ChestShopInteractionKind.SHIFT_LEFT else ChestShopInteractionKind.LEFT
            Action.RIGHT_CLICK_BLOCK -> if (event.player.isSneaking) ChestShopInteractionKind.SHIFT_RIGHT else ChestShopInteractionKind.RIGHT
            else -> return
        }
        val player = event.player
        val block = event.clickedBlock ?: return
        if (handle(player, block, interactionKind)) {
            event.isCancelled = true
        }
    }

    @SubscribeEvent
    fun onBreak(event: BlockBreakEvent) {
        if (!ChestShopModule.canBreakProtectedBlock(event.player, event.block)) {
            event.isCancelled = true
        }
    }

    private fun handle(player: Player, block: Block, interactionKind: ChestShopInteractionKind): Boolean {
        return ChestShopModule.handleInteraction(player, block, interactionKind)
    }
}
