package com.y54895.matrixshop.core.warehouse

import com.y54895.matrixshop.core.text.Texts
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

enum class DeliveryRoute {
    INVENTORY,
    MAILBOX,
    DROP,
    FAILED
}

data class DeliveryResult(
    val success: Boolean,
    val route: DeliveryRoute
)

object PlayerItemDelivery {

    fun canDeliver(player: Player, stacks: List<ItemStack>): Boolean {
        return canFit(player.inventory.contents.filterNotNull(), stacks) || CommerceWarehouseBridge.isAvailable()
    }

    fun deliverOrStore(
        player: Player,
        stacks: List<ItemStack>,
        sourceModule: String,
        sourceId: String,
        reason: String,
        allowDropWhenUnavailable: Boolean = false
    ): DeliveryResult {
        val normalized = stacks
            .filter { it.type != Material.AIR && it.amount > 0 }
            .map { it.clone() }
        if (normalized.isEmpty()) {
            return DeliveryResult(true, DeliveryRoute.INVENTORY)
        }
        if (canFit(player.inventory.contents.filterNotNull(), normalized)) {
            normalized.forEach { player.inventory.addItem(it) }
            player.updateInventory()
            return DeliveryResult(true, DeliveryRoute.INVENTORY)
        }
        if (CommerceWarehouseBridge.isAvailable()) {
            val stored = normalized.allIndexed { index, item ->
                CommerceWarehouseBridge.store(
                    CommerceWarehouseStoreRequest(
                        ownerId = player.uniqueId,
                        ownerName = player.name,
                        sourceModule = sourceModule,
                        sourceId = "$sourceId#$index",
                        reason = reason,
                        item = item.clone(),
                        metadata = mapOf("delivery-target" to "mailbox", "delivery-route" to "inventory-full-mailbox")
                    )
                )
            }
            if (stored) {
                Texts.sendKey(player, "@messages.delivery-mailbox")
                return DeliveryResult(true, DeliveryRoute.MAILBOX)
            }
        }
        if (allowDropWhenUnavailable) {
            normalized.forEach { player.world.dropItemNaturally(player.location, it) }
            return DeliveryResult(true, DeliveryRoute.DROP)
        }
        return DeliveryResult(false, DeliveryRoute.FAILED)
    }

    private fun canFit(currentContents: List<ItemStack>, incoming: List<ItemStack>): Boolean {
        val virtual = ArrayList<ItemStack?>()
        currentContents.forEach { virtual += it.clone() }
        while (virtual.size < 36) {
            virtual += null
        }
        incoming.forEach { incomingStack ->
            var remaining = incomingStack.amount
            virtual.forEachIndexed { index, content ->
                if (remaining <= 0) {
                    return@forEachIndexed
                }
                if (content != null && content.isSimilar(incomingStack) && content.amount < content.maxStackSize) {
                    val free = content.maxStackSize - content.amount
                    val take = remaining.coerceAtMost(free)
                    content.amount += take
                    remaining -= take
                    virtual[index] = content
                }
            }
            while (remaining > 0) {
                val emptyIndex = virtual.indexOfFirst { it == null || it.type == Material.AIR }
                if (emptyIndex == -1) {
                    return false
                }
                val placed = incomingStack.clone()
                placed.amount = remaining.coerceAtMost(placed.maxStackSize)
                virtual[emptyIndex] = placed
                remaining -= placed.amount
            }
        }
        return true
    }

    private inline fun <T> Iterable<T>.allIndexed(predicate: (Int, T) -> Boolean): Boolean {
        var index = 0
        for (element in this) {
            if (!predicate(index++, element)) {
                return false
            }
        }
        return true
    }
}

