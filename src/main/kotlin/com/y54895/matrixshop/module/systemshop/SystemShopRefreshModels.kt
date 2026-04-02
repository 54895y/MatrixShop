package com.y54895.matrixshop.module.systemshop

import org.bukkit.inventory.ItemStack
import java.io.File

data class SystemShopGoodsPool(
    val id: String,
    val entries: List<SystemShopGoodsPoolEntry>,
    val sourceFile: File
)

data class SystemShopGoodsPoolEntry(
    val id: String,
    val goodsId: String,
    val weight: Int,
    val amount: Int? = null,
    val price: Double? = null,
    val configuredCurrency: String? = null,
    val buyMax: Int? = null,
    val name: String? = null,
    val lore: List<String>? = null,
    val item: ItemStack? = null
)

data class SystemShopRefreshArea(
    val iconKey: Char,
    val enabled: Boolean,
    val cron: String,
    val timezone: String,
    val sameForPlayersInGroup: Boolean,
    val groups: LinkedHashMap<String, SystemShopRefreshGroup>
)

data class SystemShopRefreshGroup(
    val id: String,
    val enabled: Boolean,
    val matchScript: List<String>,
    val randomRefresh: Boolean,
    val pick: Int,
    val goodsRefs: List<String>,
    val poolRef: String?,
    val inlinePool: SystemShopGoodsPool?,
    val fallbackRefs: List<String>
)

data class SystemShopRefreshWindowState(
    var version: Long,
    var lastRefreshAt: Long,
    var nextRefreshAt: Long
)

data class SystemShopRefreshAreaState(
    val groups: LinkedHashMap<String, SystemShopRefreshGroupState> = linkedMapOf()
)

data class SystemShopRefreshGroupState(
    var sharedSnapshot: SystemShopResolvedSnapshot? = null,
    val playerSnapshots: LinkedHashMap<String, SystemShopResolvedSnapshot> = linkedMapOf()
)

data class SystemShopResolvedSnapshot(
    val version: Long,
    val products: List<SystemShopProduct>
)

data class SystemShopRenderedView(
    val categoryId: String,
    val slotProducts: Map<Int, SystemShopProduct>
)
