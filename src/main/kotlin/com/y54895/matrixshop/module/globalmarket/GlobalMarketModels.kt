package com.y54895.matrixshop.module.globalmarket

import com.y54895.matrixshop.core.menu.ConfiguredShopMenu
import com.y54895.matrixshop.core.menu.MenuDefinition
import com.y54895.matrixshop.core.economy.ConditionalTaxConfig
import org.bukkit.inventory.ItemStack
import java.util.UUID

data class GlobalMarketListing(
    val id: String,
    val shopId: String = "default",
    val ownerId: UUID,
    val ownerName: String,
    var price: Double,
    val currency: String,
    val item: ItemStack,
    val createdAt: Long,
    val expireAt: Long
)

data class GlobalMarketMenus(
    val marketViews: Map<String, ConfiguredShopMenu>,
    val manage: MenuDefinition,
    val upload: MenuDefinition
)

data class GlobalMarketSettings(
    val expireHours: Int,
    val tax: ConditionalTaxConfig,
    val currencyKey: String
)
