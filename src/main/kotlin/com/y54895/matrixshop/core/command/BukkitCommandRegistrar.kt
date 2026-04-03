package com.y54895.matrixshop.core.command

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandMap
import org.bukkit.command.CommandSender
import java.lang.reflect.Field

internal object BukkitCommandRegistrar {

    fun register(command: Command) {
        commandMap()?.register(command.name.lowercase(), command)
        syncCommands()
    }

    private fun commandMap(): CommandMap? {
        return runCatching {
            val server = Bukkit.getServer()
            val field = findCommandMapField(server.javaClass)
            field.isAccessible = true
            field.get(server) as? CommandMap
        }.onFailure {
            Bukkit.getLogger().warning("MatrixShop failed to resolve Bukkit command map: ${it.message}")
        }.getOrNull()
    }

    private fun findCommandMapField(type: Class<*>): Field {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return current.getDeclaredField("commandMap")
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        throw NoSuchFieldException("commandMap")
    }

    private fun syncCommands() {
        runCatching {
            val method = Bukkit.getServer().javaClass.methods.firstOrNull {
                it.name == "syncCommands" && it.parameterCount == 0
            } ?: return
            method.invoke(Bukkit.getServer())
        }
    }
}

internal class MatrixRoutingCommand(
    name: String,
    description: String,
    usage: String,
    permissionNode: String? = null,
    private val executeHandler: (CommandSender, String, Array<out String>) -> Boolean,
    private val tabHandler: (CommandSender, String, Array<out String>) -> List<String>
) : Command(name) {

    init {
        this.description = description
        this.usageMessage = usage
        this.permission = permissionNode
    }

    override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>): Boolean {
        return executeHandler(sender, commandLabel, args)
    }

    override fun tabComplete(sender: CommandSender, alias: String, args: Array<out String>): MutableList<String> {
        return tabHandler(sender, alias, args).toMutableList()
    }
}
