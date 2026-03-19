package com.y54895.matrixshop.module.chestshop

import com.y54895.matrixshop.core.config.ConfigFiles
import com.y54895.matrixshop.core.economy.VaultEconomyBridge
import com.y54895.matrixshop.core.menu.MatrixMenuHolder
import com.y54895.matrixshop.core.menu.MenuDefinition
import com.y54895.matrixshop.core.menu.MenuLoader
import com.y54895.matrixshop.core.menu.MenuRenderer
import com.y54895.matrixshop.core.module.MatrixModule
import com.y54895.matrixshop.core.record.RecordService
import com.y54895.matrixshop.core.text.Texts
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Chest
import org.bukkit.block.Sign
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object ChestShopModule : MatrixModule {

    override val id: String = "chestshop"
    override val displayName: String = "ChestShop"

    private lateinit var settings: ChestShopSettings
    private lateinit var menus: ChestShopMenus
    private lateinit var signConfig: YamlConfiguration
    private val lastContext = HashMap<UUID, String>()
    private val viewMultipliers = HashMap<UUID, Int>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

    override fun isEnabled(): Boolean {
        return ConfigFiles.isModuleEnabled(id, true)
    }

    override fun reload() {
        lastContext.clear()
        viewMultipliers.clear()
        if (!isEnabled()) {
            return
        }
        ChestShopRepository.initialize()
        val dataFolder = ConfigFiles.dataFolder()
        settings = loadSettings()
        signConfig = YamlConfiguration.loadConfiguration(File(dataFolder, "ChestShop/signs.yml"))
        menus = ChestShopMenus(
            shop = MenuLoader.load(File(dataFolder, "ChestShop/ui/shop.yml")),
            create = MenuLoader.load(File(dataFolder, "ChestShop/ui/create.yml")),
            edit = MenuLoader.load(File(dataFolder, "ChestShop/ui/edit.yml")),
            stock = MenuLoader.load(File(dataFolder, "ChestShop/ui/stock.yml")),
            history = MenuLoader.load(File(dataFolder, "ChestShop/ui/history.yml"))
        )
        allShops().forEach { updateSigns(it) }
    }

    fun open(player: Player) {
        if (!ensureReady(player)) {
            return
        }
        val shop = resolveContextShop(player) ?: run {
            openCreate(player)
            Texts.send(player, "&eLook at a chest shop chest or sign, then use /chestshop open.")
            return
        }
        openShop(player, shop)
    }

    fun openCreate(player: Player) {
        if (!ensureReady(player)) {
            return
        }
        val hand = player.inventory.itemInMainHand ?: ItemStack(Material.AIR)
        MenuRenderer.open(
            player = player,
            definition = menus.create,
            placeholders = mapOf(
                "item" to itemDisplayName(hand),
                "amount" to hand.amount.coerceAtLeast(1).toString()
            ),
            goodsRenderer = { holder, _ ->
                buttonSlot(menus.create, 'i')?.let { slot ->
                    holder.backingInventory.setItem(slot, if (hand.type == Material.AIR) ItemStack(Material.BARRIER) else hand.clone())
                }
            }
        )
    }

    fun openEdit(player: Player) {
        if (!ensureReady(player)) {
            return
        }
        val shop = resolveContextShop(player) ?: run {
            Texts.send(player, "&cLook at a chest shop chest or sign first.")
            return
        }
        if (!canManage(player, shop)) {
            Texts.send(player, "&cOnly the shop owner can edit this chest shop.")
            return
        }
        openEditMenu(player, shop)
    }

    fun openStock(player: Player, page: Int = 1) {
        if (!ensureReady(player)) {
            return
        }
        val shop = resolveContextShop(player) ?: run {
            Texts.send(player, "&cLook at a chest shop chest or sign first.")
            return
        }
        openStockMenu(player, shop, page)
    }

    fun openHistory(player: Player, page: Int = 1) {
        if (!ensureReady(player)) {
            return
        }
        val shop = resolveContextShop(player) ?: run {
            Texts.send(player, "&cLook at a chest shop chest or sign first.")
            return
        }
        openHistoryMenu(player, shop, page)
    }

    fun create(player: Player, modeRaw: String?, firstPrice: Double?, secondPrice: Double?, amountRaw: Int?) {
        if (!ensureReady(player)) {
            return
        }
        val mode = parseMode(modeRaw) ?: run {
            openCreate(player)
            Texts.send(player, "&cUsage: /chestshop create <buy|sell|dual> <price> [sell-price] [amount]")
            return
        }
        val target = targetBlock(player) ?: run {
            Texts.send(player, "&cLook at a chest block first.")
            return
        }
        if (!isChestBlock(target)) {
            Texts.send(player, "&cThe target block is not a chest.")
            return
        }
        if (findShopByBlock(target) != null) {
            Texts.send(player, "&cThis chest is already bound to a chest shop.")
            return
        }
        val hand = player.inventory.itemInMainHand ?: ItemStack(Material.AIR)
        if (hand.type == Material.AIR || hand.amount <= 0) {
            Texts.send(player, "&cHold the shop item in your main hand first.")
            return
        }
        val secondary = findAdjacentChest(target)
        if (mode == ChestShopMode.DUAL && secondary == null) {
            Texts.send(player, "&cDUAL mode requires a double chest.")
            return
        }
        if (secondary != null && findShopByBlock(secondary) != null) {
            Texts.send(player, "&cThe second chest is already bound to another chest shop.")
            return
        }
        val tradeAmount = (amountRaw ?: hand.amount).coerceAtLeast(1)
        if ((mode == ChestShopMode.BUY || mode == ChestShopMode.SELL) && firstPrice == null) {
            Texts.send(player, "&cPrice is required.")
            return
        }
        if (mode == ChestShopMode.DUAL && (firstPrice == null || secondPrice == null)) {
            Texts.send(player, "&cDUAL mode requires both buy and sell price.")
            return
        }
        val shop = ChestShopShop(
            id = nextShopId(),
            ownerId = player.uniqueId,
            ownerName = player.name,
            primaryChest = target.toShopLocation(),
            secondaryChest = secondary?.toShopLocation(),
            mode = mode,
            buyPrice = when (mode) {
                ChestShopMode.SELL -> 0.0
                else -> (firstPrice ?: 0.0).coerceAtLeast(0.0)
            },
            sellPrice = when (mode) {
                ChestShopMode.BUY -> 0.0
                ChestShopMode.SELL -> (firstPrice ?: 0.0).coerceAtLeast(0.0)
                ChestShopMode.DUAL -> (secondPrice ?: 0.0).coerceAtLeast(0.0)
            },
            tradeAmount = tradeAmount,
            item = hand.clone().apply { amount = tradeAmount.coerceAtMost(maxStackSize) },
            createdAt = System.currentTimeMillis()
        )
        detectSigns(shop)
        val shops = allShops().toMutableList()
        shops += shop
        ChestShopRepository.saveAll(shops)
        updateSigns(shop)
        lastContext[player.uniqueId] = shop.id
        Texts.send(player, "&aChest shop created: &f${shop.id}")
        RecordService.append(
            module = "chestshop",
            type = "create",
            actor = player.name,
            detail = "shop=${shop.id};mode=${shop.mode.name};buy=${trimDouble(shop.buyPrice)};sell=${trimDouble(shop.sellPrice)};amount=${shop.tradeAmount}"
        )
    }

    fun remove(player: Player) {
        if (!ensureReady(player)) {
            return
        }
        val shop = resolveContextShop(player) ?: run {
            Texts.send(player, "&cLook at a chest shop chest or sign first.")
            return
        }
        if (!canManage(player, shop)) {
            Texts.send(player, "&cOnly the shop owner can remove this chest shop.")
            return
        }
        clearSigns(shop)
        val shops = allShops().toMutableList()
        shops.removeIf { it.id == shop.id }
        ChestShopRepository.saveAll(shops)
        lastContext.entries.removeIf { it.value == shop.id }
        Texts.send(player, "&aChest shop removed: &f${shop.id}")
        RecordService.append(
            module = "chestshop",
            type = "remove",
            actor = player.name,
            detail = "shop=${shop.id}"
        )
    }

    fun setPrice(player: Player, sideRaw: String?, value: Double?) {
        if (!ensureReady(player)) {
            return
        }
        val shop = resolveContextShop(player) ?: run {
            Texts.send(player, "&cLook at a chest shop chest or sign first.")
            return
        }
        if (!canManage(player, shop)) {
            Texts.send(player, "&cOnly the owner can edit prices.")
            return
        }
        val side = sideRaw?.lowercase(Locale.ROOT)
        val newPrice = value?.coerceAtLeast(0.0)
        if (newPrice == null || side !in listOf("buy", "sell")) {
            Texts.send(player, "&cUsage: /chestshop price <buy|sell> <value>")
            return
        }
        when (side) {
            "buy" -> {
                if (shop.mode == ChestShopMode.SELL) {
                    Texts.send(player, "&cThis shop mode does not support buy price.")
                    return
                }
                shop.buyPrice = newPrice
            }
            "sell" -> {
                if (shop.mode == ChestShopMode.BUY) {
                    Texts.send(player, "&cThis shop mode does not support sell price.")
                    return
                }
                shop.sellPrice = newPrice
            }
        }
        saveShop(shop)
        updateSigns(shop)
        Texts.send(player, "&aChest shop price updated.")
        openEditMenu(player, shop)
    }

    fun setAmount(player: Player, value: Int?) {
        if (!ensureReady(player)) {
            return
        }
        val shop = resolveContextShop(player) ?: run {
            Texts.send(player, "&cLook at a chest shop chest or sign first.")
            return
        }
        if (!canManage(player, shop)) {
            Texts.send(player, "&cOnly the owner can edit trade amount.")
            return
        }
        val amount = value?.coerceAtLeast(1)
        if (amount == null) {
            Texts.send(player, "&cUsage: /chestshop amount <number>")
            return
        }
        shop.tradeAmount = amount
        saveShop(shop)
        updateSigns(shop)
        Texts.send(player, "&aChest shop trade amount updated.")
        openEditMenu(player, shop)
    }

    fun setMode(player: Player, modeRaw: String?) {
        if (!ensureReady(player)) {
            return
        }
        val shop = resolveContextShop(player) ?: run {
            Texts.send(player, "&cLook at a chest shop chest or sign first.")
            return
        }
        if (!canManage(player, shop)) {
            Texts.send(player, "&cOnly the owner can edit shop mode.")
            return
        }
        val mode = parseMode(modeRaw) ?: run {
            Texts.send(player, "&cUsage: /chestshop mode <buy|sell|dual>")
            return
        }
        if (mode == ChestShopMode.DUAL && shop.secondaryChest == null) {
            Texts.send(player, "&cDUAL mode requires a double chest.")
            return
        }
        shop.mode = mode
        saveShop(shop)
        updateSigns(shop)
        Texts.send(player, "&aChest shop mode updated to &f${mode.name}&a.")
        openEditMenu(player, shop)
    }

    fun handleChestInteract(player: Player, block: Block): Boolean {
        if (!ensureReady(player) || !settings.openGuiOnChestRightClick) {
            return false
        }
        val shop = findShopByBlock(block) ?: return false
        lastContext[player.uniqueId] = shop.id
        if (canManage(player, shop)) {
            openEditMenu(player, shop)
        } else {
            openShop(player, shop)
        }
        return true
    }

    fun handleSignInteract(player: Player, block: Block): Boolean {
        if (!ensureReady(player) || !settings.openGuiOnSignRightClick) {
            return false
        }
        val shop = findShopBySign(block) ?: return false
        lastContext[player.uniqueId] = shop.id
        if (canManage(player, shop)) {
            openEditMenu(player, shop)
        } else {
            openShop(player, shop)
        }
        return true
    }

    fun canBreakProtectedBlock(player: Player, block: Block): Boolean {
        if (!ensureReady(player)) {
            return true
        }
        val shop = findShopByBlock(block) ?: findShopBySign(block) ?: return true
        if (canManage(player, shop)) {
            Texts.send(player, "&eUse /chestshop remove to delete the shop cleanly.")
        } else {
            Texts.send(player, "&cThis chest shop is protected.")
        }
        return false
    }

    private fun openShop(player: Player, shop: ChestShopShop) {
        val multiplier = viewMultipliers[player.uniqueId] ?: 1
        val actual = reloadShop(shop.id) ?: shop
        MenuRenderer.open(
            player = player,
            definition = menus.shop,
            placeholders = shopPlaceholders(actual, multiplier),
            goodsRenderer = { holder, _ ->
                buttonSlot(menus.shop, 'i')?.let { slot ->
                    holder.backingInventory.setItem(slot, buildPreviewItem(actual, multiplier))
                }
                wireShopControls(player, holder, actual, multiplier)
            }
        )
    }

    private fun openEditMenu(player: Player, shop: ChestShopShop) {
        val actual = reloadShop(shop.id) ?: shop
        MenuRenderer.open(
            player = player,
            definition = menus.edit,
            placeholders = shopPlaceholders(actual, 1),
            backAction = { openShop(player, actual) },
            goodsRenderer = { holder, _ ->
                buttonSlot(menus.edit, 'i')?.let { slot ->
                    holder.backingInventory.setItem(slot, buildPreviewItem(actual, 1))
                }
                wireEditControls(player, holder, actual)
            }
        )
    }

    private fun openStockMenu(player: Player, shop: ChestShopShop, page: Int) {
        val actual = reloadShop(shop.id) ?: shop
        val items = stockInventories(actual).flatMap { inventory ->
            inventory.contents.filterNotNull().map { it.clone() }
        }
        val slots = goodsSlots(menus.stock)
        val pageSize = slots.size.coerceAtLeast(1)
        val maxPage = ((items.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        val currentPage = page.coerceIn(1, maxPage)
        val entries = items.drop((currentPage - 1) * pageSize).take(pageSize)
        MenuRenderer.open(
            player = player,
            definition = menus.stock,
            placeholders = mapOf(
                "page" to currentPage.toString(),
                "max-page" to maxPage.toString(),
                "shop-id" to actual.id,
                "stock" to countStock(actual).toString(),
                "item" to itemDisplayName(actual.item)
            ),
            backAction = { if (canManage(player, actual)) openEditMenu(player, actual) else openShop(player, actual) },
            goodsRenderer = { holder, goods ->
                entries.forEachIndexed { index, item ->
                    holder.backingInventory.setItem(goods[index], item)
                }
                wirePagedMenu(
                    holder = holder,
                    definition = menus.stock,
                    currentPage = currentPage,
                    maxPage = maxPage,
                    onPage = { next -> openStockMenu(player, actual, next) },
                    onBack = { if (canManage(player, actual)) openEditMenu(player, actual) else openShop(player, actual) }
                )
            }
        )
    }

    private fun openHistoryMenu(player: Player, shop: ChestShopShop, page: Int) {
        val actual = reloadShop(shop.id) ?: shop
        val history = actual.history.sortedByDescending { it.createdAt }
        val slots = goodsSlots(menus.history)
        val pageSize = slots.size.coerceAtLeast(1)
        val maxPage = ((history.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        val currentPage = page.coerceIn(1, maxPage)
        val entries = history.drop((currentPage - 1) * pageSize).take(pageSize)
        MenuRenderer.open(
            player = player,
            definition = menus.history,
            placeholders = mapOf(
                "page" to currentPage.toString(),
                "max-page" to maxPage.toString(),
                "shop-id" to actual.id
            ),
            backAction = { if (canManage(player, actual)) openEditMenu(player, actual) else openShop(player, actual) },
            goodsRenderer = { holder, goods ->
                entries.forEachIndexed { index, entry ->
                    val icon = ItemStack(historyMaterial(entry.type))
                    icon.itemMeta = icon.itemMeta?.apply {
                        MenuRenderer.decorate(
                            this,
                            Texts.color("&f${entry.type.uppercase(Locale.ROOT)} &7/ &b${entry.actor}"),
                            listOf(
                                Texts.color("&7Amount: &f${entry.amount}"),
                                Texts.color("&7Money: &e${trimDouble(entry.money)}"),
                                Texts.color("&7Time: &f${dateFormat.format(Date(entry.createdAt))}"),
                                Texts.color("&7Note: &f${entry.note.ifBlank { "-" }}")
                            )
                        )
                    }
                    holder.backingInventory.setItem(goods[index], icon)
                }
                wirePagedMenu(
                    holder = holder,
                    definition = menus.history,
                    currentPage = currentPage,
                    maxPage = maxPage,
                    onPage = { next -> openHistoryMenu(player, actual, next) },
                    onBack = { if (canManage(player, actual)) openEditMenu(player, actual) else openShop(player, actual) }
                )
            }
        )
    }

    private fun wireShopControls(player: Player, holder: MatrixMenuHolder, shop: ChestShopShop, multiplier: Int) {
        buttonSlot(menus.shop, 'B')?.let { slot ->
            holder.handlers[slot] = {
                if (shop.mode == ChestShopMode.SELL || shop.mode == ChestShopMode.DUAL) {
                    buyFromShop(player, shop, multiplier)
                    openShop(player, reloadShop(shop.id) ?: shop)
                } else {
                    Texts.send(player, "&cThis shop does not sell items.")
                }
            }
        }
        buttonSlot(menus.shop, 'S')?.let { slot ->
            holder.handlers[slot] = {
                if (shop.mode == ChestShopMode.BUY || shop.mode == ChestShopMode.DUAL) {
                    sellToShop(player, shop, multiplier)
                    openShop(player, reloadShop(shop.id) ?: shop)
                } else {
                    Texts.send(player, "&cThis shop does not buy items.")
                }
            }
        }
        buttonSlot(menus.shop, 'M')?.let { slot ->
            if (canManage(player, shop)) {
                holder.handlers[slot] = { openEditMenu(player, shop) }
            }
        }
        listOf(1, 8, 64).forEachIndexed { index, value ->
            val symbol = listOf('1', '2', '3')[index]
            buttonSlot(menus.shop, symbol)?.let { slot ->
                holder.handlers[slot] = {
                    viewMultipliers[player.uniqueId] = value
                    openShop(player, shop)
                }
            }
        }
        buttonSlot(menus.shop, 'I')?.let { slot ->
            holder.handlers[slot] = { openStockMenu(player, shop, 1) }
        }
        buttonSlot(menus.shop, 'H')?.let { slot ->
            holder.handlers[slot] = { openHistoryMenu(player, shop, 1) }
        }
        buttonSlot(menus.shop, 'R')?.let { slot ->
            holder.handlers[slot] = { player.closeInventory() }
        }
    }

    private fun wireEditControls(player: Player, holder: MatrixMenuHolder, shop: ChestShopShop) {
        buttonSlot(menus.edit, 'P')?.let { slot ->
            holder.handlers[slot] = {
                Texts.send(player, "&7Use /chestshop price <buy|sell> <value> to change prices.")
            }
        }
        buttonSlot(menus.edit, 'M')?.let { slot ->
            holder.handlers[slot] = {
                val nextMode = nextMode(shop)
                if (nextMode == ChestShopMode.DUAL && shop.secondaryChest == null) {
                    Texts.send(player, "&cDUAL mode requires a double chest.")
                } else {
                    shop.mode = nextMode
                    saveShop(shop)
                    updateSigns(shop)
                    openEditMenu(player, shop)
                }
            }
        }
        buttonSlot(menus.edit, 'A')?.let { slot ->
            holder.handlers[slot] = {
                shop.tradeAmount = nextAmount(shop.tradeAmount)
                saveShop(shop)
                updateSigns(shop)
                openEditMenu(player, shop)
            }
        }
        buttonSlot(menus.edit, 'S')?.let { slot ->
            holder.handlers[slot] = {
                detectSigns(shop)
                saveShop(shop)
                updateSigns(shop)
                Texts.send(player, "&aSigns rescanned and updated.")
                openEditMenu(player, shop)
            }
        }
        buttonSlot(menus.edit, 'L')?.let { slot ->
            holder.handlers[slot] = { openStockMenu(player, shop, 1) }
        }
        buttonSlot(menus.edit, 'D')?.let { slot ->
            holder.handlers[slot] = {
                remove(player)
                player.closeInventory()
            }
        }
        buttonSlot(menus.edit, 'H')?.let { slot ->
            holder.handlers[slot] = { openHistoryMenu(player, shop, 1) }
        }
        buttonSlot(menus.edit, 'O')?.let { slot ->
            holder.handlers[slot] = { openShop(player, shop) }
        }
        buttonSlot(menus.edit, 'R')?.let { slot ->
            holder.handlers[slot] = { player.closeInventory() }
        }
    }

    private fun buyFromShop(player: Player, sourceShop: ChestShopShop, multiplier: Int) {
        val shop = reloadShop(sourceShop.id) ?: return
        val totalAmount = (shop.tradeAmount * multiplier).coerceAtLeast(1)
        val totalPrice = shop.sellPrice * multiplier
        if (shop.mode == ChestShopMode.BUY) {
            Texts.send(player, "&cThis shop does not sell items.")
            return
        }
        if (countStock(shop) < totalAmount) {
            Texts.send(player, "&cThis chest shop does not have enough stock.")
            return
        }
        val purchaseStacks = splitStacks(shop.item, totalAmount)
        if (!canFitPlayer(player, purchaseStacks)) {
            Texts.send(player, "&cYou do not have enough inventory space.")
            return
        }
        if (totalPrice > 0 && (!VaultEconomyBridge.isAvailable() || !VaultEconomyBridge.has(player, totalPrice))) {
            Texts.send(player, "&cYou do not have enough balance.")
            return
        }
        if (totalPrice > 0 && !VaultEconomyBridge.withdraw(player, totalPrice)) {
            Texts.send(player, "&cFailed to charge the purchase price.")
            return
        }
        if (!removeFromStock(shop, totalAmount)) {
            if (totalPrice > 0) {
                VaultEconomyBridge.deposit(player, totalPrice)
            }
            Texts.send(player, "&cFailed to remove items from chest stock.")
            return
        }
        val owner = Bukkit.getOfflinePlayer(shop.ownerId)
        if (totalPrice > 0 && !VaultEconomyBridge.deposit(owner, totalPrice)) {
            addToStock(shop, purchaseStacks)
            VaultEconomyBridge.deposit(player, totalPrice)
            Texts.send(player, "&cFailed to transfer money to the shop owner.")
            return
        }
        purchaseStacks.forEach { player.inventory.addItem(it) }
        player.updateInventory()
        appendHistory(shop, "buy", player.name, totalAmount, totalPrice, "player bought from shop")
        saveShop(shop)
        val ownerPlayer = Bukkit.getPlayer(shop.ownerId)
        if (ownerPlayer != null && ownerPlayer.isOnline) {
            Texts.send(ownerPlayer, "&a${player.name} bought &f${itemDisplayName(shop.item)} &7x&f$totalAmount &7for &e${trimDouble(totalPrice)}")
        }
        Texts.send(player, "&aBought &f${itemDisplayName(shop.item)} &7x&f$totalAmount &7for &e${trimDouble(totalPrice)}")
        RecordService.append(
            module = "chestshop",
            type = "purchase",
            actor = player.name,
            target = shop.ownerName,
            moneyChange = -totalPrice,
            detail = "shop=${shop.id};amount=$totalAmount;price=${trimDouble(totalPrice)}"
        )
        RecordService.append(
            module = "chestshop",
            type = "sale",
            actor = shop.ownerName,
            target = player.name,
            moneyChange = totalPrice,
            detail = "shop=${shop.id};amount=$totalAmount;price=${trimDouble(totalPrice)}"
        )
    }

    private fun sellToShop(player: Player, sourceShop: ChestShopShop, multiplier: Int) {
        val shop = reloadShop(sourceShop.id) ?: return
        val totalAmount = (shop.tradeAmount * multiplier).coerceAtLeast(1)
        val totalPrice = shop.buyPrice * multiplier
        if (shop.mode == ChestShopMode.SELL) {
            Texts.send(player, "&cThis shop does not buy items.")
            return
        }
        if (countMatching(player.inventory.contents.take(36), shop.item) < totalAmount) {
            Texts.send(player, "&cYou do not have enough matching items.")
            return
        }
        val incomingStacks = splitStacks(shop.item, totalAmount)
        if (!canFitInventories(stockInventories(shop), incomingStacks)) {
            Texts.send(player, "&cThis chest shop does not have enough free space.")
            return
        }
        val owner = Bukkit.getOfflinePlayer(shop.ownerId)
        if (totalPrice > 0 && (!VaultEconomyBridge.isAvailable() || !VaultEconomyBridge.has(owner, totalPrice))) {
            Texts.send(player, "&cThe shop owner does not have enough balance.")
            return
        }
        if (totalPrice > 0 && !VaultEconomyBridge.withdraw(owner, totalPrice)) {
            Texts.send(player, "&cFailed to withdraw money from the shop owner.")
            return
        }
        if (!removeFromPlayer(player, shop.item, totalAmount)) {
            if (totalPrice > 0) {
                VaultEconomyBridge.deposit(owner, totalPrice)
            }
            Texts.send(player, "&cFailed to remove items from your inventory.")
            return
        }
        if (!addToStock(shop, incomingStacks)) {
            player.inventory.addItem(*incomingStacks.toTypedArray())
            if (totalPrice > 0) {
                VaultEconomyBridge.deposit(owner, totalPrice)
            }
            Texts.send(player, "&cFailed to add items into the shop chest.")
            return
        }
        if (totalPrice > 0 && !VaultEconomyBridge.deposit(player, totalPrice)) {
            removeFromStock(shop, totalAmount)
            player.inventory.addItem(*incomingStacks.toTypedArray())
            VaultEconomyBridge.deposit(owner, totalPrice)
            Texts.send(player, "&cFailed to pay the seller.")
            return
        }
        player.updateInventory()
        appendHistory(shop, "sell", player.name, totalAmount, totalPrice, "player sold to shop")
        saveShop(shop)
        val ownerPlayer = Bukkit.getPlayer(shop.ownerId)
        if (ownerPlayer != null && ownerPlayer.isOnline) {
            Texts.send(ownerPlayer, "&a${player.name} sold &f${itemDisplayName(shop.item)} &7x&f$totalAmount &7for &e${trimDouble(totalPrice)}")
        }
        Texts.send(player, "&aSold &f${itemDisplayName(shop.item)} &7x&f$totalAmount &7for &e${trimDouble(totalPrice)}")
        RecordService.append(
            module = "chestshop",
            type = "expense",
            actor = shop.ownerName,
            target = player.name,
            moneyChange = -totalPrice,
            detail = "shop=${shop.id};amount=$totalAmount;price=${trimDouble(totalPrice)};direction=buy-from-player"
        )
        RecordService.append(
            module = "chestshop",
            type = "income",
            actor = player.name,
            target = shop.ownerName,
            moneyChange = totalPrice,
            detail = "shop=${shop.id};amount=$totalAmount;price=${trimDouble(totalPrice)};direction=sell-to-shop"
        )
    }

    private fun ensureReady(player: Player): Boolean {
        if (!isEnabled()) {
            Texts.send(player, "&cChestShop is disabled.")
            return false
        }
        if (!::settings.isInitialized || !::menus.isInitialized) {
            Texts.send(player, "&cChestShop is not loaded yet.")
            return false
        }
        return true
    }

    private fun canManage(player: Player, shop: ChestShopShop): Boolean {
        return shop.ownerId == player.uniqueId || player.hasPermission("matrixshop.admin.chestshop.manage.others")
    }

    private fun resolveContextShop(player: Player): ChestShopShop? {
        val lookedAt = targetBlock(player)
        val targetShop = when {
            lookedAt == null -> null
            isChestBlock(lookedAt) -> findShopByBlock(lookedAt)
            isSignBlock(lookedAt) -> findShopBySign(lookedAt)
            else -> null
        }
        if (targetShop != null) {
            lastContext[player.uniqueId] = targetShop.id
            return targetShop
        }
        val contextId = lastContext[player.uniqueId] ?: return null
        return reloadShop(contextId)
    }

    private fun reloadShop(id: String): ChestShopShop? {
        return allShops().firstOrNull { it.id == id }
    }

    private fun allShops(): MutableList<ChestShopShop> {
        return ChestShopRepository.loadAll()
    }

    private fun saveShop(shop: ChestShopShop) {
        val shops = allShops()
        val index = shops.indexOfFirst { it.id == shop.id }
        if (index >= 0) {
            shops[index] = shop
        } else {
            shops += shop
        }
        ChestShopRepository.saveAll(shops)
    }

    private fun loadSettings(): ChestShopSettings {
        val yaml = YamlConfiguration.loadConfiguration(File(ConfigFiles.dataFolder(), "ChestShop/settings.yml"))
        val triggers = yaml.getStringList("Entry.Open-GUI-On").map { it.uppercase(Locale.ROOT) }
        return ChestShopSettings(
            openGuiOnChestRightClick = triggers.contains("CHEST_RIGHT_CLICK"),
            openGuiOnSignRightClick = triggers.contains("SIGN_RIGHT_CLICK"),
            doubleChestMode = yaml.getString("Stock.Double-Chest-Mode", "expand_only").orEmpty()
        )
    }

    private fun buttonSlot(definition: MenuDefinition, symbol: Char): Int? {
        definition.layout.forEachIndexed { row, line ->
            line.forEachIndexed { column, current ->
                if (current == symbol) {
                    return row * 9 + column
                }
            }
        }
        return null
    }

    private fun goodsSlots(definition: MenuDefinition): List<Int> {
        val result = ArrayList<Int>()
        definition.layout.forEachIndexed { row, line ->
            line.forEachIndexed { column, symbol ->
                val icon = definition.icons[symbol] ?: return@forEachIndexed
                if (icon.mode.equals("goods", true)) {
                    result += row * 9 + column
                }
            }
        }
        return result
    }

    private fun wirePagedMenu(
        holder: MatrixMenuHolder,
        definition: MenuDefinition,
        currentPage: Int,
        maxPage: Int,
        onPage: (Int) -> Unit,
        onBack: () -> Unit
    ) {
        buttonSlot(definition, 'P')?.let { slot ->
            holder.handlers[slot] = { onPage((currentPage - 1).coerceAtLeast(1)) }
        }
        buttonSlot(definition, 'N')?.let { slot ->
            holder.handlers[slot] = { onPage((currentPage + 1).coerceAtMost(maxPage)) }
        }
        buttonSlot(definition, 'B')?.let { slot ->
            holder.handlers[slot] = { onBack() }
        }
        buttonSlot(definition, 'R')?.let { slot ->
            holder.handlers[slot] = { holder.backAction?.invoke() ?: onBack() }
        }
    }

    private fun shopPlaceholders(shop: ChestShopShop, multiplier: Int): Map<String, String> {
        val totalAmount = (shop.tradeAmount * multiplier).coerceAtLeast(1)
        return mapOf(
            "shop-id" to shop.id,
            "owner" to shop.ownerName,
            "mode" to shop.mode.name,
            "amount" to shop.tradeAmount.toString(),
            "multiplier" to multiplier.toString(),
            "total-amount" to totalAmount.toString(),
            "buy-price" to trimDouble(shop.buyPrice),
            "sell-price" to trimDouble(shop.sellPrice),
            "total-buy-price" to trimDouble(shop.buyPrice * multiplier),
            "total-sell-price" to trimDouble(shop.sellPrice * multiplier),
            "stock" to countStock(shop).toString(),
            "item" to itemDisplayName(shop.item),
            "double" to (shop.secondaryChest != null).toString()
        )
    }

    private fun buildPreviewItem(shop: ChestShopShop, multiplier: Int): ItemStack {
        val totalAmount = (shop.tradeAmount * multiplier).coerceAtLeast(1)
        val preview = shop.item.clone().apply {
            amount = totalAmount.coerceAtMost(maxStackSize)
        }
        val lore = ArrayList<String>()
        lore += preview.itemMeta?.lore ?: emptyList()
        lore += Texts.color("&7Shop: &f${shop.id}")
        lore += Texts.color("&7Owner: &f${shop.ownerName}")
        lore += Texts.color("&7Mode: &f${shop.mode.name}")
        lore += Texts.color("&7Trade amount: &f${shop.tradeAmount}")
        lore += Texts.color("&7Preview amount: &f$totalAmount")
        lore += Texts.color("&7Stock: &f${countStock(shop)}")
        if (shop.mode != ChestShopMode.SELL) {
            lore += Texts.color("&7Buy from player: &e${trimDouble(shop.buyPrice)}")
        }
        if (shop.mode != ChestShopMode.BUY) {
            lore += Texts.color("&7Sell to player: &e${trimDouble(shop.sellPrice)}")
        }
        preview.itemMeta = preview.itemMeta?.apply {
            MenuRenderer.decorate(this, itemDisplayName(shop.item), lore)
        }
        return preview
    }

    private fun historyMaterial(type: String): Material {
        return when (type.lowercase(Locale.ROOT)) {
            "buy", "purchase", "income" -> Material.EMERALD
            "sell", "sale", "expense" -> Material.GOLD_INGOT
            "create" -> Material.CHEST
            "remove" -> Material.BARRIER
            else -> Material.PAPER
        }
    }

    private fun nextMode(shop: ChestShopShop): ChestShopMode {
        val sequence = if (shop.secondaryChest != null && settings.doubleChestMode.equals("expand_only", true)) {
            listOf(ChestShopMode.BUY, ChestShopMode.SELL, ChestShopMode.DUAL)
        } else {
            listOf(ChestShopMode.BUY, ChestShopMode.SELL)
        }
        val currentIndex = sequence.indexOf(shop.mode).coerceAtLeast(0)
        return sequence[(currentIndex + 1) % sequence.size]
    }

    private fun nextAmount(current: Int): Int {
        val steps = listOf(1, 8, 16, 32, 64)
        val exact = steps.indexOf(current)
        if (exact >= 0) {
            return steps[(exact + 1) % steps.size]
        }
        return steps.firstOrNull { it > current } ?: steps.first()
    }

    private fun detectSigns(shop: ChestShopShop) {
        val locations = LinkedHashSet<String>()
        val result = ArrayList<ChestShopLocation>()
        listOfNotNull(locationToBlock(shop.primaryChest), shop.secondaryChest?.let { locationToBlock(it) }).forEach { chest ->
            listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP).forEach { face ->
                val relative = chest.getRelative(face)
                if (isSignBlock(relative)) {
                    val location = relative.toShopLocation()
                    if (locations.add(location.key())) {
                        result += location
                    }
                }
            }
        }
        shop.signLocations.clear()
        shop.signLocations += result
    }

    private fun updateSigns(shop: ChestShopShop) {
        val placeholders = shopPlaceholders(shop, 1)
        val lines = signConfig.getStringList("Formats.${shop.mode.name}")
            .ifEmpty { defaultSignLines(shop.mode) }
        val applied = Texts.apply(lines, placeholders)
        shop.signLocations.removeIf { location ->
            val sign = locationToBlock(location)?.state as? Sign ?: return@removeIf true
            for (index in 0 until 4) {
                sign.setLine(index, applied.getOrNull(index) ?: "")
            }
            sign.update(true)
            false
        }
        saveShop(shop)
    }

    private fun clearSigns(shop: ChestShopShop) {
        shop.signLocations.forEach { location ->
            val sign = locationToBlock(location)?.state as? Sign ?: return@forEach
            for (index in 0 until 4) {
                sign.setLine(index, "")
            }
            sign.update(true)
        }
    }

    private fun defaultSignLines(mode: ChestShopMode): List<String> {
        return when (mode) {
            ChestShopMode.BUY -> listOf("&8[ChestShop]", "&aBUY", "&f{item} x{amount}", "&e{buy-price}")
            ChestShopMode.SELL -> listOf("&8[ChestShop]", "&6SELL", "&f{item} x{amount}", "&e{sell-price}")
            ChestShopMode.DUAL -> listOf("&8[ChestShop]", "&bDUAL", "&f{item} x{amount}", "&a{buy-price} &7/ &6{sell-price}")
        }
    }

    private fun findShopByBlock(block: Block): ChestShopShop? {
        val key = block.toShopLocation().key()
        return allShops().firstOrNull { it.primaryChest.key() == key || it.secondaryChest?.key() == key }
    }

    private fun findShopBySign(block: Block): ChestShopShop? {
        if (!isSignBlock(block)) {
            return null
        }
        val key = block.toShopLocation().key()
        return allShops().firstOrNull { shop -> shop.signLocations.any { it.key() == key } }
    }

    @Suppress("DEPRECATION")
    private fun targetBlock(player: Player): Block? {
        return player.getTargetBlock(null as Set<Material>?, 5)
    }

    private fun isChestBlock(block: Block): Boolean {
        return block.state is Chest
    }

    private fun isSignBlock(block: Block): Boolean {
        return block.state is Sign
    }

    private fun findAdjacentChest(block: Block): Block? {
        return listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)
            .map { block.getRelative(it) }
            .firstOrNull { isChestBlock(it) }
    }

    private fun parseMode(modeRaw: String?): ChestShopMode? {
        if (modeRaw.isNullOrBlank()) {
            return null
        }
        return runCatching { ChestShopMode.valueOf(modeRaw.uppercase(Locale.ROOT)) }.getOrNull()
    }

    private fun nextShopId(): String {
        return "cs-${System.currentTimeMillis().toString(36)}-${UUID.randomUUID().toString().take(6)}"
    }

    private fun trimDouble(value: Double): String {
        return if (value % 1.0 == 0.0) {
            value.toInt().toString()
        } else {
            String.format(Locale.US, "%.2f", value)
        }
    }

    private fun countStock(shop: ChestShopShop): Int {
        return stockInventories(shop).sumOf { inventory ->
            countMatching(inventory.contents.toList(), shop.item)
        }
    }

    private fun splitStacks(template: ItemStack, totalAmount: Int): List<ItemStack> {
        val result = ArrayList<ItemStack>()
        var remaining = totalAmount
        while (remaining > 0) {
            val stack = template.clone()
            stack.amount = remaining.coerceAtMost(stack.maxStackSize)
            result += stack
            remaining -= stack.amount
        }
        return result
    }

    private fun canFitPlayer(player: Player, incoming: List<ItemStack>): Boolean {
        return canFitSlots(player.inventory.contents.take(36), 36, incoming)
    }

    private fun canFitInventories(inventories: List<Inventory>, incoming: List<ItemStack>): Boolean {
        return canFitSlots(inventories.flatMap { it.contents.toList() }, inventories.map { it.size }.sum(), incoming)
    }

    private fun canFitSlots(contents: List<ItemStack?>, size: Int, incoming: List<ItemStack>): Boolean {
        val virtual = ArrayList<ItemStack?>()
        contents.forEach { virtual += it?.clone() }
        while (virtual.size < size) {
            virtual += null
        }
        incoming.forEach { stack ->
            var remaining = stack.amount
            virtual.forEachIndexed { index, current ->
                if (remaining <= 0) {
                    return@forEachIndexed
                }
                if (current != null && current.isSimilar(stack) && current.amount < current.maxStackSize) {
                    val free = current.maxStackSize - current.amount
                    val moved = remaining.coerceAtMost(free)
                    current.amount += moved
                    remaining -= moved
                    virtual[index] = current
                }
            }
            while (remaining > 0) {
                val emptyIndex = virtual.indexOfFirst { it == null || it.type == Material.AIR }
                if (emptyIndex == -1) {
                    return false
                }
                val placed = stack.clone()
                placed.amount = remaining.coerceAtMost(placed.maxStackSize)
                virtual[emptyIndex] = placed
                remaining -= placed.amount
            }
        }
        return true
    }

    private fun removeFromStock(shop: ChestShopShop, amount: Int): Boolean {
        if (countStock(shop) < amount) {
            return false
        }
        var remaining = amount
        stockInventories(shop).forEach { inventory ->
            for (slot in 0 until inventory.size) {
                val item = inventory.getItem(slot) ?: continue
                if (!item.isSimilar(shop.item)) {
                    continue
                }
                val taken = remaining.coerceAtMost(item.amount)
                item.amount -= taken
                if (item.amount <= 0) {
                    inventory.setItem(slot, null)
                } else {
                    inventory.setItem(slot, item)
                }
                remaining -= taken
                if (remaining <= 0) {
                    return true
                }
            }
        }
        return remaining <= 0
    }

    private fun addToStock(shop: ChestShopShop, stacks: List<ItemStack>): Boolean {
        if (!canFitInventories(stockInventories(shop), stacks)) {
            return false
        }
        val pending = stacks.map { it.clone() }.toMutableList()
        stockInventories(shop).forEach { inventory ->
            if (pending.isEmpty()) {
                return@forEach
            }
            val leftovers = inventory.addItem(*pending.toTypedArray())
            pending.clear()
            pending += leftovers.values.map { it.clone() }
        }
        return pending.isEmpty()
    }

    private fun appendHistory(shop: ChestShopShop, type: String, actor: String, amount: Int, money: Double, note: String) {
        shop.history += ChestShopHistoryEntry(
            type = type,
            actor = actor,
            amount = amount,
            money = money,
            createdAt = System.currentTimeMillis(),
            note = note
        )
        while (shop.history.size > 50) {
            shop.history.removeAt(0)
        }
    }

    private fun countMatching(items: List<ItemStack?>, template: ItemStack): Int {
        return items.filterNotNull().filter { it.isSimilar(template) }.sumOf { it.amount }
    }

    private fun removeFromPlayer(player: Player, template: ItemStack, amount: Int): Boolean {
        if (countMatching(player.inventory.contents.take(36), template) < amount) {
            return false
        }
        var remaining = amount
        for (slot in 0 until 36) {
            val item = player.inventory.getItem(slot) ?: continue
            if (!item.isSimilar(template)) {
                continue
            }
            val taken = remaining.coerceAtMost(item.amount)
            item.amount -= taken
            if (item.amount <= 0) {
                player.inventory.setItem(slot, null)
            } else {
                player.inventory.setItem(slot, item)
            }
            remaining -= taken
            if (remaining <= 0) {
                return true
            }
        }
        return remaining <= 0
    }

    private fun stockInventories(shop: ChestShopShop): List<Inventory> {
        val inventories = LinkedHashMap<String, Inventory>()
        listOfNotNull(shop.primaryChest, shop.secondaryChest).forEach { location ->
            val chest = locationToBlock(location)?.state as? Chest ?: return@forEach
            inventories[location.key()] = chest.blockInventory
        }
        return inventories.values.toList()
    }

    private fun itemDisplayName(item: ItemStack): String {
        if (item.type == Material.AIR) {
            return "air"
        }
        return item.itemMeta?.displayName ?: item.type.name.lowercase(Locale.ROOT).replace('_', ' ')
    }

    private fun Block.toShopLocation(): ChestShopLocation {
        return ChestShopLocation(world.name, x, y, z)
    }

    private fun locationToBlock(location: ChestShopLocation): Block? {
        val world = Bukkit.getWorld(location.world) ?: return null
        return world.getBlockAt(location.x, location.y, location.z)
    }
}
