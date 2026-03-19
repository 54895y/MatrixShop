package com.y54895.matrixshop.core.command

import com.y54895.matrixshop.MatrixShop
import com.y54895.matrixshop.core.config.ConfigFiles
import com.y54895.matrixshop.core.economy.VaultEconomyBridge
import com.y54895.matrixshop.core.module.ModuleRegistry
import com.y54895.matrixshop.core.text.Texts
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import taboolib.common.platform.ProxyCommandSender
import taboolib.common.platform.command.simpleCommand

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
            usage = "/matrixshopadmin",
            permission = "matrixshop.admin"
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
    }

    private fun handleMain(sender: ProxyCommandSender, args: Array<String>) {
        val player = sender.castSafely<Player>()
        if (player == null) {
            sender.sendMessage(Texts.prefixed("&cThis command can only be used by players."))
            return
        }
        if (args.isEmpty()) {
            ModuleRegistry.systemShop.openMain(player)
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
            "chestshop" -> Texts.send(player, "&e${args[0]} module scaffold exists, implementation is still pending.")
            else -> sendPlayerHelp(player)
        }
    }

    private fun handleSystem(player: Player, args: List<String>) {
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
        if (args.isEmpty()) {
            Texts.send(player, "&cMissing confirm subcommand.")
            return
        }
        when (args[0].lowercase()) {
            "action", "buy" -> ModuleRegistry.systemShop.confirmPurchase(player)
            "cart" -> ModuleRegistry.cart.addCurrentSystemSelection(player)
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
        if (args.isEmpty() || args[0].equals("open", true)) {
            ModuleRegistry.cart.open(player)
            return
        }
        when (args[0].lowercase()) {
            "checkout" -> ModuleRegistry.cart.checkout(player, args.getOrNull(1)?.equals("valid_only", true) == true)
            "clear" -> ModuleRegistry.cart.clear(player)
            "remove" -> {
                val index = args.getOrNull(1)?.toIntOrNull()
                if (index == null) {
                    Texts.send(player, "&cUsage: /matrixshop cart remove <slot>")
                    return
                }
                ModuleRegistry.cart.remove(player, index)
            }
            "remove_invalid" -> ModuleRegistry.cart.removeInvalid(player)
            "amount" -> {
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
        if (args.isEmpty() || args[0].equals("open", true)) {
            ModuleRegistry.globalMarket.openMarket(player)
            return
        }
        when (args[0].lowercase()) {
            "upload" -> {
                val price = args.getOrNull(1)?.toDoubleOrNull()
                if (price == null) {
                    ModuleRegistry.globalMarket.openUpload(player)
                    return
                }
                val amount = args.getOrNull(2)?.toIntOrNull()
                ModuleRegistry.globalMarket.uploadFromHand(player, price, amount)
            }
            "manage" -> ModuleRegistry.globalMarket.openManage(player)
            else -> Texts.send(player, "&cUnknown global market subcommand.")
        }
    }

    private fun handlePlayerShop(player: Player, args: List<String>) {
        if (args.isEmpty()) {
            ModuleRegistry.playerShop.openShop(player, player.uniqueId, player.name)
            return
        }
        when (args[0].lowercase()) {
            "open" -> {
                val targetName = args.getOrNull(1) ?: player.name
                ModuleRegistry.playerShop.openShop(player, targetName)
            }
            "edit" -> ModuleRegistry.playerShop.openEdit(player)
            "upload" -> {
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
        if (args.isEmpty() || args[0].equals("open", true)) {
            val keyword = args.drop(1).joinToString(" ").ifBlank { null }
            ModuleRegistry.record.open(player, keyword)
            return
        }
        when (args[0].lowercase()) {
            "detail" -> {
                val recordId = args.getOrNull(1)
                if (recordId.isNullOrBlank()) {
                    Texts.send(player, "&cUsage: /matrixshop record detail <id>")
                    return
                }
                ModuleRegistry.record.openDetail(player, recordId)
            }
            "income" -> ModuleRegistry.record.openIncome(player)
            "expense" -> ModuleRegistry.record.openExpense(player)
            "search" -> {
                val keyword = args.drop(1).joinToString(" ").ifBlank { null }
                ModuleRegistry.record.open(player, keyword)
            }
            "stats" -> ModuleRegistry.record.openIncome(player)
            else -> Texts.send(player, "&cUnknown record subcommand.")
        }
    }

    private fun handleAuction(player: Player, args: List<String>) {
        if (args.isEmpty() || args[0].equals("open", true)) {
            ModuleRegistry.auction.openAuction(player)
            return
        }
        when (args[0].lowercase()) {
            "upload" -> {
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
                val id = args.getOrNull(1)
                if (id.isNullOrBlank()) {
                    Texts.send(player, "&cUsage: /auction bid <id> [price]")
                    return
                }
                ModuleRegistry.auction.bid(player, id, args.getOrNull(2)?.toDoubleOrNull())
            }
            "buyout" -> {
                val id = args.getOrNull(1)
                if (id.isNullOrBlank()) {
                    Texts.send(player, "&cUsage: /auction buyout <id>")
                    return
                }
                ModuleRegistry.auction.buyout(player, id)
            }
            "my_items", "manage" -> ModuleRegistry.auction.openManage(player)
            "my_bids", "bids" -> ModuleRegistry.auction.openBids(player)
            "remove" -> {
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

    private fun handleAdmin(sender: ProxyCommandSender, args: Array<String>) {
        val commandSender = sender.castSafely<CommandSender>() ?: return
        if (args.isEmpty()) {
            sendAdminHelp(commandSender)
            return
        }
        when (args[0].lowercase()) {
            "reload" -> {
                MatrixShop.reloadPlugin()
                Texts.send(commandSender, "&aConfiguration and modules reloaded.")
            }
            "status" -> {
                Texts.send(commandSender, "&fData folder: &7${ConfigFiles.dataFolder().absolutePath}")
                Texts.send(commandSender, "&fEconomy provider: &7${VaultEconomyBridge.providerName()}")
                ModuleRegistry.moduleStates().forEach { Texts.sendRaw(commandSender, it) }
            }
            else -> sendAdminHelp(commandSender)
        }
    }

    private fun sendPlayerHelp(player: Player) {
        Texts.sendRaw(
            player,
            """
            &8[&bMatrixShop&8] &fPlayer Commands
            &7/matrixshop &8- &fOpen SystemShop
            &7/auction open &8- &fOpen the auction hall
            &7/auction upload <english|dutch> <start> [buyout|end] [duration] &8- &fList the main-hand item
            &7/auction bid <id> [price] &8- &fPlace an auction bid
            &7/auction buyout <id> &8- &fBuy at buyout or current Dutch price
            &7/auction my_items|my_bids &8- &fOpen your auction views
            &7/matrixshop system open <id> &8- &fOpen a system category
            &7/matrixshop player_shop open [player] &8- &fOpen a player shop
            &7/matrixshop player_shop edit &8- &fManage your player shop
            &7/matrixshop player_shop upload <price> [amount] &8- &fList the main-hand item
            &7/matrixshop global_market open &8- &fOpen GlobalMarket
            &7/matrixshop global_market upload <price> [amount] &8- &fList to GlobalMarket
            &7/matrixshop global_market manage &8- &fManage your GlobalMarket listings
            &7/matrixshop cart open &8- &fOpen cart
            &7/matrixshop cart checkout [valid_only] &8- &fCheckout the cart
            &7/matrixshop record open [keyword] &8- &fOpen ledger records
            &7/matrixshop record detail <id> &8- &fOpen one record detail
            &7/matrixshop record income &8- &fOpen income statistics
            &7/matrixshop record expense &8- &fOpen expense statistics
            &7/trade request <player> &8- &fSend a face-to-face trade request
            &7/trade accept [player] &8- &fAccept a pending trade request
            &7/trade money <amount> &8- &fSet your money offer
            &7/trade exp <amount> &8- &fSet your exp offer
            &7/trade ready|confirm|cancel|logs &8- &fControl the active trade
            """.trimIndent()
        )
    }

    private fun sendAdminHelp(sender: CommandSender) {
        Texts.sendRaw(
            sender,
            """
            &8[&bMatrixShop&8] &fAdmin Commands
            &7/matrixshopadmin reload &8- &fReload configuration and modules
            &7/matrixshopadmin status &8- &fShow module and economy status
            """.trimIndent()
        )
    }
}
