package com.y54895.matrixshop.module.transaction

import com.y54895.matrixshop.core.menu.ConfiguredShopMenu
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import java.util.UUID

data class TransactionRequest(
    val shopId: String,
    val moneyCurrencyKey: String,
    val requesterId: UUID,
    val requesterName: String,
    val targetId: UUID,
    val targetName: String,
    val createdAt: Long,
    val expireAt: Long
) {

    fun isExpired(now: Long = System.currentTimeMillis()): Boolean {
        return now >= expireAt
    }
}

data class TransactionSession(
    val id: String,
    val shopId: String,
    val moneyCurrencyKey: String,
    val leftId: UUID,
    val leftName: String,
    val rightId: UUID,
    val rightName: String,
    val leftOffers: MutableList<ItemStack?> = MutableList(9) { null },
    val rightOffers: MutableList<ItemStack?> = MutableList(9) { null },
    var leftMoney: Double = 0.0,
    var rightMoney: Double = 0.0,
    var leftExp: Int = 0,
    var rightExp: Int = 0,
    var leftReady: Boolean = false,
    var rightReady: Boolean = false,
    var leftConfirmed: Boolean = false,
    var rightConfirmed: Boolean = false,
    var confirmPhase: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

data class TransactionSettings(
    val moneyCurrencyKey: String,
    val requestTimeoutSeconds: Int,
    val maxPending: Int,
    val allowMultiPending: Boolean,
    val maxDistance: Double,
    val sameWorldOnly: Boolean,
    val cancelOnDamage: Boolean,
    val cancelOnDeath: Boolean,
    val cancelOnQuit: Boolean,
    val cancelOnWorldChange: Boolean,
    val allowItems: Boolean,
    val allowMoney: Boolean,
    val allowExp: Boolean,
    val writeOnComplete: Boolean,
    val writeOnCancel: Boolean
)

data class TransactionMenus(
    val shopViews: Map<String, ConfiguredShopMenu>,
    val request: com.y54895.matrixshop.core.menu.MenuDefinition,
    val trade: com.y54895.matrixshop.core.menu.MenuDefinition,
    val confirm: com.y54895.matrixshop.core.menu.MenuDefinition
)

enum class TransactionSide {
    LEFT,
    RIGHT
}

class TransactionTradeHolder(
    val sessionId: String,
    val viewerId: UUID
) : InventoryHolder {

    lateinit var backingInventory: Inventory

    override fun getInventory(): Inventory {
        return backingInventory
    }
}
