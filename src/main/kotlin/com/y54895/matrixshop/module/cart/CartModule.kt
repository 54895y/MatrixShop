package com.y54895.matrixshop.module.cart

import com.y54895.matrixshop.core.config.ConfigFiles
import com.y54895.matrixshop.core.menu.MenuDefinition
import com.y54895.matrixshop.core.menu.MenuLoader
import com.y54895.matrixshop.core.menu.MenuRenderer
import com.y54895.matrixshop.core.menu.MatrixMenuHolder
import com.y54895.matrixshop.core.module.MatrixModule
import com.y54895.matrixshop.core.record.RecordService
import com.y54895.matrixshop.core.text.Texts
import com.y54895.matrixshop.module.playershop.PlayerShopModule
import com.y54895.matrixshop.module.systemshop.SystemShopModule
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.io.File

object CartModule : MatrixModule {

    override val id: String = "cart"
    override val displayName: String = "Cart"

    private lateinit var menu: MenuDefinition

    override fun isEnabled(): Boolean {
        return ConfigFiles.isModuleEnabled(id, true)
    }

    override fun reload() {
        if (!isEnabled()) {
            return
        }
        CartRepository.initialize()
        menu = MenuLoader.load(File(ConfigFiles.dataFolder(), "Cart/ui/cart.yml"))
    }

    fun open(player: Player, page: Int = 1) {
        val store = CartRepository.load(player.uniqueId)
        val goodsSlots = goodsSlots(menu)
        val maxPage = ((store.entries.size + goodsSlots.size - 1) / goodsSlots.size).coerceAtLeast(1)
        val currentPage = page.coerceIn(1, maxPage)
        val entries = orderedEntries(store).drop((currentPage - 1) * goodsSlots.size).take(goodsSlots.size)
        val placeholders = mapOf(
            "page" to currentPage.toString(),
            "max-page" to maxPage.toString(),
            "size" to store.entries.size.toString(),
            "estimate-total" to trimDouble(store.entries.filter { !it.watchOnly }.sumOf { it.snapshotPrice * it.amount }),
            "auction-watch-size" to store.entries.count { it.watchOnly }.toString()
        )
        MenuRenderer.open(
            player = player,
            definition = menu,
            placeholders = placeholders,
            goodsRenderer = { holder, slots ->
                renderEntries(player, holder, entries, slots)
                wireControls(player, holder, currentPage, maxPage)
            }
        )
    }

    fun addCurrentSystemSelection(player: Player) {
        val selection = SystemShopModule.currentSelection(player)
        if (selection == null) {
            Texts.send(player, "&c当前没有可加入购物车的系统商店确认单。")
            return
        }
        val store = CartRepository.load(player.uniqueId)
        store.entries += CartEntry(
            id = "cart-${System.currentTimeMillis().toString(36)}",
            sourceModule = "system_shop",
            sourceId = "${selection.categoryId}:${selection.productId}",
            name = selection.product.name,
            currency = selection.product.currency,
            snapshotPrice = selection.product.price,
            amount = selection.amount,
            item = selection.product.toItemStack(Texts.color(selection.product.name), Texts.apply(selection.product.lore, emptyMap())),
            editableAmount = true,
            metadata = linkedMapOf(
                "category-id" to selection.categoryId,
                "product-id" to selection.productId
            )
        )
        CartRepository.save(store)
        Texts.send(player, "&a已加入购物车: &f${selection.product.name} &7x&f${selection.amount}")
    }

    fun addPlayerShopListing(player: Player, ownerId: String, ownerName: String, listingId: String) {
        val selection = PlayerShopModule.selection(java.util.UUID.fromString(ownerId), ownerName, listingId)
        if (selection == null) {
            Texts.send(player, "&c该玩家商店商品已失效。")
            return
        }
        val store = CartRepository.load(player.uniqueId)
        store.entries += CartEntry(
            id = "cart-${System.currentTimeMillis().toString(36)}",
            sourceModule = "player_shop",
            sourceId = "${ownerId}:${listingId}",
            name = selection.listing.item.itemMeta?.displayName ?: selection.listing.item.type.name,
            currency = selection.listing.currency,
            snapshotPrice = selection.listing.price,
            amount = selection.listing.item.amount,
            ownerName = selection.ownerName,
            item = selection.listing.item.clone(),
            editableAmount = false,
            metadata = linkedMapOf(
                "owner-id" to ownerId,
                "owner-name" to ownerName,
                "listing-id" to listingId
            )
        )
        CartRepository.save(store)
        Texts.send(player, "&a已加入购物车: &f${selection.listing.item.itemMeta?.displayName ?: selection.listing.item.type.name}")
    }

    fun remove(player: Player, index: Int) {
        val store = CartRepository.load(player.uniqueId)
        val entry = orderedEntries(store).getOrNull(index - 1)
        if (entry == null) {
            Texts.send(player, "&c未找到该购物车条目。")
            return
        }
        store.entries.removeIf { it.id == entry.id }
        CartRepository.save(store)
        Texts.send(player, "&a已移除购物车条目: &f${entry.name}")
    }

    fun clear(player: Player) {
        val store = CartRepository.load(player.uniqueId)
        val before = store.entries.size
        store.entries.removeIf { !it.protectedOnClear }
        CartRepository.save(store)
        Texts.send(player, "&a已清理购物车，移除了 &f${before - store.entries.size} &a个条目。")
    }

    fun removeInvalid(player: Player) {
        val store = CartRepository.load(player.uniqueId)
        val before = store.entries.size
        store.entries.removeIf { !validate(it).valid }
        CartRepository.save(store)
        Texts.send(player, "&a已移除失效条目 &f${before - store.entries.size} &a个。")
    }

    fun changeAmount(player: Player, index: Int, amount: Int) {
        val store = CartRepository.load(player.uniqueId)
        val entry = orderedEntries(store).getOrNull(index - 1)
        if (entry == null) {
            Texts.send(player, "&c未找到该购物车条目。")
            return
        }
        if (!entry.editableAmount) {
            Texts.send(player, "&c该条目不支持修改数量。")
            return
        }
        if (amount <= 0) {
            Texts.send(player, "&c数量必须大于 0。")
            return
        }
        entry.amount = amount
        CartRepository.save(store)
        Texts.send(player, "&a已更新数量: &f${entry.name} &7x&f$amount")
    }

    fun checkout(player: Player, validOnly: Boolean) {
        val store = CartRepository.load(player.uniqueId)
        var success = 0
        var invalid = 0
        val iterator = orderedEntries(store).iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val validation = validate(entry)
            if (!validation.valid) {
                invalid++
                if (!validOnly) {
                    continue
                }
                continue
            }
            val result = when (entry.sourceModule) {
                "system_shop" -> SystemShopModule.purchaseDirect(
                    player,
                    entry.metadata["category-id"].orEmpty(),
                    entry.metadata["product-id"].orEmpty(),
                    entry.amount,
                    false
                )
                "player_shop" -> PlayerShopModule.purchaseDirect(
                    player,
                    java.util.UUID.fromString(entry.metadata["owner-id"].orEmpty()),
                    entry.metadata["owner-name"].orEmpty(),
                    entry.metadata["listing-id"].orEmpty(),
                    false
                )
                else -> com.y54895.matrixshop.module.systemshop.ModuleOperationResult(false, "&c不支持的购物车来源。")
            }
            if (result.success) {
                store.entries.removeIf { it.id == entry.id }
                success++
            } else if (result.message.isNotBlank()) {
                Texts.send(player, result.message)
            }
        }
        CartRepository.save(store)
        Texts.send(player, "&a购物车结算完成。成功: &f$success &a失效/未处理: &f$invalid")
        RecordService.append("cart", "checkout", player.name, "success=$success;invalid=$invalid")
    }

    fun validate(entry: CartEntry): CartValidation {
        return when (entry.sourceModule) {
            "system_shop" -> {
                val result = SystemShopModule.validateProduct(
                    entry.metadata["category-id"].orEmpty(),
                    entry.metadata["product-id"].orEmpty(),
                    entry.amount
                )
                if (result.success) CartValidation(true, "valid", "")
                else CartValidation(false, "invalid", Texts.color(result.message))
            }
            "player_shop" -> {
                val result = PlayerShopModule.validateListing(
                    java.util.UUID.fromString(entry.metadata["owner-id"].orEmpty()),
                    entry.metadata["owner-name"].orEmpty(),
                    entry.metadata["listing-id"].orEmpty()
                )
                if (result.success) CartValidation(true, "valid", "")
                else CartValidation(false, "invalid", Texts.color(result.message))
            }
            else -> CartValidation(false, "invalid", Texts.color("&c未知来源"))
        }
    }

    private fun renderEntries(player: Player, holder: MatrixMenuHolder, entries: List<CartEntry>, slots: List<Int>) {
        entries.forEachIndexed { index, entry ->
            val validation = validate(entry)
            val slotNumber = (orderedEntries(CartRepository.load(player.uniqueId)).indexOfFirst { it.id == entry.id } + 1).coerceAtLeast(1)
            val item = entry.item.clone()
            item.itemMeta = item.itemMeta?.apply {
                lore = (lore ?: emptyList()) + listOf(
                    Texts.color("&7来源: &f${entry.sourceModule}"),
                    Texts.color("&7数量: &f${entry.amount}"),
                    Texts.color("&7单价: &e${trimDouble(entry.snapshotPrice)} ${entry.currency}"),
                    Texts.color("&7状态: &f${validation.state}"),
                    Texts.color("&7编号: &f$slotNumber"),
                    Texts.color(if (entry.editableAmount) "&e右键移除，命令可改数量" else "&e右键移除")
                ) + if (validation.reason.isNotBlank()) listOf(validation.reason) else emptyList()
            }
            val slot = slots[index]
            holder.backingInventory.setItem(slot, item)
            holder.handlers[slot] = { event ->
                if (event.click.isRightClick) {
                    remove(player, slotNumber)
                    open(player)
                } else {
                    Texts.send(player, "&7可用命令: /matrixshop cart amount $slotNumber <number>")
                }
            }
        }
    }

    private fun wireControls(player: Player, holder: MatrixMenuHolder, currentPage: Int, maxPage: Int) {
        buttonSlot(menu, 'P')?.let { holder.handlers[it] = { open(player, (currentPage - 1).coerceAtLeast(1)) } }
        buttonSlot(menu, 'N')?.let { holder.handlers[it] = { open(player, (currentPage + 1).coerceAtMost(maxPage)) } }
        buttonSlot(menu, 'C')?.let { holder.handlers[it] = { checkout(player, false) } }
        buttonSlot(menu, 'X')?.let { holder.handlers[it] = { clear(player); open(player, currentPage) } }
        buttonSlot(menu, 'V')?.let { holder.handlers[it] = { removeInvalid(player); open(player, currentPage) } }
        buttonSlot(menu, 'A')?.let { holder.handlers[it] = { Texts.send(player, "&7使用 /matrixshop cart amount <slot> <number> 修改数量。") } }
    }

    private fun goodsSlots(definition: MenuDefinition): List<Int> {
        val slots = ArrayList<Int>()
        definition.layout.forEachIndexed { row, line ->
            line.forEachIndexed { column, char ->
                val icon = definition.icons[char] ?: return@forEachIndexed
                if (icon.mode.equals("goods", true)) {
                    slots += row * 9 + column
                }
            }
        }
        return slots
    }

    private fun buttonSlot(definition: MenuDefinition, symbol: Char): Int? {
        definition.layout.forEachIndexed { row, line ->
            line.forEachIndexed { column, char ->
                if (char == symbol) {
                    return row * 9 + column
                }
            }
        }
        return null
    }

    private fun orderedEntries(store: CartStore): List<CartEntry> {
        return store.entries.sortedBy { it.createdAt }
    }

    private fun trimDouble(value: Double): String {
        return if (value % 1.0 == 0.0) value.toInt().toString() else "%.2f".format(value)
    }
}
