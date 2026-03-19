package com.y54895.matrixshop.module.playershop

import com.y54895.matrixshop.core.config.ConfigFiles
import com.y54895.matrixshop.core.economy.VaultEconomyBridge
import com.y54895.matrixshop.core.menu.MenuDefinition
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
import org.bukkit.OfflinePlayer
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
            browse = MenuLoader.load(File(ConfigFiles.dataFolder(), "PlayerShop/ui/shop.yml")),
            edit = MenuLoader.load(File(ConfigFiles.dataFolder(), "PlayerShop/ui/edit.yml"))
        )
    }

    fun openShop(viewer: Player, targetName: String) {
        val offline = Bukkit.getOfflinePlayer(targetName)
        openShop(viewer, offline.uniqueId, offline.name ?: targetName)
    }

    fun openShop(viewer: Player, ownerId: UUID, ownerName: String, page: Int = 1) {
        val store = loadStore(ownerId, ownerName)
        val goodsPerPage = goodsSlots(menus.browse).size.coerceAtLeast(1)
        val ownerView = viewer.uniqueId == ownerId
        val maxPage = if (ownerView) {
            ((store.unlockedSlots + goodsPerPage - 1) / goodsPerPage).coerceAtLeast(1)
        } else {
            ((store.listings.size + goodsPerPage - 1) / goodsPerPage).coerceAtLeast(1)
        }
        val currentPage = page.coerceIn(1, maxPage)
        val placeholders = basePlaceholders(viewer, store, currentPage, maxPage)
        MenuRenderer.open(
            player = viewer,
            definition = menus.browse,
            placeholders = placeholders,
            goodsRenderer = { holder, slots ->
                renderBrowse(viewer, holder, store, slots, currentPage, ownerView)
                wireBrowseControls(viewer, holder, store, currentPage, maxPage, ownerView)
            }
        )
    }

    fun openEdit(owner: Player, page: Int = 1) {
        val store = loadStore(owner.uniqueId, owner.name)
        val goodsPerPage = goodsSlots(menus.edit).size.coerceAtLeast(1)
        val maxPage = ((store.unlockedSlots + goodsPerPage - 1) / goodsPerPage).coerceAtLeast(1)
        val currentPage = page.coerceIn(1, maxPage)
        val placeholders = basePlaceholders(owner, store, currentPage, maxPage)
        MenuRenderer.open(
            player = owner,
            definition = menus.edit,
            placeholders = placeholders,
            backAction = { openShop(owner, owner.uniqueId, owner.name, currentPage) },
            goodsRenderer = { holder, slots ->
                renderEdit(owner, holder, store, slots, currentPage)
                wireEditControls(owner, holder, currentPage, maxPage)
            }
        )
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
            Texts.send(player, "&c主手物品数量不足，无法上架 $amount 个。")
            return
        }
        val store = loadStore(player.uniqueId, player.name)
        val slotIndex = PlayerShopRepository.nextFreeSlot(store)
        if (slotIndex == null) {
            Texts.send(player, "&c你的商店上架槽位已满。")
            return
        }
        val listed = hand.clone().apply { this.amount = amount }
        val remaining = hand.amount - amount
        if (remaining <= 0) {
            player.inventory.itemInMainHand = ItemStack(Material.AIR)
        } else {
            hand.amount = remaining
            player.inventory.itemInMainHand = hand
        }
        val listing = PlayerShopListing(
            id = "ps-${System.currentTimeMillis().toString(36)}-${slotIndex}",
            slotIndex = slotIndex,
            price = price,
            currency = "vault",
            item = listed,
            createdAt = System.currentTimeMillis()
        )
        store.listings += listing
        PlayerShopRepository.save(store)
        player.updateInventory()
        Texts.send(player, "&a上架成功: &f${itemDisplayName(listed)} &7x&f${listed.amount} &7- &e${trimDouble(price)}")
        RecordService.append("player_shop", "list", player.name, "listing=${listing.id};price=${trimDouble(price)};amount=${listed.amount}")
        openEdit(player, pageForSlot(slotIndex, goodsPerPage(menus.edit)))
    }

    fun selection(ownerId: UUID, ownerName: String, listingId: String): PlayerShopSelection? {
        val store = loadStore(ownerId, ownerName)
        val listing = store.listings.firstOrNull { it.id == listingId } ?: return null
        return PlayerShopSelection(ownerId = ownerId, ownerName = store.ownerName, listing = listing)
    }

    fun validateListing(ownerId: UUID, ownerName: String, listingId: String): ModuleOperationResult {
        val selection = selection(ownerId, ownerName, listingId)
            ?: return ModuleOperationResult(false, "&c该商品已被下架或售出。")
        return if (selection.listing.item.type == Material.AIR || selection.listing.item.amount <= 0) {
            ModuleOperationResult(false, "&c该商品数据无效。")
        } else {
            ModuleOperationResult(true, "")
        }
    }

    fun purchaseDirect(
        viewer: Player,
        ownerId: UUID,
        ownerName: String,
        listingId: String,
        reopenAfterSuccess: Boolean = true
    ): ModuleOperationResult {
        val store = loadStore(ownerId, ownerName)
        val listing = store.listings.firstOrNull { it.id == listingId }
            ?: return ModuleOperationResult(false, "&c该商品已被下架或售出。")
        return purchase(viewer, store, listing, reopenAfterSuccess)
    }

    private fun purchase(
        viewer: Player,
        store: PlayerShopStore,
        listing: PlayerShopListing,
        reopenAfterSuccess: Boolean = true
    ): ModuleOperationResult {
        if (viewer.uniqueId == store.ownerId) {
            openEdit(viewer, pageForSlot(listing.slotIndex, goodsPerPage(menus.edit)))
            return ModuleOperationResult(false, "")
        }
        val refreshed = loadStore(store.ownerId, store.ownerName)
        val target = refreshed.listings.firstOrNull { it.id == listing.id }
        if (target == null) {
            Texts.send(viewer, "&c该商品已被下架或售出。")
            if (reopenAfterSuccess) {
                openShop(viewer, store.ownerId, store.ownerName)
            }
            return ModuleOperationResult(false, "&c该商品已被下架或售出。")
        }
        if (listing.price > 0 && !VaultEconomyBridge.isAvailable()) {
            return ModuleOperationResult(false, "&c当前未接入 Vault 经济，无法购买玩家商店商品。")
        }
        if (!canFit(viewer.inventory.contents.filterNotNull(), listOf(target.item))) {
            return ModuleOperationResult(false, "&c背包空间不足。")
        }
        if (!VaultEconomyBridge.has(viewer, target.price)) {
            return ModuleOperationResult(false, "&c余额不足，需要 &e${trimDouble(target.price)} &c金币。")
        }
        if (!VaultEconomyBridge.withdraw(viewer, target.price)) {
            return ModuleOperationResult(false, "&c扣款失败，购买取消。")
        }
        val seller = Bukkit.getOfflinePlayer(store.ownerId)
        if (!VaultEconomyBridge.deposit(seller, target.price)) {
            VaultEconomyBridge.deposit(viewer, target.price)
            return ModuleOperationResult(false, "&c卖家收款失败，已回退本次交易。")
        }
        refreshed.listings.remove(target)
        PlayerShopRepository.save(refreshed)
        viewer.inventory.addItem(target.item.clone())
        viewer.updateInventory()
        Texts.send(viewer, "&a购买成功: &f${itemDisplayName(target.item)} &7x&f${target.item.amount} &7- &e${trimDouble(target.price)}")
        val sellerPlayer = Bukkit.getPlayer(store.ownerId)
        if (sellerPlayer != null && sellerPlayer.isOnline) {
            Texts.send(sellerPlayer, "&a你的商品已售出: &f${itemDisplayName(target.item)} &7- &e${trimDouble(target.price)}")
        }
        RecordService.append(
            "player_shop",
            "purchase",
            viewer.name,
            "seller=${store.ownerName};listing=${target.id};price=${trimDouble(target.price)};amount=${target.item.amount}"
        )
        if (reopenAfterSuccess) {
            openShop(viewer, store.ownerId, store.ownerName)
        }
        return ModuleOperationResult(true, "")
    }

    private fun removeListing(owner: Player, listingId: String) {
        val store = loadStore(owner.uniqueId, owner.name)
        val listing = store.listings.firstOrNull { it.id == listingId }
        if (listing == null) {
            Texts.send(owner, "&c未找到该上架条目。")
            return
        }
        store.listings.remove(listing)
        PlayerShopRepository.save(store)
        owner.inventory.addItem(listing.item.clone()).values.forEach {
            owner.world.dropItemNaturally(owner.location, it)
        }
        owner.updateInventory()
        Texts.send(owner, "&a已下架并返还: &f${itemDisplayName(listing.item)}")
        RecordService.append("player_shop", "remove", owner.name, "listing=${listing.id}")
        openEdit(owner, pageForSlot(listing.slotIndex, goodsPerPage(menus.edit)))
    }

    private fun renderBrowse(
        viewer: Player,
        holder: MatrixMenuHolder,
        store: PlayerShopStore,
        slots: List<Int>,
        currentPage: Int,
        ownerView: Boolean
    ) {
        val entries = if (ownerView) {
            val start = (currentPage - 1) * slots.size
            val end = start + slots.size
            store.listings.sortedBy { it.slotIndex }.filter { it.slotIndex in start until end }
        } else {
            val compact = store.listings.sortedBy { it.slotIndex }
            compact.drop((currentPage - 1) * slots.size).take(slots.size)
        }
        entries.forEachIndexed { index, listing ->
            val slot = slots[index]
            val item = listing.item.clone()
            item.itemMeta = item.itemMeta?.apply {
                lore = (lore ?: emptyList()) + listOf(
                    Texts.color("&7售价: &e${trimDouble(listing.price)} ${listing.currency}"),
                    Texts.color("&7卖家: &f${store.ownerName}"),
                    Texts.color(if (ownerView) "&e左键进入编辑页" else "&e左键购买"),
                    Texts.color(if (ownerView) "&7" else "&6右键加入购物车")
                )
            }
            holder.backingInventory.setItem(slot, item)
            holder.handlers[slot] = { event ->
                if (ownerView) {
                    openEdit(viewer, pageForSlot(listing.slotIndex, slots.size))
                } else if (event.click.isRightClick) {
                    CartModule.addPlayerShopListing(viewer, store.ownerId.toString(), store.ownerName, listing.id)
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
                        Texts.color("&7售价: &e${trimDouble(listing.price)} ${listing.currency}"),
                        Texts.color("&7槽位: &f${listing.slotIndex + 1}/${store.unlockedSlots}"),
                        Texts.color("&c左键下架并返还")
                    )
                }
                holder.backingInventory.setItem(inventorySlot, item)
                holder.handlers[inventorySlot] = {
                    removeListing(owner, listing.id)
                }
            }
    }

    private fun wireBrowseControls(viewer: Player, holder: MatrixMenuHolder, store: PlayerShopStore, currentPage: Int, maxPage: Int, ownerView: Boolean) {
        buttonSlot(menus.browse, 'P')?.let { slot ->
            holder.handlers[slot] = { openShop(viewer, store.ownerId, store.ownerName, (currentPage - 1).coerceAtLeast(1)) }
        }
        buttonSlot(menus.browse, 'N')?.let { slot ->
            holder.handlers[slot] = { openShop(viewer, store.ownerId, store.ownerName, (currentPage + 1).coerceAtMost(maxPage)) }
        }
        buttonSlot(menus.browse, 'E')?.let { slot ->
            if (ownerView) {
                holder.handlers[slot] = { openEdit(viewer, currentPage) }
            } else {
                holder.backingInventory.setItem(slot, fillerItem())
                holder.handlers.remove(slot)
            }
        }
    }

    private fun wireEditControls(owner: Player, holder: MatrixMenuHolder, currentPage: Int, maxPage: Int) {
        buttonSlot(menus.edit, 'P')?.let { slot ->
            holder.handlers[slot] = { openEdit(owner, (currentPage - 1).coerceAtLeast(1)) }
        }
        buttonSlot(menus.edit, 'N')?.let { slot ->
            holder.handlers[slot] = { openEdit(owner, (currentPage + 1).coerceAtMost(maxPage)) }
        }
        buttonSlot(menus.edit, 'H')?.let { slot ->
            holder.handlers[slot] = { Texts.send(owner, "&e使用 /matrixshop player_shop upload <price> [amount] 上架主手物品。") }
        }
    }

    private fun basePlaceholders(viewer: Player, store: PlayerShopStore, page: Int, maxPage: Int): Map<String, String> {
        return mapOf(
            "player" to viewer.name,
            "owner" to store.ownerName,
            "page" to page.toString(),
            "max-page" to maxPage.toString(),
            "money" to trimDouble(VaultEconomyBridge.balance(viewer)),
            "listed" to store.listings.size.toString(),
            "unlocked" to store.unlockedSlots.toString()
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

    private fun loadStore(ownerId: UUID, ownerName: String): PlayerShopStore {
        return PlayerShopRepository.load(ownerId, ownerName, settings.unlockedBase, settings.unlockedMax)
    }

    private fun loadSettings(): PlayerShopSettings {
        val yaml = YamlConfiguration.loadConfiguration(File(ConfigFiles.dataFolder(), "PlayerShop/settings.yml"))
        return PlayerShopSettings(
            unlockedBase = yaml.getInt("Unlock.Base", 21),
            unlockedMax = yaml.getInt("Unlock.Max", 100)
        )
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
