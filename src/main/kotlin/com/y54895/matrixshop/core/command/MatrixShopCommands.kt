package com.y54895.matrixshop.core.command

import com.y54895.matrixshop.MatrixShop
import com.y54895.matrixshop.core.config.ConfigFiles
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
        simpleCommand(
            name = "trade",
            aliases = listOf("tm"),
            description = "MatrixShop trade command",
            usage = "/trade",
            permission = "matrixshop.transaction.use"
        ) { sender, args ->
            handleTradeAlias(sender, args)
        }
        simpleCommand(
            name = "auction",
            aliases = listOf("ah"),
            description = "MatrixShop auction command",
            usage = "/auction",
            permission = "matrixshop.auction.use"
        ) { sender, args ->
            handleAuctionAlias(sender, args)
        }
        simpleCommand(
            name = "chestshop",
            aliases = listOf("cshop"),
            description = "MatrixShop chest shop command",
            usage = "/chestshop",
            permission = "matrixshop.chestshop.use"
        ) { sender, args ->
            handleChestShopAlias(sender, args)
        }
    }

    private fun handleMain(sender: ProxyCommandSender, args: Array<String>) {
        val player = sender.castSafely<Player>()
        if (player == null) {
            sender.sendMessage(Texts.prefixed("&cThis command can only be used by players."))
            return
        }
        if (args.isEmpty()) {
            if (Permissions.require(player, PermissionNodes.SYSTEMSHOP_USE)) {
                ModuleRegistry.systemShop.openMain(player)
            }
            return
        }
        when (args[0].lowercase()) {
            "help" -> sendPlayerHelp(player)
            "open" -> ModuleRegistry.systemShop.openMain(player)
            "auction" -> handleAuction(player, args.drop(1))
            "system" -> handleSystem(player, args.drop(1))
            "player_shop", "playershop" -> handlePlayerShop(player, args.drop(1))
            "global_market", "globalmarket", "market" -> handleGlobalMarket(player, args.drop(1))
            "cart" -> handleCart(player, args.drop(1))
            "record" -> handleRecord(player, args.drop(1))
            "transaction", "trade" -> handleTransaction(player, args.drop(1))
            "chestshop" -> handleChestShop(player, args.drop(1))
            else -> sendPlayerHelp(player)
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

    private fun handleCart(player: Player, args: List<String>) {
        if (!Permissions.require(player, PermissionNodes.CART_USE)) {
            return
        }
        if (args.isEmpty() || args[0].equals("open", true)) {
            ModuleRegistry.cart.open(player)
            return
        }
        when (args[0].lowercase()) {
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

    private fun handleGlobalMarket(player: Player, args: List<String>) {
        if (!Permissions.require(player, PermissionNodes.GLOBALMARKET_USE)) {
            return
        }
        if (args.isEmpty() || args[0].equals("open", true)) {
            ModuleRegistry.globalMarket.openMarket(player)
            return
        }
        when (args[0].lowercase()) {
            "upload" -> {
                if (!Permissions.require(player, PermissionNodes.GLOBALMARKET_SELL)) {
                    return
                }
                val price = args.getOrNull(1)?.toDoubleOrNull()
                if (price == null) {
                    ModuleRegistry.globalMarket.openUpload(player)
                    return
                }
                val amount = args.getOrNull(2)?.toIntOrNull()
                ModuleRegistry.globalMarket.uploadFromHand(player, price, amount)
            }
            "manage" -> {
                if (!Permissions.require(player, PermissionNodes.GLOBALMARKET_MANAGE_OWN)) {
                    return
                }
                ModuleRegistry.globalMarket.openManage(player)
            }
            else -> Texts.send(player, "&cUnknown global market subcommand.")
        }
    }

    private fun handlePlayerShop(player: Player, args: List<String>) {
        if (!Permissions.require(player, PermissionNodes.PLAYERSHOP_USE)) {
            return
        }
        if (args.isEmpty()) {
            ModuleRegistry.playerShop.openShop(player, player.uniqueId, player.name)
            return
        }
        when (args[0].lowercase()) {
            "open" -> {
                val targetName = args.getOrNull(1) ?: player.name
                ModuleRegistry.playerShop.openShop(player, targetName)
            }
            "edit" -> {
                if (!Permissions.require(player, PermissionNodes.PLAYERSHOP_MANAGE_OWN)) {
                    return
                }
                ModuleRegistry.playerShop.openEdit(player)
            }
            "upload" -> {
                if (!Permissions.require(player, PermissionNodes.PLAYERSHOP_SELL)) {
                    return
                }
                val price = args.getOrNull(1)?.toDoubleOrNull()
                if (price == null) {
                    Texts.send(player, "&cUsage: /matrixshop player_shop upload <price> [amount]")
                    return
                }
                val amount = args.getOrNull(2)?.toIntOrNull()
                ModuleRegistry.playerShop.uploadFromHand(player, price, amount)
            }
            else -> Texts.send(player, "&cUnknown player shop subcommand.")
        }
    }

    private fun handleRecord(player: Player, args: List<String>) {
        if (!Permissions.require(player, PermissionNodes.RECORD_USE)) {
            return
        }
        if (args.isEmpty() || args[0].equals("open", true)) {
            val keyword = args.drop(1).joinToString(" ").ifBlank { null }
            ModuleRegistry.record.open(player, keyword)
            return
        }
        when (args[0].lowercase()) {
            "detail" -> {
                if (!Permissions.require(player, PermissionNodes.RECORD_DETAIL_SELF)) {
                    return
                }
                val recordId = args.getOrNull(1)
                if (recordId.isNullOrBlank()) {
                    Texts.send(player, "&cUsage: /matrixshop record detail <id>")
                    return
                }
                ModuleRegistry.record.openDetail(player, recordId)
            }
            "income" -> {
                if (!Permissions.require(player, PermissionNodes.RECORD_STATS_SELF)) {
                    return
                }
                ModuleRegistry.record.openIncome(player)
            }
            "expense" -> {
                if (!Permissions.require(player, PermissionNodes.RECORD_STATS_SELF)) {
                    return
                }
                ModuleRegistry.record.openExpense(player)
            }
            "search" -> {
                val keyword = args.drop(1).joinToString(" ").ifBlank { null }
                ModuleRegistry.record.open(player, keyword)
            }
            "stats" -> {
                if (!Permissions.require(player, PermissionNodes.RECORD_STATS_SELF)) {
                    return
                }
                ModuleRegistry.record.openIncome(player)
            }
            else -> Texts.send(player, "&cUnknown record subcommand.")
        }
    }

    private fun handleAuction(player: Player, args: List<String>) {
        if (!Permissions.require(player, PermissionNodes.AUCTION_USE)) {
            return
        }
        if (args.isEmpty() || args[0].equals("open", true)) {
            ModuleRegistry.auction.openAuction(player)
            return
        }
        when (args[0].lowercase()) {
            "upload" -> {
                if (!Permissions.require(player, PermissionNodes.AUCTION_SELL)) {
                    return
                }
                if (args.size == 1) {
                    ModuleRegistry.auction.openUpload(player)
                    return
                }
                ModuleRegistry.auction.uploadFromHand(
                    player = player,
                    modeRaw = args.getOrNull(1),
                    startPrice = args.getOrNull(2)?.toDoubleOrNull(),
                    secondPrice = args.getOrNull(3)?.toDoubleOrNull(),
                    durationSeconds = args.getOrNull(4)?.toIntOrNull()
                )
            }
            "detail" -> {
                val id = args.getOrNull(1)
                if (id.isNullOrBlank()) {
                    Texts.send(player, "&cUsage: /auction detail <id>")
                    return
                }
                ModuleRegistry.auction.openDetail(player, id)
            }
            "bid" -> {
                if (!Permissions.require(player, PermissionNodes.AUCTION_BID)) {
                    return
                }
                val id = args.getOrNull(1)
                if (id.isNullOrBlank()) {
                    Texts.send(player, "&cUsage: /auction bid <id> [price]")
                    return
                }
                ModuleRegistry.auction.bid(player, id, args.getOrNull(2)?.toDoubleOrNull())
            }
            "buyout" -> {
                if (!Permissions.require(player, PermissionNodes.AUCTION_BUYOUT)) {
                    return
                }
                val id = args.getOrNull(1)
                if (id.isNullOrBlank()) {
                    Texts.send(player, "&cUsage: /auction buyout <id>")
                    return
                }
                ModuleRegistry.auction.buyout(player, id)
            }
            "my_items", "manage" -> {
                if (!Permissions.require(player, PermissionNodes.AUCTION_MANAGE_OWN)) {
                    return
                }
                ModuleRegistry.auction.openManage(player)
            }
            "my_bids", "bids" -> ModuleRegistry.auction.openBids(player)
            "remove" -> {
                if (!Permissions.require(player, PermissionNodes.AUCTION_MANAGE_OWN)) {
                    return
                }
                val id = args.getOrNull(1)
                if (id.isNullOrBlank()) {
                    Texts.send(player, "&cUsage: /auction remove <id>")
                    return
                }
                ModuleRegistry.auction.remove(player, id)
            }
            else -> Texts.send(player, "&cUnknown auction subcommand.")
        }
    }

    private fun handleTransaction(player: Player, args: List<String>) {
        if (!Permissions.require(player, PermissionNodes.TRANSACTION_USE)) {
            return
        }
        if (args.isEmpty() || args[0].equals("open", true)) {
            ModuleRegistry.transaction.open(player)
            return
        }
        when (args[0].lowercase()) {
            "request" -> ModuleRegistry.transaction.requestTrade(player, args.getOrNull(1))
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

    private fun handleChestShop(player: Player, args: List<String>) {
        if (!Permissions.require(player, PermissionNodes.CHESTSHOP_USE)) {
            return
        }
        if (args.isEmpty() || args[0].equals("open", true)) {
            ModuleRegistry.chestShop.open(player)
            return
        }
        when (args[0].lowercase()) {
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
                ModuleRegistry.chestShop.openEdit(player)
            }
            "stock" -> ModuleRegistry.chestShop.openStock(player, args.getOrNull(1)?.toIntOrNull() ?: 1)
            "history" -> ModuleRegistry.chestShop.openHistory(player, args.getOrNull(1)?.toIntOrNull() ?: 1)
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

    private fun handleTradeAlias(sender: ProxyCommandSender, args: Array<String>) {
        val player = sender.castSafely<Player>()
        if (player == null) {
            sender.sendMessage(Texts.prefixed("&cThis command can only be used by players."))
            return
        }
        handleTransaction(player, args.toList())
    }

    private fun handleAuctionAlias(sender: ProxyCommandSender, args: Array<String>) {
        val player = sender.castSafely<Player>()
        if (player == null) {
            sender.sendMessage(Texts.prefixed("&cThis command can only be used by players."))
            return
        }
        handleAuction(player, args.toList())
    }

    private fun handleChestShopAlias(sender: ProxyCommandSender, args: Array<String>) {
        val player = sender.castSafely<Player>()
        if (player == null) {
            sender.sendMessage(Texts.prefixed("&cThis command can only be used by players."))
            return
        }
        handleChestShop(player, args.toList())
    }

    private fun handleAdmin(sender: ProxyCommandSender, args: Array<String>) {
        val commandSender = sender.castSafely<CommandSender>() ?: return
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
        addHelp(lines, ModuleRegistry.isEnabled("system-shop") && Permissions.has(player, PermissionNodes.SYSTEMSHOP_USE), "&7/matrixshop &8- &fOpen SystemShop")
        addHelp(lines, ModuleRegistry.isEnabled("auction") && Permissions.has(player, PermissionNodes.AUCTION_USE), "&7/auction open &8- &fOpen the auction hall")
        addHelp(lines, ModuleRegistry.isEnabled("auction") && Permissions.has(player, PermissionNodes.AUCTION_SELL), "&7/auction upload <english|dutch> <start> [buyout|end] [duration] &8- &fList the main-hand item")
        addHelp(lines, ModuleRegistry.isEnabled("auction") && Permissions.has(player, PermissionNodes.AUCTION_BID), "&7/auction bid <id> [price] &8- &fPlace an auction bid")
        addHelp(lines, ModuleRegistry.isEnabled("auction") && Permissions.has(player, PermissionNodes.AUCTION_BUYOUT), "&7/auction buyout <id> &8- &fBuy at buyout or current Dutch price")
        addHelp(lines, ModuleRegistry.isEnabled("auction") && Permissions.has(player, PermissionNodes.AUCTION_MANAGE_OWN), "&7/auction my_items|my_bids &8- &fOpen your auction views")
        addHelp(lines, ModuleRegistry.isEnabled("player-shop") && Permissions.has(player, PermissionNodes.PLAYERSHOP_USE), "&7/matrixshop player_shop open [player] &8- &fOpen a player shop")
        addHelp(lines, ModuleRegistry.isEnabled("player-shop") && Permissions.has(player, PermissionNodes.PLAYERSHOP_MANAGE_OWN), "&7/matrixshop player_shop edit &8- &fManage your player shop")
        addHelp(lines, ModuleRegistry.isEnabled("player-shop") && Permissions.has(player, PermissionNodes.PLAYERSHOP_SELL), "&7/matrixshop player_shop upload <price> [amount] &8- &fList the main-hand item")
        addHelp(lines, ModuleRegistry.isEnabled("global-market") && Permissions.has(player, PermissionNodes.GLOBALMARKET_USE), "&7/matrixshop global_market open &8- &fOpen GlobalMarket")
        addHelp(lines, ModuleRegistry.isEnabled("global-market") && Permissions.has(player, PermissionNodes.GLOBALMARKET_SELL), "&7/matrixshop global_market upload <price> [amount] &8- &fList to GlobalMarket")
        addHelp(lines, ModuleRegistry.isEnabled("global-market") && Permissions.has(player, PermissionNodes.GLOBALMARKET_MANAGE_OWN), "&7/matrixshop global_market manage &8- &fManage your GlobalMarket listings")
        addHelp(lines, ModuleRegistry.isEnabled("chestshop") && Permissions.has(player, PermissionNodes.CHESTSHOP_CREATE), "&7/chestshop create <buy|sell|dual> <price> [sell-price] [amount] &8- &fCreate a chest shop from the target chest")
        addHelp(lines, ModuleRegistry.isEnabled("chestshop") && Permissions.has(player, PermissionNodes.CHESTSHOP_USE), "&7/chestshop open|stock|history &8- &fOpen the target chest shop views")
        addHelp(lines, ModuleRegistry.isEnabled("chestshop") && Permissions.has(player, PermissionNodes.CHESTSHOP_MANAGE_OWN), "&7/chestshop edit|remove|price|amount|mode &8- &fManage your chest shop")
        addHelp(lines, ModuleRegistry.isEnabled("cart") && Permissions.has(player, PermissionNodes.CART_USE), "&7/matrixshop cart open &8- &fOpen cart")
        addHelp(lines, ModuleRegistry.isEnabled("cart") && Permissions.has(player, PermissionNodes.CART_CHECKOUT), "&7/matrixshop cart checkout [valid_only] &8- &fCheckout the cart")
        addHelp(lines, ModuleRegistry.isEnabled("record") && Permissions.has(player, PermissionNodes.RECORD_USE), "&7/matrixshop record open [keyword] &8- &fOpen ledger records")
        addHelp(lines, ModuleRegistry.isEnabled("record") && Permissions.has(player, PermissionNodes.RECORD_DETAIL_SELF), "&7/matrixshop record detail <id> &8- &fOpen one record detail")
        addHelp(lines, ModuleRegistry.isEnabled("record") && Permissions.has(player, PermissionNodes.RECORD_STATS_SELF), "&7/matrixshop record income|expense|stats &8- &fOpen ledger statistics")
        addHelp(lines, ModuleRegistry.isEnabled("transaction") && Permissions.has(player, PermissionNodes.TRANSACTION_USE), "&7/trade request <player> &8- &fSend a face-to-face trade request")
        addHelp(lines, ModuleRegistry.isEnabled("transaction") && Permissions.has(player, PermissionNodes.TRANSACTION_USE), "&7/trade accept [player] / ready / confirm / cancel / logs &8- &fControl the active trade")
        addHelp(lines, ModuleRegistry.isEnabled("transaction") && Permissions.has(player, PermissionNodes.TRANSACTION_USE), "&7/trade money <amount> / exp <amount> &8- &fSet your money or exp offer")
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

    private fun formatEpochMillis(value: String): String {
        val millis = value.toLongOrNull() ?: return value
        return runCatching {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(millis))
        }.getOrDefault(value)
    }
}
