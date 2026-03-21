package com.y54895.matrixshop.module.globalmarket

import com.y54895.matrixshop.core.config.ConfigFiles
import com.y54895.matrixshop.core.economy.VaultEconomyBridge
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

object GlobalMarketModule : MatrixModule {

    override val id: String = "global-market"
    override val displayName: String = "GlobalMarket"

    private lateinit var settings: GlobalMarketSettings
    private lateinit var menus: GlobalMarketMenus

    override fun isEnabled(): Boolean {
        return ConfigFiles.isModuleEnabled(id, true)
    }

    override fun reload() {
        if (!isEnabled()) {
            return
        }
        GlobalMarketRepository.initialize()
        settings = loadSettings()
        menus = GlobalMarketMenus(
            marketViews = ShopMenuLoader.load("GlobalMarket", "market.yml"),
            manage = MenuLoader.load(File(ConfigFiles.dataFolder(), "GlobalMarket/ui/manage.yml")),
            upload = MenuLoader.load(File(ConfigFiles.dataFolder(), "GlobalMarket/ui/upload.yml"))
        )
    }

    fun openMarket(player: Player, page: Int = 1) {
        openMarket(player, null, page)
    }

    fun openMarket(player: Player, shopId: String?, page: Int = 1) {
        val selectedShop = selectShop(shopId)
        val listings = activeListings(selectedShop.id)
        val goodsSlots = goodsSlots(selectedShop.definition).size.coerceAtLeast(1)
        val maxPage = ((listings.size + goodsSlots - 1) / goodsSlots).coerceAtLeast(1)
        val currentPage = page.coerceIn(1, maxPage)
        val entries = listings.drop((currentPage - 1) * goodsSlots).take(goodsSlots)
        MenuRenderer.open(
            player = player,
            definition = selectedShop.definition,
            placeholders = mapOf(
                "page" to currentPage.toString(),
                "max-page" to maxPage.toString(),
                "shop-id" to selectedShop.id
            ),
            goodsRenderer = { holder, slots ->
                renderMarket(player, holder, entries, slots, selectedShop.id)
                wireMarketControls(player, holder, selectedShop.definition, selectedShop.id, currentPage, maxPage)
            }
        )
    }

    fun hasMarketView(shopId: String?): Boolean {
        return ShopMenuLoader.contains(menus.marketViews, shopId)
    }

    fun resolveBoundShop(token: String?): String? {
        return ShopMenuLoader.resolveByBinding(menus.marketViews, token)?.id
    }

    fun helpEntries(): List<ShopMenuSelection> {
        return ShopMenuLoader.helpEntries(menus.marketViews)
    }

    fun standaloneEntries(): List<ShopMenuSelection> {
        return ShopMenuLoader.standaloneEntries(menus.marketViews)
    }

    fun openManage(player: Player, page: Int = 1) {
        openManage(player, null, page)
    }

    fun openManage(player: Player, shopId: String?, page: Int = 1) {
        val resolvedShopId = resolveShopId(shopId)
        val listings = activeListings(resolvedShopId).filter { it.ownerId == player.uniqueId }
        val goodsSlots = goodsSlots(menus.manage).size.coerceAtLeast(1)
        val maxPage = ((listings.size + goodsSlots - 1) / goodsSlots).coerceAtLeast(1)
        val currentPage = page.coerceIn(1, maxPage)
        val entries = listings.drop((currentPage - 1) * goodsSlots).take(goodsSlots)
        MenuRenderer.open(
            player = player,
            definition = menus.manage,
            placeholders = mapOf(
                "page" to currentPage.toString(),
                "max-page" to maxPage.toString(),
                "listed" to listings.size.toString(),
                "shop-id" to resolvedShopId
            ),
            backAction = { openMarket(player, resolvedShopId) },
            goodsRenderer = { holder, slots ->
                renderManage(player, holder, entries, slots, resolvedShopId)
                wireManageControls(player, holder, resolvedShopId, currentPage, maxPage)
            }
        )
    }

    fun openUpload(player: Player) {
        openUpload(player, null)
    }

    fun openUpload(player: Player, shopId: String?) {
        val resolvedShopId = resolveShopId(shopId)
        MenuRenderer.open(
            player = player,
            definition = menus.upload,
            placeholders = mapOf(
                "player" to player.name,
                "shop-id" to resolvedShopId
            ),
            backAction = { openMarket(player, resolvedShopId) }
        )
    }

    fun uploadFromHand(player: Player, price: Double, requestedAmount: Int?) {
        uploadFromHand(player, null, price, requestedAmount)
    }

    fun uploadFromHand(player: Player, shopId: String?, price: Double, requestedAmount: Int?) {
        if (price <= 0) {
            Texts.send(player, "&cPrice must be greater than 0.")
            return
        }
        val hand = player.inventory.itemInMainHand ?: ItemStack(Material.AIR)
        if (hand.type == Material.AIR || hand.amount <= 0) {
            Texts.send(player, "&cHold the item you want to list in your main hand.")
            return
        }
        val amount = (requestedAmount ?: hand.amount).coerceAtLeast(1)
        if (amount > hand.amount) {
            Texts.send(player, "&cYou do not have enough items in your main hand.")
            return
        }
        val listingItem = hand.clone().apply { this.amount = amount }
        val remain = hand.amount - amount
        player.inventory.itemInMainHand = if (remain <= 0) ItemStack(Material.AIR) else hand.apply { this.amount = remain }
        val now = System.currentTimeMillis()
        val listing = GlobalMarketListing(
            id = "gm-${now.toString(36)}",
            shopId = resolveShopId(shopId),
            ownerId = player.uniqueId,
            ownerName = player.name,
            price = price,
            currency = "vault",
            item = listingItem,
            createdAt = now,
            expireAt = now + settings.expireHours.coerceAtLeast(1) * 3_600_000L
        )
        val listings = GlobalMarketRepository.loadAll().toMutableList()
        listings += listing
        GlobalMarketRepository.saveAll(listings)
        player.updateInventory()
        Texts.send(player, "&aListed in &f${listing.shopId}&a: &f${itemDisplayName(listingItem)} &7x&f${listingItem.amount} &7- &e${trimDouble(price)}")
        RecordService.append("global_market", "list", player.name, "shop=${listing.shopId};listing=${listing.id};price=${trimDouble(price)};amount=${listingItem.amount}")
    }

    fun selection(listingId: String): GlobalMarketListing? {
        return selection(null, listingId)
    }

    fun selection(shopId: String?, listingId: String): GlobalMarketListing? {
        val resolvedShopId = shopId?.trim()?.takeIf(String::isNotBlank)?.lowercase()
        return GlobalMarketRepository.loadAll()
            .asSequence()
            .filter { resolvedShopId == null || it.shopId.equals(resolvedShopId, true) }
            .filter { it.id == listingId }
            .firstOrNull()
    }

    fun validateListing(listingId: String): ModuleOperationResult {
        return validateListing(null, listingId)
    }

    fun validateListing(shopId: String?, listingId: String): ModuleOperationResult {
        val listing = selection(shopId, listingId) ?: return ModuleOperationResult(false, "&cThat listing is no longer available.")
        return if (listing.item.type == Material.AIR || listing.item.amount <= 0) {
            ModuleOperationResult(false, "&cThat listing contains invalid item data.")
        } else {
            ModuleOperationResult(true, "")
        }
    }

    fun purchaseDirect(player: Player, listingId: String, reopenAfterSuccess: Boolean = true): ModuleOperationResult {
        return purchaseDirect(player, null, listingId, reopenAfterSuccess)
    }

    fun purchaseDirect(player: Player, shopId: String?, listingId: String, reopenAfterSuccess: Boolean = true): ModuleOperationResult {
        val resolvedShopId = resolveShopId(shopId)
        val listings = GlobalMarketRepository.loadAll().toMutableList()
        val listing = listings.firstOrNull { it.id == listingId && it.shopId.equals(resolvedShopId, true) }
            ?: return ModuleOperationResult(false, "&cThat listing is no longer available.")
        if (listing.ownerId == player.uniqueId) {
            if (reopenAfterSuccess) {
                openManage(player, listing.shopId)
            }
            return ModuleOperationResult(false, "")
        }
        if (listing.price > 0 && !VaultEconomyBridge.isAvailable()) {
            return ModuleOperationResult(false, "&cVault economy is required for GlobalMarket purchases.")
        }
        if (!canFit(player.inventory.contents.filterNotNull(), listOf(listing.item))) {
            return ModuleOperationResult(false, "&cYour inventory does not have enough free space.")
        }
        if (!VaultEconomyBridge.has(player, listing.price)) {
            return ModuleOperationResult(false, "&cYou need &e${trimDouble(listing.price)} &cto buy this listing.")
        }
        if (!VaultEconomyBridge.withdraw(player, listing.price)) {
            return ModuleOperationResult(false, "&cFailed to withdraw the purchase price.")
        }
        val tax = listing.price * settings.taxPercent / 100.0
        val sellerIncome = (listing.price - tax).coerceAtLeast(0.0)
        val seller = Bukkit.getOfflinePlayer(listing.ownerId)
        if (!VaultEconomyBridge.deposit(seller, sellerIncome)) {
            VaultEconomyBridge.deposit(player, listing.price)
            return ModuleOperationResult(false, "&cFailed to deliver money to the seller. The purchase was rolled back.")
        }
        listings.removeIf { it.id == listing.id }
        GlobalMarketRepository.saveAll(listings)
        player.inventory.addItem(listing.item.clone())
        player.updateInventory()
        Texts.send(player, "&aPurchased from &f${listing.shopId}&a: &f${itemDisplayName(listing.item)} &7x&f${listing.item.amount} &7- &e${trimDouble(listing.price)}")
        Bukkit.getPlayer(listing.ownerId)?.let {
            Texts.send(it, "&aYour GlobalMarket listing sold in &f${listing.shopId}&a for &e${trimDouble(sellerIncome)}&a.")
        }
        RecordService.append(
            module = "global_market",
            type = "purchase",
            actor = player.name,
            target = listing.ownerName,
            moneyChange = -listing.price,
            detail = "shop=${listing.shopId};seller=${listing.ownerName};listing=${listing.id};price=${trimDouble(listing.price)};amount=${listing.item.amount};tax=${trimDouble(tax)}"
        )
        RecordService.append(
            module = "global_market",
            type = "sale",
            actor = listing.ownerName,
            target = player.name,
            moneyChange = sellerIncome,
            detail = "shop=${listing.shopId};buyer=${player.name};listing=${listing.id};price=${trimDouble(listing.price)};amount=${listing.item.amount};tax=${trimDouble(tax)}"
        )
        if (reopenAfterSuccess) {
            openMarket(player, listing.shopId)
        }
        return ModuleOperationResult(true, "")
    }

    private fun removeListing(player: Player, shopId: String, listingId: String) {
        val listings = GlobalMarketRepository.loadAll().toMutableList()
        val listing = listings.firstOrNull { it.id == listingId && it.ownerId == player.uniqueId && it.shopId.equals(shopId, true) }
        if (listing == null) {
            Texts.send(player, "&cListing not found in this market.")
            return
        }
        listings.removeIf { it.id == listing.id }
        GlobalMarketRepository.saveAll(listings)
        player.inventory.addItem(listing.item.clone()).values.forEach {
            player.world.dropItemNaturally(player.location, it)
        }
        player.updateInventory()
        Texts.send(player, "&aRemoved listing from &f${listing.shopId}&a: &f${itemDisplayName(listing.item)}")
        RecordService.append("global_market", "remove", player.name, "shop=${listing.shopId};listing=${listing.id}")
        openManage(player, shopId)
    }

    private fun renderMarket(player: Player, holder: MatrixMenuHolder, entries: List<GlobalMarketListing>, slots: List<Int>, shopId: String) {
        entries.forEachIndexed { index, listing ->
            val item = listing.item.clone()
            item.itemMeta = item.itemMeta?.apply {
                lore = (lore ?: emptyList()) + listOf(
                    Texts.color("&7Seller: &f${listing.ownerName}"),
                    Texts.color("&7Price: &e${trimDouble(listing.price)} ${listing.currency}"),
                    Texts.color("&7Time Left: &f${remainingText(listing)}"),
                    Texts.color("&eLeft click to buy"),
                    Texts.color("&6Right click to add to cart")
                )
            }
            val slot = slots[index]
            holder.backingInventory.setItem(slot, item)
            holder.handlers[slot] = { event ->
                if (event.click.isRightClick) {
                    CartModule.addGlobalMarketListing(player, shopId, listing.id)
                } else {
                    val result = purchaseDirect(player, shopId, listing.id, true)
                    if (!result.success && result.message.isNotBlank()) {
                        Texts.send(player, result.message)
                    }
                }
            }
        }
    }

    private fun renderManage(player: Player, holder: MatrixMenuHolder, entries: List<GlobalMarketListing>, slots: List<Int>, shopId: String) {
        entries.forEachIndexed { index, listing ->
            val item = listing.item.clone()
            item.itemMeta = item.itemMeta?.apply {
                lore = (lore ?: emptyList()) + listOf(
                    Texts.color("&7Price: &e${trimDouble(listing.price)} ${listing.currency}"),
                    Texts.color("&7Time Left: &f${remainingText(listing)}"),
                    Texts.color("&cLeft click to remove")
                )
            }
            val slot = slots[index]
            holder.backingInventory.setItem(slot, item)
            holder.handlers[slot] = { removeListing(player, shopId, listing.id) }
        }
    }

    private fun wireMarketControls(player: Player, holder: MatrixMenuHolder, definition: MenuDefinition, shopId: String, currentPage: Int, maxPage: Int) {
        buttonSlot(definition, 'P')?.let { holder.handlers[it] = { openMarket(player, shopId, (currentPage - 1).coerceAtLeast(1)) } }
        buttonSlot(definition, 'N')?.let { holder.handlers[it] = { openMarket(player, shopId, (currentPage + 1).coerceAtMost(maxPage)) } }
        buttonSlot(definition, 'U')?.let { holder.handlers[it] = { openUpload(player, shopId) } }
        buttonSlot(definition, 'M')?.let { holder.handlers[it] = { openManage(player, shopId) } }
        buttonSlot(definition, 'C')?.let { holder.handlers[it] = { CartModule.open(player) } }
    }

    private fun wireManageControls(player: Player, holder: MatrixMenuHolder, shopId: String, currentPage: Int, maxPage: Int) {
        buttonSlot(menus.manage, 'P')?.let { holder.handlers[it] = { openManage(player, shopId, (currentPage - 1).coerceAtLeast(1)) } }
        buttonSlot(menus.manage, 'N')?.let { holder.handlers[it] = { openManage(player, shopId, (currentPage + 1).coerceAtMost(maxPage)) } }
        buttonSlot(menus.manage, 'U')?.let { holder.handlers[it] = { openUpload(player, shopId) } }
    }

    private fun loadSettings(): GlobalMarketSettings {
        val yaml = YamlConfiguration.loadConfiguration(File(ConfigFiles.dataFolder(), "GlobalMarket/settings.yml"))
        return GlobalMarketSettings(
            expireHours = yaml.getInt("Listing.Expire-Hours", 72),
            taxPercent = yaml.getDouble("Listing.Tax-Percent", 3.0)
        )
    }

    private fun activeListings(shopId: String? = null): List<GlobalMarketListing> {
        val now = System.currentTimeMillis()
        val resolvedShopId = shopId?.trim()?.takeIf(String::isNotBlank)?.lowercase()
        return GlobalMarketRepository.loadAll()
            .filter { it.expireAt <= 0L || it.expireAt > now }
            .filter { resolvedShopId == null || it.shopId.equals(resolvedShopId, true) }
            .sortedByDescending { it.createdAt }
    }

    private fun selectShop(shopId: String?): ShopMenuSelection {
        return ShopMenuLoader.resolve(menus.marketViews, shopId)
    }

    private fun resolveShopId(shopId: String?): String {
        return selectShop(shopId).id
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

    private fun remainingText(listing: GlobalMarketListing): String {
        val remain = (listing.expireAt - System.currentTimeMillis()).coerceAtLeast(0L)
        val hours = remain / 3_600_000L
        val minutes = (remain % 3_600_000L) / 60_000L
        return "${hours}h ${minutes}m"
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
