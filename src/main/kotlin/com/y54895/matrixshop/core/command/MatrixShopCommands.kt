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
    }

    private fun handleMain(sender: ProxyCommandSender, args: Array<String>) {
        val player = sender.castSafely<Player>()
        if (player == null) {
            sender.sendMessage(Texts.prefixed("&c该命令只能由玩家执行。"))
            return
        }
        if (args.isEmpty()) {
            ModuleRegistry.systemShop.openMain(player)
            return
        }
        when (args[0].lowercase()) {
            "help" -> sendPlayerHelp(player)
            "open" -> ModuleRegistry.systemShop.openMain(player)
            "system" -> handleSystem(player, args.drop(1))
            "player_shop", "playershop" -> handlePlayerShop(player, args.drop(1))
            "cart" -> handleCart(player, args.drop(1))
            "auction", "transaction", "record", "chestshop" -> Texts.send(player, "&e${args[0]} 模块骨架已创建，业务实现将在后续迭代中补全。")
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
            Texts.send(player, "&c缺少 confirm 子命令。")
            return
        }
        when (args[0].lowercase()) {
            "action", "buy" -> ModuleRegistry.systemShop.confirmPurchase(player)
            "cart" -> ModuleRegistry.cart.addCurrentSystemSelection(player)
            "amount" -> {
                if (args.getOrNull(1)?.equals("add", true) != true) {
                    Texts.send(player, "&c用法: /matrixshop system confirm amount add <number>")
                    return
                }
                val delta = args.getOrNull(2)?.toIntOrNull()
                if (delta == null) {
                    Texts.send(player, "&c数量必须是整数。")
                    return
                }
                ModuleRegistry.systemShop.adjustConfirmAmount(player, delta)
            }
            else -> Texts.send(player, "&e当前仅实现了购买确认和数量调整。")
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
                    Texts.send(player, "&c用法: /matrixshop cart remove <slot>")
                    return
                }
                ModuleRegistry.cart.remove(player, index)
            }
            "remove_invalid" -> ModuleRegistry.cart.removeInvalid(player)
            "amount" -> {
                val index = args.getOrNull(1)?.toIntOrNull()
                val amount = args.getOrNull(2)?.toIntOrNull()
                if (index == null || amount == null) {
                    Texts.send(player, "&c用法: /matrixshop cart amount <slot> <number>")
                    return
                }
                ModuleRegistry.cart.changeAmount(player, index, amount)
            }
            else -> Texts.send(player, "&c未知的购物车子命令。")
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
                    Texts.send(player, "&c用法: /matrixshop player_shop upload <price> [amount]")
                    return
                }
                val amount = args.getOrNull(2)?.toIntOrNull()
                ModuleRegistry.playerShop.uploadFromHand(player, price, amount)
            }
            else -> Texts.send(player, "&c未知的玩家商店子命令。")
        }
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
                Texts.send(commandSender, "&a配置与模块已重载。")
            }
            "status" -> {
                Texts.send(commandSender, "&f数据目录: &7${ConfigFiles.dataFolder().absolutePath}")
                Texts.send(commandSender, "&f经济提供者: &7${VaultEconomyBridge.providerName()}")
                ModuleRegistry.moduleStates().forEach { Texts.sendRaw(commandSender, it) }
            }
            else -> sendAdminHelp(commandSender)
        }
    }

    private fun sendPlayerHelp(player: Player) {
        Texts.sendRaw(
            player,
            """
            &8[&bMatrixShop&8] &f玩家命令
            &7/matrixshop &8- &f打开系统商店主界面
            &7/matrixshop system open <id> &8- &f打开指定系统商店分类
            &7/matrixshop player_shop open [player] &8- &f打开玩家商店
            &7/matrixshop player_shop edit &8- &f管理自己的玩家商店
            &7/matrixshop player_shop upload <price> [amount] &8- &f上架主手物品
            &7/matrixshop cart open &8- &f打开购物车
            &7/matrixshop cart checkout &8- &f结算购物车
            &7/matrixshop help &8- &f查看帮助
            """.trimIndent()
        )
    }

    private fun sendAdminHelp(sender: CommandSender) {
        Texts.sendRaw(
            sender,
            """
            &8[&bMatrixShop&8] &f管理员命令
            &7/matrixshopadmin reload &8- &f重载配置与模块
            &7/matrixshopadmin status &8- &f查看模块与经济状态
            """.trimIndent()
        )
    }
}
