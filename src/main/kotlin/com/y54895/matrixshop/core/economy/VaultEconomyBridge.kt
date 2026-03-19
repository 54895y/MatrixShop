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
        val provider = economy ?: return false
        val response = runCatching {
            provider.javaClass.methods.firstOrNull {
                it.name == "withdrawPlayer" && it.parameterTypes.size == 2
            }?.invoke(provider, player, amount)
        }.getOrNull() ?: return false
        return invokeBoolean(response, "transactionSuccess")
    }

    private fun invokeDouble(target: Any, methodName: String, vararg args: Any): Double {
        val method = target.javaClass.methods.firstOrNull {
            it.name == methodName && it.parameterTypes.size == args.size
        } ?: return 0.0
        return (method.invoke(target, *args) as? Number)?.toDouble() ?: 0.0
    }

    private fun invokeBoolean(target: Any, methodName: String, vararg args: Any): Boolean {
        val method = target.javaClass.methods.firstOrNull {
            it.name == methodName && it.parameterTypes.size == args.size
        } ?: return false
        return method.invoke(target, *args) as? Boolean ?: false
    }
}
