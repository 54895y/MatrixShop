package com.y54895.matrixshop.module.transaction

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
import com.y54895.matrixshop.core.module.ModuleRegistry
import com.y54895.matrixshop.core.record.RecordService
import com.y54895.matrixshop.core.text.Texts
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import java.io.File
import java.util.UUID

object TransactionModule : MatrixModule {

    override val id: String = "transaction"
    override val displayName: String = "Transaction"

    private lateinit var settings: TransactionSettings
    private lateinit var menus: TransactionMenus

    private val pendingRequests = HashMap<UUID, MutableList<TransactionRequest>>()
    private val sessionsById = HashMap<String, TransactionSession>()
    private val sessionByPlayer = HashMap<UUID, String>()

    override fun isEnabled(): Boolean {
        return ConfigFiles.isModuleEnabled(id, true)
    }

    override fun reload() {
        if (::settings.isInitialized) {
            sessionsById.values.toList().distinctBy { it.id }.forEach {
                cancelSession(it, "&cTrade module reloaded, the active trade was cancelled.", false)
            }
        }
        pendingRequests.clear()
        sessionsById.clear()
        sessionByPlayer.clear()
        if (!isEnabled()) {
            return
        }
        val dataFolder = ConfigFiles.dataFolder()
        settings = loadSettings()
        menus = TransactionMenus(
            shopViews = ShopMenuLoader.load("Transaction", "request.yml"),
            request = MenuLoader.load(File(dataFolder, "Transaction/ui/request.yml")),
            trade = MenuLoader.load(File(dataFolder, "Transaction/ui/trade.yml")),
            confirm = MenuLoader.load(File(dataFolder, "Transaction/ui/confirm.yml"))
        )
    }

    fun requestTrade(requester: Player, targetName: String?) {
        requestTrade(requester, null, targetName)
    }

    fun requestTrade(requester: Player, shopId: String?, targetName: String?) {
        if (!ensureReady(requester)) {
            return
        }
        val resolvedName = targetName?.trim().orEmpty()
        if (resolvedName.isBlank()) {
            Texts.send(requester, "&cUsage: ${CommandUsageContext.modulePrefix(requester, "transaction", "/trade")} request <player>")
            return
        }
        cleanupExpiredRequests()
        val target = Bukkit.getPlayer(resolvedName)
        if (target == null || !target.isOnline) {
            Texts.send(requester, "&cTarget player is not online.")
            return
        }
        if (target.uniqueId == requester.uniqueId) {
            Texts.send(requester, "&cYou cannot trade with yourself.")
            return
        }
        if (activeSession(requester.uniqueId) != null) {
            Texts.send(requester, "&cYou already have an active trade.")
            return
        }
        if (activeSession(target.uniqueId) != null) {
            Texts.send(requester, "&cThe target player already has an active trade.")
            return
        }
        val requests = pendingRequests.computeIfAbsent(target.uniqueId) { mutableListOf() }
        if (!settings.allowMultiPending && requests.isNotEmpty()) {
            Texts.send(requester, "&cThe target already has a pending trade request.")
            return
        }
        if (requests.size >= settings.maxPending) {
            Texts.send(requester, "&cThe target already reached the maximum pending requests.")
            return
        }
        if (requests.any { it.requesterId == requester.uniqueId && !it.isExpired() }) {
            Texts.send(requester, "&cYou already sent a pending request to this player.")
            return
        }
        val request = TransactionRequest(
            shopId = resolveShopId(shopId),
            requesterId = requester.uniqueId,
            requesterName = requester.name,
            targetId = target.uniqueId,
            targetName = target.name,
            createdAt = System.currentTimeMillis(),
            expireAt = System.currentTimeMillis() + settings.requestTimeoutSeconds * 1000L
        )
        requests += request
        Texts.send(requester, "&aTrade request sent to &f${target.name}&a.")
        Texts.send(target, "&e${requester.name} &frequested a trade with you.")
        openRequestMenu(target, request)
    }

    fun acceptRequest(target: Player, requesterName: String? = null) {
        if (!ensureReady(target)) {
            return
        }
        cleanupExpiredRequests()
        val request = findRequest(target.uniqueId, requesterName)
        if (request == null) {
            Texts.send(target, "&cNo matching trade request was found.")
            return
        }
        val requester = Bukkit.getPlayer(request.requesterId)
        if (requester == null || !requester.isOnline) {
            removeRequest(request)
            Texts.send(target, "&cThe requester is no longer online.")
            return
        }
        if (activeSession(target.uniqueId) != null || activeSession(requester.uniqueId) != null) {
            removeRequest(request)
            Texts.send(target, "&cOne of the players already has an active trade.")
            return
        }
        val violation = sessionConstraintViolation(requester, target)
        if (violation != null) {
            removeRequest(request)
            Texts.send(target, violation)
            Texts.send(requester, violation)
            return
        }
        removeAllRelatedRequests(requester.uniqueId, target.uniqueId)
        val session = TransactionSession(
            id = nextSessionId(),
            shopId = request.shopId,
            leftId = requester.uniqueId,
            leftName = requester.name,
            rightId = target.uniqueId,
            rightName = target.name
        )
        sessionsById[session.id] = session
        sessionByPlayer[requester.uniqueId] = session.id
        sessionByPlayer[target.uniqueId] = session.id
        Texts.send(requester, "&a${target.name} accepted your trade request.")
        Texts.send(target, "&aTrade started with &f${requester.name}&a.")
        openTrade(requester, session)
        openTrade(target, session)
    }

    fun denyRequest(target: Player, requesterName: String? = null) {
        if (!ensureReady(target)) {
            return
        }
        cleanupExpiredRequests()
        val request = findRequest(target.uniqueId, requesterName)
        if (request == null) {
            Texts.send(target, "&cNo matching trade request was found.")
            return
        }
        removeRequest(request)
        Texts.send(target, "&aTrade request denied.")
        Bukkit.getPlayer(request.requesterId)?.let {
            Texts.send(it, "&c${target.name} denied your trade request.")
        }
    }

    fun open(player: Player) {
        open(player, null)
    }

    fun open(player: Player, shopId: String?) {
        if (!ensureReady(player)) {
            return
        }
        val session = activeSession(player.uniqueId)
        if (session == null) {
            openShopEntry(player, resolveShopId(shopId))
            return
        }
        if (session.confirmPhase) {
            openConfirm(player, session)
        } else {
            openTrade(player, session)
        }
    }

    fun setMoney(player: Player, amount: Double?) {
        if (!ensureReady(player)) {
            return
        }
        if (!settings.allowMoney) {
            Texts.send(player, "&cThis server disabled money offers in trade.")
            return
        }
        val session = activeSession(player.uniqueId)
        if (session == null) {
            Texts.send(player, "&cYou do not have an active trade.")
            return
        }
        val safeAmount = (amount ?: 0.0).coerceAtLeast(0.0)
        if (!VaultEconomyBridge.isAvailable() && safeAmount > 0) {
            Texts.send(player, "&cVault economy is not available.")
            return
        }
        if (safeAmount > VaultEconomyBridge.balance(player)) {
            Texts.send(player, "&cYou do not have enough balance for this offer.")
            return
        }
        setMoney(session, sideOf(session, player.uniqueId), safeAmount)
        markDirty(session)
        Texts.send(player, "&aTrade money offer updated to &e${trimDouble(safeAmount)}&a.")
    }

    fun setExp(player: Player, amount: Int?) {
        if (!ensureReady(player)) {
            return
        }
        if (!settings.allowExp) {
            Texts.send(player, "&cThis server disabled exp offers in trade.")
            return
        }
        val session = activeSession(player.uniqueId)
        if (session == null) {
            Texts.send(player, "&cYou do not have an active trade.")
            return
        }
        val safeAmount = (amount ?: 0).coerceAtLeast(0)
        if (safeAmount > totalExperience(player)) {
            Texts.send(player, "&cYou do not have enough experience for this offer.")
            return
        }
        setExp(session, sideOf(session, player.uniqueId), safeAmount)
        markDirty(session)
        Texts.send(player, "&aTrade exp offer updated to &f$safeAmount&a.")
    }

    fun toggleReady(player: Player) {
        if (!ensureReady(player)) {
            return
        }
        val session = activeSession(player.uniqueId)
        if (session == null) {
            Texts.send(player, "&cYou do not have an active trade.")
            return
        }
        if (session.confirmPhase) {
            Texts.send(player, "&cThe trade is already in confirm phase.")
            return
        }
        val side = sideOf(session, player.uniqueId)
        setReady(session, side, !readyOf(session, side))
        setConfirmed(session, TransactionSide.LEFT, false)
        setConfirmed(session, TransactionSide.RIGHT, false)
        refreshSessionViews(session)
        if (session.leftReady && session.rightReady) {
            notifyPlayers(session, "&aBoth players are ready. Click confirm to enter final confirmation.")
        } else {
            notifyPlayers(session, "&eTrade ready state updated.")
        }
    }

    fun confirm(player: Player, submit: Boolean = false) {
        if (!ensureReady(player)) {
            return
        }
        val session = activeSession(player.uniqueId)
        if (session == null) {
            Texts.send(player, "&cYou do not have an active trade.")
            return
        }
        if (!session.leftReady || !session.rightReady) {
            Texts.send(player, "&cBoth players must be ready before final confirmation.")
            return
        }
        if (!session.confirmPhase && !submit) {
            session.confirmPhase = true
            session.leftConfirmed = false
            session.rightConfirmed = false
            openConfirmForBoth(session)
            return
        }
        if (!session.confirmPhase) {
            session.confirmPhase = true
        }
        val side = sideOf(session, player.uniqueId)
        setConfirmed(session, side, true)
        if (session.leftConfirmed && session.rightConfirmed) {
            completeSession(session)
        } else {
            Texts.send(player, "&aYour confirmation was submitted. Waiting for the other player.")
            Bukkit.getPlayer(otherId(session, player.uniqueId))?.let {
                Texts.send(it, "&e${player.name} confirmed the trade. Submit yours to finish.")
            }
            openConfirm(player, session)
        }
    }

    fun cancel(player: Player) {
        val session = activeSession(player.uniqueId)
        if (session == null) {
            Texts.send(player, "&cYou do not have an active trade.")
            return
        }
        cancelSession(session, "&c${player.name} cancelled the trade.", true)
    }

    fun openLogs(player: Player) {
        ModuleRegistry.record.open(player, "transaction")
    }

    fun hasShopView(shopId: String?): Boolean {
        return ShopMenuLoader.contains(menus.shopViews, shopId)
    }

    fun resolveBoundShop(token: String?): String? {
        return ShopMenuLoader.resolveByBinding(menus.shopViews, token)?.id
    }

    fun helpEntries(): List<ShopMenuSelection> {
        return ShopMenuLoader.helpEntries(menus.shopViews)
    }

    fun allShopEntries(): List<ShopMenuSelection> {
        return ShopMenuLoader.allEntries(menus.shopViews)
    }

    fun standaloneEntries(): List<ShopMenuSelection> {
        return ShopMenuLoader.standaloneEntries(menus.shopViews)
    }

    internal fun handleTradeClick(player: Player, holder: TransactionTradeHolder, event: InventoryClickEvent) {
        val session = sessionsById[holder.sessionId] ?: run {
            player.closeInventory()
            return
        }
        if (holder.viewerId != player.uniqueId) {
            event.isCancelled = true
            return
        }
        event.isCancelled = true
        val ownOfferSlots = slotsByMode(menus.trade, "self-offer")
        val moneySlot = buttonSlot(menus.trade, 'M')
        val expSlot = buttonSlot(menus.trade, 'E')
        val readySlot = buttonSlot(menus.trade, 'R')
        val confirmSlot = buttonSlot(menus.trade, 'C')
        val cancelSlot = buttonSlot(menus.trade, 'Q')
        if (event.clickedInventory == event.view.topInventory) {
            when (event.rawSlot) {
                in ownOfferSlots -> returnOffer(player, session, ownOfferSlots.indexOf(event.rawSlot))
                moneySlot -> {
                    if (event.click.isRightClick) {
                        setMoney(player, 0.0)
                    } else {
                        Texts.send(
                            player,
                            Texts.tr(
                                ModuleBindings.hintKey("transaction", "money") ?: "@commands.hints.transaction-money",
                                mapOf("command" to "${CommandUsageContext.modulePrefix(player, "transaction", "/trade")} money")
                            )
                        )
                    }
                }
                expSlot -> {
                    if (event.click.isRightClick) {
                        setExp(player, 0)
                    } else {
                        Texts.send(
                            player,
                            Texts.tr(
                                ModuleBindings.hintKey("transaction", "exp") ?: "@commands.hints.transaction-exp",
                                mapOf("command" to "${CommandUsageContext.modulePrefix(player, "transaction", "/trade")} exp")
                            )
                        )
                    }
                }
                readySlot -> toggleReady(player)
                confirmSlot -> confirm(player, false)
                cancelSlot -> cancel(player)
            }
            return
        }
        if (event.clickedInventory == player.inventory && event.isShiftClick) {
            val current = event.currentItem ?: return
            if (current.type == Material.AIR || current.amount <= 0) {
                return
            }
            addOfferFromInventory(player, session, event.slot, current)
            return
        }
        if (event.clickedInventory == player.inventory) {
            Texts.send(player, "&7Shift-click an item to add it to the trade.")
        }
    }

    internal fun handleTradeDrag(event: InventoryDragEvent) {
        if (event.inventory.holder is TransactionTradeHolder) {
            event.isCancelled = true
        }
    }

    internal fun handleDamage(player: Player) {
        if (!::settings.isInitialized || !settings.cancelOnDamage) {
            return
        }
        activeSession(player.uniqueId)?.let {
            cancelSession(it, "&cTrade cancelled because one player took damage.", true)
        }
    }

    internal fun handleDeath(player: Player) {
        if (!::settings.isInitialized || !settings.cancelOnDeath) {
            return
        }
        activeSession(player.uniqueId)?.let {
            cancelSession(it, "&cTrade cancelled because one player died.", true)
        }
    }

    internal fun handleQuit(player: Player) {
        if (!::settings.isInitialized || !settings.cancelOnQuit) {
            return
        }
        activeSession(player.uniqueId)?.let {
            cancelSession(it, "&cTrade cancelled because one player left the server.", true)
        }
    }

    internal fun handleWorldChange(player: Player) {
        if (!::settings.isInitialized || !settings.cancelOnWorldChange) {
            return
        }
        activeSession(player.uniqueId)?.let {
            cancelSession(it, "&cTrade cancelled because one player changed world.", true)
        }
    }

    private fun openShopEntry(player: Player, shopId: String) {
        val selectedShop = ShopMenuLoader.resolve(menus.shopViews, shopId)
        MenuRenderer.open(
            player = player,
            definition = selectedShop.definition,
            placeholders = mapOf(
                "command" to CommandUsageContext.modulePrefix(player, "transaction", "/trade"),
                "hint-request" to Texts.tr(
                    selectedShop.bindings.hintKeys["request"] ?: ModuleBindings.hintKey("transaction", "request") ?: "@commands.hints.transaction-request",
                    mapOf("command" to "${CommandUsageContext.modulePrefix(player, "transaction", "/trade")} request")
                ),
                "player" to player.name,
                "shop-id" to selectedShop.id
            ),
            goodsRenderer = { holder, _ ->
                buttonSlot(selectedShop.definition, 'R')?.let { slot ->
                    holder.handlers[slot] = {
                        Texts.send(
                            player,
                            Texts.tr(
                                selectedShop.bindings.hintKeys["request"] ?: ModuleBindings.hintKey("transaction", "request") ?: "@commands.hints.transaction-request",
                                mapOf("command" to "${CommandUsageContext.modulePrefix(player, "transaction", "/trade")} request")
                            )
                        )
                    }
                }
                buttonSlot(selectedShop.definition, 'L')?.let { slot ->
                    holder.handlers[slot] = { openLogs(player) }
                }
                buttonSlot(selectedShop.definition, 'A')?.let { slot ->
                    holder.handlers[slot] = {
                        val session = activeSession(player.uniqueId)
                        if (session == null) {
                            Texts.send(player, "&cYou do not have an active trade.")
                        } else if (session.confirmPhase) {
                            openConfirm(player, session)
                        } else {
                            openTrade(player, session)
                        }
                    }
                }
            }
        )
    }

    private fun openRequestMenu(target: Player, request: TransactionRequest) {
        val placeholders = mapOf(
            "requester" to request.requesterName,
            "expire" to "${((request.expireAt - System.currentTimeMillis()).coerceAtLeast(0L) / 1000L)}s",
            "shop-id" to request.shopId
        )
        MenuRenderer.open(
            player = target,
            definition = menus.request,
            placeholders = placeholders,
            goodsRenderer = { holder, _ ->
                renderRequestInfo(holder, request, placeholders)
                buttonSlot(menus.request, 'A')?.let { slot ->
                    holder.handlers[slot] = {
                        acceptRequest(target, request.requesterName)
                    }
                }
                buttonSlot(menus.request, 'D')?.let { slot ->
                    holder.handlers[slot] = {
                        denyRequest(target, request.requesterName)
                    }
                }
            }
        )
    }

    private fun renderRequestInfo(holder: MatrixMenuHolder, request: TransactionRequest, placeholders: Map<String, String>) {
        val slot = buttonSlot(menus.request, 'P') ?: return
        val icon = menus.request.icons['P'] ?: return
        val stack = ItemStack(Material.SKULL_ITEM, 1, 3.toShort())
        val meta = stack.itemMeta as? SkullMeta
        if (meta != null) {
            meta.owner = request.requesterName
            MenuRenderer.decorate(meta, Texts.apply(icon.name, placeholders), Texts.apply(icon.lore, placeholders))
            stack.itemMeta = meta
        }
        holder.backingInventory.setItem(slot, stack)
    }

    private fun openTrade(player: Player, session: TransactionSession) {
        val holder = TransactionTradeHolder(session.id, player.uniqueId)
        val title = Texts.apply(
            menus.trade.title.firstOrNull().orEmpty(),
            mapOf("self" to player.name, "target" to otherName(session, player.uniqueId), "shop-id" to session.shopId)
        )
        val inventory = Bukkit.createInventory(holder, menus.trade.layout.size * 9, title)
        holder.backingInventory = inventory
        renderTradeInventory(player, holder, session)
        player.openInventory(inventory)
    }

    private fun renderTradeInventory(player: Player, holder: TransactionTradeHolder, session: TransactionSession) {
        val placeholders = tradePlaceholders(session, player.uniqueId)
        holder.backingInventory.clear()
        menus.trade.layout.forEachIndexed { row, line ->
            line.forEachIndexed { column, symbol ->
                val slot = row * 9 + column
                val icon = menus.trade.icons[symbol] ?: return@forEachIndexed
                if (icon.mode.equals("self-offer", true) || icon.mode.equals("target-offer", true)) {
                    return@forEachIndexed
                }
                holder.backingInventory.setItem(slot, MenuRenderer.buildIcon(icon, placeholders))
            }
        }
        renderOfferSide(holder, slotsByMode(menus.trade, "self-offer"), offersOf(session, sideOf(session, player.uniqueId)))
        renderOfferSide(holder, slotsByMode(menus.trade, "target-offer"), offersOf(session, oppositeSide(sideOf(session, player.uniqueId))))
    }

    private fun renderOfferSide(holder: TransactionTradeHolder, slots: List<Int>, offers: List<ItemStack?>) {
        slots.forEachIndexed { index, slot ->
            holder.backingInventory.setItem(slot, offers.getOrNull(index)?.clone())
        }
    }

    private fun openConfirmForBoth(session: TransactionSession) {
        Bukkit.getPlayer(session.leftId)?.let { openConfirm(it, session) }
        Bukkit.getPlayer(session.rightId)?.let { openConfirm(it, session) }
    }

    private fun openConfirm(player: Player, session: TransactionSession) {
        val selfSide = sideOf(session, player.uniqueId)
        val targetSide = oppositeSide(selfSide)
        MenuRenderer.open(
            player = player,
            definition = menus.confirm,
            placeholders = mapOf(
                "self-item-size" to offeredItems(offersOf(session, selfSide)).size.toString(),
                "self-money" to trimDouble(moneyOf(session, selfSide)),
                "self-exp" to expOf(session, selfSide).toString(),
                "target-item-size" to offeredItems(offersOf(session, targetSide)).size.toString(),
                "target-money" to trimDouble(moneyOf(session, targetSide)),
                "target-exp" to expOf(session, targetSide).toString(),
                "shop-id" to session.shopId
            ),
            goodsRenderer = { holder, _ ->
                buttonSlot(menus.confirm, 'C')?.let { slot ->
                    holder.handlers[slot] = {
                        confirm(player, true)
                    }
                }
                buttonSlot(menus.confirm, 'X')?.let { slot ->
                    holder.handlers[slot] = {
                        cancel(player)
                    }
                }
            }
        )
    }

    private fun addOfferFromInventory(player: Player, session: TransactionSession, inventorySlot: Int, current: ItemStack) {
        if (!settings.allowItems) {
            Texts.send(player, "&cThis server disabled item offers in trade.")
            return
        }
        val side = sideOf(session, player.uniqueId)
        val offers = offersOf(session, side)
        val freeIndex = offers.indexOfFirst { it == null || it.type == Material.AIR }
        if (freeIndex == -1) {
            Texts.send(player, "&cYour trade offer slots are full.")
            return
        }
        offers[freeIndex] = current.clone()
        player.inventory.setItem(inventorySlot, ItemStack(Material.AIR))
        player.updateInventory()
        markDirty(session)
        notifyPlayers(session, "&e${player.name} updated the offered items.")
    }

    private fun returnOffer(player: Player, session: TransactionSession, offerIndex: Int) {
        val side = sideOf(session, player.uniqueId)
        val offers = offersOf(session, side)
        val item = offers.getOrNull(offerIndex) ?: return
        offers[offerIndex] = null
        returnItem(player, item)
        player.updateInventory()
        markDirty(session)
        notifyPlayers(session, "&e${player.name} updated the offered items.")
    }

    private fun completeSession(session: TransactionSession) {
        val left = Bukkit.getPlayer(session.leftId)
        val right = Bukkit.getPlayer(session.rightId)
        if (left == null || right == null || !left.isOnline || !right.isOnline) {
            cancelSession(session, "&cTrade cancelled because one player is offline.", true)
            return
        }
        val violation = sessionConstraintViolation(left, right)
        if (violation != null) {
            markDirty(session)
            Texts.send(left, violation)
            Texts.send(right, violation)
            openTrade(left, session)
            openTrade(right, session)
            return
        }
        val leftIncoming = offeredItems(session.rightOffers)
        val rightIncoming = offeredItems(session.leftOffers)
        if (!canFit(left.inventory.contents.filterNotNull(), leftIncoming)) {
            markDirty(session)
            notifyPlayers(session, "&c${left.name} does not have enough inventory space.")
            openTrade(left, session)
            openTrade(right, session)
            return
        }
        if (!canFit(right.inventory.contents.filterNotNull(), rightIncoming)) {
            markDirty(session)
            notifyPlayers(session, "&c${right.name} does not have enough inventory space.")
            openTrade(left, session)
            openTrade(right, session)
            return
        }
        if (settings.allowMoney && (session.leftMoney > 0 || session.rightMoney > 0) && !VaultEconomyBridge.isAvailable()) {
            markDirty(session)
            notifyPlayers(session, "&cVault economy is not available.")
            openTrade(left, session)
            openTrade(right, session)
            return
        }
        if (session.leftMoney > VaultEconomyBridge.balance(left) || session.rightMoney > VaultEconomyBridge.balance(right)) {
            markDirty(session)
            notifyPlayers(session, "&cOne player does not have enough balance anymore.")
            openTrade(left, session)
            openTrade(right, session)
            return
        }
        val leftOriginalExp = totalExperience(left)
        val rightOriginalExp = totalExperience(right)
        if (session.leftExp > leftOriginalExp || session.rightExp > rightOriginalExp) {
            markDirty(session)
            notifyPlayers(session, "&cOne player does not have enough experience anymore.")
            openTrade(left, session)
            openTrade(right, session)
            return
        }
        var withdrewLeft = false
        var withdrewRight = false
        var depositedLeft = false
        var depositedRight = false
        if (session.leftMoney > 0) {
            if (!VaultEconomyBridge.withdraw(left, session.leftMoney)) {
                markDirty(session)
                notifyPlayers(session, "&cFailed to withdraw ${left.name}'s money offer.")
                openTrade(left, session)
                openTrade(right, session)
                return
            }
            withdrewLeft = true
        }
        if (session.rightMoney > 0) {
            if (!VaultEconomyBridge.withdraw(right, session.rightMoney)) {
                if (withdrewLeft) {
                    VaultEconomyBridge.deposit(left, session.leftMoney)
                }
                markDirty(session)
                notifyPlayers(session, "&cFailed to withdraw ${right.name}'s money offer.")
                openTrade(left, session)
                openTrade(right, session)
                return
            }
            withdrewRight = true
        }
        if (session.rightMoney > 0) {
            if (!VaultEconomyBridge.deposit(left, session.rightMoney)) {
                rollbackMoney(left, right, session, withdrewLeft, withdrewRight, depositedLeft, depositedRight)
                markDirty(session)
                notifyPlayers(session, "&cFailed to deposit ${right.name}'s money offer.")
                openTrade(left, session)
                openTrade(right, session)
                return
            }
            depositedLeft = true
        }
        if (session.leftMoney > 0) {
            if (!VaultEconomyBridge.deposit(right, session.leftMoney)) {
                rollbackMoney(left, right, session, withdrewLeft, withdrewRight, depositedLeft, depositedRight)
                markDirty(session)
                notifyPlayers(session, "&cFailed to deposit ${left.name}'s money offer.")
                openTrade(left, session)
                openTrade(right, session)
                return
            }
            depositedRight = true
        }
        setTotalExperience(left, leftOriginalExp - session.leftExp + session.rightExp)
        setTotalExperience(right, rightOriginalExp - session.rightExp + session.leftExp)
        leftIncoming.forEach { left.inventory.addItem(it.clone()) }
        rightIncoming.forEach { right.inventory.addItem(it.clone()) }
        left.updateInventory()
        right.updateInventory()
        if (settings.writeOnComplete) {
            writeCompletionRecord(session)
        }
        left.closeInventory()
        right.closeInventory()
        removeSession(session)
        Texts.send(left, "&aTrade completed successfully with &f${right.name}&a.")
        Texts.send(right, "&aTrade completed successfully with &f${left.name}&a.")
    }

    private fun rollbackMoney(
        left: Player,
        right: Player,
        session: TransactionSession,
        withdrewLeft: Boolean,
        withdrewRight: Boolean,
        depositedLeft: Boolean,
        depositedRight: Boolean
    ) {
        if (depositedLeft && session.rightMoney > 0) {
            VaultEconomyBridge.withdraw(left, session.rightMoney)
        }
        if (depositedRight && session.leftMoney > 0) {
            VaultEconomyBridge.withdraw(right, session.leftMoney)
        }
        if (withdrewLeft && session.leftMoney > 0) {
            VaultEconomyBridge.deposit(left, session.leftMoney)
        }
        if (withdrewRight && session.rightMoney > 0) {
            VaultEconomyBridge.deposit(right, session.rightMoney)
        }
    }

    private fun writeCompletionRecord(session: TransactionSession) {
        val leftIncoming = offeredItems(session.rightOffers)
        val rightIncoming = offeredItems(session.leftOffers)
        RecordService.append(
            module = "transaction",
            type = "complete",
            actor = session.leftName,
            target = session.rightName,
            moneyChange = session.rightMoney - session.leftMoney,
            detail = "shop=${session.shopId};offer-money=${trimDouble(session.leftMoney)};offer-exp=${session.leftExp};offer-items=${itemSummary(session.leftOffers)};receive-money=${trimDouble(session.rightMoney)};receive-exp=${session.rightExp};receive-items=${itemSummary(rightIncoming)}"
        )
        RecordService.append(
            module = "transaction",
            type = "complete",
            actor = session.rightName,
            target = session.leftName,
            moneyChange = session.leftMoney - session.rightMoney,
            detail = "shop=${session.shopId};offer-money=${trimDouble(session.rightMoney)};offer-exp=${session.rightExp};offer-items=${itemSummary(session.rightOffers)};receive-money=${trimDouble(session.leftMoney)};receive-exp=${session.leftExp};receive-items=${itemSummary(leftIncoming)}"
        )
    }

    private fun cancelSession(session: TransactionSession, message: String, writeRecord: Boolean) {
        Bukkit.getPlayer(session.leftId)?.let { player ->
            offeredItems(session.leftOffers).forEach { returnItem(player, it) }
            player.updateInventory()
            Texts.send(player, message)
        }
        Bukkit.getPlayer(session.rightId)?.let { player ->
            offeredItems(session.rightOffers).forEach { returnItem(player, it) }
            player.updateInventory()
            Texts.send(player, message)
        }
        if (writeRecord && settings.writeOnCancel) {
            RecordService.append(
                module = "transaction",
                type = "cancel",
                actor = session.leftName,
                target = session.rightName,
                detail = "shop=${session.shopId};reason=${message.removePrefix("&c")};offer-money=${trimDouble(session.leftMoney)};offer-exp=${session.leftExp};offer-items=${itemSummary(session.leftOffers)}"
            )
            RecordService.append(
                module = "transaction",
                type = "cancel",
                actor = session.rightName,
                target = session.leftName,
                detail = "shop=${session.shopId};reason=${message.removePrefix("&c")};offer-money=${trimDouble(session.rightMoney)};offer-exp=${session.rightExp};offer-items=${itemSummary(session.rightOffers)}"
            )
        }
        removeSession(session)
    }

    private fun removeSession(session: TransactionSession) {
        sessionsById.remove(session.id)
        sessionByPlayer.remove(session.leftId)
        sessionByPlayer.remove(session.rightId)
    }

    private fun markDirty(session: TransactionSession) {
        val wasConfirm = session.confirmPhase
        session.leftReady = false
        session.rightReady = false
        session.leftConfirmed = false
        session.rightConfirmed = false
        session.confirmPhase = false
        if (wasConfirm) {
            Bukkit.getPlayer(session.leftId)?.let {
                Texts.send(it, "&eTrade changed, returning to the trade view.")
                openTrade(it, session)
            }
            Bukkit.getPlayer(session.rightId)?.let {
                Texts.send(it, "&eTrade changed, returning to the trade view.")
                openTrade(it, session)
            }
        } else {
            refreshSessionViews(session)
        }
    }

    private fun refreshSessionViews(session: TransactionSession) {
        Bukkit.getPlayer(session.leftId)?.let { player ->
            val holder = player.openInventory.topInventory.holder as? TransactionTradeHolder
            if (holder != null && holder.sessionId == session.id) {
                renderTradeInventory(player, holder, session)
            }
        }
        Bukkit.getPlayer(session.rightId)?.let { player ->
            val holder = player.openInventory.topInventory.holder as? TransactionTradeHolder
            if (holder != null && holder.sessionId == session.id) {
                renderTradeInventory(player, holder, session)
            }
        }
    }

    private fun ensureReady(player: Player): Boolean {
        if (!isEnabled() || !::settings.isInitialized) {
            Texts.send(player, "&cTransaction module is disabled.")
            return false
        }
        return true
    }

    private fun cleanupExpiredRequests() {
        val now = System.currentTimeMillis()
        pendingRequests.entries.removeIf { entry ->
            entry.value.removeIf { it.isExpired(now) }
            entry.value.isEmpty()
        }
    }

    private fun activeSession(playerId: UUID): TransactionSession? {
        val sessionId = sessionByPlayer[playerId] ?: return null
        return sessionsById[sessionId]
    }

    private fun findRequest(targetId: UUID, requesterName: String?): TransactionRequest? {
        val requests = pendingRequests[targetId].orEmpty().filterNot { it.isExpired() }
        if (requesterName.isNullOrBlank()) {
            return requests.maxByOrNull { it.createdAt }
        }
        return requests.firstOrNull { it.requesterName.equals(requesterName, true) }
    }

    private fun removeRequest(request: TransactionRequest) {
        pendingRequests[request.targetId]?.removeIf {
            it.requesterId == request.requesterId && it.targetId == request.targetId
        }
        if (pendingRequests[request.targetId].isNullOrEmpty()) {
            pendingRequests.remove(request.targetId)
        }
    }

    private fun removeAllRelatedRequests(first: UUID, second: UUID) {
        pendingRequests[first]?.removeIf { it.requesterId == second || it.targetId == second }
        pendingRequests[second]?.removeIf { it.requesterId == first || it.targetId == first }
        pendingRequests.entries.removeIf { it.value.isEmpty() }
    }

    private fun sessionConstraintViolation(first: Player, second: Player): String? {
        if (settings.sameWorldOnly && first.world.name != second.world.name) {
            return "&cBoth players must be in the same world to trade."
        }
        if (first.location.distance(second.location) > settings.maxDistance) {
            return "&cPlayers are too far apart to trade."
        }
        return null
    }

    private fun loadSettings(): TransactionSettings {
        val yaml = YamlConfiguration.loadConfiguration(File(ConfigFiles.dataFolder(), "Transaction/settings.yml"))
        return TransactionSettings(
            requestTimeoutSeconds = yaml.getInt("Options.Request.Timeout-Seconds", 30),
            maxPending = yaml.getInt("Options.Request.Max-Pending", 5),
            allowMultiPending = yaml.getBoolean("Options.Request.Allow-Multi-Pending", true),
            maxDistance = yaml.getDouble("Options.Session.Max-Distance", 8.0),
            sameWorldOnly = yaml.getBoolean("Options.Session.Same-World-Only", true),
            cancelOnDamage = yaml.getBoolean("Options.Session.Cancel-On-Damage", true),
            cancelOnDeath = yaml.getBoolean("Options.Session.Cancel-On-Death", true),
            cancelOnQuit = yaml.getBoolean("Options.Session.Cancel-On-Quit", true),
            cancelOnWorldChange = yaml.getBoolean("Options.Session.Cancel-On-World-Change", true),
            allowItems = yaml.getBoolean("Options.Trade.Allow-Items", true),
            allowMoney = yaml.getBoolean("Options.Trade.Allow-Money", true),
            allowExp = yaml.getBoolean("Options.Trade.Allow-Exp", true),
            writeOnComplete = yaml.getBoolean("Options.Record.Write-On-Complete", true),
            writeOnCancel = yaml.getBoolean("Options.Record.Write-On-Cancel", false)
        )
    }

    private fun tradePlaceholders(session: TransactionSession, viewerId: UUID): Map<String, String> {
        val selfSide = sideOf(session, viewerId)
        val targetSide = oppositeSide(selfSide)
        val viewer = Bukkit.getPlayer(viewerId)
        val command = if (viewer != null) CommandUsageContext.modulePrefix(viewer, "transaction", "/trade") else "/trade"
        return mapOf(
            "command" to command,
            "hint-money" to Texts.tr(ModuleBindings.hintKey("transaction", "money") ?: "@commands.hints.transaction-money", mapOf("command" to "$command money")),
            "hint-exp" to Texts.tr(ModuleBindings.hintKey("transaction", "exp") ?: "@commands.hints.transaction-exp", mapOf("command" to "$command exp")),
            "self" to nameOf(session, selfSide),
            "target" to nameOf(session, targetSide),
            "self-money" to trimDouble(moneyOf(session, selfSide)),
            "self-exp" to expOf(session, selfSide).toString(),
            "target-money" to trimDouble(moneyOf(session, targetSide)),
            "target-exp" to expOf(session, targetSide).toString(),
            "target-ready" to if (readyOf(session, targetSide)) "READY" else "NOT READY",
            "shop-id" to session.shopId
        )
    }

    private fun sideOf(session: TransactionSession, playerId: UUID): TransactionSide {
        return if (session.leftId == playerId) TransactionSide.LEFT else TransactionSide.RIGHT
    }

    private fun oppositeSide(side: TransactionSide): TransactionSide {
        return if (side == TransactionSide.LEFT) TransactionSide.RIGHT else TransactionSide.LEFT
    }

    private fun otherId(session: TransactionSession, playerId: UUID): UUID {
        return if (session.leftId == playerId) session.rightId else session.leftId
    }

    private fun otherName(session: TransactionSession, playerId: UUID): String {
        return if (session.leftId == playerId) session.rightName else session.leftName
    }

    private fun nameOf(session: TransactionSession, side: TransactionSide): String {
        return if (side == TransactionSide.LEFT) session.leftName else session.rightName
    }

    private fun offersOf(session: TransactionSession, side: TransactionSide): MutableList<ItemStack?> {
        return if (side == TransactionSide.LEFT) session.leftOffers else session.rightOffers
    }

    private fun moneyOf(session: TransactionSession, side: TransactionSide): Double {
        return if (side == TransactionSide.LEFT) session.leftMoney else session.rightMoney
    }

    private fun expOf(session: TransactionSession, side: TransactionSide): Int {
        return if (side == TransactionSide.LEFT) session.leftExp else session.rightExp
    }

    private fun readyOf(session: TransactionSession, side: TransactionSide): Boolean {
        return if (side == TransactionSide.LEFT) session.leftReady else session.rightReady
    }

    private fun setMoney(session: TransactionSession, side: TransactionSide, value: Double) {
        if (side == TransactionSide.LEFT) session.leftMoney = value else session.rightMoney = value
    }

    private fun setExp(session: TransactionSession, side: TransactionSide, value: Int) {
        if (side == TransactionSide.LEFT) session.leftExp = value else session.rightExp = value
    }

    private fun setReady(session: TransactionSession, side: TransactionSide, value: Boolean) {
        if (side == TransactionSide.LEFT) session.leftReady = value else session.rightReady = value
    }

    private fun setConfirmed(session: TransactionSession, side: TransactionSide, value: Boolean) {
        if (side == TransactionSide.LEFT) session.leftConfirmed = value else session.rightConfirmed = value
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

    private fun slotsByMode(definition: MenuDefinition, mode: String): List<Int> {
        val slots = ArrayList<Int>()
        definition.layout.forEachIndexed { row, line ->
            line.forEachIndexed { column, char ->
                val icon = definition.icons[char] ?: return@forEachIndexed
                if (icon.mode.equals(mode, true)) {
                    slots += row * 9 + column
                }
            }
        }
        return slots
    }

    private fun offeredItems(offers: List<ItemStack?>): List<ItemStack> {
        return offers.filterNotNull().filter { it.type != Material.AIR && it.amount > 0 }
    }

    private fun notifyPlayers(session: TransactionSession, message: String) {
        Bukkit.getPlayer(session.leftId)?.let { Texts.send(it, message) }
        Bukkit.getPlayer(session.rightId)?.let { Texts.send(it, message) }
    }

    private fun returnItem(player: Player, item: ItemStack) {
        player.inventory.addItem(item.clone()).values.forEach {
            player.world.dropItemNaturally(player.location, it)
        }
    }

    private fun totalExperience(player: Player): Int {
        return player.totalExperience
    }

    private fun setTotalExperience(player: Player, amount: Int) {
        player.exp = 0f
        player.level = 0
        player.totalExperience = 0
        if (amount > 0) {
            player.giveExp(amount)
        }
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

    private fun itemSummary(items: List<ItemStack?>): String {
        return items.filterNotNull()
            .filter { it.type != Material.AIR && it.amount > 0 }
            .joinToString("|") { "${it.type.name}:${it.amount}" }
            .ifBlank { "none" }
    }

    private fun nextSessionId(): String {
        return "trade-${System.currentTimeMillis().toString(36)}"
    }

    private fun trimDouble(value: Double): String {
        return if (value % 1.0 == 0.0) value.toInt().toString() else "%.2f".format(value)
    }

    private fun resolveShopId(shopId: String?): String {
        return ShopMenuLoader.resolve(menus.shopViews, shopId).id
    }
}
