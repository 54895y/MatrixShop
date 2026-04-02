package com.y54895.matrixshop.module.systemshop

import org.bukkit.Material
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import java.io.File

data class SystemShopCategory(
    val id: String,
    val menu: com.y54895.matrixshop.core.menu.MenuDefinition,
    val currencyKey: String,
    val products: List<SystemShopProduct>,
    val shopFile: File
)

enum class SystemShopProductSourceType {
    INLINE,
    REFERENCE
}

data class SystemShopProductSource(
    val type: SystemShopProductSourceType,
    val configFile: File,
    val configPath: String? = null
)

data class SystemShopGoodsGroup(
    val id: String,
    val entries: List<String>,
    val sourceFile: File
)

data class SystemShopProductTemplate(
    val id: String,
    val material: String,
    val amount: Int,
    val name: String,
    val lore: List<String>,
    val price: Double,
    val configuredCurrency: String?,
    val buyMax: Int,
    val item: ItemStack? = null,
    val source: SystemShopProductSource
) {

    fun resolve(categoryCurrencyKey: String): SystemShopProduct {
        return SystemShopProduct(
            id = id,
            material = material,
            amount = amount,
            name = name,
            lore = lore,
            price = price,
            currency = configuredCurrency?.trim()?.takeIf(String::isNotBlank) ?: categoryCurrencyKey,
            buyMax = buyMax,
            item = item,
            source = source
        )
    }
}

data class SystemShopProduct(
    val id: String,
    val material: String,
    val amount: Int,
    val name: String,
    val lore: List<String>,
    val price: Double,
    val currency: String,
    val buyMax: Int,
    val item: ItemStack? = null,
    val source: SystemShopProductSource
) {

    fun toItemStack(displayName: String, displayLore: List<String>): ItemStack {
        val stack = (item?.clone() ?: ItemStack(Material.matchMaterial(material) ?: Material.STONE, amount.coerceAtLeast(1))).apply {
            amount = this@SystemShopProduct.amount.coerceAtLeast(1)
        }
        stack.itemMeta = stack.itemMeta?.apply {
            setDisplayName(displayName)
            lore = displayLore
            addItemFlags(*ItemFlag.values())
        }
        return stack
    }

    fun toPurchasedItem(purchaseTimes: Int): List<ItemStack> {
        val stacks = ArrayList<ItemStack>()
        var remaining = amount * purchaseTimes
        val template = item?.clone() ?: ItemStack(Material.matchMaterial(material) ?: Material.STONE, amount.coerceAtLeast(1))
        val maxStackSize = template.maxStackSize.coerceAtLeast(1)
        while (remaining > 0) {
            val take = remaining.coerceAtMost(maxStackSize)
            stacks += template.clone().apply { amount = take }
            remaining -= take
        }
        return stacks
    }
}

data class ConfirmSession(
    val categoryId: String,
    val productId: String,
    var amount: Int
)

data class SystemShopSelection(
    val categoryId: String,
    val productId: String,
    val product: SystemShopProduct,
    val amount: Int
)

data class ModuleOperationResult(
    val success: Boolean,
    val message: String
)
