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
    val refreshAreas: Map<Char, SystemShopRefreshArea> = emptyMap(),
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

data class SystemShopDiscountRule(
    val id: String?,
    val enabled: Boolean,
    val priority: Int,
    val condition: List<String>,
    val whitelist: List<String>,
    val blacklist: List<String>,
    val percent: Double,
    val amountOff: Double,
    val surcharge: Double
) {

    fun isEffective(): Boolean {
        return enabled && (percent > 0.0 || amountOff > 0.0 || surcharge > 0.0)
    }
}

data class SystemShopPriceConfig(
    val base: Double,
    val discounts: List<SystemShopDiscountRule> = emptyList()
)

data class SystemShopAppliedDiscount(
    val id: String,
    val percent: Double,
    val amountOff: Double,
    val surcharge: Double
)

data class SystemShopResolvedPrice(
    val base: Double,
    val final: Double,
    val appliedDiscounts: List<SystemShopAppliedDiscount>,
    val percentTotal: Double,
    val amountOffTotal: Double,
    val surchargeTotal: Double
)

data class SystemShopProductTemplate(
    val id: String,
    val material: String,
    val amount: Int,
    val name: String,
    val lore: List<String>,
    val priceConfig: SystemShopPriceConfig,
    val configuredCurrency: String?,
    val buyMax: Int,
    val item: ItemStack? = null,
    val source: SystemShopProductSource
) {

    val basePrice: Double
        get() = priceConfig.base

    fun resolve(categoryCurrencyKey: String): SystemShopProduct {
        return SystemShopProduct(
            id = id,
            goodsId = id,
            material = material,
            amount = amount,
            name = name,
            lore = lore,
            priceConfig = priceConfig,
            price = priceConfig.base,
            currency = configuredCurrency?.trim()?.takeIf(String::isNotBlank) ?: categoryCurrencyKey,
            buyMax = buyMax,
            item = item,
            source = source
        )
    }
}

data class SystemShopProduct(
    val id: String,
    val goodsId: String,
    val material: String,
    val amount: Int,
    val name: String,
    val lore: List<String>,
    val priceConfig: SystemShopPriceConfig,
    val price: Double,
    val currency: String,
    val buyMax: Int,
    val item: ItemStack? = null,
    val source: SystemShopProductSource,
    val appliedDiscounts: List<SystemShopAppliedDiscount> = emptyList(),
    val refreshArea: Char? = null,
    val refreshGroupId: String? = null,
    val sameForPlayersInGroup: Boolean = true
) {

    val basePrice: Double
        get() = priceConfig.base

    val discountCount: Int
        get() = appliedDiscounts.size

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
    var product: SystemShopProduct,
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
