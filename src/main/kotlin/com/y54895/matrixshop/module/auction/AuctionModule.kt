package com.y54895.matrixshop.module.auction

import com.y54895.matrixshop.core.command.CommandUsageContext
import com.y54895.matrixshop.core.config.ModuleBindings
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
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.io.File
import java.util.UUID

object AuctionModule : MatrixModule {

    override val id: String = "auction"
    override val displayName: String = "Auction"

    private lateinit var settings: AuctionSettings
    private lateinit var menus: AuctionMenus

    override fun isEnabled(): Boolean {
        return ConfigFiles.isModuleEnabled(id, true)
    }

    override fun reload() {
        if (!isEnabled()) {
            return
        }
        AuctionRepository.initialize()
        AuctionDeliveryRepository.initialize()
        val dataFolder = ConfigFiles.dataFolder()
        settings = loadSettings()
        menus = AuctionMenus(
            auctionViews = ShopMenuLoader.load("Auction", "auction.yml"),
            upload = MenuLoader.load(File(dataFolder, "Auction/ui/upload.yml")),
            detail = MenuLoader.load(File(dataFolder, "Auction/ui/detail.yml")),
            bid = MenuLoader.load(File(dataFolder, "Auction/ui/bid.yml")),
            manage = MenuLoader.load(File(dataFolder, "Auction/ui/manage.yml")),
            bids = MenuLoader.load(File(dataFolder, "Auction/ui/bids.yml"))
        )
        cleanupExpiredListings()
    }

    fun openAuction(player: Player, page: Int = 1) {
        openAuction(player, null, page)
    }

    fun openAuction(player: Player, shopId: String?, page: Int = 1) {
        if (!ensureReady(player)) {
            return
        }
        deliverPending(player)
        cleanupExpiredListings()
        val selectedMenu = ShopMenuLoader.resolve(menus.auctionViews, shopId)
        val browseMenu = selectedMenu.definition
        val listings = activeListings(selectedMenu.id)
        val goodsSlots = goodsSlots(browseMenu).size.coerceAtLeast(1)
        val maxPage = ((listings.size + goodsSlots - 1) / goodsSlots).coerceAtLeast(1)
        val currentPage = page.coerceIn(1, maxPage)
        val entries = listings.drop((currentPage - 1) * goodsSlots).take(goodsSlots)
        val placeholders = mapOf(
            "page" to currentPage.toString(),
            "max-page" to maxPage.toString(),
            "shop-id" to selectedMenu.id
        )
        MenuRenderer.open(
            player = player,
            definition = browseMenu,
            placeholders = placeholders,
            goodsRenderer = { holder, slots ->
                renderAuctionEntries(player, holder, entries, slots, selectedMenu.id)
                wireAuctionControls(player, holder, browseMenu, selectedMenu.id, currentPage, maxPage)
            }
        )
    }

    fun hasAuctionView(shopId: String?): Boolean {
        return ShopMenuLoader.contains(menus.auctionViews, shopId)
    }

    fun resolveBoundShop(token: String?): String? {
        return ShopMenuLoader.resolveByBinding(menus.auctionViews, token)?.id
    }

    fun helpEntries(): List<ShopMenuSelection> {
        return ShopMenuLoader.helpEntries(menus.auctionViews)
    }

    fun allShopEntries(): List<ShopMenuSelection> {
        return ShopMenuLoader.allEntries(menus.auctionViews)
    }

    fun standaloneEntries(): List<ShopMenuSelection> {
        return ShopMenuLoader.standaloneEntries(menus.auctionViews)
    }

    fun openUpload(player: Player) {
        openUpload(player, null)
    }

    fun openUpload(player: Player, shopId: String?) {
        if (!ensureReady(player)) {
            return
        }
        val resolvedShopId = resolveShopId(shopId)
        val hand = player.inventory.itemInMainHand ?: ItemStack(Material.AIR)
        MenuRenderer.open(
            player = player,
            definition = menus.upload,
            placeholders = mapOf(
                "command" to CommandUsageContext.modulePrefix(player, "auction", "/auction"),
                "hint-upload-english" to Texts.tr(
                    ModuleBindings.hintKey("auction", "upload-english") ?: "@commands.hints.auction-upload-english",
                    mapOf("command" to "${CommandUsageContext.modulePrefix(player, "auction", "/auction")} upload english")
                ),
                "hint-upload-dutch" to Texts.tr(
                    ModuleBindings.hintKey("auction", "upload-dutch") ?: "@commands.hints.auction-upload-dutch",
                    mapOf("command" to "${CommandUsageContext.modulePrefix(player, "auction", "/auction")} upload dutch")
                ),
                "item" to itemDisplayName(hand),
                "default-duration" to settings.defaultDuration.toString(),
                "min-duration" to settings.minDuration.toString(),
                "max-duration" to settings.maxDuration.toString(),
                "shop-id" to resolvedShopId
            ),
            backAction = { openAuction(player, resolvedShopId) },
            goodsRenderer = { holder, _ ->
                buttonSlot(menus.upload, 'i')?.let { slot ->
                    holder.backingInventory.setItem(slot, if (hand.type == Material.AIR) ItemStack(Material.BARRIER) else hand.clone())
                }
            }
        )
    }

    fun openManage(player: Player, page: Int = 1) {
        openManage(player, null, page)
    }

    fun openManage(player: Player, shopId: String?, page: Int = 1) {
        if (!ensureReady(player)) {
            return
        }
        cleanupExpiredListings()
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
                "size" to listings.size.toString(),
                "shop-id" to resolvedShopId
            ),
            backAction = { openAuction(player, resolvedShopId) },
            goodsRenderer = { holder, slots ->
                renderManageEntries(player, holder, entries, slots, resolvedShopId)
                wireManageControls(player, holder, resolvedShopId, currentPage, maxPage)
            }
        )
    }

    fun openBids(player: Player, page: Int = 1) {
        openBids(player, null, page)
    }

    fun openBids(player: Player, shopId: String?, page: Int = 1) {
        if (!ensureReady(player)) {
            return
        }
        cleanupExpiredListings()
        val resolvedShopId = resolveShopId(shopId)
        val listings = activeListings(resolvedShopId).filter { listing ->
            listing.bidHistory.any { it.bidderId == player.uniqueId }
        }
        val goodsSlots = goodsSlots(menus.bids).size.coerceAtLeast(1)
        val maxPage = ((listings.size + goodsSlots - 1) / goodsSlots).coerceAtLeast(1)
        val currentPage = page.coerceIn(1, maxPage)
        val entries = listings.drop((currentPage - 1) * goodsSlots).take(goodsSlots)
        MenuRenderer.open(
            player = player,
            definition = menus.bids,
            placeholders = mapOf(
                "page" to currentPage.toString(),
                "max-page" to maxPage.toString(),
                "size" to listings.size.toString(),
                "shop-id" to resolvedShopId
            ),
            backAction = { openAuction(player, resolvedShopId) },
            goodsRenderer = { holder, slots ->
                renderBidEntries(player, holder, entries, slots, resolvedShopId)
                wireBidsControls(player, holder, resolvedShopId, currentPage, maxPage)
            }
        )
    }

    fun openDetail(player: Player, listingId: String, source: String = "auction") {
        openDetail(player, null, listingId, source)
    }

    fun openDetail(player: Player, shopId: String?, listingId: String, source: String = "auction") {
        if (!ensureReady(player)) {
            return
        }
        cleanupExpiredListings()
        val listing = activeListings(shopId).firstOrNull { it.id == listingId }
        if (listing == null) {
            Texts.send(player, "&cAuction listing not found.")
            return
        }
        val placeholders = detailPlaceholders(listing)
        MenuRenderer.open(
            player = player,
            definition = menus.detail,
            placeholders = placeholders,
            backAction = { reopenSource(player, source) },
            goodsRenderer = { holder, _ ->
                buttonSlot(menus.detail, 'i')?.let { slot ->
                    holder.backingInventory.setItem(slot, buildDisplayItem(listing, detailLore(listing, player)))
                }
                wireDetailControls(player, holder, listing, source)
            }
        )
    }

    fun openBidMenu(player: Player, listingId: String) {
        openBidMenu(player, null, listingId)
    }

    fun openBidMenu(player: Player, shopId: String?, listingId: String) {
        if (!ensureReady(player)) {
            return
        }
        cleanupExpiredListings()
        val listing = activeListings(shopId).firstOrNull { it.id == listingId }
        if (listing == null) {
            Texts.send(player, "&cAuction listing not found.")
            return
        }
        if (listing.mode != AuctionMode.ENGLISH) {
            Texts.send(player, "&cDutch auctions do not use the bid menu. Use buyout/current-price purchase instead.")
            return
        }
        val suggestions = bidSuggestions(listing)
        val suggestionPlaceholders = suggestions.mapIndexed { index, value ->
            "bid-${index + 1}" to trimDouble(value)
        }.toMap()
        val placeholders = detailPlaceholders(listing) + suggestionPlaceholders
        MenuRenderer.open(
            player = player,
            definition = menus.bid,
            placeholders = placeholders + mapOf(
                "command" to CommandUsageContext.modulePrefix(player, "auction", "/auction"),
                "hint-bid" to Texts.tr(
                    ModuleBindings.hintKey("auction", "bid") ?: "@commands.hints.auction-bid",
                    mapOf("command" to "${CommandUsageContext.modulePrefix(player, "auction", "/auction")} bid")
                )
            ),
            backAction = { openDetail(player, listing.shopId, listing.id) },
            goodsRenderer = { holder, _ ->
                buttonSlot(menus.bid, 'i')?.let { slot ->
                    holder.backingInventory.setItem(slot, buildDisplayItem(listing, detailLore(listing, player)))
                }
                wireBidControls(player, holder, listing, suggestions)
            }
        )
    }

    fun uploadFromHand(player: Player, modeRaw: String?, startPrice: Double?, secondPrice: Double?, durationSeconds: Int?) {
        uploadFromHand(player, null, modeRaw, startPrice, secondPrice, durationSeconds)
    }

    fun uploadFromHand(player: Player, shopId: String?, modeRaw: String?, startPrice: Double?, secondPrice: Double?, durationSeconds: Int?) {
        if (!ensureReady(player)) {
            return
        }
        cleanupExpiredListings()
        val resolvedShopId = resolveShopId(shopId)
        val mode = parseMode(modeRaw)
        if (mode == null) {
            Texts.send(player, "&cUsage: ${CommandUsageContext.modulePrefix(player, "auction", "/auction")} upload <english|dutch> <start-price> [buyout|end-price] [duration-seconds]")
            return
        }
        val hand = player.inventory.itemInMainHand ?: ItemStack(Material.AIR)
        if (hand.type == Material.AIR || hand.amount <= 0) {
            Texts.send(player, "&cHold the item you want to list in your main hand.")
            return
        }
        val listings = AuctionRepository.loadAll().toMutableList()
        if (listings.count { it.ownerId == player.uniqueId } >= settings.maxActive) {
            Texts.send(player, "&cYou already reached the maximum active auction listings.")
            return
        }
        val safeStart = (startPrice ?: 0.0).coerceAtLeast(0.0)
        val safeDuration = (durationSeconds ?: settings.defaultDuration).coerceIn(settings.minDuration, settings.maxDuration)
        if (mode == AuctionMode.ENGLISH && safeStart < settings.englishMinStartPrice) {
            Texts.send(player, "&cStart price is below the minimum for English auctions.")
            return
        }
        if (mode == AuctionMode.DUTCH && safeStart < settings.dutchMinStartPrice) {
            Texts.send(player, "&cStart price is below the minimum for Dutch auctions.")
            return
        }
        val buyoutPrice = if (mode == AuctionMode.ENGLISH && settings.englishAllowBuyout) (secondPrice ?: 0.0).coerceAtLeast(0.0) else 0.0
        val endPrice = if (mode == AuctionMode.DUTCH) (secondPrice ?: settings.dutchEndPriceMin).coerceAtLeast(settings.dutchEndPriceMin) else 0.0
        if (mode == AuctionMode.ENGLISH && buyoutPrice > 0 && buyoutPrice <= safeStart) {
            Texts.send(player, "&cBuyout price must be higher than the start price.")
            return
        }
        if (mode == AuctionMode.DUTCH && endPrice >= safeStart) {
            Texts.send(player, "&cDutch end price must be lower than the start price.")
            return
        }
        val deposit = listingFee(safeStart, settings.depositMode, settings.depositValue).takeIf { settings.depositEnabled } ?: 0.0
        if (deposit > 0 && !VaultEconomyBridge.isAvailable()) {
            Texts.send(player, "&cVault economy is required for auction deposits.")
            return
        }
        if (deposit > 0 && !VaultEconomyBridge.has(player, deposit)) {
            Texts.send(player, "&cYou need &e${trimDouble(deposit)} &cto pay the listing deposit.")
            return
        }
        if (deposit > 0 && !VaultEconomyBridge.withdraw(player, deposit)) {
            Texts.send(player, "&cFailed to charge the listing deposit.")
            return
        }
        val listingItem = hand.clone()
        player.inventory.itemInMainHand = ItemStack(Material.AIR)
        val now = System.currentTimeMillis()
        val listing = AuctionListing(
            id = nextListingId(),
            shopId = resolvedShopId,
            ownerId = player.uniqueId,
            ownerName = player.name,
            mode = mode,
            item = listingItem,
            startPrice = safeStart,
            buyoutPrice = buyoutPrice,
            endPrice = endPrice,
            currentBid = 0.0,
            createdAt = now,
            expireAt = now + safeDuration * 1000L,
            depositPaid = deposit
        )
        listings += listing
        AuctionRepository.saveAll(listings)
        player.updateInventory()
        Texts.send(player, "&aAuction listed in &f${listing.shopId}&a: &f${itemDisplayName(listingItem)} &7- &e${trimDouble(safeStart)}")
        if (settings.recordWriteOnCreate) {
            RecordService.append(
                module = "auction",
                type = "create",
                actor = player.name,
                moneyChange = -deposit,
                detail = "shop=${listing.shopId};listing=${listing.id};mode=${listing.mode.name};start=${trimDouble(listing.startPrice)};buyout=${trimDouble(listing.buyoutPrice)};end=${trimDouble(listing.endPrice)};duration=$safeDuration;deposit=${trimDouble(deposit)}"
            )
        }
    }

    fun bid(player: Player, listingId: String, amount: Double?) {
        bid(player, null, listingId, amount)
    }

    fun bid(player: Player, shopId: String?, listingId: String, amount: Double?) {
        if (!ensureReady(player)) {
            return
        }
        cleanupExpiredListings()
        val resolvedShopId = resolveShopId(shopId)
        val listings = AuctionRepository.loadAll().toMutableList()
        val listing = listings.firstOrNull { it.id == listingId && it.shopId.equals(resolvedShopId, true) }
        if (listing == null) {
            Texts.send(player, "&cAuction listing not found.")
            return
        }
        if (listing.ownerId == player.uniqueId) {
            Texts.send(player, "&cYou cannot bid on your own auction.")
            return
        }
        if (listing.mode == AuctionMode.DUTCH) {
            buyout(player, resolvedShopId, listingId)
            return
        }
        val offered = amount ?: nextMinimumBid(listing)
        val minimum = nextMinimumBid(listing)
        if (offered < minimum) {
            Texts.send(player, "&cYour bid must be at least &e${trimDouble(minimum)}&c.")
            return
        }
        if (!VaultEconomyBridge.isAvailable()) {
            Texts.send(player, "&cVault economy is required for auction bidding.")
            return
        }
        if (!VaultEconomyBridge.has(player, offered)) {
            Texts.send(player, "&cYou do not have enough balance for this bid.")
            return
        }
        if (!VaultEconomyBridge.withdraw(player, offered)) {
            Texts.send(player, "&cFailed to reserve your bid money.")
            return
        }
        refundPreviousBidder(listing)
        listing.currentBid = offered
        listing.highestBidderId = player.uniqueId
        listing.highestBidderName = player.name
        listing.bidHistory += AuctionBidEntry(player.uniqueId, player.name, offered, System.currentTimeMillis())
        applySnipeProtection(listing)
        AuctionRepository.saveAll(listings)
        Texts.send(player, "&aBid placed successfully at &e${trimDouble(offered)}&a.")
        Bukkit.getPlayer(listing.ownerId)?.let {
            Texts.send(it, "&e${player.name} placed a bid on your auction &f${listing.id}&e.")
        }
        if (settings.recordWriteOnBid) {
            RecordService.append(
                module = "auction",
                type = "bid",
                actor = player.name,
                target = listing.ownerName,
                moneyChange = -offered,
                detail = "shop=${listing.shopId};listing=${listing.id};amount=${trimDouble(offered)}"
            )
        }
    }

    fun buyout(player: Player, listingId: String) {
        buyout(player, null, listingId)
    }

    fun buyout(player: Player, shopId: String?, listingId: String) {
        if (!ensureReady(player)) {
            return
        }
        cleanupExpiredListings()
        val resolvedShopId = resolveShopId(shopId)
        val listings = AuctionRepository.loadAll().toMutableList()
        val listing = listings.firstOrNull { it.id == listingId && it.shopId.equals(resolvedShopId, true) }
        if (listing == null) {
            Texts.send(player, "&cAuction listing not found.")
            return
        }
        if (listing.ownerId == player.uniqueId) {
            Texts.send(player, "&cYou cannot buy your own auction.")
            return
        }
        val price = when (listing.mode) {
            AuctionMode.DUTCH -> currentDutchPrice(listing)
            AuctionMode.ENGLISH -> listing.buyoutPrice.takeIf { it > 0 } ?: run {
                Texts.send(player, "&cThis auction has no buyout price.")
                return
            }
        }
        if (!VaultEconomyBridge.isAvailable()) {
            Texts.send(player, "&cVault economy is required for auction purchases.")
            return
        }
        if (!VaultEconomyBridge.has(player, price)) {
            Texts.send(player, "&cYou do not have enough balance.")
            return
        }
        if (!VaultEconomyBridge.withdraw(player, price)) {
            Texts.send(player, "&cFailed to charge the auction price.")
            return
        }
        refundPreviousBidder(listing)
        completeSale(listing, player.uniqueId, player.name, price, listings, if (listing.mode == AuctionMode.DUTCH) "dutch" else "buyout")
        Texts.send(player, "&aAuction purchased for &e${trimDouble(price)}&a.")
    }

    fun remove(player: Player, listingId: String) {
        remove(player, null, listingId)
    }

    fun remove(player: Player, shopId: String?, listingId: String) {
        if (!ensureReady(player)) {
            return
        }
        cleanupExpiredListings()
        val resolvedShopId = resolveShopId(shopId)
        val listings = AuctionRepository.loadAll().toMutableList()
        val listing = listings.firstOrNull { it.id == listingId && it.shopId.equals(resolvedShopId, true) }
        if (listing == null) {
            Texts.send(player, "&cAuction listing not found.")
            return
        }
        if (listing.ownerId != player.uniqueId) {
            Texts.send(player, "&cYou can only remove your own listings.")
            return
        }
        if (!settings.ownerCancelAllow) {
            Texts.send(player, "&cOwner cancel is disabled on this server.")
            return
        }
        if (settings.ownerCancelDenyIfHasBid && listing.highestBidderId != null) {
            Texts.send(player, "&cYou cannot remove an auction that already has bids.")
            return
        }
        listings.removeIf { it.id == listing.id }
        AuctionRepository.saveAll(listings)
        deliverItemOrQueue(player.uniqueId, player.name, listing.item.clone(), "Cancelled auction returned item ${listing.id}.")
        if (listing.highestBidderId != null) {
            refundPreviousBidder(listing)
        }
        if (settings.depositRefundOnCancel && listing.depositPaid > 0) {
            deliverMoneyOrQueue(player.uniqueId, player.name, listing.depositPaid, "Cancelled auction deposit refunded for ${listing.id}.")
        }
        Texts.send(player, "&aAuction removed and returned.")
        if (settings.recordWriteOnCancel) {
            RecordService.append(
                module = "auction",
                type = "cancel",
                actor = player.name,
                detail = "shop=${listing.shopId};listing=${listing.id};mode=${listing.mode.name};deposit-refund=${trimDouble(if (settings.depositRefundOnCancel) listing.depositPaid else 0.0)}"
            )
        }
    }

    fun deliverPending(player: Player) {
        if (!::settings.isInitialized) {
            return
        }
        val allEntries = AuctionDeliveryRepository.loadAll().toMutableList()
        val remaining = mutableListOf<AuctionDeliveryEntry>()
        allEntries.forEach { entry ->
            if (entry.ownerId != player.uniqueId) {
                remaining += entry
                return@forEach
            }
            var delivered = true
            if (entry.money > 0) {
                if (!VaultEconomyBridge.isAvailable() || !VaultEconomyBridge.deposit(player, entry.money)) {
                    delivered = false
                }
            }
            if (delivered && entry.item != null) {
                player.inventory.addItem(entry.item.clone()).values.forEach {
                    player.world.dropItemNaturally(player.location, it)
                }
            }
            if (delivered) {
                Texts.send(player, "&a${entry.message}")
            } else {
                remaining += entry
            }
        }
        AuctionDeliveryRepository.saveAll(remaining)
    }

    private fun renderAuctionEntries(player: Player, holder: MatrixMenuHolder, entries: List<AuctionListing>, slots: List<Int>, shopId: String) {
        entries.forEachIndexed { index, listing ->
            val slot = slots[index]
            val item = buildDisplayItem(listing, listLore(listing, player))
            holder.backingInventory.setItem(slot, item)
            holder.handlers[slot] = {
                openDetail(player, shopId, listing.id, "auction:$shopId")
            }
        }
    }

    private fun renderManageEntries(player: Player, holder: MatrixMenuHolder, entries: List<AuctionListing>, slots: List<Int>, shopId: String) {
        entries.forEachIndexed { index, listing ->
            val slot = slots[index]
            val item = buildDisplayItem(listing, listLore(listing, player) + Texts.color("&cRight click to remove"))
            holder.backingInventory.setItem(slot, item)
            holder.handlers[slot] = { event ->
                if (event.click.isRightClick) {
                    remove(player, shopId, listing.id)
                    openManage(player, shopId)
                } else {
                    openDetail(player, shopId, listing.id, "manage:$shopId")
                }
            }
        }
    }

    private fun renderBidEntries(player: Player, holder: MatrixMenuHolder, entries: List<AuctionListing>, slots: List<Int>, shopId: String) {
        entries.forEachIndexed { index, listing ->
            val slot = slots[index]
            val item = buildDisplayItem(listing, listLore(listing, player))
            holder.backingInventory.setItem(slot, item)
            holder.handlers[slot] = {
                openDetail(player, shopId, listing.id, "bids:$shopId")
            }
        }
    }

    private fun wireAuctionControls(player: Player, holder: MatrixMenuHolder, definition: MenuDefinition, shopId: String, currentPage: Int, maxPage: Int) {
        buttonSlot(definition, 'P')?.let { holder.handlers[it] = { openAuction(player, shopId, (currentPage - 1).coerceAtLeast(1)) } }
        buttonSlot(definition, 'N')?.let { holder.handlers[it] = { openAuction(player, shopId, (currentPage + 1).coerceAtMost(maxPage)) } }
        buttonSlot(definition, 'U')?.let { holder.handlers[it] = { openUpload(player, shopId) } }
        buttonSlot(definition, 'M')?.let { holder.handlers[it] = { openManage(player, shopId) } }
        buttonSlot(definition, 'B')?.let { holder.handlers[it] = { openBids(player, shopId) } }
    }

    private fun wireManageControls(player: Player, holder: MatrixMenuHolder, shopId: String, currentPage: Int, maxPage: Int) {
        buttonSlot(menus.manage, 'P')?.let { holder.handlers[it] = { openManage(player, shopId, (currentPage - 1).coerceAtLeast(1)) } }
        buttonSlot(menus.manage, 'N')?.let { holder.handlers[it] = { openManage(player, shopId, (currentPage + 1).coerceAtMost(maxPage)) } }
        buttonSlot(menus.manage, 'R')?.let { holder.handlers[it] = { openAuction(player, shopId) } }
        buttonSlot(menus.manage, 'I')?.let { holder.handlers[it] = { openUpload(player, shopId) } }
    }

    private fun wireBidsControls(player: Player, holder: MatrixMenuHolder, shopId: String, currentPage: Int, maxPage: Int) {
        buttonSlot(menus.bids, 'P')?.let { holder.handlers[it] = { openBids(player, shopId, (currentPage - 1).coerceAtLeast(1)) } }
        buttonSlot(menus.bids, 'N')?.let { holder.handlers[it] = { openBids(player, shopId, (currentPage + 1).coerceAtMost(maxPage)) } }
        buttonSlot(menus.bids, 'R')?.let { holder.handlers[it] = { openAuction(player, shopId) } }
    }

    private fun wireDetailControls(player: Player, holder: MatrixMenuHolder, listing: AuctionListing, source: String) {
        buttonSlot(menus.detail, 'B')?.let { slot ->
            holder.handlers[slot] = {
                if (listing.mode == AuctionMode.DUTCH) {
                    buyout(player, listing.shopId, listing.id)
                } else {
                    openBidMenu(player, listing.shopId, listing.id)
                }
            }
        }
        buttonSlot(menus.detail, 'T')?.let { slot ->
            holder.handlers[slot] = {
                buyout(player, listing.shopId, listing.id)
            }
        }
        buttonSlot(menus.detail, 'M')?.let { slot ->
            if (listing.ownerId == player.uniqueId) {
                holder.handlers[slot] = {
                    remove(player, listing.shopId, listing.id)
                    reopenSource(player, source)
                }
            }
        }
        buttonSlot(menus.detail, 'R')?.let { slot ->
            holder.handlers[slot] = {
                reopenSource(player, source)
            }
        }
    }

    private fun wireBidControls(player: Player, holder: MatrixMenuHolder, listing: AuctionListing, suggestions: List<Double>) {
        val keys = listOf('1', '2', '3', '4', '5', '6')
        keys.forEachIndexed { index, symbol ->
            buttonSlot(menus.bid, symbol)?.let { slot ->
                holder.handlers[slot] = {
                    bid(player, listing.shopId, listing.id, suggestions.getOrNull(index))
                    openDetail(player, listing.shopId, listing.id)
                }
            }
        }
        buttonSlot(menus.bid, 'A')?.let { slot ->
            holder.handlers[slot] = {
                bid(player, listing.shopId, listing.id, nextMinimumBid(listing))
                openDetail(player, listing.shopId, listing.id)
            }
        }
        buttonSlot(menus.bid, 'C')?.let { slot ->
            holder.handlers[slot] = {
                buyout(player, listing.shopId, listing.id)
                openAuction(player, listing.shopId)
            }
        }
        buttonSlot(menus.bid, 'R')?.let { slot ->
            holder.handlers[slot] = {
                openDetail(player, listing.shopId, listing.id)
            }
        }
    }

    private fun completeSale(
        listing: AuctionListing,
        buyerId: UUID,
        buyerName: String,
        finalPrice: Double,
        listings: MutableList<AuctionListing>,
        cause: String
    ) {
        listings.removeIf { it.id == listing.id }
        AuctionRepository.saveAll(listings)
        val tax = taxAmount(finalPrice)
        val sellerIncome = (finalPrice - tax).coerceAtLeast(0.0)
        if (listing.depositPaid > 0 && settings.depositRefundOnSell) {
            deliverMoneyOrQueue(listing.ownerId, listing.ownerName, listing.depositPaid, "Auction deposit refunded for ${listing.id}.")
        }
        deliverMoneyOrQueue(listing.ownerId, listing.ownerName, sellerIncome, "Auction ${listing.id} sold to $buyerName for ${trimDouble(finalPrice)}.")
        deliverItemOrQueue(buyerId, buyerName, listing.item.clone(), "Auction ${listing.id} item delivered.")
        if (settings.recordWriteOnComplete) {
            RecordService.append(
                module = "auction",
                type = "purchase",
                actor = buyerName,
                target = listing.ownerName,
                moneyChange = -finalPrice,
                detail = "shop=${listing.shopId};listing=${listing.id};mode=${listing.mode.name};price=${trimDouble(finalPrice)};cause=$cause"
            )
            RecordService.append(
                module = "auction",
                type = "sale",
                actor = listing.ownerName,
                target = buyerName,
                moneyChange = sellerIncome + if (settings.depositRefundOnSell) listing.depositPaid else 0.0,
                detail = "shop=${listing.shopId};listing=${listing.id};mode=${listing.mode.name};price=${trimDouble(finalPrice)};tax=${trimDouble(tax)};cause=$cause"
            )
        }
    }

    private fun cleanupExpiredListings() {
        if (!::settings.isInitialized) {
            return
        }
        val listings = AuctionRepository.loadAll().toMutableList()
        val expired = listings.filter { it.expireAt > 0 && it.expireAt <= System.currentTimeMillis() }
        if (expired.isEmpty()) {
            return
        }
        expired.forEach { listing ->
            if (listing.mode == AuctionMode.ENGLISH && listing.highestBidderId != null && listing.currentBid > 0) {
                completeSale(
                    listing = listing,
                    buyerId = listing.highestBidderId!!,
                    buyerName = listing.highestBidderName,
                    finalPrice = listing.currentBid,
                    listings = listings,
                    cause = "expire"
                )
            } else {
                listings.removeIf { it.id == listing.id }
                deliverItemOrQueue(listing.ownerId, listing.ownerName, listing.item.clone(), "Auction ${listing.id} expired without sale. Item returned.")
                if (settings.recordWriteOnCancel) {
                    RecordService.append(
                        module = "auction",
                        type = "expire",
                        actor = listing.ownerName,
                        detail = "shop=${listing.shopId};listing=${listing.id};mode=${listing.mode.name};highest=${trimDouble(listing.currentBid)}"
                    )
                }
            }
        }
        AuctionRepository.saveAll(listings)
    }

    private fun refundPreviousBidder(listing: AuctionListing) {
        val bidderId = listing.highestBidderId ?: return
        if (listing.currentBid <= 0) {
            return
        }
        deliverMoneyOrQueue(bidderId, listing.highestBidderName, listing.currentBid, "Auction ${listing.id} refunded your previous highest bid.")
    }

    private fun deliverMoneyOrQueue(ownerId: UUID, ownerName: String, amount: Double, message: String) {
        if (amount <= 0) {
            return
        }
        val player = Bukkit.getPlayer(ownerId)
        if (player != null && player.isOnline && VaultEconomyBridge.isAvailable() && VaultEconomyBridge.deposit(player, amount)) {
            Texts.send(player, "&a$message")
            return
        }
        val deliveries = AuctionDeliveryRepository.loadAll().toMutableList()
        deliveries += AuctionDeliveryEntry(
            id = nextDeliveryId(),
            ownerId = ownerId,
            ownerName = ownerName,
            money = amount,
            message = message
        )
        AuctionDeliveryRepository.saveAll(deliveries)
    }

    private fun deliverItemOrQueue(ownerId: UUID, ownerName: String, item: ItemStack, message: String) {
        val player = Bukkit.getPlayer(ownerId)
        if (player != null && player.isOnline) {
            player.inventory.addItem(item.clone()).values.forEach {
                player.world.dropItemNaturally(player.location, it)
            }
            Texts.send(player, "&a$message")
            return
        }
        val deliveries = AuctionDeliveryRepository.loadAll().toMutableList()
        deliveries += AuctionDeliveryEntry(
            id = nextDeliveryId(),
            ownerId = ownerId,
            ownerName = ownerName,
            item = item,
            message = message
        )
        AuctionDeliveryRepository.saveAll(deliveries)
    }

    private fun ensureReady(player: Player): Boolean {
        if (!isEnabled() || !::settings.isInitialized) {
            Texts.send(player, "&cAuction module is disabled.")
            return false
        }
        return true
    }

    private fun activeListings(shopId: String? = null): List<AuctionListing> {
        val resolvedShopId = shopId?.trim()?.takeIf(String::isNotBlank)?.lowercase()
        return AuctionRepository.loadAll()
            .filter { resolvedShopId == null || it.shopId.equals(resolvedShopId, true) }
            .sortedByDescending { it.createdAt }
    }

    private fun loadSettings(): AuctionSettings {
        val yaml = YamlConfiguration.loadConfiguration(File(ConfigFiles.dataFolder(), "Auction/settings.yml"))
        return AuctionSettings(
            maxActive = yaml.getInt("Options.Listing.Max-Active", 20),
            defaultDuration = yaml.getInt("Options.Listing.Duration.Default", 86400),
            minDuration = yaml.getInt("Options.Listing.Duration.Min", 600),
            maxDuration = yaml.getInt("Options.Listing.Duration.Max", 604800),
            englishAllowBuyout = yaml.getBoolean("Options.Listing.Modes.ENGLISH.Allow-Buyout", true),
            englishMinStartPrice = yaml.getDouble("Options.Listing.Modes.ENGLISH.Min-Start-Price", 100.0),
            englishStepMode = yaml.getString("Options.Listing.Modes.ENGLISH.Min-Step.Mode", "percent").orEmpty(),
            englishStepFixed = yaml.getDouble("Options.Listing.Modes.ENGLISH.Min-Step.Fixed", 100.0),
            englishStepPercent = yaml.getDouble("Options.Listing.Modes.ENGLISH.Min-Step.Percent", 5.0),
            dutchMinStartPrice = yaml.getDouble("Options.Listing.Modes.DUTCH.Min-Start-Price", 100.0),
            dutchEndPriceMin = yaml.getDouble("Options.Listing.Modes.DUTCH.End-Price-Min", 1.0),
            dutchTickSeconds = yaml.getInt("Options.Listing.Modes.DUTCH.Tick-Seconds", 60),
            depositEnabled = yaml.getBoolean("Options.Listing.Deposit.Enabled", true),
            depositMode = yaml.getString("Options.Listing.Deposit.Mode", "percent").orEmpty(),
            depositValue = yaml.getDouble("Options.Listing.Deposit.Value", 5.0),
            depositRefundOnSell = yaml.getBoolean("Options.Listing.Deposit.Refund-On-Sell", true),
            depositRefundOnCancel = yaml.getBoolean("Options.Listing.Deposit.Refund-On-Cancel", false),
            taxEnabled = yaml.getBoolean("Options.Listing.Tax.Enabled", true),
            taxMode = yaml.getString("Options.Listing.Tax.Mode", "percent").orEmpty(),
            taxValue = yaml.getDouble("Options.Listing.Tax.Value", 3.0),
            snipeEnabled = yaml.getBoolean("Options.Snipe-Protection.Enabled", true),
            snipeTriggerSeconds = yaml.getInt("Options.Snipe-Protection.Trigger-Seconds", 30),
            snipeExtendSeconds = yaml.getInt("Options.Snipe-Protection.Extend-Seconds", 30),
            snipeMaxExtendTimes = yaml.getInt("Options.Snipe-Protection.Max-Extend-Times", 5),
            ownerCancelAllow = yaml.getBoolean("Options.Cancel.Owner.Allow", true),
            ownerCancelDenyIfHasBid = yaml.getBoolean("Options.Cancel.Owner.Deny-If-Has-Bid", true),
            recordWriteOnCreate = yaml.getBoolean("Options.Record.Write-On-Create", true),
            recordWriteOnBid = yaml.getBoolean("Options.Record.Write-On-Bid", false),
            recordWriteOnComplete = yaml.getBoolean("Options.Record.Write-On-Complete", true),
            recordWriteOnCancel = yaml.getBoolean("Options.Record.Write-On-Cancel", true)
        )
    }

    private fun detailPlaceholders(listing: AuctionListing): Map<String, String> {
        return mapOf(
            "id" to listing.id,
            "shop-id" to listing.shopId,
            "mode" to listing.mode.name,
            "owner" to listing.ownerName,
            "current-price" to trimDouble(currentPrice(listing)),
            "next-bid" to trimDouble(nextMinimumBid(listing)),
            "buyout" to trimDouble(listing.buyoutPrice),
            "remain" to remainingText(listing),
            "highest-bidder" to listing.highestBidderName.ifBlank { "none" }
        )
    }

    private fun currentPrice(listing: AuctionListing): Double {
        return when (listing.mode) {
            AuctionMode.ENGLISH -> if (listing.currentBid > 0) listing.currentBid else listing.startPrice
            AuctionMode.DUTCH -> currentDutchPrice(listing)
        }
    }

    private fun currentDutchPrice(listing: AuctionListing): Double {
        val durationMillis = (listing.expireAt - listing.createdAt).coerceAtLeast(1L)
        val elapsedMillis = (System.currentTimeMillis() - listing.createdAt).coerceAtLeast(0L)
        val ticks = (elapsedMillis / (settings.dutchTickSeconds.coerceAtLeast(1) * 1000L)).toInt()
        val maxTicks = (durationMillis / (settings.dutchTickSeconds.coerceAtLeast(1) * 1000L)).coerceAtLeast(1L).toInt()
        val totalDrop = (listing.startPrice - listing.endPrice).coerceAtLeast(0.0)
        val perTick = if (maxTicks <= 0) totalDrop else totalDrop / maxTicks.toDouble()
        return (listing.startPrice - perTick * ticks).coerceAtLeast(listing.endPrice)
    }

    private fun nextMinimumBid(listing: AuctionListing): Double {
        if (listing.mode == AuctionMode.DUTCH) {
            return currentDutchPrice(listing)
        }
        if (listing.currentBid <= 0.0) {
            return listing.startPrice
        }
        return listing.currentBid + stepAmount(listing.currentBid)
    }

    private fun stepAmount(base: Double): Double {
        return when (settings.englishStepMode.lowercase()) {
            "fixed" -> settings.englishStepFixed
            "percent" -> (base * settings.englishStepPercent / 100.0).coerceAtLeast(0.01)
            else -> maxOf(settings.englishStepFixed, base * settings.englishStepPercent / 100.0)
        }
    }

    private fun bidSuggestions(listing: AuctionListing): List<Double> {
        val start = nextMinimumBid(listing)
        val step = stepAmount(start)
        return listOf(
            start,
            start + step,
            start + step * 2,
            start + step * 3,
            start + step * 5,
            start + step * 8
        )
    }

    private fun applySnipeProtection(listing: AuctionListing) {
        if (!settings.snipeEnabled) {
            return
        }
        val remain = listing.expireAt - System.currentTimeMillis()
        if (remain <= settings.snipeTriggerSeconds * 1000L && listing.extendCount < settings.snipeMaxExtendTimes) {
            listing.expireAt += settings.snipeExtendSeconds * 1000L
            listing.extendCount += 1
        }
    }

    private fun taxAmount(finalPrice: Double): Double {
        if (!settings.taxEnabled) {
            return 0.0
        }
        return listingFee(finalPrice, settings.taxMode, settings.taxValue)
    }

    private fun listingFee(base: Double, mode: String, value: Double): Double {
        return when (mode.lowercase()) {
            "fixed" -> value
            "percent" -> base * value / 100.0
            else -> value
        }.coerceAtLeast(0.0)
    }

    private fun parseMode(raw: String?): AuctionMode? {
        return when (raw?.lowercase()) {
            "english", "en" -> AuctionMode.ENGLISH
            "dutch", "du" -> AuctionMode.DUTCH
            else -> null
        }
    }

    private fun reopenSource(player: Player, source: String) {
        when {
            source.startsWith("auction:", true) -> openAuction(player, source.substringAfter(':'))
            source.startsWith("manage:", true) -> openManage(player, source.substringAfter(':'))
            source.startsWith("bids:", true) -> openBids(player, source.substringAfter(':'))
            source.equals("manage", true) -> openManage(player)
            source.equals("bids", true) -> openBids(player)
            else -> openAuction(player)
        }
    }

    private fun resolveShopId(shopId: String?): String {
        return ShopMenuLoader.resolve(menus.auctionViews, shopId).id
    }

    private fun buildDisplayItem(listing: AuctionListing, lore: List<String>): ItemStack {
        val item = listing.item.clone()
        item.itemMeta = item.itemMeta?.apply {
            MenuRenderer.decorate(this, displayTitle(listing), lore)
        }
        return item
    }

    private fun displayTitle(listing: AuctionListing): String {
        return Texts.color("&f${itemDisplayName(listing.item)}")
    }

    private fun listLore(listing: AuctionListing, player: Player): List<String> {
        val lore = mutableListOf(
            Texts.color("&7Mode: &f${listing.mode.name}"),
            Texts.color("&7Owner: &f${listing.ownerName}"),
            Texts.color("&7Current: &e${trimDouble(currentPrice(listing))}"),
            Texts.color("&7Remaining: &f${remainingText(listing)}")
        )
        if (listing.mode == AuctionMode.ENGLISH) {
            lore += Texts.color("&7Next min: &e${trimDouble(nextMinimumBid(listing))}")
            if (listing.buyoutPrice > 0) {
                lore += Texts.color("&7Buyout: &e${trimDouble(listing.buyoutPrice)}")
            }
            lore += Texts.color("&7Highest bidder: &f${listing.highestBidderName.ifBlank { "none" }}")
        }
        lore += Texts.color(if (listing.ownerId == player.uniqueId) "&eLeft detail / right remove" else "&eLeft detail")
        return lore
    }

    private fun detailLore(listing: AuctionListing, player: Player): List<String> {
        val lore = listLore(listing, player).toMutableList()
        lore += Texts.color("&7ID: &f${listing.id}")
        lore += Texts.color("&7Created: &f${listing.createdAt}")
        if (listing.bidHistory.isNotEmpty()) {
            lore += Texts.color("&7Bids: &f${listing.bidHistory.size}")
        }
        return lore
    }

    private fun remainingText(listing: AuctionListing): String {
        val remain = (listing.expireAt - System.currentTimeMillis()).coerceAtLeast(0L)
        val hours = remain / 3_600_000L
        val minutes = (remain % 3_600_000L) / 60_000L
        val seconds = (remain % 60_000L) / 1000L
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m ${seconds}s"
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

    private fun itemDisplayName(item: ItemStack): String {
        return item.itemMeta?.displayName ?: item.type.name.lowercase().replace('_', ' ')
    }

    private fun nextListingId(): String {
        return "auc-${System.currentTimeMillis().toString(36)}"
    }

    private fun nextDeliveryId(): String {
        return "delivery-${System.currentTimeMillis().toString(36)}-${UUID.randomUUID().toString().take(6)}"
    }

    private fun trimDouble(value: Double): String {
        return if (value % 1.0 == 0.0) value.toInt().toString() else "%.2f".format(value)
    }
}
