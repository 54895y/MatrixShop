package com.y54895.matrixshop.module.cart

import com.y54895.matrixshop.core.command.CommandUsageContext
import com.y54895.matrixshop.core.config.ModuleBindings
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
import org.bukkit.ChatColor
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

object CartModule : MatrixModule {

    override val id: String = "cart"
    override val displayName: String = "Cart"

    private lateinit var menu: MenuDefinition
    private lateinit var checkoutMenu: MenuDefinition
    private lateinit var conflictMenu: MenuDefinition
    private var viewConfig: CartViewConfig = CartViewConfig()
    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    override fun isEnabled(): Boolean {
        return ConfigFiles.isModuleEnabled(id, true)
    }

    override fun reload() {
        if (!isEnabled()) {
            return
        }
        val dataFolder = ConfigFiles.dataFolder()
        CartRepository.initialize()
        menu = MenuLoader.load(File(dataFolder, "Cart/ui/cart.yml"))
        checkoutMenu = MenuLoader.load(File(dataFolder, "Cart/ui/checkout.yml"))
        conflictMenu = MenuLoader.load(File(dataFolder, "Cart/ui/conflict.yml"))
        viewConfig = loadViewConfig()
    }

    fun hasShopView(shopId: String?): Boolean {
        return false
    }

    fun helpEntries(): List<com.y54895.matrixshop.core.menu.ShopMenuSelection> {
        return emptyList()
    }

    fun allShopEntries(): List<com.y54895.matrixshop.core.menu.ShopMenuSelection> {
        return emptyList()
    }

    fun standaloneEntries(): List<com.y54895.matrixshop.core.menu.ShopMenuSelection> {
        return emptyList()
    }

    fun open(player: Player, page: Int = 1, shopId: String? = null) {
        val store = CartRepository.load(player.uniqueId)
        val visibleEntries = visibleEntries(store)
        val goodsSlots = goodsSlots(menu)
        val maxPage = ((visibleEntries.size + goodsSlots.size - 1) / goodsSlots.size).coerceAtLeast(1)
        val currentPage = page.coerceIn(1, maxPage)
        val entries = visibleEntries.drop((currentPage - 1) * goodsSlots.size).take(goodsSlots.size)
        val summary = summarize(store)
        MenuRenderer.open(
            player = player,
            definition = menu,
            placeholders = mapOf(
                "command" to CommandUsageContext.modulePrefix(player, "cart", "/cart"),
                "shop-id" to defaultViewId(),
                "page" to currentPage.toString(),
                "max-page" to maxPage.toString(),
                "size" to visibleEntries.size.toString(),
                "estimate-total" to trimDouble(summary.finalTotal),
                "auction-watch-size" to summary.watchOnly.size.toString(),
                "source-filter" to sourceFilterLabel()
            ),
            goodsRenderer = { holder, slots ->
                renderEntries(player, holder, menu, entries, slots)
                wireControls(player, holder, menu, currentPage, maxPage)
            }
        )
    }

    fun openCheckout(player: Player, validOnly: Boolean = false, page: Int = 1, shopId: String? = null) {
        val store = CartRepository.load(player.uniqueId)
        val summary = summarize(store)
        val entries = if (validOnly) summary.valid else summary.checkoutEntries
        val slots = goodsSlots(checkoutMenu)
        val maxPage = ((entries.size + slots.size - 1) / slots.size).coerceAtLeast(1)
        val currentPage = page.coerceIn(1, maxPage)
        val pageEntries = entries.drop((currentPage - 1) * slots.size).take(slots.size)
        MenuRenderer.open(
            player = player,
            definition = checkoutMenu,
            placeholders = mapOf(
                "command" to CommandUsageContext.modulePrefix(player, "cart", "/cart"),
                "shop-id" to defaultViewId(),
                "page" to currentPage.toString(),
                "max-page" to maxPage.toString(),
                "valid-size" to summary.valid.size.toString(),
                "invalid-size" to summary.invalid.size.toString(),
                "auction-watch-size" to summary.watchOnly.size.toString(),
                "final-total" to trimDouble(summary.finalTotal),
                "source-filter" to sourceFilterLabel(),
                "checkout-mode" to if (validOnly) "valid_only" else "all"
            ),
            backAction = { open(player) },
            goodsRenderer = { holder, renderSlots ->
                renderCheckoutEntries(player, holder, pageEntries, renderSlots)
                wireCheckoutControls(player, holder, currentPage, maxPage, validOnly)
            }
        )
    }

    fun openConflict(player: Player, page: Int = 1, shopId: String? = null) {
        val store = CartRepository.load(player.uniqueId)
        val conflicts = summarize(store).invalid
        val slots = goodsSlots(conflictMenu)
        val maxPage = ((conflicts.size + slots.size - 1) / slots.size).coerceAtLeast(1)
        val currentPage = page.coerceIn(1, maxPage)
        val pageEntries = conflicts.drop((currentPage - 1) * slots.size).take(slots.size)
        MenuRenderer.open(
            player = player,
            definition = conflictMenu,
            placeholders = mapOf(
                "command" to CommandUsageContext.modulePrefix(player, "cart", "/cart"),
                "shop-id" to defaultViewId(),
                "page" to currentPage.toString(),
                "max-page" to maxPage.toString(),
                "size" to conflicts.size.toString(),
                "source-filter" to sourceFilterLabel()
            ),
            backAction = { openCheckout(player, false) },
            goodsRenderer = { holder, renderSlots ->
                renderConflictEntries(holder, pageEntries, renderSlots)
                wireConflictControls(player, holder, currentPage, maxPage)
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

    fun remove(player: Player, index: Int, shopId: String? = null) {
        val store = CartRepository.load(player.uniqueId)
        val entry = visibleEntries(store).getOrNull(index - 1)
        if (entry == null) {
            Texts.send(player, "&cCart entry not found.")
            return
        }
        store.entries.removeIf { it.id == entry.id }
        CartRepository.save(store)
        Texts.send(player, "&aRemoved cart entry: &f${entry.name}")
    }

    fun clear(player: Player, shopId: String? = null) {
        val store = CartRepository.load(player.uniqueId)
        val removableIds = visibleEntries(store)
            .filter { !it.protectedOnClear }
            .map { it.id }
            .toSet()
        val before = store.entries.size
        store.entries.removeIf { it.id in removableIds }
        CartRepository.save(store)
        Texts.send(player, "&aCleared cart entries: &f${before - store.entries.size}")
    }

    fun removeInvalid(player: Player, shopId: String? = null) {
        val store = CartRepository.load(player.uniqueId)
        val removableIds = visibleEntries(store)
            .filter { !validate(it).valid }
            .map { it.id }
            .toSet()
        val before = store.entries.size
        store.entries.removeIf { it.id in removableIds }
        CartRepository.save(store)
        Texts.send(player, "&aRemoved invalid cart entries: &f${before - store.entries.size}")
    }

    fun changeAmount(player: Player, index: Int, amount: Int, shopId: String? = null) {
        val store = CartRepository.load(player.uniqueId)
        val entry = visibleEntries(store).getOrNull(index - 1)
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

    fun checkout(player: Player, validOnly: Boolean, shopId: String? = null) {
        val store = CartRepository.load(player.uniqueId)
        val summary = summarize(store)
        var success = 0
        var invalid = 0
        summary.checkoutEntries.forEach { entry ->
            val validation = validate(entry)
            if (!validation.valid) {
                invalid++
                return@forEach
            }
            val result = when (entry.sourceModule.lowercase()) {
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
            detail = "shop=${defaultViewId()};success=$success;invalid=$invalid;remaining=${store.entries.size}"
        )
    }

    fun validate(entry: CartEntry): CartValidation {
        return when (entry.sourceModule.lowercase()) {
            "system_shop" -> {
                val result = SystemShopModule.validateProduct(
                    entry.metadata["category-id"].orEmpty(),
                    entry.metadata["product-id"].orEmpty(),
                    entry.amount
                )
                val currentPrice = SystemShopModule.currentPrice(
                    entry.metadata["category-id"].orEmpty(),
                    entry.metadata["product-id"].orEmpty()
                )
                if (result.success) {
                    CartValidation(true, "valid", "", "", currentPrice)
                } else {
                    CartValidation(false, "invalid", Texts.color(result.message), "product", currentPrice)
                }
            }
            "player_shop" -> {
                val ownerId = UUID.fromString(entry.metadata["owner-id"].orEmpty())
                val currentPrice = PlayerShopModule.currentListingPrice(
                    ownerId,
                    entry.metadata["owner-name"].orEmpty(),
                    entry.metadata["shop-id"],
                    entry.metadata["listing-id"].orEmpty()
                )
                val result = PlayerShopModule.validateListing(
                    ownerId,
                    entry.metadata["owner-name"].orEmpty(),
                    entry.metadata["shop-id"],
                    entry.metadata["listing-id"].orEmpty()
                )
                if (result.success) {
                    CartValidation(true, "valid", "", "", currentPrice)
                } else {
                    CartValidation(false, "invalid", Texts.color(result.message), "listing", currentPrice)
                }
            }
            "global_market" -> {
                val currentPrice = GlobalMarketModule.currentListingPrice(
                    entry.metadata["shop-id"],
                    entry.metadata["listing-id"].orEmpty()
                )
                val result = GlobalMarketModule.validateListing(
                    entry.metadata["shop-id"],
                    entry.metadata["listing-id"].orEmpty()
                )
                if (result.success) {
                    CartValidation(true, "valid", "", "", currentPrice)
                } else {
                    CartValidation(false, "invalid", Texts.color(result.message), "listing", currentPrice)
                }
            }
            else -> CartValidation(false, "invalid", Texts.color("&cUnknown cart source."), "source")
        }
    }

    private fun renderEntries(
        player: Player,
        holder: MatrixMenuHolder,
        definition: MenuDefinition,
        entries: List<CartEntry>,
        slots: List<Int>
    ) {
        val ordered = visibleEntries(CartRepository.load(player.uniqueId))
        entries.forEachIndexed { index, entry ->
            val validation = validate(entry)
            val slotNumber = (ordered.indexOfFirst { it.id == entry.id } + 1).coerceAtLeast(1)
            val slot = slots[index]
            holder.backingInventory.setItem(slot, buildEntryItem(player, entry, definition.template, validation, slotNumber))
            holder.handlers[slot] = { event ->
                if (event.click.isRightClick) {
                    remove(player, slotNumber)
                    open(player)
                } else {
                    Texts.send(
                        player,
                        Texts.tr(
                            ModuleBindings.hintKey("cart", "amount") ?: "@commands.hints.cart-amount",
                            mapOf("command" to "${CommandUsageContext.modulePrefix(player, "cart", "/cart")} amount", "slot" to slotNumber.toString())
                        )
                    )
                }
            }
        }
    }

    private fun renderCheckoutEntries(
        player: Player,
        holder: MatrixMenuHolder,
        entries: List<CartEntry>,
        slots: List<Int>
    ) {
        entries.forEachIndexed { index, entry ->
            val validation = validate(entry)
            val slot = slots[index]
            holder.backingInventory.setItem(slot, buildEntryItem(player, entry, checkoutMenu.template, validation, index + 1))
            holder.handlers[slot] = { openConflict(player) }
        }
    }

    private fun renderConflictEntries(holder: MatrixMenuHolder, entries: List<CartConflictEntry>, slots: List<Int>) {
        entries.forEachIndexed { index, conflict ->
            val slot = slots[index]
            holder.backingInventory.setItem(slot, buildConflictItem(conflict))
        }
    }

    private fun wireControls(player: Player, holder: MatrixMenuHolder, definition: MenuDefinition, currentPage: Int, maxPage: Int) {
        buttonSlot(definition, 'P')?.let { holder.handlers[it] = { open(player, (currentPage - 1).coerceAtLeast(1)) } }
        buttonSlot(definition, 'N')?.let { holder.handlers[it] = { open(player, (currentPage + 1).coerceAtMost(maxPage)) } }
        buttonSlot(definition, 'C')?.let { holder.handlers[it] = { openCheckout(player) } }
        buttonSlot(definition, 'X')?.let { holder.handlers[it] = { clear(player); open(player, currentPage) } }
        buttonSlot(definition, 'V')?.let { holder.handlers[it] = { removeInvalid(player); open(player, currentPage) } }
        buttonSlot(definition, 'A')?.let {
            holder.handlers[it] = {
                Texts.send(
                    player,
                    Texts.tr(
                        ModuleBindings.hintKey("cart", "amount") ?: "@commands.hints.cart-amount",
                        mapOf("command" to "${CommandUsageContext.modulePrefix(player, "cart", "/cart")} amount", "slot" to "<slot>")
                    )
                )
            }
        }
        buttonSlot(definition, 'R')?.let { holder.handlers[it] = { player.closeInventory() } }
    }

    private fun wireCheckoutControls(player: Player, holder: MatrixMenuHolder, currentPage: Int, maxPage: Int, validOnly: Boolean) {
        buttonSlot(checkoutMenu, 'P')?.let { holder.handlers[it] = { openCheckout(player, validOnly, (currentPage - 1).coerceAtLeast(1)) } }
        buttonSlot(checkoutMenu, 'N')?.let { holder.handlers[it] = { openCheckout(player, validOnly, (currentPage + 1).coerceAtMost(maxPage)) } }
        buttonSlot(checkoutMenu, 'C')?.let { holder.handlers[it] = { checkout(player, validOnly); open(player) } }
        buttonSlot(checkoutMenu, 'F')?.let { holder.handlers[it] = { openConflict(player) } }
        buttonSlot(checkoutMenu, 'R')?.let { holder.handlers[it] = { open(player) } }
    }

    private fun wireConflictControls(player: Player, holder: MatrixMenuHolder, currentPage: Int, maxPage: Int) {
        buttonSlot(conflictMenu, 'P')?.let { holder.handlers[it] = { openConflict(player, (currentPage - 1).coerceAtLeast(1)) } }
        buttonSlot(conflictMenu, 'N')?.let { holder.handlers[it] = { openConflict(player, (currentPage + 1).coerceAtMost(maxPage)) } }
        buttonSlot(conflictMenu, 'R')?.let { holder.handlers[it] = { openConflict(player, currentPage) } }
        buttonSlot(conflictMenu, 'V')?.let { holder.handlers[it] = { openCheckout(player, true) } }
        buttonSlot(conflictMenu, 'D')?.let { holder.handlers[it] = { removeInvalid(player); openConflict(player, 1) } }
        buttonSlot(conflictMenu, 'C')?.let { holder.handlers[it] = { openCheckout(player) } }
    }

    private fun buildEntryItem(
        player: Player,
        entry: CartEntry,
        template: com.y54895.matrixshop.core.menu.MenuTemplate,
        validation: CartValidation,
        slotNumber: Int,
    ): org.bukkit.inventory.ItemStack {
        val currentPrice = validation.currentPrice ?: entry.snapshotPrice
        val placeholders = mapOf(
            "command" to CommandUsageContext.modulePrefix(player, "cart", "/cart"),
            "name" to currentEntryName(entry),
            "source-module" to entry.sourceModule,
            "source-id" to entry.sourceId,
            "snapshot-price" to trimDouble(entry.snapshotPrice),
            "current-price" to trimDouble(currentPrice),
            "currency" to entry.currency,
            "amount" to entry.amount.toString(),
            "state" to entryStateLabel(entry, validation),
            "limitation" to entryLimitation(entry),
            "created-at" to timeFormatter.format(Instant.ofEpochMilli(entry.createdAt)),
            "shop-id" to defaultViewId(),
            "slot" to slotNumber.toString(),
            "reason" to ChatColor.stripColor(validation.reason).orEmpty(),
            "conflict-type" to validation.conflictType.ifBlank { validation.state }
        )
        val item = entry.item.clone()
        val meta = item.itemMeta ?: return item
        val name = if (template.name.isNotBlank()) {
            Texts.apply(template.name, placeholders)
        } else {
            Texts.color("&f${currentEntryName(entry)}")
        }
        val lore = if (template.lore.isNotEmpty()) {
            Texts.apply(template.lore, placeholders)
        } else {
            defaultEntryLore(entry, validation, slotNumber, currentPrice)
        }
        MenuRenderer.decorate(meta, name, lore)
        item.itemMeta = meta
        return item
    }

    private fun buildConflictItem(conflict: CartConflictEntry): org.bukkit.inventory.ItemStack {
        val entry = conflict.entry
        val validation = conflict.validation
        val item = entry.item.clone()
        val meta = item.itemMeta ?: return item
        val placeholders = mapOf(
            "name" to currentEntryName(entry),
            "source-module" to entry.sourceModule,
            "source-id" to entry.sourceId,
            "snapshot-price" to trimDouble(entry.snapshotPrice),
            "current-price" to trimDouble(validation.currentPrice ?: entry.snapshotPrice),
            "currency" to entry.currency,
            "amount" to entry.amount.toString(),
            "state" to validation.state,
            "limitation" to entryLimitation(entry),
            "created-at" to timeFormatter.format(Instant.ofEpochMilli(entry.createdAt)),
            "reason" to ChatColor.stripColor(validation.reason).orEmpty(),
            "conflict-type" to validation.conflictType.ifBlank { validation.state }
        )
        val name = if (conflictMenu.template.name.isNotBlank()) {
            Texts.apply(conflictMenu.template.name, placeholders)
        } else {
            Texts.color("&c${currentEntryName(entry)}")
        }
        val lore = if (conflictMenu.template.lore.isNotEmpty()) {
            Texts.apply(conflictMenu.template.lore, placeholders)
        } else {
            listOf(
                Texts.color("&7Source: &f${entry.sourceModule}"),
                Texts.color("&7Conflict: &c${validation.conflictType.ifBlank { validation.state }}"),
                Texts.color("&7Reason: &f${ChatColor.stripColor(validation.reason).orEmpty()}"),
                Texts.color("&7Snapshot price: &e${trimDouble(entry.snapshotPrice)} ${entry.currency}"),
                Texts.color("&7Current price: &6${trimDouble(validation.currentPrice ?: entry.snapshotPrice)} ${entry.currency}")
            )
        }
        MenuRenderer.decorate(meta, name, lore)
        item.itemMeta = meta
        return item
    }

    private fun defaultEntryLore(entry: CartEntry, validation: CartValidation, slotNumber: Int, currentPrice: Double): List<String> {
        val command = CommandUsageContext.modulePrefix(org.bukkit.Bukkit.getPlayer(entry.ownerName ?: "") ?: return mutableListOf(
            Texts.color("&7Source: &f${entry.sourceModule}"),
            Texts.color("&7Amount: &f${entry.amount}"),
            Texts.color("&7Snapshot price: &e${trimDouble(entry.snapshotPrice)} ${entry.currency}"),
            Texts.color("&7Current price: &6${trimDouble(currentPrice)} ${entry.currency}"),
            Texts.color("&7State: &f${entryStateLabel(entry, validation)}"),
            Texts.color("&7Slot: &f$slotNumber"),
            Texts.color("&7Created at: &f${timeFormatter.format(Instant.ofEpochMilli(entry.createdAt))}")
        ), "cart", "/cart")
        val lore = mutableListOf(
            Texts.color("&7Source: &f${entry.sourceModule}"),
            Texts.color("&7Amount: &f${entry.amount}"),
            Texts.color("&7Snapshot price: &e${trimDouble(entry.snapshotPrice)} ${entry.currency}"),
            Texts.color("&7Current price: &6${trimDouble(currentPrice)} ${entry.currency}"),
            Texts.color("&7State: &f${entryStateLabel(entry, validation)}"),
            Texts.color("&7Slot: &f$slotNumber"),
            Texts.color("&7Created at: &f${timeFormatter.format(Instant.ofEpochMilli(entry.createdAt))}")
        )
        if (validation.reason.isNotBlank()) {
            lore += Texts.color("&7Reason: &f${ChatColor.stripColor(validation.reason).orEmpty()}")
        }
        lore += Texts.color(if (entry.editableAmount) "&eRight click to remove. Use $command amount <slot> <number> to change amount." else "&eRight click to remove.")
        return lore
    }

    private fun summarize(store: CartStore): CartSummary {
        val visible = visibleEntries(store)
        val watchOnly = visible.filter { it.watchOnly }
        val checkoutEntries = visible.filter { !it.watchOnly }
        val valid = mutableListOf<CartEntry>()
        val invalid = mutableListOf<CartConflictEntry>()
        checkoutEntries.forEach { entry ->
            val validation = validate(entry)
            if (validation.valid) {
                valid += entry
            } else {
                invalid += CartConflictEntry(entry, validation)
            }
        }
        val total = valid.sumOf { effectiveCheckoutTotal(it) }
        return CartSummary(
            visibleEntries = visible,
            watchOnly = watchOnly,
            checkoutEntries = checkoutEntries,
            valid = valid,
            invalid = invalid,
            finalTotal = total
        )
    }

    private fun effectiveCheckoutTotal(entry: CartEntry): Double {
        return when (entry.sourceModule.lowercase()) {
            "system_shop" -> (SystemShopModule.currentPrice(entry.metadata["category-id"].orEmpty(), entry.metadata["product-id"].orEmpty())
                ?: entry.snapshotPrice) * entry.amount
            "player_shop" -> PlayerShopModule.currentListingPrice(
                UUID.fromString(entry.metadata["owner-id"].orEmpty()),
                entry.metadata["owner-name"].orEmpty(),
                entry.metadata["shop-id"],
                entry.metadata["listing-id"].orEmpty()
            ) ?: entry.snapshotPrice
            "global_market" -> GlobalMarketModule.currentListingPrice(entry.metadata["shop-id"], entry.metadata["listing-id"].orEmpty())
                ?: entry.snapshotPrice
            else -> entry.snapshotPrice * entry.amount
        }
    }

    private fun currentEntryName(entry: CartEntry): String {
        return entry.item.itemMeta?.displayName ?: entry.name
    }

    private fun entryStateLabel(entry: CartEntry, validation: CartValidation): String {
        return when {
            entry.watchOnly -> "watch_only"
            validation.valid -> "valid"
            else -> validation.state.ifBlank { "invalid" }
        }
    }

    private fun entryLimitation(entry: CartEntry): String {
        return if (entry.editableAmount) "editable" else "fixed"
    }

    private fun visibleEntries(store: CartStore): List<CartEntry> {
        val config = viewConfig
        return orderedEntries(store).filter { entry ->
            config.allowedSources.isEmpty() || config.allowedSources.contains(entry.sourceModule.lowercase())
        }
    }

    private fun goodsSlots(definition: MenuDefinition): List<Int> {
        val slots = ArrayList<Int>()
        definition.layout.forEachIndexed { row, line ->
            line.forEachIndexed { column, char ->
                val icon = definition.icons[char] ?: return@forEachIndexed
                if (!icon.mode.isNullOrBlank()) {
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

    private fun loadViewConfig(): CartViewConfig {
        val yaml = YamlConfiguration.loadConfiguration(File(ConfigFiles.dataFolder(), "Cart/ui/cart.yml"))
        return CartViewConfig(
            allowedSources = readStringSet(
                yaml,
                "Options.Source-Modules",
                "Options.Sources",
                "Options.View.Source-Modules",
                "View.Source-Modules"
            )
        )
    }

    private fun readStringSet(yaml: YamlConfiguration, vararg paths: String): Set<String> {
        paths.forEach { path ->
            if (yaml.isList(path)) {
                return yaml.getStringList(path)
                    .mapNotNull { it?.trim()?.takeIf(String::isNotBlank)?.lowercase() }
                    .toSet()
            }
        }
        return emptySet()
    }

    private fun sourceFilterLabel(): String {
        val config = viewConfig
        return if (config.allowedSources.isEmpty()) "all" else config.allowedSources.joinToString(",")
    }

    private fun defaultViewId(): String {
        return "cart"
    }

    private fun trimDouble(value: Double): String {
        return if (value % 1.0 == 0.0) value.toInt().toString() else "%.2f".format(value)
    }
}

private data class CartViewConfig(
    val allowedSources: Set<String> = emptySet()
)

private data class CartConflictEntry(
    val entry: CartEntry,
    val validation: CartValidation
)

private data class CartSummary(
    val visibleEntries: List<CartEntry>,
    val watchOnly: List<CartEntry>,
    val checkoutEntries: List<CartEntry>,
    val valid: List<CartEntry>,
    val invalid: List<CartConflictEntry>,
    val finalTotal: Double
)
