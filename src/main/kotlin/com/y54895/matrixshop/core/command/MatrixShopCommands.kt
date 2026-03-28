package com.y54895.matrixshop.core.command

import com.y54895.matrixshop.MatrixShop
import com.y54895.matrixshop.core.config.ConfigFiles
import com.y54895.matrixshop.core.config.ModuleBindings
import com.y54895.matrixshop.core.database.DatabaseManager
import com.y54895.matrixshop.core.database.LegacyDataMigrationService
import com.y54895.matrixshop.core.economy.VaultEconomyBridge
import com.y54895.matrixshop.core.module.ModuleRegistry
import com.y54895.matrixshop.core.permission.PermissionNodes
import com.y54895.matrixshop.core.permission.Permissions
import com.y54895.matrixshop.core.text.Texts
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import taboolib.common.platform.ProxyCommandSender
import taboolib.common.platform.command.simpleCommand
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object MatrixShopCommands {

    private var registered = false

    fun register() {
        if (registered) {
            return
        }
        registered = true
        val reserved = hashSetOf("matrixshop", "shop", "ms", "matrixshopadmin", "msa")
        simpleCommand(
            name = "matrixshop",
            aliases = listOf("shop", "ms"),
            description = "MatrixShop player command",
            usage = "/matrixshop"
        ) { sender, args ->
            handleMain(sender, args)
        }
        simpleCommand(
            name = "matrixshopadmin",
            aliases = listOf("msa"),
            description = "MatrixShop admin command",
            usage = "/matrixshop admin"
        ) { sender, args ->
            handleAdmin(sender, args)
        }
        registerStandaloneCommand("menu", "menu", "MatrixShop menu command", "/menu", "matrixshop.menu.use", ::handleMenuAlias, reserved)
        registerStandaloneCommand("cart", "cart", "MatrixShop cart command", "/cart", "matrixshop.cart.use", ::handleCartAlias, reserved)
        registerStandaloneCommand("record", "record", "MatrixShop record command", "/record", "matrixshop.record.use", ::handleRecordAlias, reserved)
        registerStandaloneCommand("transaction", "trade", "MatrixShop trade command", "/trade", "matrixshop.transaction.use", ::handleTradeAlias, reserved)
        registerStandaloneCommand("auction", "auction", "MatrixShop auction command", "/auction", "matrixshop.auction.use", ::handleAuctionAlias, reserved)
        registerStandaloneCommand("chestshop", "chestshop", "MatrixShop chest shop command", "/chestshop", "matrixshop.chestshop.use", ::handleChestShopAlias, reserved)
        registerStandaloneShopCommands("menu", "MatrixShop menu command", "/menu", "matrixshop.menu.use", reserved)
        registerStandaloneShopCommands("global-market", "MatrixShop global market command", "/market", "matrixshop.globalmarket.use", reserved)
        registerStandaloneShopCommands("player-shop", "MatrixShop player shop command", "/playershop", "matrixshop.playershop.use", reserved)
        registerStandaloneShopCommands("auction", "MatrixShop auction command", "/auction", "matrixshop.auction.use", reserved)
        registerStandaloneShopCommands("transaction", "MatrixShop trade command", "/trade", "matrixshop.transaction.use", reserved)
    }

    private fun handleMain(sender: ProxyCommandSender, args: Array<String>) {
        if (args.isNotEmpty() && args[0].equals("admin", true)) {
            handleAdmin(sender, args.copyOfRange(1, args.size))
            return
        }
        val player = requirePlayer(sender) ?: return
        if (args.isEmpty()) {
            if (Permissions.require(player, PermissionNodes.SYSTEMSHOP_USE)) {
                ModuleRegistry.systemShop.openMain(player)
            }
            return
        }
        val shopRoute = resolveBoundShopRoute(args[0])
        val moduleRoute = ModuleBindings.resolveModule(args[0])
        when {
            args[0].equals("help", true) -> sendPlayerHelp(player)
            args[0].equals("open", true) -> handleMainOpen(player, args.drop(1))
            shopRoute != null -> handleBoundShop(player, shopRoute, args.drop(1))
            moduleRoute == "menu" -> handleMenu(player, args.drop(1))
            moduleRoute == "auction" -> handleAuction(player, args.drop(1))
            moduleRoute == "system-shop" -> handleSystem(player, args.drop(1))
            moduleRoute == "player-shop" -> handlePlayerShop(player, args.drop(1))
            moduleRoute == "global-market" -> handleGlobalMarket(player, args.drop(1))
            moduleRoute == "cart" -> handleCart(player, args.drop(1))
            moduleRoute == "record" -> handleRecord(player, args.drop(1))
            moduleRoute == "transaction" -> handleTransaction(player, args.drop(1))
            moduleRoute == "chestshop" -> handleChestShop(player, args.drop(1))
            else -> sendPlayerHelp(player)
        }
    }

    private fun handleMenu(player: Player, args: List<String>, defaultShopId: String? = null) {
        if (!Permissions.require(player, PermissionNodes.MENU_USE)) {
            return
        }
        if (args.isEmpty()) {
            ModuleRegistry.menu.open(player, defaultShopId)
            return
        }
        when (args[0].lowercase()) {
            "open" -> ModuleRegistry.menu.open(player, args.getOrNull(1) ?: defaultShopId)
            else -> sendUsage(player, boundUsage("menu", defaultShopId, "open"))
        }
    }

    private fun handleMainOpen(player: Player, args: List<String>) {
        if (args.isEmpty()) {
            if (Permissions.require(player, PermissionNodes.SYSTEMSHOP_USE)) {
                ModuleRegistry.systemShop.openMain(player)
            }
            return
        }
        when (val explicit = resolveExplicitOpenTarget(args[0])) {
            is ExplicitOpenResolution.ShopFound -> {
                handleBoundShop(player, explicit.route, listOf("open") + args.drop(1))
                return
            }
            is ExplicitOpenResolution.ShopAmbiguous -> {
                val modules = explicit.routes.joinToString(", ") { typedRouteToken(it) }
                Texts.sendKey(player, "@commands.errors.shop-id-ambiguous-routes", mapOf("shop" to args[0], "routes" to modules))
                return
            }
            is ExplicitOpenResolution.SystemCategory -> {
                if (!Permissions.require(player, PermissionNodes.SYSTEMSHOP_USE)) {
                    return
                }
                ModuleRegistry.systemShop.openCategory(player, explicit.categoryId)
                return
            }
            is ExplicitOpenResolution.InvalidPrefix -> {
                Texts.sendKey(player, "@commands.errors.unknown-shop-prefix", mapOf("prefix" to explicit.prefix))
                return
            }
            is ExplicitOpenResolution.NotFound -> {
                Texts.sendKey(player, "@commands.errors.open-target-not-found", mapOf("target" to typedTargetToken(explicit.moduleId, explicit.targetId)))
                return
            }
            null -> Unit
        }
        when (val resolution = resolveShopIdRoute(args[0])) {
            is ShopIdResolution.Found -> handleBoundShop(player, resolution.route, listOf("open") + args.drop(1))
            is ShopIdResolution.Ambiguous -> {
                val modules = resolution.routes.joinToString(", ") { typedRouteToken(it) }
                Texts.sendKey(player, "@commands.errors.shop-id-ambiguous-modules", mapOf("shop" to args[0], "routes" to modules))
            }
            ShopIdResolution.NotFound -> {
                if (!Permissions.require(player, PermissionNodes.SYSTEMSHOP_USE)) {
                    return
                }
                ModuleRegistry.systemShop.openCategory(player, args[0])
            }
        }
    }

    private fun handleSystem(player: Player, args: List<String>) {
        if (!Permissions.require(player, PermissionNodes.SYSTEMSHOP_USE)) {
            return
        }
        if (args.isEmpty()) {
            ModuleRegistry.systemShop.openMain(player)
            return
        }
        when (args[0].lowercase()) {
            "open" -> {
                val id = args.getOrNull(1) ?: ConfigFiles.defaultSystemCategory()
                ModuleRegistry.systemShop.openCategory(player, id)
            }
            "confirm" -> handleConfirm(player, args.drop(1))
            else -> sendPlayerHelp(player)
        }
    }

    private fun handleConfirm(player: Player, args: List<String>) {
        if (!Permissions.require(player, PermissionNodes.SYSTEMSHOP_USE)) {
            return
        }
        if (args.isEmpty()) {
            Texts.sendKey(player, "@commands.errors.confirm-subcommand-missing")
            return
        }
        when (args[0].lowercase()) {
            "action", "buy" -> ModuleRegistry.systemShop.confirmPurchase(player)
            "cart" -> {
                if (!Permissions.require(player, PermissionNodes.CART_USE)) {
                    return
                }
                ModuleRegistry.cart.addCurrentSystemSelection(player)
            }
            "amount" -> {
                if (args.getOrNull(1)?.equals("add", true) != true) {
                    sendUsage(player, "/matrixshop system confirm amount add <number>")
                    return
                }
                val delta = args.getOrNull(2)?.toIntOrNull()
                if (delta == null) {
                    Texts.sendKey(player, "@commands.errors.amount-integer")
                    return
                }
                ModuleRegistry.systemShop.adjustConfirmAmount(player, delta)
            }
            else -> Texts.sendKey(player, "@commands.errors.confirm-supported-actions")
        }
    }

    private fun handleCart(player: Player, args: List<String>, defaultShopId: String? = null) {
        if (!Permissions.require(player, PermissionNodes.CART_USE)) {
            return
        }
        if (args.isEmpty()) {
            sendUsage(player, boundUsage("cart", defaultShopId, "open"))
            return
        }
        when (args[0].lowercase()) {
            "open" -> ModuleRegistry.cart.open(player, shopId = args.getOrNull(1) ?: defaultShopId)
            "checkout" -> {
                if (!Permissions.require(player, PermissionNodes.CART_CHECKOUT)) {
                    return
                }
                val tail = args.drop(1)
                val confirm = tail.any { it.equals("confirm", true) }
                val validOnly = tail.any { it.equals("valid_only", true) }
                if (confirm) {
                    ModuleRegistry.cart.checkout(player, validOnly, defaultShopId)
                } else {
                    ModuleRegistry.cart.openCheckout(player, validOnly, shopId = defaultShopId)
                }
            }
            "clear" -> {
                if (!Permissions.require(player, PermissionNodes.CART_CLEAR)) {
                    return
                }
                ModuleRegistry.cart.clear(player, defaultShopId)
            }
            "remove" -> {
                if (!Permissions.require(player, PermissionNodes.CART_CLEAR)) {
                    return
                }
                val index = args.getOrNull(1)?.toIntOrNull()
                if (index == null) {
                    sendUsage(player, "/matrixshop cart remove <slot>")
                    return
                }
                ModuleRegistry.cart.remove(player, index, defaultShopId)
            }
            "remove_invalid" -> {
                if (!Permissions.require(player, PermissionNodes.CART_CLEAR)) {
                    return
                }
                ModuleRegistry.cart.removeInvalid(player, defaultShopId)
            }
            "conflict" -> {
                if (!Permissions.require(player, PermissionNodes.CART_CHECKOUT)) {
                    return
                }
                if (args.getOrNull(1)?.equals("recheck", true) == true) {
                    ModuleRegistry.cart.openConflict(player, shopId = defaultShopId)
                } else {
                    ModuleRegistry.cart.openConflict(player, shopId = defaultShopId)
                }
            }
            "amount" -> {
                if (!Permissions.require(player, PermissionNodes.CART_USE)) {
                    return
                }
                val index = args.getOrNull(1)?.toIntOrNull()
                val amount = args.getOrNull(2)?.toIntOrNull()
                if (index == null || amount == null) {
                    sendUsage(player, "/matrixshop cart amount <slot> <number>")
                    return
                }
                ModuleRegistry.cart.changeAmount(player, index, amount, defaultShopId)
            }
            else -> sendUnknownSubcommand(player, "cart")
        }
    }

    private fun handleGlobalMarket(player: Player, args: List<String>, defaultShopId: String? = null) {
        if (!Permissions.require(player, PermissionNodes.GLOBALMARKET_USE)) {
            return
        }
        if (args.isEmpty()) {
            sendUsage(player, boundUsage("global-market", defaultShopId, "open"))
            return
        }
        when (args[0].lowercase()) {
            "open" -> ModuleRegistry.globalMarket.openMarket(player, shopId = args.getOrNull(1) ?: defaultShopId)
            "upload" -> {
                if (!Permissions.require(player, PermissionNodes.GLOBALMARKET_SELL)) {
                    return
                }
                val explicitShopId = if (defaultShopId == null && ModuleRegistry.globalMarket.hasMarketView(args.getOrNull(1))) args.getOrNull(1) else null
                val priceIndex = if (explicitShopId != null) 2 else 1
                val price = args.getOrNull(priceIndex)?.toDoubleOrNull()
                if (price == null) {
                    ModuleRegistry.globalMarket.openUpload(player, explicitShopId ?: defaultShopId)
                    return
                }
                val amount = args.getOrNull(priceIndex + 1)?.toIntOrNull()
                ModuleRegistry.globalMarket.uploadFromHand(player, explicitShopId ?: defaultShopId, price, amount)
            }
            "manage" -> {
                if (!Permissions.require(player, PermissionNodes.GLOBALMARKET_MANAGE_OWN)) {
                    return
                }
                val shopId = if (defaultShopId == null && ModuleRegistry.globalMarket.hasMarketView(args.getOrNull(1))) args.getOrNull(1) else defaultShopId
                ModuleRegistry.globalMarket.openManage(player, shopId)
            }
            else -> sendUnknownSubcommand(player, "global-market")
        }
    }

    private fun handlePlayerShop(player: Player, args: List<String>, defaultShopId: String? = null) {
        if (!Permissions.require(player, PermissionNodes.PLAYERSHOP_USE)) {
            return
        }
        if (args.isEmpty()) {
            sendUsage(player, boundUsage("player-shop", defaultShopId, "open [player]"))
            return
        }
        when (args[0].lowercase()) {
            "open" -> {
                val (targetName, shopId) = resolvePlayerShopOpen(player, args.drop(1), defaultShopId)
                ModuleRegistry.playerShop.openShop(player, targetName, shopId)
            }
            "edit" -> {
                if (!Permissions.require(player, PermissionNodes.PLAYERSHOP_MANAGE_OWN)) {
                    return
                }
                val shopId = if (defaultShopId == null && ModuleRegistry.playerShop.hasBrowseView(args.getOrNull(1))) args.getOrNull(1) else defaultShopId
                ModuleRegistry.playerShop.openEdit(player, shopId)
            }
            "upload" -> {
                if (!Permissions.require(player, PermissionNodes.PLAYERSHOP_SELL)) {
                    return
                }
                val explicitShopId = if (defaultShopId == null && ModuleRegistry.playerShop.hasBrowseView(args.getOrNull(1))) args.getOrNull(1) else null
                val priceIndex = if (explicitShopId != null) 2 else 1
                val price = args.getOrNull(priceIndex)?.toDoubleOrNull()
                if (price == null) {
                    sendUsage(player, "/matrixshop player_shop upload <price> [amount]")
                    return
                }
                val amount = args.getOrNull(priceIndex + 1)?.toIntOrNull()
                ModuleRegistry.playerShop.uploadFromHand(player, explicitShopId ?: defaultShopId, price, amount)
            }
            else -> sendUnknownSubcommand(player, "player-shop")
        }
    }

    private fun handleRecord(player: Player, args: List<String>, defaultShopId: String? = null) {
        if (!Permissions.require(player, PermissionNodes.RECORD_USE)) {
            return
        }
        if (args.isEmpty()) {
            sendUsage(player, boundUsage("record", defaultShopId, "open [keyword]"))
            return
        }
        when (args[0].lowercase()) {
            "open" -> {
                val keyword = args.drop(1).joinToString(" ").ifBlank { null }
                ModuleRegistry.record.open(
                    player,
                    keyword,
                    1,
                    defaultShopId,
                    ModuleRegistry.record.currentFilter(player, defaultShopId)
                )
            }
            "detail" -> {
                if (!Permissions.require(player, PermissionNodes.RECORD_DETAIL_SELF)) {
                    return
                }
                val recordId = args.getOrNull(1)
                if (recordId.isNullOrBlank()) {
                    sendUsage(player, "/matrixshop record detail <id>")
                    return
                }
                ModuleRegistry.record.openDetail(
                    player,
                    recordId,
                    keyword = ModuleRegistry.record.currentKeyword(player, defaultShopId),
                    shopId = defaultShopId,
                    moduleFilter = ModuleRegistry.record.currentFilter(player, defaultShopId)
                )
            }
            "income" -> {
                if (!Permissions.require(player, PermissionNodes.RECORD_STATS_SELF)) {
                    return
                }
                ModuleRegistry.record.openIncome(
                    player,
                    shopId = defaultShopId,
                    keyword = ModuleRegistry.record.currentKeyword(player, defaultShopId),
                    moduleFilter = ModuleRegistry.record.currentFilter(player, defaultShopId)
                )
            }
            "expense" -> {
                if (!Permissions.require(player, PermissionNodes.RECORD_STATS_SELF)) {
                    return
                }
                ModuleRegistry.record.openExpense(
                    player,
                    shopId = defaultShopId,
                    keyword = ModuleRegistry.record.currentKeyword(player, defaultShopId),
                    moduleFilter = ModuleRegistry.record.currentFilter(player, defaultShopId)
                )
            }
            "search" -> {
                val keyword = args.drop(1).joinToString(" ").ifBlank { null }
                ModuleRegistry.record.open(
                    player,
                    keyword,
                    1,
                    defaultShopId,
                    ModuleRegistry.record.currentFilter(player, defaultShopId)
                )
            }
            "filter" -> {
                val moduleFilter = args.getOrNull(1)
                if (moduleFilter.isNullOrBlank()) {
                    ModuleRegistry.record.cycleFilter(player, defaultShopId)
                } else {
                    ModuleRegistry.record.applyFilter(
                        player,
                        moduleFilter,
                        defaultShopId,
                        ModuleRegistry.record.currentKeyword(player, defaultShopId)
                    )
                }
            }
            "stats" -> {
                if (!Permissions.require(player, PermissionNodes.RECORD_STATS_SELF)) {
                    return
                }
                ModuleRegistry.record.openIncome(
                    player,
                    shopId = defaultShopId,
                    keyword = ModuleRegistry.record.currentKeyword(player, defaultShopId),
                    moduleFilter = ModuleRegistry.record.currentFilter(player, defaultShopId)
                )
            }
            else -> sendUnknownSubcommand(player, "record")
        }
    }

    private fun handleAuction(player: Player, args: List<String>, defaultShopId: String? = null) {
        if (!Permissions.require(player, PermissionNodes.AUCTION_USE)) {
            return
        }
        if (args.isEmpty()) {
            sendUsage(player, boundUsage("auction", defaultShopId, "open"))
            return
        }
        when (args[0].lowercase()) {
            "open" -> ModuleRegistry.auction.openAuction(player, shopId = args.getOrNull(1) ?: defaultShopId)
            "upload" -> {
                if (!Permissions.require(player, PermissionNodes.AUCTION_SELL)) {
                    return
                }
                val explicitShopId = if (defaultShopId == null && ModuleRegistry.auction.hasAuctionView(args.getOrNull(1))) args.getOrNull(1) else null
                val baseIndex = if (explicitShopId != null) 2 else 1
                if (args.size <= baseIndex) {
                    ModuleRegistry.auction.openUpload(player, explicitShopId ?: defaultShopId)
                    return
                }
                ModuleRegistry.auction.uploadFromHand(
                    player = player,
                    shopId = explicitShopId ?: defaultShopId,
                    modeRaw = args.getOrNull(baseIndex),
                    startPrice = args.getOrNull(baseIndex + 1)?.toDoubleOrNull(),
                    secondPrice = args.getOrNull(baseIndex + 2)?.toDoubleOrNull(),
                    durationSeconds = args.getOrNull(baseIndex + 3)?.toIntOrNull()
                )
            }
            "detail" -> {
                val explicitShopId = if (defaultShopId == null && ModuleRegistry.auction.hasAuctionView(args.getOrNull(1))) args.getOrNull(1) else null
                val id = args.getOrNull(if (explicitShopId != null) 2 else 1)
                if (id.isNullOrBlank()) {
                    sendUsage(player, "/auction detail <id>")
                    return
                }
                ModuleRegistry.auction.openDetail(player, explicitShopId ?: defaultShopId, id)
            }
            "bid" -> {
                if (!Permissions.require(player, PermissionNodes.AUCTION_BID)) {
                    return
                }
                val explicitShopId = if (defaultShopId == null && ModuleRegistry.auction.hasAuctionView(args.getOrNull(1))) args.getOrNull(1) else null
                val baseIndex = if (explicitShopId != null) 2 else 1
                val id = args.getOrNull(baseIndex)
                if (id.isNullOrBlank()) {
                    sendUsage(player, "/auction bid <id> [price]")
                    return
                }
                ModuleRegistry.auction.bid(player, explicitShopId ?: defaultShopId, id, args.getOrNull(baseIndex + 1)?.toDoubleOrNull())
            }
            "buyout" -> {
                if (!Permissions.require(player, PermissionNodes.AUCTION_BUYOUT)) {
                    return
                }
                val explicitShopId = if (defaultShopId == null && ModuleRegistry.auction.hasAuctionView(args.getOrNull(1))) args.getOrNull(1) else null
                val id = args.getOrNull(if (explicitShopId != null) 2 else 1)
                if (id.isNullOrBlank()) {
                    sendUsage(player, "/auction buyout <id>")
                    return
                }
                ModuleRegistry.auction.buyout(player, explicitShopId ?: defaultShopId, id)
            }
            "my_items", "manage" -> {
                if (!Permissions.require(player, PermissionNodes.AUCTION_MANAGE_OWN)) {
                    return
                }
                val shopId = if (defaultShopId == null && ModuleRegistry.auction.hasAuctionView(args.getOrNull(1))) args.getOrNull(1) else defaultShopId
                ModuleRegistry.auction.openManage(player, shopId)
            }
            "my_bids", "bids" -> {
                val shopId = if (defaultShopId == null && ModuleRegistry.auction.hasAuctionView(args.getOrNull(1))) args.getOrNull(1) else defaultShopId
                ModuleRegistry.auction.openBids(player, shopId)
            }
            "remove" -> {
                if (!Permissions.require(player, PermissionNodes.AUCTION_MANAGE_OWN)) {
                    return
                }
                val explicitShopId = if (defaultShopId == null && ModuleRegistry.auction.hasAuctionView(args.getOrNull(1))) args.getOrNull(1) else null
                val id = args.getOrNull(if (explicitShopId != null) 2 else 1)
                if (id.isNullOrBlank()) {
                    sendUsage(player, "/auction remove <id>")
                    return
                }
                ModuleRegistry.auction.remove(player, explicitShopId ?: defaultShopId, id)
            }
            else -> sendUnknownSubcommand(player, "auction")
        }
    }

    private fun handleTransaction(player: Player, args: List<String>, defaultShopId: String? = null) {
        if (!Permissions.require(player, PermissionNodes.TRANSACTION_USE)) {
            return
        }
        if (args.isEmpty()) {
            sendUsage(player, boundUsage("transaction", defaultShopId, "open"))
            return
        }
        when (args[0].lowercase()) {
            "open" -> ModuleRegistry.transaction.open(player, args.getOrNull(1) ?: defaultShopId)
            "request" -> ModuleRegistry.transaction.requestTrade(player, defaultShopId, args.getOrNull(1))
            "accept" -> ModuleRegistry.transaction.acceptRequest(player, args.getOrNull(1))
            "deny" -> ModuleRegistry.transaction.denyRequest(player, args.getOrNull(1))
            "money" -> ModuleRegistry.transaction.setMoney(player, args.getOrNull(1)?.toDoubleOrNull())
            "exp" -> ModuleRegistry.transaction.setExp(player, args.getOrNull(1)?.toIntOrNull())
            "ready" -> ModuleRegistry.transaction.toggleReady(player)
            "confirm" -> ModuleRegistry.transaction.confirm(player, args.getOrNull(1)?.equals("submit", true) == true)
            "cancel" -> ModuleRegistry.transaction.cancel(player)
            "logs" -> ModuleRegistry.transaction.openLogs(player)
            else -> sendUnknownSubcommand(player, "transaction")
        }
    }

    private fun handleChestShop(player: Player, args: List<String>, defaultShopId: String? = null) {
        if (!Permissions.require(player, PermissionNodes.CHESTSHOP_USE)) {
            return
        }
        if (args.isEmpty()) {
            sendUsage(player, moduleUsage("chestshop", "open"))
            return
        }
        when (args[0].lowercase()) {
            "open" -> ModuleRegistry.chestShop.open(player, null)
            "create" -> {
                if (!Permissions.require(player, PermissionNodes.CHESTSHOP_CREATE)) {
                    return
                }
                ModuleRegistry.chestShop.create(
                    player = player,
                    modeRaw = args.getOrNull(1),
                    firstPrice = args.getOrNull(2)?.toDoubleOrNull(),
                    secondPrice = args.getOrNull(3)?.toDoubleOrNull(),
                    amountRaw = args.getOrNull(4)?.toIntOrNull()
                )
            }
            "edit" -> {
                if (!Permissions.require(player, PermissionNodes.CHESTSHOP_MANAGE_OWN)) {
                    return
                }
                ModuleRegistry.chestShop.openEdit(player, defaultShopId)
            }
            "stock" -> ModuleRegistry.chestShop.openStock(player, args.getOrNull(1)?.toIntOrNull() ?: 1, defaultShopId)
            "history" -> ModuleRegistry.chestShop.openHistory(player, args.getOrNull(1)?.toIntOrNull() ?: 1, defaultShopId)
            "remove" -> {
                if (!Permissions.require(player, PermissionNodes.CHESTSHOP_MANAGE_OWN)) {
                    return
                }
                ModuleRegistry.chestShop.remove(player)
            }
            "price" -> {
                if (!Permissions.require(player, PermissionNodes.CHESTSHOP_MANAGE_OWN)) {
                    return
                }
                ModuleRegistry.chestShop.setPrice(player, args.getOrNull(1), args.getOrNull(2)?.toDoubleOrNull())
            }
            "amount" -> {
                if (!Permissions.require(player, PermissionNodes.CHESTSHOP_MANAGE_OWN)) {
                    return
                }
                ModuleRegistry.chestShop.setAmount(player, args.getOrNull(1)?.toIntOrNull())
            }
            "mode" -> {
                if (!Permissions.require(player, PermissionNodes.CHESTSHOP_MANAGE_OWN)) {
                    return
                }
                ModuleRegistry.chestShop.setMode(player, args.getOrNull(1))
            }
            else -> sendUnknownSubcommand(player, "chestshop")
        }
    }

    private fun handleCartAlias(sender: ProxyCommandSender, args: Array<String>) {
        val player = requirePlayer(sender) ?: return
        handleCart(player, args.toList())
    }

    private fun handleMenuAlias(sender: ProxyCommandSender, args: Array<String>) {
        val player = requirePlayer(sender) ?: return
        handleMenu(player, args.toList())
    }

    private fun handleRecordAlias(sender: ProxyCommandSender, args: Array<String>) {
        val player = requirePlayer(sender) ?: return
        handleRecord(player, args.toList())
    }

    private fun handleTradeAlias(sender: ProxyCommandSender, args: Array<String>) {
        val player = requirePlayer(sender) ?: return
        handleTransaction(player, args.toList())
    }

    private fun handleAuctionAlias(sender: ProxyCommandSender, args: Array<String>) {
        val player = requirePlayer(sender) ?: return
        handleAuction(player, args.toList())
    }

    private fun handleChestShopAlias(sender: ProxyCommandSender, args: Array<String>) {
        val player = requirePlayer(sender) ?: return
        handleChestShop(player, args.toList())
    }

    private fun handleAdmin(sender: ProxyCommandSender, args: Array<String>) {
        val commandSender = sender.origin as? CommandSender ?: run {
            sender.sendMessage(Texts.prefixedKey("@messages.unsupported-sender"))
            return
        }
        if (args.isEmpty() || args[0].equals("help", true)) {
            sendAdminHelp(commandSender)
            return
        }
        when (args[0].lowercase()) {
            "reload" -> {
                if (!Permissions.require(commandSender, PermissionNodes.ADMIN_RELOAD)) {
                    return
                }
                MatrixShop.reloadPlugin()
                Texts.sendKey(commandSender, "@commands.admin.reloaded")
            }
            "sync" -> {
                if (!Permissions.require(commandSender, PermissionNodes.ADMIN_SYNC)) {
                    return
                }
                val schemaResult = DatabaseManager.syncSchema()
                if (!schemaResult.success) {
                    Texts.send(commandSender, schemaResult.message)
                    return
                }
                val legacyResult = LegacyDataMigrationService.migrateAll()
                Texts.sendKey(
                    commandSender,
                    "@commands.admin.sync-success",
                    mapOf(
                        "message" to schemaResult.message,
                        "from" to (schemaResult.startedVersion?.toString() ?: "file"),
                        "to" to (schemaResult.finalVersion?.toString() ?: "file")
                    )
                )
                Texts.sendKey(commandSender, "@commands.admin.legacy-import", mapOf("message" to legacyResult.message))
            }
            "status" -> {
                if (!Permissions.require(commandSender, PermissionNodes.ADMIN_STATUS)) {
                    return
                }
                val diagnostics = DatabaseManager.diagnostics()
                Texts.sendRaw(commandSender, statusLine("@commands.admin.status.data-folder", ConfigFiles.dataFolder().absolutePath))
                Texts.sendRaw(commandSender, statusLine("@commands.admin.status.economy-provider", VaultEconomyBridge.providerName()))
                Texts.sendRaw(commandSender, statusLine("@commands.admin.status.configured-backend", diagnostics.configuredBackend))
                Texts.sendRaw(commandSender, statusLine("@commands.admin.status.active-backend", diagnostics.activeBackend))
                Texts.sendRaw(commandSender, statusLine("@commands.admin.status.data-target", diagnostics.target))
                Texts.sendRaw(
                    commandSender,
                    statusLine(
                        "@commands.admin.status.data-schema",
                        diagnostics.schemaVersion?.let { "$it/${diagnostics.expectedSchemaVersion}" } ?: "file-backend"
                    )
                )
                Texts.sendRaw(commandSender, statusLine("@commands.admin.status.schema-state", Texts.tr(if (diagnostics.schemaCurrent) "@commands.words.current" else "@commands.words.pending")) )
                Texts.sendRaw(commandSender, statusLine("@commands.admin.status.redis-notify", Texts.tr(if (diagnostics.redisEnabled) "@commands.words.enabled" else "@commands.words.disabled")) )
                Texts.sendRaw(commandSender, statusLine("@commands.admin.status.record-backend", com.y54895.matrixshop.core.record.RecordService.backendName()))
                if (diagnostics.pendingMigrations.isNotEmpty()) {
                    Texts.sendRaw(commandSender, statusLine("@commands.admin.status.pending-migrations", diagnostics.pendingMigrations.joinToString(", ")))
                }
                if (diagnostics.lastMigration.isNotBlank()) {
                    Texts.sendRaw(commandSender, statusLine("@commands.admin.status.last-migration", diagnostics.lastMigration))
                }
                if (diagnostics.lastMigrationAt.isNotBlank()) {
                    Texts.sendRaw(commandSender, statusLine("@commands.admin.status.last-migration-at", formatEpochMillis(diagnostics.lastMigrationAt)))
                }
                if (diagnostics.lastLegacyImport.isNotBlank()) {
                    Texts.sendRaw(commandSender, statusLine("@commands.admin.status.last-legacy-import", diagnostics.lastLegacyImport))
                }
                if (diagnostics.lastLegacyImportAt.isNotBlank()) {
                    Texts.sendRaw(commandSender, statusLine("@commands.admin.status.last-legacy-import-at", formatEpochMillis(diagnostics.lastLegacyImportAt)))
                }
                if (diagnostics.lastLegacyImport.isNotBlank()) {
                    Texts.sendRaw(commandSender, statusLine("@commands.admin.status.last-legacy-import-total", diagnostics.lastLegacyImportTotal.toString()))
                }
                if (diagnostics.failureReason.isNotBlank()) {
                    Texts.sendRaw(commandSender, statusLine("@commands.admin.status.backend-reason", diagnostics.failureReason))
                }
                if (diagnostics.tableCounts.isNotEmpty()) {
                    Texts.sendRaw(
                        commandSender,
                        statusLine(
                            "@commands.admin.status.data-tables",
                            diagnostics.tableCounts.entries.joinToString(", ") { (table, count) -> "$table=$count" }
                        )
                    )
                }
                ModuleRegistry.moduleStates().forEach { Texts.sendRaw(commandSender, it) }
            }
            "module" -> handleAdminModule(commandSender, args.drop(1))
            else -> sendAdminHelp(commandSender)
        }
    }

    private fun sendPlayerHelp(player: Player) {
        val lines = mutableListOf(Texts.tr("@commands.help.player-title"))
        addHelp(lines, showModuleHelp("system-shop") && Permissions.has(player, PermissionNodes.SYSTEMSHOP_USE), helpLine("/matrixshop", "@commands.help.desc.system-main"))
        addHelp(lines, showModuleHelp("system-shop") && Permissions.has(player, PermissionNodes.SYSTEMSHOP_USE), helpLine(msUsage("system-shop", "open [category]"), "@commands.help.desc.system-open"))
        addHelp(lines, Permissions.has(player, PermissionNodes.SYSTEMSHOP_USE), helpLine("/ms open <shop-id|category>", "@commands.help.desc.ms-open"))
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("menu") && Permissions.has(player, PermissionNodes.MENU_USE), shopHelpEntries("menu"), "open", "@commands.help.desc.menu-open")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("auction") && Permissions.has(player, PermissionNodes.AUCTION_USE), shopHelpEntries("auction"), "open", "@commands.help.desc.auction-open")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("auction") && Permissions.has(player, PermissionNodes.AUCTION_SELL), shopHelpEntries("auction"), "upload <english|dutch> <start> [buyout|end] [duration]", "@commands.help.desc.auction-upload")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("auction") && Permissions.has(player, PermissionNodes.AUCTION_BID), shopHelpEntries("auction"), "bid <id> [price]", "@commands.help.desc.auction-bid")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("auction") && Permissions.has(player, PermissionNodes.AUCTION_BUYOUT), shopHelpEntries("auction"), "buyout <id>", "@commands.help.desc.auction-buyout")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("auction") && Permissions.has(player, PermissionNodes.AUCTION_MANAGE_OWN), shopHelpEntries("auction"), "manage | bids", "@commands.help.desc.auction-manage")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("player-shop") && Permissions.has(player, PermissionNodes.PLAYERSHOP_USE), shopHelpEntries("player-shop"), "open", "@commands.help.desc.player-shop-open-self")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("player-shop") && Permissions.has(player, PermissionNodes.PLAYERSHOP_USE), shopHelpEntries("player-shop"), "open [player]", "@commands.help.desc.player-shop-open-other")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("player-shop") && Permissions.has(player, PermissionNodes.PLAYERSHOP_MANAGE_OWN), shopHelpEntries("player-shop"), "edit", "@commands.help.desc.player-shop-edit")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("player-shop") && Permissions.has(player, PermissionNodes.PLAYERSHOP_SELL), shopHelpEntries("player-shop"), "upload <price> [amount]", "@commands.help.desc.player-shop-upload")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("global-market") && Permissions.has(player, PermissionNodes.GLOBALMARKET_USE), shopHelpEntries("global-market"), "open", "@commands.help.desc.global-market-open")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("global-market") && Permissions.has(player, PermissionNodes.GLOBALMARKET_SELL), shopHelpEntries("global-market"), "upload <price> [amount]", "@commands.help.desc.global-market-upload")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("global-market") && Permissions.has(player, PermissionNodes.GLOBALMARKET_MANAGE_OWN), shopHelpEntries("global-market"), "manage", "@commands.help.desc.global-market-manage")
        addHelp(lines, showModuleHelp("chestshop") && Permissions.has(player, PermissionNodes.CHESTSHOP_USE), helpLine(moduleUsage("chestshop", "open"), "@commands.help.desc.chestshop-open"))
        addHelp(lines, showModuleHelp("chestshop") && Permissions.has(player, PermissionNodes.CHESTSHOP_CREATE), helpLine(moduleUsage("chestshop", "create <buy|sell|dual> <price> [sell-price] [amount]"), "@commands.help.desc.chestshop-create"))
        addHelp(lines, showModuleHelp("chestshop") && Permissions.has(player, PermissionNodes.CHESTSHOP_USE), helpLine(moduleUsage("chestshop", "stock | history"), "@commands.help.desc.chestshop-stock-history"))
        addHelp(lines, showModuleHelp("chestshop") && Permissions.has(player, PermissionNodes.CHESTSHOP_MANAGE_OWN), helpLine(moduleUsage("chestshop", "edit | remove | price | amount | mode"), "@commands.help.desc.chestshop-manage"))
        addHelp(lines, showModuleHelp("cart") && Permissions.has(player, PermissionNodes.CART_USE), helpLine(moduleUsage("cart", "open"), "@commands.help.desc.cart-open"))
        addHelp(lines, showModuleHelp("cart") && Permissions.has(player, PermissionNodes.CART_CHECKOUT), helpLine(moduleUsage("cart", "checkout [valid_only]"), "@commands.help.desc.cart-checkout"))
        addHelp(lines, showModuleHelp("cart") && Permissions.has(player, PermissionNodes.CART_CHECKOUT), helpLine(moduleUsage("cart", "checkout confirm [valid_only] | conflict"), "@commands.help.desc.cart-conflict"))
        addHelp(lines, showModuleHelp("cart") && Permissions.has(player, PermissionNodes.CART_CLEAR), helpLine(moduleUsage("cart", "remove <slot> | remove_invalid | clear"), "@commands.help.desc.cart-manage"))
        addHelp(lines, showModuleHelp("cart") && Permissions.has(player, PermissionNodes.CART_USE), helpLine(moduleUsage("cart", "amount <slot> <number>"), "@commands.help.desc.cart-amount"))
        addHelp(lines, showModuleHelp("record") && Permissions.has(player, PermissionNodes.RECORD_USE), helpLine(moduleUsage("record", "open [keyword]"), "@commands.help.desc.record-open"))
        addHelp(lines, showModuleHelp("record") && Permissions.has(player, PermissionNodes.RECORD_DETAIL_SELF), helpLine(moduleUsage("record", "detail <id>"), "@commands.help.desc.record-detail"))
        addHelp(lines, showModuleHelp("record") && Permissions.has(player, PermissionNodes.RECORD_USE), helpLine(moduleUsage("record", "filter [module|all]"), "@commands.help.desc.record-filter"))
        addHelp(lines, showModuleHelp("record") && Permissions.has(player, PermissionNodes.RECORD_STATS_SELF), helpLine(moduleUsage("record", "income | expense | stats"), "@commands.help.desc.record-stats"))
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("transaction") && Permissions.has(player, PermissionNodes.TRANSACTION_USE), shopHelpEntries("transaction"), "open", "@commands.help.desc.transaction-open")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("transaction") && Permissions.has(player, PermissionNodes.TRANSACTION_USE), shopHelpEntries("transaction"), "request <player>", "@commands.help.desc.transaction-request")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("transaction") && Permissions.has(player, PermissionNodes.TRANSACTION_USE), shopHelpEntries("transaction"), "accept [player] | ready | confirm | cancel | logs", "@commands.help.desc.transaction-control")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("transaction") && Permissions.has(player, PermissionNodes.TRANSACTION_USE), shopHelpEntries("transaction"), "money <amount> | exp <amount>", "@commands.help.desc.transaction-offer")
        Texts.sendRaw(player, lines.joinToString("\n"))
    }

    private fun sendAdminHelp(sender: CommandSender) {
        val lines = mutableListOf(Texts.tr("@commands.help.admin-title"))
        addHelp(lines, Permissions.has(sender, PermissionNodes.ADMIN_RELOAD), helpLine("/matrixshop admin reload", "@commands.help.desc.admin-reload"))
        addHelp(lines, Permissions.has(sender, PermissionNodes.ADMIN_SYNC), helpLine("/matrixshop admin sync", "@commands.help.desc.admin-sync"))
        addHelp(lines, Permissions.has(sender, PermissionNodes.ADMIN_STATUS), helpLine("/matrixshop admin status", "@commands.help.desc.admin-status"))
        addHelp(lines, Permissions.has(sender, PermissionNodes.ADMIN_MODULE), helpLine("/matrixshop admin module list", "@commands.help.desc.admin-module-list"))
        addHelp(lines, Permissions.has(sender, PermissionNodes.ADMIN_MODULE), helpLine("/matrixshop admin module <enable|disable|toggle> <id>", "@commands.help.desc.admin-module-change"))
        lines += Texts.tr("@commands.help.admin-alias")
        Texts.sendRaw(sender, lines.joinToString("\n"))
    }

    private fun handleAdminModule(sender: CommandSender, args: List<String>) {
        if (!Permissions.require(sender, PermissionNodes.ADMIN_MODULE)) {
            return
        }
        if (args.isEmpty() || args[0].equals("list", true)) {
            Texts.sendKey(sender, "@commands.admin.module-list-title")
            ModuleRegistry.all().forEach { module ->
                Texts.sendRaw(
                    sender,
                    Texts.tr(
                        "@commands.admin.module-list-line",
                        mapOf(
                            "module" to module.id,
                            "state" to Texts.tr(if (module.isEnabled()) "@commands.words.enabled" else "@commands.words.disabled")
                        )
                    )
                )
            }
            return
        }
        val action = args[0].lowercase()
        val moduleId = resolveModuleId(args.getOrNull(1))
        if (moduleId == null) {
            sendUsage(sender, "/matrixshop admin module <enable|disable|toggle> <id>")
            return
        }
        val enabled = when (action) {
            "enable", "on" -> true
            "disable", "off" -> false
            "toggle" -> !ConfigFiles.isModuleEnabled(moduleId, true)
            else -> {
                sendUsage(sender, "/matrixshop admin module <enable|disable|toggle> <id>")
                return
            }
        }
        ConfigFiles.setModuleEnabled(moduleId, enabled)
        MatrixShop.reloadPlugin()
        Texts.sendKey(
            sender,
            "@commands.admin.module-state-changed",
            mapOf(
                "module" to moduleId,
                "state" to Texts.tr(if (enabled) "@commands.words.enabled" else "@commands.words.disabled")
            )
        )
    }

    private fun resolveModuleId(raw: String?): String? {
        return when (raw?.lowercase()) {
            "menu", "menus" -> "menu"
            "system", "systemshop", "system-shop" -> "system-shop"
            "playershop", "player_shop", "player-shop" -> "player-shop"
            "globalmarket", "global_market", "global-market", "market" -> "global-market"
            "auction" -> "auction"
            "chestshop", "chest-shop" -> "chestshop"
            "transaction", "trade" -> "transaction"
            "cart" -> "cart"
            "record", "records" -> "record"
            else -> null
        }
    }

    private fun addHelp(lines: MutableList<String>, visible: Boolean, line: String) {
        if (visible) {
            lines += line
        }
    }

    private fun addBoundShopHelp(
        lines: MutableList<String>,
        visible: Boolean,
        entries: List<com.y54895.matrixshop.core.menu.ShopMenuSelection>,
        suffix: String,
        descriptionKey: String
    ) {
        if (!visible) {
            return
        }
        entries.forEach { entry ->
            val key = entry.bindings.keys.firstOrNull() ?: entry.id
            lines += Texts.tr(
                "@commands.help.bound-line",
                mapOf(
                    "command" to key,
                    "suffix" to suffix,
                    "description" to Texts.tr(descriptionKey),
                    "shop" to entry.id
                )
            )
        }
    }

    private fun helpLine(command: String, descriptionKey: String): String {
        return Texts.tr("@commands.help.line", mapOf("command" to command, "description" to Texts.tr(descriptionKey)))
    }

    private fun sendUsage(sender: CommandSender, usage: String) {
        Texts.sendKey(sender, "@commands.errors.usage", mapOf("usage" to usage))
    }

    private fun sendUnknownSubcommand(sender: CommandSender, moduleId: String) {
        Texts.sendKey(sender, "@commands.errors.unknown-subcommand", mapOf("module" to moduleDisplayName(moduleId)))
    }

    private fun statusLine(labelKey: String, value: String): String {
        return Texts.tr("@commands.admin.status.line", mapOf("label" to Texts.tr(labelKey), "value" to value))
    }

    private fun moduleDisplayName(moduleId: String): String {
        val key = when (moduleId) {
            "menu" -> "@commands.modules.menu"
            "system-shop" -> "@commands.modules.system-shop"
            "player-shop" -> "@commands.modules.player-shop"
            "global-market" -> "@commands.modules.global-market"
            "auction" -> "@commands.modules.auction"
            "transaction" -> "@commands.modules.transaction"
            "chestshop" -> "@commands.modules.chestshop"
            "cart" -> "@commands.modules.cart"
            "record" -> "@commands.modules.record"
            else -> return moduleId
        }
        return Texts.tr(key)
    }

    private fun shopHelpEntries(moduleId: String): List<com.y54895.matrixshop.core.menu.ShopMenuSelection> {
        if (!ModuleRegistry.isEnabled(moduleId)) {
            return emptyList()
        }
        return when (moduleId) {
            "menu" -> ModuleRegistry.menu.helpEntries()
            "auction" -> ModuleRegistry.auction.helpEntries()
            "player-shop" -> ModuleRegistry.playerShop.helpEntries()
            "global-market" -> ModuleRegistry.globalMarket.helpEntries()
            "transaction" -> ModuleRegistry.transaction.helpEntries()
            else -> emptyList()
        }
    }

    private fun msUsage(moduleId: String, suffix: String): String {
        return "/ms ${ModuleBindings.primary(moduleId)} $suffix".trim()
    }

    private fun moduleUsage(moduleId: String, suffix: String): String {
        return if (ModuleBindings.registerStandalone(moduleId)) {
            "/${ModuleBindings.primary(moduleId)} $suffix".trim()
        } else {
            msUsage(moduleId, suffix)
        }
    }

    private fun boundUsage(moduleId: String, shopId: String?, suffix: String): String {
        val key = if (shopId != null) {
            shopHelpEntries(moduleId).firstOrNull { it.id.equals(shopId, true) }?.bindings?.keys?.firstOrNull()
        } else {
            null
        }
        return if (key != null) {
            "/$key $suffix".trim()
        } else {
            "/ms ${ModuleBindings.primary(moduleId)} $suffix".trim()
        }
    }

    private fun showModuleHelp(moduleId: String): Boolean {
        return ModuleRegistry.isEnabled(moduleId) && ModuleBindings.showInHelp(moduleId)
    }

    private fun requirePlayer(sender: ProxyCommandSender): Player? {
        val player = sender.origin as? Player
        if (player == null) {
            val message = ConfigFiles.config.getString("messages.player-only", "@messages.player-only").orEmpty()
            sender.sendMessage(Texts.prefixed(message))
        }
        return player
    }

    private fun resolvePlayerShopOpen(player: Player, args: List<String>, defaultShopId: String? = null): Pair<String, String?> {
        val first = args.getOrNull(0)
        val second = args.getOrNull(1)
        return when {
            first == null -> player.name to defaultShopId
            defaultShopId != null -> first to defaultShopId
            second != null -> first to second
            ModuleRegistry.playerShop.hasBrowseView(first) -> player.name to first
            else -> first to null
        }
    }

    private fun registerStandaloneCommand(
        moduleId: String,
        defaultName: String,
        description: String,
        usage: String,
        permission: String,
        executor: (ProxyCommandSender, Array<String>) -> Unit,
        reserved: MutableSet<String>
    ) {
        if (!ModuleBindings.registerStandalone(moduleId)) {
            return
        }
        val bindings = ModuleBindings.keys(moduleId)
        val name = bindings.firstOrNull() ?: defaultName
        val aliases = bindings.drop(1).filterNot { reserved.contains(it.lowercase()) }
        if (reserved.contains(name.lowercase())) {
            return
        }
        reserved += name.lowercase()
        aliases.forEach { reserved += it.lowercase() }
        simpleCommand(
            name = name,
            aliases = aliases,
            description = description,
            usage = usage,
            permission = permission
        ) { sender, args ->
            executor(sender, args)
        }
    }

    private fun registerStandaloneShopCommands(
        moduleId: String,
        description: String,
        usage: String,
        permission: String,
        reserved: MutableSet<String>
    ) {
        standaloneBoundShopEntries(moduleId).forEach { entry ->
            val bindings = entry.selection.bindings.keys
            val name = bindings.firstOrNull() ?: return@forEach
            if (reserved.contains(name.lowercase())) {
                return@forEach
            }
            val aliases = bindings.drop(1).filterNot { reserved.contains(it.lowercase()) }
            reserved += name.lowercase()
            aliases.forEach { reserved += it.lowercase() }
            simpleCommand(
                name = name,
                aliases = aliases,
                description = description,
                usage = usage,
                permission = permission
            ) { sender, args ->
                val player = requirePlayer(sender) ?: return@simpleCommand
                handleBoundShop(player, entry.route, args.toList())
            }
        }
    }

    private fun handleBoundShop(player: Player, route: ShopBindingRoute, args: List<String>) {
        when (route.moduleId) {
            "menu" -> handleMenu(player, args, route.shopId)
            "auction" -> handleAuction(player, args, route.shopId)
            "global-market" -> handleGlobalMarket(player, args, route.shopId)
            "player-shop" -> handlePlayerShop(player, args, route.shopId)
            "cart" -> handleCart(player, args, route.shopId)
            "record" -> handleRecord(player, args, route.shopId)
            "chestshop" -> handleChestShop(player, args, route.shopId)
            "transaction" -> handleTransaction(player, args, route.shopId)
            else -> sendPlayerHelp(player)
        }
    }

    private fun resolveBoundShopRoute(token: String): ShopBindingRoute? {
        val normalized = token.trim().lowercase()
        return boundShopEntries()
            .firstOrNull { it.selection.bindings.keys.contains(normalized) }
            ?.route
    }

    private fun boundShopEntries(moduleId: String? = null): List<BoundShopEntry> {
        val routes = mutableListOf<BoundShopEntry>()
        if ((moduleId == null || moduleId == "global-market") && ModuleRegistry.isEnabled("global-market")) {
            ModuleRegistry.globalMarket.allShopEntries().forEach { routes += BoundShopEntry(ShopBindingRoute("global-market", it.id), it) }
        }
        if ((moduleId == null || moduleId == "menu") && ModuleRegistry.isEnabled("menu")) {
            ModuleRegistry.menu.allShopEntries().forEach { routes += BoundShopEntry(ShopBindingRoute("menu", it.id), it) }
        }
        if ((moduleId == null || moduleId == "player-shop") && ModuleRegistry.isEnabled("player-shop")) {
            ModuleRegistry.playerShop.allShopEntries().forEach { routes += BoundShopEntry(ShopBindingRoute("player-shop", it.id), it) }
        }
        if ((moduleId == null || moduleId == "auction") && ModuleRegistry.isEnabled("auction")) {
            ModuleRegistry.auction.allShopEntries().forEach { routes += BoundShopEntry(ShopBindingRoute("auction", it.id), it) }
        }
        if ((moduleId == null || moduleId == "transaction") && ModuleRegistry.isEnabled("transaction")) {
            ModuleRegistry.transaction.allShopEntries().forEach { routes += BoundShopEntry(ShopBindingRoute("transaction", it.id), it) }
        }
        return routes
    }

    private fun resolveShopIdRoute(token: String): ShopIdResolution {
        val normalized = token.trim().lowercase()
        val matches = boundShopEntries()
            .map { it.route }
            .filter { it.shopId.trim().lowercase() == normalized }
        return when {
            matches.isEmpty() -> ShopIdResolution.NotFound
            matches.size == 1 -> ShopIdResolution.Found(matches.first())
            else -> ShopIdResolution.Ambiguous(matches)
        }
    }

    private fun resolveExplicitOpenTarget(token: String): ExplicitOpenResolution? {
        val separator = token.indexOfAny(charArrayOf(':', '：'))
        if (separator <= 0 || separator >= token.length - 1) {
            return null
        }
        val prefix = token.substring(0, separator).trim()
        val targetId = token.substring(separator + 1).trim()
        if (prefix.isBlank() || targetId.isBlank()) {
            return null
        }
        val moduleId = ModuleBindings.resolveModule(prefix) ?: resolveModuleId(prefix)
            ?: return ExplicitOpenResolution.InvalidPrefix(prefix)
        if (moduleId == "system-shop") {
            return ExplicitOpenResolution.SystemCategory(targetId)
        }
        val matches = boundShopEntries(moduleId)
            .map { it.route }
            .filter { it.shopId.equals(targetId, true) }
        return when {
            matches.isEmpty() -> ExplicitOpenResolution.NotFound(moduleId, targetId)
            matches.size == 1 -> ExplicitOpenResolution.ShopFound(matches.first())
            else -> ExplicitOpenResolution.ShopAmbiguous(matches)
        }
    }

    private fun typedRouteToken(route: ShopBindingRoute): String {
        return typedTargetToken(route.moduleId, route.shopId)
    }

    private fun typedTargetToken(moduleId: String, targetId: String): String {
        return "${typedPrefix(moduleId)}:$targetId"
    }

    private fun typedPrefix(moduleId: String): String {
        return when (moduleId) {
            "system-shop" -> "systemshop"
            "player-shop" -> "playershop"
            "global-market" -> "globalmarket"
            else -> moduleId.replace("-", "")
        }
    }

    private fun standaloneBoundShopEntries(moduleId: String): List<BoundShopEntry> {
        val entries = mutableListOf<BoundShopEntry>()
        when (moduleId) {
            "menu" -> if (ModuleRegistry.isEnabled("menu")) ModuleRegistry.menu.standaloneEntries().forEach { entries += BoundShopEntry(ShopBindingRoute("menu", it.id), it) }
            "global-market" -> if (ModuleRegistry.isEnabled("global-market")) ModuleRegistry.globalMarket.standaloneEntries().forEach { entries += BoundShopEntry(ShopBindingRoute("global-market", it.id), it) }
            "player-shop" -> if (ModuleRegistry.isEnabled("player-shop")) ModuleRegistry.playerShop.standaloneEntries().forEach { entries += BoundShopEntry(ShopBindingRoute("player-shop", it.id), it) }
            "auction" -> if (ModuleRegistry.isEnabled("auction")) ModuleRegistry.auction.standaloneEntries().forEach { entries += BoundShopEntry(ShopBindingRoute("auction", it.id), it) }
            "transaction" -> if (ModuleRegistry.isEnabled("transaction")) ModuleRegistry.transaction.standaloneEntries().forEach { entries += BoundShopEntry(ShopBindingRoute("transaction", it.id), it) }
        }
        return entries
    }

    private fun formatEpochMillis(value: String): String {
        val millis = value.toLongOrNull() ?: return value
        return runCatching {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(millis))
        }.getOrDefault(value)
    }
}

private data class ShopBindingRoute(
    val moduleId: String,
    val shopId: String
)

private data class BoundShopEntry(
    val route: ShopBindingRoute,
    val selection: com.y54895.matrixshop.core.menu.ShopMenuSelection
)

private sealed class ShopIdResolution {
    data class Found(val route: ShopBindingRoute) : ShopIdResolution()
    data class Ambiguous(val routes: List<ShopBindingRoute>) : ShopIdResolution()
    data object NotFound : ShopIdResolution()
}

private sealed class ExplicitOpenResolution {
    data class ShopFound(val route: ShopBindingRoute) : ExplicitOpenResolution()
    data class ShopAmbiguous(val routes: List<ShopBindingRoute>) : ExplicitOpenResolution()
    data class SystemCategory(val categoryId: String) : ExplicitOpenResolution()
    data class InvalidPrefix(val prefix: String) : ExplicitOpenResolution()
    data class NotFound(val moduleId: String, val targetId: String) : ExplicitOpenResolution()
}
