package com.y54895.matrixshop.core.economy

import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import taboolib.common.platform.function.warning

object VaultEconomyBridge {

    private var economy: Any? = null
    private var economyClass: Class<*>? = null

    fun reload() {
        economy = null
        economyClass = null
        runCatching {
            val clazz = Class.forName("net.milkbowl.vault.economy.Economy")
            val registration = Bukkit.getServicesManager().getRegistration(clazz)
            economyClass = clazz
            economy = registration?.provider
        }.onFailure {
            warning("Vault API not found. Paid purchases will require Vault before they can work.")
        }
    }

    fun providerName(): String {
        return economy?.javaClass?.simpleName ?: "none"
    }

    fun isAvailable(): Boolean {
        return economy != null && economyClass != null
    }

    fun balance(player: OfflinePlayer): Double {
        val provider = economy ?: return 0.0
        return invokeDouble(provider, "getBalance", player)
    }

    fun has(player: OfflinePlayer, amount: Double): Boolean {
        if (amount <= 0) {
            return true
        }
        val provider = economy ?: return false
        return invokeBoolean(provider, "has", player, amount)
    }

    fun withdraw(player: Player, amount: Double): Boolean {
        if (amount <= 0) {
            return true
        }
        val response = invokeEconomy("withdrawPlayer", player, amount) ?: return false
        return transactionSuccess(response)
    }

    fun deposit(player: OfflinePlayer, amount: Double): Boolean {
        if (amount <= 0) {
            return true
        }
        val response = invokeEconomy("depositPlayer", player, amount) ?: return false
        return transactionSuccess(response)
    }

    private fun invokeDouble(target: Any, methodName: String, vararg args: Any): Double {
        return (invokeTarget(target, methodName, *args) as? Number)?.toDouble() ?: 0.0
    }

    private fun invokeBoolean(target: Any, methodName: String, vararg args: Any): Boolean {
        return invokeTarget(target, methodName, *args) as? Boolean ?: false
    }

    private fun invokeEconomy(methodName: String, vararg args: Any): Any? {
        val provider = economy ?: return null
        return invokeTarget(provider, methodName, *args)
    }

    private fun invokeTarget(target: Any, methodName: String, vararg args: Any): Any? {
        val methods = target.javaClass.methods.filter {
            it.name == methodName && it.parameterTypes.size == args.size
        }
        methods.forEach { method ->
            val adapted = adaptArguments(method.parameterTypes, args) ?: return@forEach
            val result = runCatching { method.invoke(target, *adapted) }.getOrNull()
            if (result != null) {
                return result
            }
        }
        return null
    }

    private fun adaptArguments(types: Array<Class<*>>, args: Array<out Any>): Array<Any?>? {
        val adapted = arrayOfNulls<Any>(args.size)
        for (index in args.indices) {
            val arg = args[index]
            val type = types[index]
            adapted[index] = when {
                type.isInstance(arg) -> arg
                arg is OfflinePlayer && type == String::class.java -> arg.name ?: return null
                arg is Player && type == String::class.java -> arg.name
                arg is Number && (type == Double::class.java || type == java.lang.Double.TYPE) -> arg.toDouble()
                else -> return null
            }
        }
        return adapted
    }

    private fun transactionSuccess(response: Any): Boolean {
        return when (response) {
            is Boolean -> response
            else -> invokeBoolean(response, "transactionSuccess")
        }
    }
}
