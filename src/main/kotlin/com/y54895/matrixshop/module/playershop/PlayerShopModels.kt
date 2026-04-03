package com.y54895.matrixshop.module.playershop

import com.y54895.matrixshop.core.menu.ConfiguredShopMenu
import com.y54895.matrixshop.core.menu.MenuDefinition
import com.y54895.matrixshop.core.economy.ConditionalTaxConfig
import org.bukkit.inventory.ItemStack
import java.util.UUID

data class PlayerShopStore(
    val ownerId: UUID,
    val shopId: String = "default",
    var ownerName: String,
    var unlockedSlots: Int,
    val listings: MutableList<PlayerShopListing> = mutableListOf()
)

data class PlayerShopListing(
    val id: String,
    val slotIndex: Int,
    var price: Double,
    val currency: String,
    val item: ItemStack,
    val createdAt: Long
)

data class PlayerShopSettings(
    val unlockedBase: Int,
    val unlockedMax: Int,
    val currencyKey: String,
    val tax: ConditionalTaxConfig
)

data class PlayerShopMenus(
    val browseViews: Map<String, ConfiguredShopMenu>,
    val edit: MenuDefinition
)

data class PlayerShopSelection(
    val ownerId: UUID,
    val ownerName: String,
    val listing: PlayerShopListing
)
