package com.y54895.matrixshop.module.systemshop

import com.y54895.matrixshop.core.config.ConfigFiles
import com.y54895.matrixshop.core.economy.EconomyModule
import com.y54895.matrixshop.core.menu.MenuDefinition
import com.y54895.matrixshop.core.menu.MenuLoader
import com.y54895.matrixshop.core.menu.MenuRenderer
import com.y54895.matrixshop.core.menu.MatrixMenuHolder
import com.y54895.matrixshop.core.module.MatrixModule
import com.y54895.matrixshop.core.record.RecordService
import com.y54895.matrixshop.core.text.Texts
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import taboolib.common.platform.function.warning
import java.io.File
import java.util.UUID

object SystemShopModule : MatrixModule {

    override val id: String = "system-shop"
    override val displayName: String = "SystemShop"

    private lateinit var rootMenu: MenuDefinition
    private lateinit var confirmMenu: MenuDefinition
    private val categories = LinkedHashMap<String, SystemShopCategory>()
    private val confirmSessions = HashMap<UUID, ConfirmSession>()
    private var currencyKey: String = "vault"

    override fun isEnabled(): Boolean {
        return ConfigFiles.isModuleEnabled(id, true)
    }

    override fun reload() {
        if (!isEnabled()) {
            categories.clear()
            return
        }
        val dataFolder = ConfigFiles.dataFolder()
        val settings = YamlConfiguration.loadConfiguration(File(dataFolder, "SystemShop/settings.yml"))
        currencyKey = EconomyModule.configuredKey(settings)
        rootMenu = MenuLoader.load(File(dataFolder, "SystemShop/ui/shop.yml"))
        confirmMenu = MenuLoader.load(File(dataFolder, "SystemShop/ui/confirm.yml"))
        categories.clear()
        val shopFolder = File(dataFolder, "SystemShop/shops")
        shopFolder.mkdirs()
        shopFolder.listFiles { file -> file.isFile && file.extension.equals("yml", true) }
            ?.sortedBy { it.nameWithoutExtension }
            ?.forEach { file ->
                runCatching { loadCategory(file) }.onFailure {
                    warning(Texts.tr("@system-shop.logs.category-load-failed", mapOf("file" to file.name, "reason" to (it.message ?: it.javaClass.simpleName))))
                }
            }
    }

    fun openMain(player: Player) {
        val placeholders = playerPlaceholders(player) + mapOf("page" to "1", "max-page" to "1")
        MenuRenderer.open(player, rootMenu, placeholders)
    }

    fun openCategory(player: Player, categoryId: String, page: Int = 1) {
        val category = categories[categoryId]
        if (category == null) {
            Texts.sendKey(player, "@system-shop.errors.category-not-found", mapOf("category" to categoryId))
            return
        }
        val placeholders = playerPlaceholders(player) + mapOf("page" to page.toString(), "max-page" to "1")
        MenuRenderer.open(
            player = player,
            definition = category.menu,
            placeholders = placeholders,
            backAction = { openMain(player) },
            goodsRenderer = { holder, goodsSlots ->
                renderProducts(player, holder, goodsSlots, category)
            }
        )
    }

    fun adjustConfirmAmount(player: Player, delta: Int) {
        val session = confirmSessions[player.uniqueId]
        if (session == null) {
            Texts.sendKey(player, "@system-shop.errors.no-confirm-purchase")
            return
        }
        val product = findProduct(session.categoryId, session.productId) ?: run {
            confirmSessions.remove(player.uniqueId)
            Texts.sendKey(player, "@system-shop.errors.product-invalid")
            return
        }
        session.amount = (session.amount + delta).coerceIn(1, product.buyMax.coerceAtLeast(1))
        openConfirm(player, session.categoryId, session.productId)
    }

    fun confirmPurchase(player: Player) {
        val session = confirmSessions[player.uniqueId]
        if (session == null) {
            Texts.sendKey(player, "@system-shop.errors.no-confirm-purchase")
            return
        }
        val result = purchaseDirect(player, session.categoryId, session.productId, session.amount, true)
        if (result.success) {
            confirmSessions.remove(player.uniqueId)
        } else if (result.message.isNotBlank()) {
            Texts.send(player, result.message)
        }
    }

    fun currentSelection(player: Player): SystemShopSelection? {
        val session = confirmSessions[player.uniqueId] ?: return null
        val product = findProduct(session.categoryId, session.productId) ?: return null
        return SystemShopSelection(
            categoryId = session.categoryId,
            productId = session.productId,
            product = product,
            amount = session.amount.coerceIn(1, product.buyMax.coerceAtLeast(1))
        )
    }

    fun snapshot(categoryId: String, productId: String): SystemShopProduct? {
        return findProduct(categoryId, productId)
    }

    fun validateProduct(categoryId: String, productId: String, amount: Int): ModuleOperationResult {
        val category = categories[categoryId]
        val product = findProduct(categoryId, productId)
        if (category == null || product == null) {
            return ModuleOperationResult(false, Texts.tr("@system-shop.errors.product-invalid"))
        }
        val safeAmount = amount.coerceAtLeast(1)
        if (safeAmount > product.buyMax.coerceAtLeast(1)) {
            return ModuleOperationResult(false, Texts.tr("@system-shop.errors.amount-over-limit"))
        }
        return ModuleOperationResult(true, "")
    }

    fun currentPrice(categoryId: String, productId: String): Double? {
        return findProduct(categoryId, productId)?.price
    }

    fun purchaseDirect(
        player: Player,
        categoryId: String,
        productId: String,
        amount: Int,
        closeInventoryOnSuccess: Boolean = false
    ): ModuleOperationResult {
        val category = categories[categoryId]
        val product = findProduct(categoryId, productId)
        if (category == null || product == null) {
            confirmSessions.remove(player.uniqueId)
            return ModuleOperationResult(false, Texts.tr("@system-shop.errors.product-invalid"))
        }
        val safeAmount = amount.coerceIn(1, product.buyMax.coerceAtLeast(1))
        val total = product.price * safeAmount
        if (total > 0 && !EconomyModule.isAvailable(product.currency)) {
            return ModuleOperationResult(false, Texts.tr("@economy.errors.currency-unavailable", mapOf("currency" to EconomyModule.displayName(product.currency))))
        }
        if (!EconomyModule.has(player, product.currency, total)) {
            return ModuleOperationResult(false, EconomyModule.insufficientMessage(player, product.currency, total))
        }
        val purchaseStacks = product.toPurchasedItem(safeAmount)
        if (!canFit(player.inventory.contents.filterNotNull(), purchaseStacks)) {
            return ModuleOperationResult(false, Texts.tr("@system-shop.errors.inventory-no-space"))
        }
        if (!EconomyModule.withdraw(player, product.currency, total)) {
            return ModuleOperationResult(false, Texts.tr("@economy.errors.withdraw-failed", mapOf("currency" to EconomyModule.displayName(product.currency))))
        }
        purchaseStacks.forEach { player.inventory.addItem(it) }
        if (closeInventoryOnSuccess) {
            player.closeInventory()
        }
        Texts.sendKey(
            player,
            "@system-shop.success.purchase",
            mapOf(
                "name" to product.name,
                "amount" to safeAmount.toString(),
                "total" to EconomyModule.formatAmount(product.currency, total)
            )
        )
        RecordService.append(
            module = "system_shop",
            type = "purchase",
            actor = player.name,
            moneyChange = -total,
            detail = "category=${category.id};product=${product.id};amount=$safeAmount;total=${trimDouble(total)}"
        )
        return ModuleOperationResult(true, "")
    }

    fun openConfirm(player: Player, categoryId: String, productId: String) {
        val product = findProduct(categoryId, productId) ?: return
        val session = confirmSessions.compute(player.uniqueId) { _, current ->
            if (current == null || current.categoryId != categoryId || current.productId != productId) {
                ConfirmSession(categoryId, productId, 1)
            } else {
                current
            }
        }!!
        val total = product.price * session.amount
        val placeholders = playerPlaceholders(player) + mapOf(
            "name" to product.name,
            "amount" to session.amount.toString(),
            "price" to EconomyModule.formatAmount(product.currency, product.price),
            "total-price" to EconomyModule.formatAmount(product.currency, total),
            "currency" to EconomyModule.displayName(product.currency)
        )
        MenuRenderer.open(
            player = player,
            definition = confirmMenu,
            placeholders = placeholders,
            backAction = { openCategory(player, categoryId) },
            goodsRenderer = { holder, slots ->
                renderConfirmSlots(holder, slots, product, placeholders)
            }
        )
    }

    private fun renderProducts(player: Player, holder: MatrixMenuHolder, goodsSlots: List<Int>, category: SystemShopCategory) {
        category.products.take(goodsSlots.size).forEachIndexed { index, product ->
            val slot = goodsSlots[index]
            val placeholders = mapOf(
                "name" to product.name,
                "price" to EconomyModule.formatAmount(product.currency, product.price),
                "currency" to EconomyModule.displayName(product.currency),
                "limit" to product.buyMax.toString(),
                "has_currency" to EconomyModule.formatAmount(product.currency, EconomyModule.balance(player, product.currency)),
                "lore" to ""
            )
            val displayLore = if (category.menu.template.lore.isNotEmpty()) {
                Texts.apply(category.menu.template.lore + product.lore, placeholders)
            } else {
                Texts.apply(product.lore, placeholders)
            }
            val item = product.toItemStack(Texts.apply(category.menu.template.name, placeholders), displayLore)
            holder.inventory.setItem(slot, item)
            holder.handlers[slot] = {
                openConfirm(player, category.id, product.id)
            }
        }
    }

    private fun renderConfirmSlots(holder: MatrixMenuHolder, slots: List<Int>, product: SystemShopProduct, placeholders: Map<String, String>) {
        slots.forEach { slot ->
            val preview = product.toItemStack(Texts.apply("&f${product.name}", placeholders), Texts.apply(product.lore, placeholders))
            holder.inventory.setItem(slot, preview)
        }
    }

    private fun loadCategory(file: File) {
        val yaml = YamlConfiguration.loadConfiguration(file)
        val id = yaml.getString("id", file.nameWithoutExtension).orEmpty()
        val menu = MenuLoader.load(file)
        val goodsSection = yaml.getConfigurationSection("goods")
        val products = goodsSection?.getKeys(false)?.map { key ->
            val section = goodsSection.getConfigurationSection(key)!!
            parseProduct(key, section)
        }.orEmpty()
        categories[id] = SystemShopCategory(id = id, menu = menu, products = products)
    }

    private fun parseProduct(id: String, section: ConfigurationSection): SystemShopProduct {
        return SystemShopProduct(
            id = id,
            material = section.getString("material", "STONE").orEmpty(),
            amount = section.getInt("amount", 1),
            name = section.getString("name", id).orEmpty(),
            lore = section.getStringList("lore"),
            price = section.getDouble("price", 0.0),
            currency = currencyKey,
            buyMax = section.getInt("buy-max", 64)
        )
    }

    private fun findProduct(categoryId: String, productId: String): SystemShopProduct? {
        return categories[categoryId]?.products?.firstOrNull { it.id.equals(productId, true) }
    }

    private fun playerPlaceholders(player: Player): Map<String, String> {
        return mapOf("player" to player.name, "money" to EconomyModule.formatAmount(currencyKey, EconomyModule.balance(player, currencyKey)))
    }

    private fun trimDouble(value: Double): String {
        return if (value % 1.0 == 0.0) value.toInt().toString() else "%.2f".format(value)
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
                val emptyIndex = virtual.indexOfFirst { it == null || it.type == org.bukkit.Material.AIR }
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
}
