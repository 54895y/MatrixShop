package com.y54895.matrixshop.module.chestshop

import com.y54895.matrixshop.core.menu.MenuDefinition
import org.bukkit.inventory.ItemStack
import java.util.UUID

data class ChestShopLocation(
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int
) {

    fun key(): String {
        return "$world:$x:$y:$z"
    }
}

data class ChestShopHistoryEntry(
    val type: String,
    val actor: String,
    val amount: Int,
    val money: Double,
    val createdAt: Long,
    val note: String = ""
)

data class ChestShopShop(
    val id: String,
    val ownerId: UUID,
    var ownerName: String,
    val primaryChest: ChestShopLocation,
    var secondaryChest: ChestShopLocation? = null,
    val signLocations: MutableList<ChestShopLocation> = mutableListOf(),
    var mode: ChestShopMode,
    var buyPrice: Double,
    var sellPrice: Double,
    var tradeAmount: Int,
    val item: ItemStack,
    val createdAt: Long,
    val history: MutableList<ChestShopHistoryEntry> = mutableListOf()
)

data class ChestShopMenus(
    val shopViews: Map<String, MenuDefinition>,
    val create: MenuDefinition,
    val edit: MenuDefinition,
    val stock: MenuDefinition,
    val history: MenuDefinition
)

data class ChestShopSettings(
    val openGuiOnChestRightClick: Boolean,
    val openGuiOnSignRightClick: Boolean,
    val doubleChestMode: String
)

enum class ChestShopMode {
    BUY,
    SELL,
    DUAL
}
