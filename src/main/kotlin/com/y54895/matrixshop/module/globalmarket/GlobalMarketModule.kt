package com.y54895.matrixshop.module.globalmarket

import com.y54895.matrixshop.core.config.ConfigFiles
import com.y54895.matrixshop.core.economy.VaultEconomyBridge
import com.y54895.matrixshop.core.menu.MenuLoader
import com.y54895.matrixshop.core.menu.MenuRenderer
import com.y54895.matrixshop.core.menu.MatrixMenuHolder
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
            market = MenuLoader.load(File(ConfigFiles.dataFolder(), "GlobalMarket/ui/market.yml")),
            manage = MenuLoader.load(File(ConfigFiles.dataFolder(), "GlobalMarket/ui/manage.yml")),
            upload = MenuLoader.load(File(ConfigFiles.dataFolder(), "GlobalMarket/ui/upload.yml"))
        )
    }

    fun openMarket(player: Player, page: Int = 1) {
        val listings = activeListings()
        val goodsSlots = goodsSlots(menus.market).size.coerceAtLeast(1)
        val maxPage = ((listings.size + goodsSlots - 1) / goodsSlots).coerceAtLeast(1)
        val currentPage = page.coerceIn(1, maxPage)
        val entries = listings.drop((currentPage - 1) * goodsSlots).take(goodsSlots)
        val placeholders = mapOf(
            "page" to currentPage.toString(),
            "max-page" to maxPage.toString()
        )
        MenuRenderer.open(
            player = player,
            definition = menus.market,
            placeholders = placeholders,
            goodsRenderer = { holder, slots ->
                renderMarket(player, holder, entries, slots)
                wireMarketControls(player, holder, currentPage, maxPage)
            }
        )
    }

    fun openManage(player: Player, page: Int = 1) {
        val listings = activeListings().filter { it.ownerId == player.uniqueId }
        val goodsSlots = goodsSlots(menus.manage).size.coerceAtLeast(1)
        val maxPage = ((listings.size + goodsSlots - 1) / goodsSlots).coerceAtLeast(1)
        val currentPage = page.coerceIn(1, maxPage)
        val entries = listings.drop((currentPage - 1) * goodsSlots).take(goodsSlots)
        val placeholders = mapOf(
            "page" to currentPage.toString(),
            "max-page" to maxPage.toString(),
            "listed" to listings.size.toString()
        )
        MenuRenderer.open(
            player = player,
            definition = menus.manage,
            placeholders = placeholders,
            backAction = { openMarket(player) },
            goodsRenderer = { holder, slots ->
                renderManage(player, holder, entries, slots)
                wireManageControls(player, holder, currentPage, maxPage)
            }
        )
    }

    fun openUpload(player: Player) {
        MenuRenderer.open(player, menus.upload, mapOf("player" to player.name))
    }

    fun uploadFromHand(player: Player, price: Double, requestedAmount: Int?) {
        if (price <= 0) {
            Texts.send(player, "&c价格必须大于 0。")
            return
        }
        val hand = player.inventory.itemInMainHand ?: ItemStack(Material.AIR)
        if (hand.type == Material.AIR || hand.amount <= 0) {
            Texts.send(player, "&c请先把要上架的物品拿在主手。")
            return
        }
        val amount = (requestedAmount ?: hand.amount).coerceAtLeast(1)
        if (amount > hand.amount) {
            Texts.send(player, "&c主手物品数量不足。")
            return
        }
        val listingItem = hand.clone().apply { this.amount = amount }
        val remain = hand.amount - amount
        if (remain <= 0) {
            player.inventory.itemInMainHand = ItemStack(Material.AIR)
        } else {
            hand.amount = remain
            player.inventory.itemInMainHand = hand
        }
        val now = System.currentTimeMillis()
        val listing = GlobalMarketListing(
            id = "gm-${now.toString(36)}",
            ownerId = player.uniqueId,
            ownerName = player.name,
            price = price,
            currency = "vault",
            item = listingItem,
            createdAt = now,
            expireAt = now + settings.expireHours.coerceAtLeast(1) * 3600_000L
        )
        val listings = activeListings().toMutableList()
        listings += listing
        GlobalMarketRepository.saveAll(listings)
        player.updateInventory()
        Texts.send(player, "&a已上架到全球市场: &f${itemDisplayName(listingItem)} &7x&f${listingItem.amount} &7- &e${trimDouble(price)}")
        RecordService.append("global_market", "list", player.name, "listing=${listing.id};price=${trimDouble(price)};amount=${listingItem.amount}")
    }

    fun selection(listingId: String): GlobalMarketListing? {
        return activeListings().firstOrNull { it.id == listingId }
    }

    fun validateListing(listingId: String): ModuleOperationResult {
        val listing = selection(listingId) ?: return ModuleOperationResult(false, "&c该商品已被下架或过期。")
        return if (listing.item.type == Material.AIR || listing.item.amount <= 0) {
            ModuleOperationResult(false, "&c商品数据无效。")
        } else {
            ModuleOperationResult(true, "")
        }
    }

    fun purchaseDirect(player: Player, listingId: String, reopenAfterSuccess: Boolean = true): ModuleOperationResult {
        val listings = activeListings().toMutableList()
        val listing = listings.firstOrNull { it.id == listingId } ?: return ModuleOperationResult(false, "&c该商品已被下架或过期。")
        if (listing.ownerId == player.uniqueId) {
            if (reopenAfterSuccess) {
                openManage(player)
            }
            return ModuleOperationResult(false, "")
        }
        if (listing.price > 0 && !VaultEconomyBridge.isAvailable()) {
            return ModuleOperationResult(false, "&c当前未接入 Vault 经济，无法购买全球市场商品。")
        }
        if (!canFit(player.inventory.contents.filterNotNull(), listOf(listing.item))) {
            return ModuleOperationResult(false, "&c背包空间不足。")
        }
        if (!VaultEconomyBridge.has(player, listing.price)) {
            return ModuleOperationResult(false, "&c余额不足，需要 &e${trimDouble(listing.price)} &c金币。")
        }
        if (!VaultEconomyBridge.withdraw(player, listing.price)) {
            return ModuleOperationResult(false, "&c扣款失败，购买取消。")
        }
        val tax = listing.price * settings.taxPercent / 100.0
        val sellerIncome = (listing.price - tax).coerceAtLeast(0.0)
        val seller = Bukkit.getOfflinePlayer(listing.ownerId)
        if (!VaultEconomyBridge.deposit(seller, sellerIncome)) {
            VaultEconomyBridge.deposit(player, listing.price)
            return ModuleOperationResult(false, "&c卖家收款失败，已回退本次交易。")
        }
        listings.removeIf { it.id == listing.id }
        GlobalMarketRepository.saveAll(listings)
        player.inventory.addItem(listing.item.clone())
        player.updateInventory()
        Texts.send(player, "&a购买成功: &f${itemDisplayName(listing.item)} &7x&f${listing.item.amount} &7- &e${trimDouble(listing.price)}")
        Bukkit.getPlayer(listing.ownerId)?.let {
            Texts.send(it, "&a你的全球市场商品已售出: &f${itemDisplayName(listing.item)} &7- &e${trimDouble(sellerIncome)}")
        }
        RecordService.append(
            module = "global_market",
            type = "purchase",
            actor = player.name,
            target = listing.ownerName,
            moneyChange = -listing.price,
            detail = "seller=${listing.ownerName};listing=${listing.id};price=${trimDouble(listing.price)};amount=${listing.item.amount};tax=${trimDouble(tax)}"
        )
        RecordService.append(
            module = "global_market",
            type = "sale",
            actor = listing.ownerName,
            target = player.name,
            moneyChange = sellerIncome,
            detail = "buyer=${player.name};listing=${listing.id};price=${trimDouble(listing.price)};amount=${listing.item.amount};tax=${trimDouble(tax)}"
        )
        if (reopenAfterSuccess) {
            openMarket(player)
        }
        return ModuleOperationResult(true, "")
    }

    private fun removeListing(player: Player, listingId: String) {
        val listings = activeListings().toMutableList()
        val listing = listings.firstOrNull { it.id == listingId && it.ownerId == player.uniqueId }
        if (listing == null) {
            Texts.send(player, "&c未找到该上架条目。")
            return
        }
        listings.removeIf { it.id == listing.id }
        GlobalMarketRepository.saveAll(listings)
        player.inventory.addItem(listing.item.clone()).values.forEach {
            player.world.dropItemNaturally(player.location, it)
        }
        player.updateInventory()
        Texts.send(player, "&a已下架并返还: &f${itemDisplayName(listing.item)}")
        RecordService.append("global_market", "remove", player.name, "listing=${listing.id}")
        openManage(player)
    }

    private fun renderMarket(player: Player, holder: MatrixMenuHolder, entries: List<GlobalMarketListing>, slots: List<Int>) {
        entries.forEachIndexed { index, listing ->
            val item = listing.item.clone()
            item.itemMeta = item.itemMeta?.apply {
                lore = (lore ?: emptyList()) + listOf(
                    Texts.color("&7卖家: &f${listing.ownerName}"),
                    Texts.color("&7单价: &e${trimDouble(listing.price)} ${listing.currency}"),
                    Texts.color("&7剩余时间: &f${remainingText(listing)}"),
                    Texts.color("&e左键购买"),
                    Texts.color("&6右键加入购物车")
                )
            }
            val slot = slots[index]
            holder.backingInventory.setItem(slot, item)
            holder.handlers[slot] = { event ->
                if (event.click.isRightClick) {
                    CartModule.addGlobalMarketListing(player, listing.id)
                } else {
                    val result = purchaseDirect(player, listing.id, true)
                    if (!result.success && result.message.isNotBlank()) {
                        Texts.send(player, result.message)
                    }
                }
            }
        }
    }

    private fun renderManage(player: Player, holder: MatrixMenuHolder, entries: List<GlobalMarketListing>, slots: List<Int>) {
        entries.forEachIndexed { index, listing ->
            val item = listing.item.clone()
            item.itemMeta = item.itemMeta?.apply {
                lore = (lore ?: emptyList()) + listOf(
                    Texts.color("&7售价: &e${trimDouble(listing.price)} ${listing.currency}"),
                    Texts.color("&7剩余时间: &f${remainingText(listing)}"),
                    Texts.color("&c左键下架返还")
                )
            }
            val slot = slots[index]
            holder.backingInventory.setItem(slot, item)
            holder.handlers[slot] = {
                removeListing(player, listing.id)
            }
        }
    }

    private fun wireMarketControls(player: Player, holder: MatrixMenuHolder, currentPage: Int, maxPage: Int) {
        buttonSlot(menus.market, 'P')?.let { holder.handlers[it] = { openMarket(player, (currentPage - 1).coerceAtLeast(1)) } }
        buttonSlot(menus.market, 'N')?.let { holder.handlers[it] = { openMarket(player, (currentPage + 1).coerceAtMost(maxPage)) } }
        buttonSlot(menus.market, 'U')?.let { holder.handlers[it] = { openUpload(player) } }
        buttonSlot(menus.market, 'M')?.let { holder.handlers[it] = { openManage(player) } }
        buttonSlot(menus.market, 'C')?.let { holder.handlers[it] = { CartModule.open(player) } }
    }

    private fun wireManageControls(player: Player, holder: MatrixMenuHolder, currentPage: Int, maxPage: Int) {
        buttonSlot(menus.manage, 'P')?.let { holder.handlers[it] = { openManage(player, (currentPage - 1).coerceAtLeast(1)) } }
        buttonSlot(menus.manage, 'N')?.let { holder.handlers[it] = { openManage(player, (currentPage + 1).coerceAtMost(maxPage)) } }
        buttonSlot(menus.manage, 'U')?.let { holder.handlers[it] = { openUpload(player) } }
    }

    private fun loadSettings(): GlobalMarketSettings {
        val yaml = YamlConfiguration.loadConfiguration(File(ConfigFiles.dataFolder(), "GlobalMarket/settings.yml"))
        return GlobalMarketSettings(
            expireHours = yaml.getInt("Listing.Expire-Hours", 72),
            taxPercent = yaml.getDouble("Listing.Tax-Percent", 3.0)
        )
    }

    private fun activeListings(): List<GlobalMarketListing> {
        return GlobalMarketRepository.loadAll().sortedByDescending { it.createdAt }
    }

    private fun goodsSlots(definition: com.y54895.matrixshop.core.menu.MenuDefinition): List<Int> {
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

    private fun buttonSlot(definition: com.y54895.matrixshop.core.menu.MenuDefinition, symbol: Char): Int? {
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
        val hours = remain / 3600_000L
        val minutes = (remain % 3600_000L) / 60_000L
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
