package com.y54895.matrixshop.module.playershop

import com.y54895.matrixshop.core.command.CommandUsageContext
import com.y54895.matrixshop.core.config.ConfigFiles
import com.y54895.matrixshop.core.economy.EconomyModule
import com.y54895.matrixshop.core.menu.MenuDefinition
import com.y54895.matrixshop.core.menu.MenuLoader
import com.y54895.matrixshop.core.menu.MenuRenderer
import com.y54895.matrixshop.core.menu.MatrixMenuHolder
import com.y54895.matrixshop.core.menu.ShopMenuLoader
import com.y54895.matrixshop.core.menu.ShopMenuSelection
import com.y54895.matrixshop.core.module.MatrixModule
import com.y54895.matrixshop.core.record.RecordService
import com.y54895.matrixshop.core.text.Texts
import com.y54895.matrixshop.module.cart.CartModule
import com.y54895.matrixshop.module.systemshop.ModuleOperationResult
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.io.File
import java.util.UUID

object PlayerShopModule : MatrixModule {

    override val id: String = "player-shop"
    override val displayName: String = "PlayerShop"

    private lateinit var settings: PlayerShopSettings
    private lateinit var menus: PlayerShopMenus

    override fun isEnabled(): Boolean {
        return ConfigFiles.isModuleEnabled(id, true)
    }

    override fun reload() {
        if (!isEnabled()) {
            return
        }
        PlayerShopRepository.initialize()
        settings = loadSettings()
        menus = PlayerShopMenus(
            browseViews = ShopMenuLoader.load("PlayerShop", "shop.yml"),
            edit = MenuLoader.load(File(ConfigFiles.dataFolder(), "PlayerShop/ui/edit.yml"))
        )
    }

    fun openShop(viewer: Player, targetName: String) {
        openShop(viewer, targetName, null)
    }

    fun openShop(viewer: Player, targetName: String, shopId: String?) {
        val owner = PlayerShopRepository.resolveOwner(targetName)
        if (owner == null) {
            Texts.sendKey(viewer, "@player-shop.errors.owner-not-found", mapOf("player" to targetName))
            return
        }
        openShop(viewer, owner.first, owner.second, shopId = shopId)
    }

    fun openShop(viewer: Player, ownerId: UUID, ownerName: String, page: Int = 1) {
        openShop(viewer, ownerId, ownerName, null, page)
    }

    fun openShop(viewer: Player, ownerId: UUID, ownerName: String, shopId: String?, page: Int = 1) {
        val resolvedShopId = resolveShopId(shopId)
        val store = loadStore(ownerId, ownerName, resolvedShopId)
        val selectedShop = selectShop(resolvedShopId)
        val goodsPerPage = goodsSlots(selectedShop.definition).size.coerceAtLeast(1)
        val ownerView = viewer.uniqueId == ownerId
        val maxPage = if (ownerView) {
            ((store.unlockedSlots + goodsPerPage - 1) / goodsPerPage).coerceAtLeast(1)
        } else {
            ((store.listings.size + goodsPerPage - 1) / goodsPerPage).coerceAtLeast(1)
        }
        val currentPage = page.coerceIn(1, maxPage)
        MenuRenderer.open(
            player = viewer,
            definition = selectedShop.definition,
            placeholders = basePlaceholders(viewer, store, currentPage, maxPage),
            goodsRenderer = { holder, slots ->
                renderBrowse(viewer, holder, store, slots, currentPage, ownerView)
                wireBrowseControls(viewer, holder, selectedShop.definition, resolvedShopId, store, currentPage, maxPage, ownerView)
            }
        )
    }

    fun openEdit(owner: Player, page: Int = 1) {
        openEdit(owner, null, page)
    }

    fun openEdit(owner: Player, browseShopId: String?, page: Int = 1) {
        val resolvedShopId = resolveShopId(browseShopId)
        val store = loadStore(owner.uniqueId, owner.name, resolvedShopId)
        val goodsPerPage = goodsSlots(menus.edit).size.coerceAtLeast(1)
        val maxPage = ((store.unlockedSlots + goodsPerPage - 1) / goodsPerPage).coerceAtLeast(1)
        val currentPage = page.coerceIn(1, maxPage)
        MenuRenderer.open(
            player = owner,
            definition = menus.edit,
            placeholders = basePlaceholders(owner, store, currentPage, maxPage),
            backAction = { openShop(owner, owner.uniqueId, owner.name, resolvedShopId, currentPage) },
            goodsRenderer = { holder, slots ->
                renderEdit(owner, holder, store, slots, currentPage)
                wireEditControls(owner, holder, resolvedShopId, currentPage, maxPage)
            }
        )
    }

    fun hasBrowseView(shopId: String?): Boolean {
        return ShopMenuLoader.contains(menus.browseViews, shopId)
    }

    fun resolveBoundShop(token: String?): String? {
        return ShopMenuLoader.resolveByBinding(menus.browseViews, token)?.id
    }

    fun helpEntries(): List<ShopMenuSelection> {
        return ShopMenuLoader.helpEntries(menus.browseViews)
    }

    fun allShopEntries(): List<ShopMenuSelection> {
        return ShopMenuLoader.allEntries(menus.browseViews)
    }

    fun standaloneEntries(): List<ShopMenuSelection> {
        return ShopMenuLoader.standaloneEntries(menus.browseViews)
    }

    fun uploadFromHand(player: Player, price: Double, requestedAmount: Int?) {
        uploadFromHand(player, null, price, requestedAmount)
    }

    fun uploadFromHand(player: Player, shopId: String?, price: Double, requestedAmount: Int?) {
        if (price <= 0) {
            Texts.sendKey(player, "@player-shop.errors.price-positive")
            return
        }
        val hand = player.inventory.itemInMainHand ?: ItemStack(Material.AIR)
        if (hand.type == Material.AIR || hand.amount <= 0) {
            Texts.sendKey(player, "@player-shop.errors.hold-item")
            return
        }
        val resolvedShopId = resolveShopId(shopId)
        val currencyKey = selectShop(resolvedShopId).currencyKey.ifBlank { settings.currencyKey }
        val store = loadStore(player.uniqueId, player.name, resolvedShopId)
        val slotIndex = PlayerShopRepository.nextFreeSlot(store)
        if (slotIndex == null) {
            Texts.sendKey(player, "@player-shop.errors.store-full")
            return
        }
        val amount = (requestedAmount ?: hand.amount).coerceAtLeast(1)
        if (amount > hand.amount) {
            Texts.sendKey(player, "@player-shop.errors.main-hand-not-enough")
            return
        }
        val listed = hand.clone().apply { this.amount = amount }
        val remaining = hand.amount - amount
        player.inventory.itemInMainHand = if (remaining <= 0) ItemStack(Material.AIR) else hand.apply { this.amount = remaining }
        val listing = PlayerShopListing(
            id = "ps-${System.currentTimeMillis().toString(36)}-$slotIndex",
            slotIndex = slotIndex,
            price = price,
            currency = currencyKey,
            item = listed,
            createdAt = System.currentTimeMillis()
        )
        store.listings += listing
        PlayerShopRepository.save(store)
        player.updateInventory()
        Texts.sendKey(
            player,
            "@player-shop.success.listed",
            mapOf(
                "shop" to store.shopId,
                "name" to itemDisplayName(listed),
                "amount" to listed.amount.toString(),
                "price" to trimDouble(price)
            )
        )
        RecordService.append("player_shop", "list", player.name, "shop=${store.shopId};listing=${listing.id};price=${trimDouble(price)};amount=${listed.amount}")
        openEdit(player, resolvedShopId, pageForSlot(slotIndex, goodsPerPage(menus.edit)))
    }

    fun selection(ownerId: UUID, ownerName: String, listingId: String): PlayerShopSelection? {
        return selection(ownerId, ownerName, null, listingId)
    }

    fun selection(ownerId: UUID, ownerName: String, shopId: String?, listingId: String): PlayerShopSelection? {
        val resolvedShopId = resolveShopId(shopId)
        val store = loadStore(ownerId, ownerName, resolvedShopId)
        val listing = store.listings.firstOrNull { it.id == listingId } ?: return null
        return PlayerShopSelection(ownerId = ownerId, ownerName = store.ownerName, listing = listing)
    }

    fun validateListing(ownerId: UUID, ownerName: String, listingId: String): ModuleOperationResult {
        return validateListing(ownerId, ownerName, null, listingId)
    }

    fun validateListing(ownerId: UUID, ownerName: String, shopId: String?, listingId: String): ModuleOperationResult {
        val selection = selection(ownerId, ownerName, shopId, listingId)
            ?: return ModuleOperationResult(false, Texts.tr("@player-shop.errors.listing-unavailable"))
        return if (selection.listing.item.type == Material.AIR || selection.listing.item.amount <= 0) {
            ModuleOperationResult(false, Texts.tr("@player-shop.errors.invalid-item"))
        } else {
            ModuleOperationResult(true, "")
        }
    }

    fun currentListingPrice(ownerId: UUID, ownerName: String, shopId: String?, listingId: String): Double? {
        return selection(ownerId, ownerName, shopId, listingId)?.listing?.price
    }

    fun purchaseDirect(viewer: Player, ownerId: UUID, ownerName: String, listingId: String, reopenAfterSuccess: Boolean = true): ModuleOperationResult {
        return purchaseDirect(viewer, ownerId, ownerName, null, listingId, reopenAfterSuccess)
    }

    fun purchaseDirect(
        viewer: Player,
        ownerId: UUID,
        ownerName: String,
        shopId: String?,
        listingId: String,
        reopenAfterSuccess: Boolean = true
    ): ModuleOperationResult {
        val resolvedShopId = resolveShopId(shopId)
        val store = loadStore(ownerId, ownerName, resolvedShopId)
        val listing = store.listings.firstOrNull { it.id == listingId }
            ?: return ModuleOperationResult(false, Texts.tr("@player-shop.errors.listing-unavailable"))
        return purchase(viewer, store, listing, reopenAfterSuccess)
    }

    private fun purchase(viewer: Player, store: PlayerShopStore, listing: PlayerShopListing, reopenAfterSuccess: Boolean): ModuleOperationResult {
        if (viewer.uniqueId == store.ownerId) {
            openEdit(viewer, store.shopId, pageForSlot(listing.slotIndex, goodsPerPage(menus.edit)))
            return ModuleOperationResult(false, "")
        }
        val refreshed = loadStore(store.ownerId, store.ownerName, store.shopId)
        val target = refreshed.listings.firstOrNull { it.id == listing.id }
            ?: return ModuleOperationResult(false, Texts.tr("@player-shop.errors.listing-unavailable"))
        if (target.price > 0 && !EconomyModule.isAvailable(target.currency)) {
            return ModuleOperationResult(false, Texts.tr("@economy.errors.currency-unavailable", mapOf("currency" to EconomyModule.displayName(target.currency))))
        }
        if (!canFit(viewer.inventory.contents.filterNotNull(), listOf(target.item))) {
            return ModuleOperationResult(false, Texts.tr("@player-shop.errors.inventory-no-space"))
        }
        if (!EconomyModule.has(viewer, target.currency, target.price)) {
            return ModuleOperationResult(false, EconomyModule.insufficientMessage(viewer, target.currency, target.price))
        }
        if (!EconomyModule.withdraw(viewer, target.currency, target.price, mapOf("seller" to store.ownerName, "item" to itemDisplayName(target.item)))) {
            return ModuleOperationResult(false, Texts.tr("@economy.errors.withdraw-failed", mapOf("currency" to EconomyModule.displayName(target.currency))))
        }
        val seller = Bukkit.getOfflinePlayer(store.ownerId)
        if (!EconomyModule.deposit(seller, target.currency, target.price, mapOf("buyer" to viewer.name, "item" to itemDisplayName(target.item)))) {
            EconomyModule.deposit(viewer, target.currency, target.price)
            return ModuleOperationResult(false, Texts.tr("@economy.errors.deposit-failed", mapOf("currency" to EconomyModule.displayName(target.currency))))
        }
        refreshed.listings.remove(target)
        PlayerShopRepository.save(refreshed)
        viewer.inventory.addItem(target.item.clone())
        viewer.updateInventory()
        Texts.sendKey(
            viewer,
            "@player-shop.success.purchased",
            mapOf(
                "shop" to store.shopId,
                "name" to itemDisplayName(target.item),
                "amount" to target.item.amount.toString(),
                "price" to EconomyModule.formatAmount(target.currency, target.price)
            )
        )
        Bukkit.getPlayer(store.ownerId)?.let {
            Texts.sendKey(
                it,
                "@player-shop.success.sold",
                mapOf("shop" to store.shopId, "price" to EconomyModule.formatAmount(target.currency, target.price))
            )
        }
        RecordService.append(
            module = "player_shop",
            type = "purchase",
            actor = viewer.name,
            target = store.ownerName,
            moneyChange = -target.price,
            detail = "shop=${store.shopId};seller=${store.ownerName};listing=${target.id};price=${trimDouble(target.price)};amount=${target.item.amount}"
        )
        RecordService.append(
            module = "player_shop",
            type = "sale",
            actor = store.ownerName,
            target = viewer.name,
            moneyChange = target.price,
            detail = "shop=${store.shopId};buyer=${viewer.name};listing=${target.id};price=${trimDouble(target.price)};amount=${target.item.amount}"
        )
        if (reopenAfterSuccess) {
            openShop(viewer, store.ownerId, store.ownerName, store.shopId)
        }
        return ModuleOperationResult(true, "")
    }

    private fun removeListing(owner: Player, shopId: String, listingId: String) {
        val store = loadStore(owner.uniqueId, owner.name, shopId)
        val listing = store.listings.firstOrNull { it.id == listingId }
        if (listing == null) {
            Texts.sendKey(owner, "@player-shop.errors.listing-not-found-in-shop")
            return
        }
        store.listings.remove(listing)
        PlayerShopRepository.save(store)
        owner.inventory.addItem(listing.item.clone()).values.forEach {
            owner.world.dropItemNaturally(owner.location, it)
        }
        owner.updateInventory()
        Texts.sendKey(
            owner,
            "@player-shop.success.removed",
            mapOf("shop" to shopId, "name" to itemDisplayName(listing.item))
        )
        RecordService.append("player_shop", "remove", owner.name, "shop=$shopId;listing=${listing.id}")
        openEdit(owner, shopId, pageForSlot(listing.slotIndex, goodsPerPage(menus.edit)))
    }

    private fun renderBrowse(viewer: Player, holder: MatrixMenuHolder, store: PlayerShopStore, slots: List<Int>, currentPage: Int, ownerView: Boolean) {
        val entries = if (ownerView) {
            val start = (currentPage - 1) * slots.size
            val end = start + slots.size
            store.listings.sortedBy { it.slotIndex }.filter { it.slotIndex in start until end }
        } else {
            store.listings.sortedBy { it.slotIndex }.drop((currentPage - 1) * slots.size).take(slots.size)
        }
        entries.forEachIndexed { index, listing ->
            val slot = slots[index]
                val item = listing.item.clone()
                item.itemMeta = item.itemMeta?.apply {
                    lore = (lore ?: emptyList()) + listOf(
                    Texts.colorKey("@player-shop.lore.price", mapOf("price" to EconomyModule.formatAmount(listing.currency, listing.price), "currency" to EconomyModule.displayName(listing.currency))),
                        Texts.colorKey("@player-shop.lore.owner", mapOf("owner" to store.ownerName)),
                        Texts.colorKey(if (ownerView) "@player-shop.lore.left-edit" else "@player-shop.lore.left-buy"),
                        Texts.colorKey(if (ownerView) "@player-shop.lore.owner-view" else "@player-shop.lore.right-cart")
                )
            }
            holder.backingInventory.setItem(slot, item)
            holder.handlers[slot] = { event ->
                if (ownerView) {
                    openEdit(viewer, store.shopId, pageForSlot(listing.slotIndex, slots.size))
                } else if (event.click.isRightClick) {
                    CartModule.addPlayerShopListing(viewer, store.shopId, store.ownerId.toString(), store.ownerName, listing.id)
                } else {
                    purchase(viewer, store, listing, true)
                }
            }
        }
    }

    private fun renderEdit(owner: Player, holder: MatrixMenuHolder, store: PlayerShopStore, slots: List<Int>, currentPage: Int) {
        val start = (currentPage - 1) * slots.size
        val end = start + slots.size
        store.listings.sortedBy { it.slotIndex }
            .filter { it.slotIndex in start until end }
            .forEach { listing ->
                val localIndex = listing.slotIndex - start
                if (localIndex !in slots.indices) {
                    return@forEach
                }
                val inventorySlot = slots[localIndex]
                val item = listing.item.clone()
                item.itemMeta = item.itemMeta?.apply {
                    lore = (lore ?: emptyList()) + listOf(
                        Texts.colorKey("@player-shop.lore.price", mapOf("price" to EconomyModule.formatAmount(listing.currency, listing.price), "currency" to EconomyModule.displayName(listing.currency))),
                        Texts.colorKey("@player-shop.lore.slot", mapOf("slot" to (listing.slotIndex + 1).toString(), "max" to store.unlockedSlots.toString())),
                        Texts.colorKey("@player-shop.lore.left-remove")
                    )
                }
                holder.backingInventory.setItem(inventorySlot, item)
                holder.handlers[inventorySlot] = { removeListing(owner, store.shopId, listing.id) }
            }
    }

    private fun wireBrowseControls(
        viewer: Player,
        holder: MatrixMenuHolder,
        definition: MenuDefinition,
        browseShopId: String,
        store: PlayerShopStore,
        currentPage: Int,
        maxPage: Int,
        ownerView: Boolean
    ) {
        buttonSlot(definition, 'P')?.let { slot ->
            holder.handlers[slot] = { openShop(viewer, store.ownerId, store.ownerName, browseShopId, (currentPage - 1).coerceAtLeast(1)) }
        }
        buttonSlot(definition, 'N')?.let { slot ->
            holder.handlers[slot] = { openShop(viewer, store.ownerId, store.ownerName, browseShopId, (currentPage + 1).coerceAtMost(maxPage)) }
        }
        buttonSlot(definition, 'E')?.let { slot ->
            if (ownerView) {
                holder.handlers[slot] = { openEdit(viewer, browseShopId, currentPage) }
            } else {
                holder.backingInventory.setItem(slot, fillerItem())
                holder.handlers.remove(slot)
            }
        }
    }

    private fun wireEditControls(owner: Player, holder: MatrixMenuHolder, browseShopId: String, currentPage: Int, maxPage: Int) {
        buttonSlot(menus.edit, 'P')?.let { slot ->
            holder.handlers[slot] = { openEdit(owner, browseShopId, (currentPage - 1).coerceAtLeast(1)) }
        }
        buttonSlot(menus.edit, 'N')?.let { slot ->
            holder.handlers[slot] = { openEdit(owner, browseShopId, (currentPage + 1).coerceAtMost(maxPage)) }
        }
        buttonSlot(menus.edit, 'H')?.let { slot ->
            holder.handlers[slot] = {
                Texts.sendKey(
                    owner,
                    "@player-shop.hints.upload-command",
                    mapOf("command" to "${CommandUsageContext.modulePrefix(owner, "player-shop", "/playershop")} upload <price> [amount]")
                )
            }
        }
    }

    private fun basePlaceholders(viewer: Player, store: PlayerShopStore, page: Int, maxPage: Int): Map<String, String> {
        return mapOf(
            "command" to CommandUsageContext.modulePrefix(viewer, "player-shop", "/playershop"),
            "player" to viewer.name,
            "owner" to store.ownerName,
            "page" to page.toString(),
            "max-page" to maxPage.toString(),
            "money" to EconomyModule.formatAmount(selectShop(store.shopId).currencyKey, EconomyModule.balance(viewer, selectShop(store.shopId).currencyKey)),
            "listed" to store.listings.size.toString(),
            "unlocked" to store.unlockedSlots.toString(),
            "shop-id" to store.shopId
        )
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

    private fun goodsPerPage(definition: MenuDefinition): Int {
        return goodsSlots(definition).size.coerceAtLeast(1)
    }

    private fun pageForSlot(slotIndex: Int, goodsPerPage: Int): Int {
        return (slotIndex / goodsPerPage) + 1
    }

    private fun loadStore(ownerId: UUID, ownerName: String, shopId: String): PlayerShopStore {
        return PlayerShopRepository.load(shopId, ownerId, ownerName, settings.unlockedBase, settings.unlockedMax)
    }

    private fun loadSettings(): PlayerShopSettings {
        val yaml = YamlConfiguration.loadConfiguration(File(ConfigFiles.dataFolder(), "PlayerShop/settings.yml"))
        return PlayerShopSettings(
            unlockedBase = yaml.getInt("Unlock.Base", 21),
            unlockedMax = yaml.getInt("Unlock.Max", 100),
            currencyKey = EconomyModule.configuredKey(yaml)
        )
    }

    private fun selectShop(shopId: String?): ShopMenuSelection {
        return ShopMenuLoader.resolve(menus.browseViews, shopId)
    }

    private fun resolveShopId(shopId: String?): String {
        return selectShop(shopId).id
    }

    private fun fillerItem(): ItemStack {
        return ItemStack(Material.STAINED_GLASS_PANE, 1).apply {
            itemMeta = itemMeta?.apply { setDisplayName(Texts.color(" ")) }
        }
    }

    private fun trimDouble(value: Double): String {
        return if (value % 1.0 == 0.0) value.toInt().toString() else "%.2f".format(value)
    }

    private fun itemDisplayName(item: ItemStack): String {
        return item.itemMeta?.displayName ?: item.type.name.lowercase().replace('_', ' ')
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
                val emptyIndex = virtual.indexOfFirst { it == null || it.type == Material.AIR }
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
