package com.y54895.matrixshop.module.cart

import com.y54895.matrixshop.core.config.ConfigFiles
import com.y54895.matrixshop.core.menu.MenuDefinition
import com.y54895.matrixshop.core.menu.MenuLoader
import com.y54895.matrixshop.core.menu.MenuRenderer
import com.y54895.matrixshop.core.menu.MatrixMenuHolder
import com.y54895.matrixshop.core.module.MatrixModule
import com.y54895.matrixshop.core.record.RecordService
import com.y54895.matrixshop.core.text.Texts
import com.y54895.matrixshop.module.globalmarket.GlobalMarketModule
import com.y54895.matrixshop.module.playershop.PlayerShopModule
import com.y54895.matrixshop.module.systemshop.ModuleOperationResult
import com.y54895.matrixshop.module.systemshop.SystemShopModule
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.io.File
import java.util.UUID

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
        MenuRenderer.open(
            player = player,
            definition = menu,
            placeholders = mapOf(
                "page" to currentPage.toString(),
                "max-page" to maxPage.toString(),
                "size" to store.entries.size.toString(),
                "estimate-total" to trimDouble(store.entries.filter { !it.watchOnly }.sumOf { it.snapshotPrice * it.amount }),
                "auction-watch-size" to store.entries.count { it.watchOnly }.toString()
            ),
            goodsRenderer = { holder, slots ->
                renderEntries(player, holder, entries, slots)
                wireControls(player, holder, currentPage, maxPage)
            }
        )
    }

    fun addCurrentSystemSelection(player: Player) {
        val selection = SystemShopModule.currentSelection(player)
        if (selection == null) {
            Texts.send(player, "&cThere is no current SystemShop selection to add.")
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
        Texts.send(player, "&aAdded to cart: &f${selection.product.name} &7x&f${selection.amount}")
    }

    fun addPlayerShopListing(player: Player, ownerId: String, ownerName: String, listingId: String) {
        addPlayerShopListing(player, "default", ownerId, ownerName, listingId)
    }

    fun addPlayerShopListing(player: Player, shopId: String, ownerId: String, ownerName: String, listingId: String) {
        val selection = PlayerShopModule.selection(UUID.fromString(ownerId), ownerName, shopId, listingId)
        if (selection == null) {
            Texts.send(player, "&cThat player shop listing is no longer available.")
            return
        }
        val store = CartRepository.load(player.uniqueId)
        store.entries += CartEntry(
            id = "cart-${System.currentTimeMillis().toString(36)}",
            sourceModule = "player_shop",
            sourceId = "$shopId:$ownerId:$listingId",
            name = selection.listing.item.itemMeta?.displayName ?: selection.listing.item.type.name,
            currency = selection.listing.currency,
            snapshotPrice = selection.listing.price,
            amount = selection.listing.item.amount,
            ownerName = selection.ownerName,
            item = selection.listing.item.clone(),
            editableAmount = false,
            metadata = linkedMapOf(
                "shop-id" to shopId,
                "owner-id" to ownerId,
                "owner-name" to ownerName,
                "listing-id" to listingId
            )
        )
        CartRepository.save(store)
        Texts.send(player, "&aAdded to cart: &f${selection.listing.item.itemMeta?.displayName ?: selection.listing.item.type.name}")
    }

    fun addGlobalMarketListing(player: Player, listingId: String) {
        addGlobalMarketListing(player, "default", listingId)
    }

    fun addGlobalMarketListing(player: Player, shopId: String, listingId: String) {
        val listing = GlobalMarketModule.selection(shopId, listingId)
        if (listing == null) {
            Texts.send(player, "&cThat GlobalMarket listing is no longer available.")
            return
        }
        val store = CartRepository.load(player.uniqueId)
        store.entries += CartEntry(
            id = "cart-${System.currentTimeMillis().toString(36)}",
            sourceModule = "global_market",
            sourceId = "${listing.shopId}:${listing.id}",
            name = listing.item.itemMeta?.displayName ?: listing.item.type.name,
            currency = listing.currency,
            snapshotPrice = listing.price,
            amount = listing.item.amount,
            ownerName = listing.ownerName,
            item = listing.item.clone(),
            editableAmount = false,
            metadata = linkedMapOf(
                "shop-id" to listing.shopId,
                "listing-id" to listing.id,
                "owner-id" to listing.ownerId.toString(),
                "owner-name" to listing.ownerName
            )
        )
        CartRepository.save(store)
        Texts.send(player, "&aAdded to cart: &f${listing.item.itemMeta?.displayName ?: listing.item.type.name}")
    }

    fun remove(player: Player, index: Int) {
        val store = CartRepository.load(player.uniqueId)
        val entry = orderedEntries(store).getOrNull(index - 1)
        if (entry == null) {
            Texts.send(player, "&cCart entry not found.")
            return
        }
        store.entries.removeIf { it.id == entry.id }
        CartRepository.save(store)
        Texts.send(player, "&aRemoved cart entry: &f${entry.name}")
    }

    fun clear(player: Player) {
        val store = CartRepository.load(player.uniqueId)
        val before = store.entries.size
        store.entries.removeIf { !it.protectedOnClear }
        CartRepository.save(store)
        Texts.send(player, "&aCleared cart entries: &f${before - store.entries.size}")
    }

    fun removeInvalid(player: Player) {
        val store = CartRepository.load(player.uniqueId)
        val before = store.entries.size
        store.entries.removeIf { !validate(it).valid }
        CartRepository.save(store)
        Texts.send(player, "&aRemoved invalid cart entries: &f${before - store.entries.size}")
    }

    fun changeAmount(player: Player, index: Int, amount: Int) {
        val store = CartRepository.load(player.uniqueId)
        val entry = orderedEntries(store).getOrNull(index - 1)
        if (entry == null) {
            Texts.send(player, "&cCart entry not found.")
            return
        }
        if (!entry.editableAmount) {
            Texts.send(player, "&cThat cart entry does not allow amount changes.")
            return
        }
        if (amount <= 0) {
            Texts.send(player, "&cAmount must be greater than 0.")
            return
        }
        val validation = validate(entry.copy(amount = amount))
        if (!validation.valid) {
            Texts.send(player, validation.reason.ifBlank { "&cThat amount is not valid." })
            return
        }
        entry.amount = amount
        CartRepository.save(store)
        Texts.send(player, "&aUpdated cart amount: &f${entry.name} &7x&f$amount")
    }

    fun checkout(player: Player, validOnly: Boolean) {
        val store = CartRepository.load(player.uniqueId)
        var success = 0
        var invalid = 0
        orderedEntries(store).forEach { entry ->
            val validation = validate(entry)
            if (!validation.valid) {
                invalid++
                return@forEach
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
                    UUID.fromString(entry.metadata["owner-id"].orEmpty()),
                    entry.metadata["owner-name"].orEmpty(),
                    entry.metadata["shop-id"],
                    entry.metadata["listing-id"].orEmpty(),
                    false
                )
                "global_market" -> GlobalMarketModule.purchaseDirect(
                    player,
                    entry.metadata["shop-id"],
                    entry.metadata["listing-id"].orEmpty(),
                    false
                )
                else -> ModuleOperationResult(false, "&cUnsupported cart source.")
            }
            if (result.success) {
                store.entries.removeIf { it.id == entry.id }
                success++
            } else if (!validOnly && result.message.isNotBlank()) {
                Texts.send(player, result.message)
            }
        }
        CartRepository.save(store)
        Texts.send(player, "&aCart checkout finished. Success: &f$success &aInvalid/Skipped: &f$invalid")
        RecordService.append(
            module = "cart",
            type = "checkout",
            actor = player.name,
            detail = "success=$success;invalid=$invalid;remaining=${store.entries.size}"
        )
    }

    fun validate(entry: CartEntry): CartValidation {
        return when (entry.sourceModule) {
            "system_shop" -> {
                val result = SystemShopModule.validateProduct(
                    entry.metadata["category-id"].orEmpty(),
                    entry.metadata["product-id"].orEmpty(),
                    entry.amount
                )
                if (result.success) CartValidation(true, "valid", "") else CartValidation(false, "invalid", Texts.color(result.message))
            }
            "player_shop" -> {
                val result = PlayerShopModule.validateListing(
                    UUID.fromString(entry.metadata["owner-id"].orEmpty()),
                    entry.metadata["owner-name"].orEmpty(),
                    entry.metadata["shop-id"],
                    entry.metadata["listing-id"].orEmpty()
                )
                if (result.success) CartValidation(true, "valid", "") else CartValidation(false, "invalid", Texts.color(result.message))
            }
            "global_market" -> {
                val result = GlobalMarketModule.validateListing(entry.metadata["shop-id"], entry.metadata["listing-id"].orEmpty())
                if (result.success) CartValidation(true, "valid", "") else CartValidation(false, "invalid", Texts.color(result.message))
            }
            else -> CartValidation(false, "invalid", Texts.color("&cUnknown cart source."))
        }
    }

    private fun renderEntries(player: Player, holder: MatrixMenuHolder, entries: List<CartEntry>, slots: List<Int>) {
        val ordered = orderedEntries(CartRepository.load(player.uniqueId))
        entries.forEachIndexed { index, entry ->
            val validation = validate(entry)
            val slotNumber = (ordered.indexOfFirst { it.id == entry.id } + 1).coerceAtLeast(1)
            val item = entry.item.clone()
            item.itemMeta = item.itemMeta?.apply {
                lore = (lore ?: emptyList()) + listOf(
                    Texts.color("&7Source: &f${entry.sourceModule}"),
                    Texts.color("&7Amount: &f${entry.amount}"),
                    Texts.color("&7Unit Price: &e${trimDouble(entry.snapshotPrice)} ${entry.currency}"),
                    Texts.color("&7State: &f${validation.state}"),
                    Texts.color("&7Slot: &f$slotNumber"),
                    Texts.color(if (entry.editableAmount) "&eRight click to remove. Use command to change amount." else "&eRight click to remove.")
                ) + if (validation.reason.isNotBlank()) listOf(validation.reason) else emptyList()
            }
            val slot = slots[index]
            holder.backingInventory.setItem(slot, item)
            holder.handlers[slot] = { event ->
                if (event.click.isRightClick) {
                    remove(player, slotNumber)
                    open(player)
                } else {
                    Texts.send(player, "&7Use /matrixshop cart amount $slotNumber <number> to change the amount.")
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
        buttonSlot(menu, 'A')?.let { holder.handlers[it] = { Texts.send(player, "&7Use /matrixshop cart amount <slot> <number> to change the amount.") } }
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
