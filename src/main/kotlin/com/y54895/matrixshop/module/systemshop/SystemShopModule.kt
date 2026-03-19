package com.y54895.matrixshop.module.systemshop

import com.y54895.matrixshop.core.config.ConfigFiles
import com.y54895.matrixshop.core.economy.VaultEconomyBridge
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

    override fun isEnabled(): Boolean {
        return ConfigFiles.isModuleEnabled(id, true)
    }

    override fun reload() {
        if (!isEnabled()) {
            categories.clear()
            return
        }
        val dataFolder = ConfigFiles.dataFolder()
        rootMenu = MenuLoader.load(File(dataFolder, "SystemShop/ui/shop.yml"))
        confirmMenu = MenuLoader.load(File(dataFolder, "SystemShop/ui/confirm.yml"))
        categories.clear()
        val shopFolder = File(dataFolder, "SystemShop/shops")
        shopFolder.mkdirs()
        shopFolder.listFiles { file -> file.isFile && file.extension.equals("yml", true) }
            ?.sortedBy { it.nameWithoutExtension }
            ?.forEach { file ->
                runCatching { loadCategory(file) }.onFailure {
                    warning("Failed to load SystemShop category ${file.name}: ${it.message}")
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
            Texts.send(player, "&c未找到系统商店分类: &f$categoryId")
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
            Texts.send(player, "&c当前没有待确认的购买。")
            return
        }
        val product = findProduct(session.categoryId, session.productId) ?: run {
            confirmSessions.remove(player.uniqueId)
            Texts.send(player, "&c当前商品已经失效。")
            return
        }
        session.amount = (session.amount + delta).coerceIn(1, product.buyMax.coerceAtLeast(1))
        openConfirm(player, session.categoryId, session.productId)
    }

    fun confirmPurchase(player: Player) {
        val session = confirmSessions[player.uniqueId]
        if (session == null) {
            Texts.send(player, "&c当前没有待确认的购买。")
            return
        }
        val category = categories[session.categoryId]
        val product = findProduct(session.categoryId, session.productId)
        if (category == null || product == null) {
            confirmSessions.remove(player.uniqueId)
            Texts.send(player, "&c商品已经失效。")
            return
        }
        val amount = session.amount.coerceIn(1, product.buyMax.coerceAtLeast(1))
        val total = product.price * amount
        if (total > 0 && !VaultEconomyBridge.isAvailable()) {
            Texts.send(player, "&c当前未接入 Vault 经济，无法完成付费购买。")
            return
        }
        if (!VaultEconomyBridge.has(player, total)) {
            Texts.send(player, "&c余额不足，当前需要 &e${trimDouble(total)} &c金币。")
            return
        }
        val purchaseStacks = product.toPurchasedItem(amount)
        if (!canFit(player.inventory.contents.filterNotNull(), purchaseStacks)) {
            Texts.send(player, "&c背包空间不足，无法放入购买物品。")
            return
        }
        if (!VaultEconomyBridge.withdraw(player, total)) {
            Texts.send(player, "&c扣款失败，购买已取消。")
            return
        }
        purchaseStacks.forEach { player.inventory.addItem(it) }
        confirmSessions.remove(player.uniqueId)
        player.closeInventory()
        Texts.send(player, "&a购买成功: &f${product.name} &7x&f$amount &7- &e${trimDouble(total)}")
        RecordService.append(
            module = "system_shop",
            type = "purchase",
            player = player.name,
            detail = "category=${category.id};product=${product.id};amount=$amount;total=${trimDouble(total)}"
        )
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
            "price" to trimDouble(product.price),
            "total-price" to trimDouble(total),
            "currency" to product.currency
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
                "price" to trimDouble(product.price),
                "currency" to product.currency,
                "limit" to product.buyMax.toString(),
                "has_currency" to trimDouble(VaultEconomyBridge.balance(player)),
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
            currency = section.getString("currency", "vault").orEmpty(),
            buyMax = section.getInt("buy-max", 64)
        )
    }

    private fun findProduct(categoryId: String, productId: String): SystemShopProduct? {
        return categories[categoryId]?.products?.firstOrNull { it.id.equals(productId, true) }
    }

    private fun playerPlaceholders(player: Player): Map<String, String> {
        return mapOf("player" to player.name, "money" to trimDouble(VaultEconomyBridge.balance(player)))
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
