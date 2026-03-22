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
            usage = "/matrixshopadmin"
        ) { sender, args ->
            handleAdmin(sender, args)
        }
        registerStandaloneCommand("cart", "cart", "MatrixShop cart command", "/cart", "matrixshop.cart.use", ::handleCartAlias, reserved)
        registerStandaloneCommand("record", "record", "MatrixShop record command", "/record", "matrixshop.record.use", ::handleRecordAlias, reserved)
        registerStandaloneCommand("transaction", "trade", "MatrixShop trade command", "/trade", "matrixshop.transaction.use", ::handleTradeAlias, reserved)
        registerStandaloneCommand("auction", "auction", "MatrixShop auction command", "/auction", "matrixshop.auction.use", ::handleAuctionAlias, reserved)
        registerStandaloneCommand("chestshop", "chestshop", "MatrixShop chest shop command", "/chestshop", "matrixshop.chestshop.use", ::handleChestShopAlias, reserved)
        registerStandaloneShopCommands("cart", "MatrixShop cart command", "/cart", "matrixshop.cart.use", reserved)
        registerStandaloneShopCommands("record", "MatrixShop record command", "/record", "matrixshop.record.use", reserved)
        registerStandaloneShopCommands("chestshop", "MatrixShop chest shop command", "/chestshop", "matrixshop.chestshop.use", reserved)
        registerStandaloneShopCommands("global-market", "MatrixShop global market command", "/market", "matrixshop.globalmarket.use", reserved)
        registerStandaloneShopCommands("player-shop", "MatrixShop player shop command", "/playershop", "matrixshop.playershop.use", reserved)
        registerStandaloneShopCommands("auction", "MatrixShop auction command", "/auction", "matrixshop.auction.use", reserved)
        registerStandaloneShopCommands("transaction", "MatrixShop trade command", "/trade", "matrixshop.transaction.use", reserved)
    }

    private fun handleMain(sender: ProxyCommandSender, args: Array<String>) {
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

    private fun handleMainOpen(player: Player, args: List<String>) {
        if (args.isEmpty()) {
            if (Permissions.require(player, PermissionNodes.SYSTEMSHOP_USE)) {
                ModuleRegistry.systemShop.openMain(player)
            }
            return
        }
        when (val resolution = resolveShopIdRoute(args[0])) {
            is ShopIdResolution.Found -> handleBoundShop(player, resolution.route, listOf("open") + args.drop(1))
            is ShopIdResolution.Ambiguous -> {
                val modules = resolution.routes.joinToString(", ") { "${it.moduleId}:${it.shopId}" }
                Texts.send(player, "&cShop id &f${args[0]} &cexists in multiple modules: &f$modules")
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
            Texts.send(player, "&cMissing confirm subcommand.")
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
                    Texts.send(player, "&cUsage: /matrixshop system confirm amount add <number>")
                    return
                }
                val delta = args.getOrNull(2)?.toIntOrNull()
                if (delta == null) {
                    Texts.send(player, "&cAmount must be an integer.")
                    return
                }
                ModuleRegistry.systemShop.adjustConfirmAmount(player, delta)
            }
            else -> Texts.send(player, "&eOnly buy, cart and amount are implemented in this confirm flow.")
        }
    }

    private fun handleCart(player: Player, args: List<String>, defaultShopId: String? = null) {
        if (!Permissions.require(player, PermissionNodes.CART_USE)) {
            return
        }
        if (args.isEmpty()) {
            Texts.send(player, "&cUsage: ${boundUsage("cart", defaultShopId, "open")}")
            return
        }
        when (args[0].lowercase()) {
            "open" -> ModuleRegistry.cart.open(player, shopId = args.getOrNull(1) ?: defaultShopId)
            "checkout" -> {
                if (!Permissions.require(player, PermissionNodes.CART_CHECKOUT)) {
                    return
                }
                ModuleRegistry.cart.checkout(player, args.getOrNull(1)?.equals("valid_only", true) == true)
            }
            "clear" -> {
                if (!Permissions.require(player, PermissionNodes.CART_CLEAR)) {
                    return
                }
                ModuleRegistry.cart.clear(player)
            }
            "remove" -> {
                if (!Permissions.require(player, PermissionNodes.CART_CLEAR)) {
                    return
                }
                val index = args.getOrNull(1)?.toIntOrNull()
                if (index == null) {
                    Texts.send(player, "&cUsage: /matrixshop cart remove <slot>")
                    return
                }
                ModuleRegistry.cart.remove(player, index)
            }
            "remove_invalid" -> {
                if (!Permissions.require(player, PermissionNodes.CART_CLEAR)) {
                    return
                }
                ModuleRegistry.cart.removeInvalid(player)
            }
            "amount" -> {
                if (!Permissions.require(player, PermissionNodes.CART_USE)) {
                    return
                }
                val index = args.getOrNull(1)?.toIntOrNull()
                val amount = args.getOrNull(2)?.toIntOrNull()
                if (index == null || amount == null) {
                    Texts.send(player, "&cUsage: /matrixshop cart amount <slot> <number>")
                    return
                }
                ModuleRegistry.cart.changeAmount(player, index, amount)
            }
            else -> Texts.send(player, "&cUnknown cart subcommand.")
        }
    }

    private fun handleGlobalMarket(player: Player, args: List<String>, defaultShopId: String? = null) {
        if (!Permissions.require(player, PermissionNodes.GLOBALMARKET_USE)) {
            return
        }
        if (args.isEmpty()) {
            Texts.send(player, "&cUsage: ${boundUsage("global-market", defaultShopId, "open")}")
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
            else -> Texts.send(player, "&cUnknown global market subcommand.")
        }
    }

    private fun handlePlayerShop(player: Player, args: List<String>, defaultShopId: String? = null) {
        if (!Permissions.require(player, PermissionNodes.PLAYERSHOP_USE)) {
            return
        }
        if (args.isEmpty()) {
            Texts.send(player, "&cUsage: ${boundUsage("player-shop", defaultShopId, "open [player]")}")
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
                    Texts.send(player, "&cUsage: /matrixshop player_shop upload <price> [amount]")
                    return
                }
                val amount = args.getOrNull(priceIndex + 1)?.toIntOrNull()
                ModuleRegistry.playerShop.uploadFromHand(player, explicitShopId ?: defaultShopId, price, amount)
            }
            else -> Texts.send(player, "&cUnknown player shop subcommand.")
        }
    }

    private fun handleRecord(player: Player, args: List<String>, defaultShopId: String? = null) {
        if (!Permissions.require(player, PermissionNodes.RECORD_USE)) {
            return
        }
        if (args.isEmpty()) {
            Texts.send(player, "&cUsage: ${boundUsage("record", defaultShopId, "open [keyword]")}")
            return
        }
        when (args[0].lowercase()) {
            "open" -> {
                val keyword = args.drop(1).joinToString(" ").ifBlank { null }
                ModuleRegistry.record.open(player, keyword, 1, defaultShopId)
            }
            "detail" -> {
                if (!Permissions.require(player, PermissionNodes.RECORD_DETAIL_SELF)) {
                    return
                }
                val recordId = args.getOrNull(1)
                if (recordId.isNullOrBlank()) {
                    Texts.send(player, "&cUsage: /matrixshop record detail <id>")
                    return
                }
                ModuleRegistry.record.openDetail(player, recordId, shopId = defaultShopId)
            }
            "income" -> {
                if (!Permissions.require(player, PermissionNodes.RECORD_STATS_SELF)) {
                    return
                }
                ModuleRegistry.record.openIncome(player, shopId = defaultShopId)
            }
            "expense" -> {
                if (!Permissions.require(player, PermissionNodes.RECORD_STATS_SELF)) {
                    return
                }
                ModuleRegistry.record.openExpense(player, shopId = defaultShopId)
            }
            "search" -> {
                val keyword = args.drop(1).joinToString(" ").ifBlank { null }
                ModuleRegistry.record.open(player, keyword, 1, defaultShopId)
            }
            "stats" -> {
                if (!Permissions.require(player, PermissionNodes.RECORD_STATS_SELF)) {
                    return
                }
                ModuleRegistry.record.openIncome(player, shopId = defaultShopId)
            }
            else -> Texts.send(player, "&cUnknown record subcommand.")
        }
    }

    private fun handleAuction(player: Player, args: List<String>, defaultShopId: String? = null) {
        if (!Permissions.require(player, PermissionNodes.AUCTION_USE)) {
            return
        }
        if (args.isEmpty()) {
            Texts.send(player, "&cUsage: ${boundUsage("auction", defaultShopId, "open")}")
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
                    Texts.send(player, "&cUsage: /auction detail <id>")
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
                    Texts.send(player, "&cUsage: /auction bid <id> [price]")
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
                    Texts.send(player, "&cUsage: /auction buyout <id>")
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
                    Texts.send(player, "&cUsage: /auction remove <id>")
                    return
                }
                ModuleRegistry.auction.remove(player, explicitShopId ?: defaultShopId, id)
            }
            else -> Texts.send(player, "&cUnknown auction subcommand.")
        }
    }

    private fun handleTransaction(player: Player, args: List<String>, defaultShopId: String? = null) {
        if (!Permissions.require(player, PermissionNodes.TRANSACTION_USE)) {
            return
        }
        if (args.isEmpty()) {
            Texts.send(player, "&cUsage: ${boundUsage("transaction", defaultShopId, "open")}")
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
            else -> Texts.send(player, "&cUnknown trade subcommand.")
        }
    }

    private fun handleChestShop(player: Player, args: List<String>, defaultShopId: String? = null) {
        if (!Permissions.require(player, PermissionNodes.CHESTSHOP_USE)) {
            return
        }
        if (args.isEmpty()) {
            Texts.send(player, "&cUsage: ${boundUsage("chestshop", defaultShopId, "open")}")
            return
        }
        when (args[0].lowercase()) {
            "open" -> ModuleRegistry.chestShop.open(player, args.getOrNull(1) ?: defaultShopId)
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
            else -> Texts.send(player, "&cUnknown chest shop subcommand.")
        }
    }

    private fun handleCartAlias(sender: ProxyCommandSender, args: Array<String>) {
        val player = requirePlayer(sender) ?: return
        handleCart(player, args.toList())
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
            sender.sendMessage(Texts.prefixed("&cUnsupported command sender."))
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
                Texts.send(commandSender, "&aConfiguration and modules reloaded.")
            }
            "sync" -> {
                if (!Permissions.require(commandSender, PermissionNodes.ADMIN_SYNC)) {
                    return
                }
                val schemaResult = DatabaseManager.syncSchema()
                if (!schemaResult.success) {
                    Texts.send(commandSender, "&c${schemaResult.message}")
                    return
                }
                val legacyResult = LegacyDataMigrationService.migrateAll()
                Texts.send(
                    commandSender,
                    "&a${schemaResult.message} &7(version ${schemaResult.startedVersion ?: "file"} -> ${schemaResult.finalVersion ?: "file"})"
                )
                Texts.send(commandSender, "&fLegacy import: &7${legacyResult.message}")
            }
            "status" -> {
                if (!Permissions.require(commandSender, PermissionNodes.ADMIN_STATUS)) {
                    return
                }
                val diagnostics = DatabaseManager.diagnostics()
                Texts.send(commandSender, "&fData folder: &7${ConfigFiles.dataFolder().absolutePath}")
                Texts.send(commandSender, "&fEconomy provider: &7${VaultEconomyBridge.providerName()}")
                Texts.send(commandSender, "&fConfigured data backend: &7${diagnostics.configuredBackend}")
                Texts.send(commandSender, "&fActive data backend: &7${diagnostics.activeBackend}")
                Texts.send(commandSender, "&fData target: &7${diagnostics.target}")
                Texts.send(
                    commandSender,
                    "&fData schema: &7${
                        diagnostics.schemaVersion?.let { "$it/${diagnostics.expectedSchemaVersion}" } ?: "file-backend"
                    }"
                )
                Texts.send(commandSender, "&fData schema state: &7${if (diagnostics.schemaCurrent) "current" else "pending"}")
                Texts.send(commandSender, "&fRedis notify: &7${if (diagnostics.redisEnabled) "enabled" else "disabled"}")
                Texts.send(commandSender, "&fRecord backend: &7${com.y54895.matrixshop.core.record.RecordService.backendName()}")
                if (diagnostics.pendingMigrations.isNotEmpty()) {
                    Texts.send(commandSender, "&fPending migrations: &7${diagnostics.pendingMigrations.joinToString(", ")}")
                }
                if (diagnostics.lastMigration.isNotBlank()) {
                    Texts.send(commandSender, "&fLast migration: &7${diagnostics.lastMigration}")
                }
                if (diagnostics.lastMigrationAt.isNotBlank()) {
                    Texts.send(commandSender, "&fLast migration at: &7${formatEpochMillis(diagnostics.lastMigrationAt)}")
                }
                if (diagnostics.lastLegacyImport.isNotBlank()) {
                    Texts.send(commandSender, "&fLast legacy import: &7${diagnostics.lastLegacyImport}")
                }
                if (diagnostics.lastLegacyImportAt.isNotBlank()) {
                    Texts.send(commandSender, "&fLast legacy import at: &7${formatEpochMillis(diagnostics.lastLegacyImportAt)}")
                }
                if (diagnostics.lastLegacyImport.isNotBlank()) {
                    Texts.send(commandSender, "&fLast legacy import total: &7${diagnostics.lastLegacyImportTotal}")
                }
                if (diagnostics.failureReason.isNotBlank()) {
                    Texts.send(commandSender, "&fData backend reason: &7${diagnostics.failureReason}")
                }
                if (diagnostics.tableCounts.isNotEmpty()) {
                    Texts.send(
                        commandSender,
                        "&fData tables: &7${
                            diagnostics.tableCounts.entries.joinToString(", ") { (table, count) -> "$table=$count" }
                        }"
                    )
                }
                ModuleRegistry.moduleStates().forEach { Texts.sendRaw(commandSender, it) }
            }
            "module" -> handleAdminModule(commandSender, args.drop(1))
            else -> sendAdminHelp(commandSender)
        }
    }

    private fun sendPlayerHelp(player: Player) {
        val lines = mutableListOf("&8[&bMatrixShop&8] &fPlayer Commands")
        addHelp(lines, showModuleHelp("system-shop") && Permissions.has(player, PermissionNodes.SYSTEMSHOP_USE), "&7/matrixshop &8- &fOpen SystemShop")
        addHelp(lines, showModuleHelp("system-shop") && Permissions.has(player, PermissionNodes.SYSTEMSHOP_USE), "&7${msUsage("system-shop", "open [category]")} &8- &fOpen a SystemShop category")
        addHelp(lines, Permissions.has(player, PermissionNodes.SYSTEMSHOP_USE), "&7/ms open <shop-id|category> &8- &fOpen a configured shop by file name, or a SystemShop category")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("auction") && Permissions.has(player, PermissionNodes.AUCTION_USE), shopHelpEntries("auction"), "open", "Open auction shop")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("auction") && Permissions.has(player, PermissionNodes.AUCTION_SELL), shopHelpEntries("auction"), "upload <english|dutch> <start> [buyout|end] [duration]", "List the main-hand item")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("auction") && Permissions.has(player, PermissionNodes.AUCTION_BID), shopHelpEntries("auction"), "bid <id> [price]", "Place an auction bid")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("auction") && Permissions.has(player, PermissionNodes.AUCTION_BUYOUT), shopHelpEntries("auction"), "buyout <id>", "Buy at buyout or Dutch price")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("auction") && Permissions.has(player, PermissionNodes.AUCTION_MANAGE_OWN), shopHelpEntries("auction"), "manage | bids", "Open your auction views")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("player-shop") && Permissions.has(player, PermissionNodes.PLAYERSHOP_USE), shopHelpEntries("player-shop"), "open", "Open your player shop")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("player-shop") && Permissions.has(player, PermissionNodes.PLAYERSHOP_USE), shopHelpEntries("player-shop"), "open [player]", "Open another player's shop")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("player-shop") && Permissions.has(player, PermissionNodes.PLAYERSHOP_MANAGE_OWN), shopHelpEntries("player-shop"), "edit", "Manage your player shop")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("player-shop") && Permissions.has(player, PermissionNodes.PLAYERSHOP_SELL), shopHelpEntries("player-shop"), "upload <price> [amount]", "List the main-hand item")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("global-market") && Permissions.has(player, PermissionNodes.GLOBALMARKET_USE), shopHelpEntries("global-market"), "open", "Open global market")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("global-market") && Permissions.has(player, PermissionNodes.GLOBALMARKET_SELL), shopHelpEntries("global-market"), "upload <price> [amount]", "List to this global market")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("global-market") && Permissions.has(player, PermissionNodes.GLOBALMARKET_MANAGE_OWN), shopHelpEntries("global-market"), "manage", "Manage your listings in this market")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("chestshop") && Permissions.has(player, PermissionNodes.CHESTSHOP_USE), shopHelpEntries("chestshop"), "open", "Open the target chest shop view")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("chestshop") && Permissions.has(player, PermissionNodes.CHESTSHOP_CREATE), shopHelpEntries("chestshop"), "create <buy|sell|dual> <price> [sell-price] [amount]", "Create a chest shop from the target chest")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("chestshop") && Permissions.has(player, PermissionNodes.CHESTSHOP_USE), shopHelpEntries("chestshop"), "stock | history", "Open stock or history for the target chest shop")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("chestshop") && Permissions.has(player, PermissionNodes.CHESTSHOP_MANAGE_OWN), shopHelpEntries("chestshop"), "edit | remove | price | amount | mode", "Manage your chest shop")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("cart") && Permissions.has(player, PermissionNodes.CART_USE), shopHelpEntries("cart"), "open", "Open cart")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("cart") && Permissions.has(player, PermissionNodes.CART_CHECKOUT), shopHelpEntries("cart"), "checkout [valid_only]", "Checkout the cart")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("cart") && Permissions.has(player, PermissionNodes.CART_CLEAR), shopHelpEntries("cart"), "remove <slot> | remove_invalid | clear", "Manage cart entries")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("cart") && Permissions.has(player, PermissionNodes.CART_USE), shopHelpEntries("cart"), "amount <slot> <number>", "Change one cart entry amount")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("record") && Permissions.has(player, PermissionNodes.RECORD_USE), shopHelpEntries("record"), "open [keyword]", "Open ledger records")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("record") && Permissions.has(player, PermissionNodes.RECORD_DETAIL_SELF), shopHelpEntries("record"), "detail <id>", "Open one record detail")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("record") && Permissions.has(player, PermissionNodes.RECORD_STATS_SELF), shopHelpEntries("record"), "income | expense | stats", "Open ledger statistics")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("transaction") && Permissions.has(player, PermissionNodes.TRANSACTION_USE), shopHelpEntries("transaction"), "open", "Open trade shop")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("transaction") && Permissions.has(player, PermissionNodes.TRANSACTION_USE), shopHelpEntries("transaction"), "request <player>", "Send a face-to-face trade request")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("transaction") && Permissions.has(player, PermissionNodes.TRANSACTION_USE), shopHelpEntries("transaction"), "accept [player] | ready | confirm | cancel | logs", "Control the active trade")
        addBoundShopHelp(lines, ModuleRegistry.isEnabled("transaction") && Permissions.has(player, PermissionNodes.TRANSACTION_USE), shopHelpEntries("transaction"), "money <amount> | exp <amount>", "Set your money or exp offer")
        Texts.sendRaw(player, lines.joinToString("\n"))
    }

    private fun sendAdminHelp(sender: CommandSender) {
        val lines = mutableListOf("&8[&bMatrixShop&8] &fAdmin Commands")
        addHelp(lines, Permissions.has(sender, PermissionNodes.ADMIN_RELOAD), "&7/matrixshopadmin reload &8- &fReload configuration and modules")
        addHelp(lines, Permissions.has(sender, PermissionNodes.ADMIN_SYNC), "&7/matrixshopadmin sync &8- &fRun schema sync and legacy data import")
        addHelp(lines, Permissions.has(sender, PermissionNodes.ADMIN_STATUS), "&7/matrixshopadmin status &8- &fShow module, economy and data-layer status")
        addHelp(lines, Permissions.has(sender, PermissionNodes.ADMIN_MODULE), "&7/matrixshopadmin module list &8- &fShow module states")
        addHelp(lines, Permissions.has(sender, PermissionNodes.ADMIN_MODULE), "&7/matrixshopadmin module <enable|disable|toggle> <id> &8- &fChange one module state")
        Texts.sendRaw(sender, lines.joinToString("\n"))
    }

    private fun handleAdminModule(sender: CommandSender, args: List<String>) {
        if (!Permissions.require(sender, PermissionNodes.ADMIN_MODULE)) {
            return
        }
        if (args.isEmpty() || args[0].equals("list", true)) {
            Texts.send(sender, "&fModules:")
            ModuleRegistry.all().forEach { module ->
                Texts.sendRaw(sender, "&7- &f${module.id}: ${if (module.isEnabled()) "&aenabled" else "&cdisabled"}")
            }
            return
        }
        val action = args[0].lowercase()
        val moduleId = resolveModuleId(args.getOrNull(1))
        if (moduleId == null) {
            Texts.send(sender, "&cUsage: /matrixshopadmin module <enable|disable|toggle> <id>")
            return
        }
        val enabled = when (action) {
            "enable", "on" -> true
            "disable", "off" -> false
            "toggle" -> !ConfigFiles.isModuleEnabled(moduleId, true)
            else -> {
                Texts.send(sender, "&cUsage: /matrixshopadmin module <enable|disable|toggle> <id>")
                return
            }
        }
        ConfigFiles.setModuleEnabled(moduleId, enabled)
        MatrixShop.reloadPlugin()
        Texts.send(sender, "&aModule &f$moduleId &ahas been set to &f${if (enabled) "enabled" else "disabled"}&a.")
    }

    private fun resolveModuleId(raw: String?): String? {
        return when (raw?.lowercase()) {
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
        description: String
    ) {
        if (!visible) {
            return
        }
        entries.forEach { entry ->
            val key = entry.bindings.keys.firstOrNull() ?: entry.id
            lines += "&7/$key $suffix &8- &f$description &7(${entry.id})"
        }
    }

    private fun shopHelpEntries(moduleId: String): List<com.y54895.matrixshop.core.menu.ShopMenuSelection> {
        if (!ModuleRegistry.isEnabled(moduleId)) {
            return emptyList()
        }
        return when (moduleId) {
            "auction" -> ModuleRegistry.auction.helpEntries()
            "player-shop" -> ModuleRegistry.playerShop.helpEntries()
            "global-market" -> ModuleRegistry.globalMarket.helpEntries()
            "cart" -> ModuleRegistry.cart.helpEntries()
            "record" -> ModuleRegistry.record.helpEntries()
            "chestshop" -> ModuleRegistry.chestShop.helpEntries()
            "transaction" -> ModuleRegistry.transaction.helpEntries()
            else -> emptyList()
        }
    }

    private fun msUsage(moduleId: String, suffix: String): String {
        return "/ms ${ModuleBindings.primary(moduleId)} $suffix".trim()
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
            sender.sendMessage(Texts.prefixed("&cThis command can only be used by players."))
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
        if ((moduleId == null || moduleId == "cart") && ModuleRegistry.isEnabled("cart")) {
            ModuleRegistry.cart.allShopEntries().forEach { routes += BoundShopEntry(ShopBindingRoute("cart", it.id), it) }
        }
        if ((moduleId == null || moduleId == "record") && ModuleRegistry.isEnabled("record")) {
            ModuleRegistry.record.allShopEntries().forEach { routes += BoundShopEntry(ShopBindingRoute("record", it.id), it) }
        }
        if ((moduleId == null || moduleId == "player-shop") && ModuleRegistry.isEnabled("player-shop")) {
            ModuleRegistry.playerShop.allShopEntries().forEach { routes += BoundShopEntry(ShopBindingRoute("player-shop", it.id), it) }
        }
        if ((moduleId == null || moduleId == "auction") && ModuleRegistry.isEnabled("auction")) {
            ModuleRegistry.auction.allShopEntries().forEach { routes += BoundShopEntry(ShopBindingRoute("auction", it.id), it) }
        }
        if ((moduleId == null || moduleId == "chestshop") && ModuleRegistry.isEnabled("chestshop")) {
            ModuleRegistry.chestShop.allShopEntries().forEach { routes += BoundShopEntry(ShopBindingRoute("chestshop", it.id), it) }
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

    private fun standaloneBoundShopEntries(moduleId: String): List<BoundShopEntry> {
        val entries = mutableListOf<BoundShopEntry>()
        when (moduleId) {
            "cart" -> if (ModuleRegistry.isEnabled("cart")) ModuleRegistry.cart.standaloneEntries().forEach { entries += BoundShopEntry(ShopBindingRoute("cart", it.id), it) }
            "record" -> if (ModuleRegistry.isEnabled("record")) ModuleRegistry.record.standaloneEntries().forEach { entries += BoundShopEntry(ShopBindingRoute("record", it.id), it) }
            "global-market" -> if (ModuleRegistry.isEnabled("global-market")) ModuleRegistry.globalMarket.standaloneEntries().forEach { entries += BoundShopEntry(ShopBindingRoute("global-market", it.id), it) }
            "player-shop" -> if (ModuleRegistry.isEnabled("player-shop")) ModuleRegistry.playerShop.standaloneEntries().forEach { entries += BoundShopEntry(ShopBindingRoute("player-shop", it.id), it) }
            "auction" -> if (ModuleRegistry.isEnabled("auction")) ModuleRegistry.auction.standaloneEntries().forEach { entries += BoundShopEntry(ShopBindingRoute("auction", it.id), it) }
            "chestshop" -> if (ModuleRegistry.isEnabled("chestshop")) ModuleRegistry.chestShop.standaloneEntries().forEach { entries += BoundShopEntry(ShopBindingRoute("chestshop", it.id), it) }
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
