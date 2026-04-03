package com.y54895.matrixshop.core.economy

import com.y54895.matrixlib.api.economy.MatrixEconomy
import com.y54895.matrixshop.core.module.MatrixModule
import com.y54895.matrixshop.core.text.Texts
import org.bukkit.OfflinePlayer
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player

object EconomyModule : MatrixModule {

    override val id: String = "economy"
    override val displayName: String = "Economy"

    override fun isEnabled(): Boolean = true

    override fun reload() {
        MatrixEconomy.reload()
    }

    fun configuredKey(yaml: YamlConfiguration, path: String = "Currency", fallback: String = "vault"): String {
        return MatrixEconomy.configuredKey(yaml, path, fallback)
    }

    fun configuredKey(section: ConfigurationSection, path: String = "Currency", fallback: String = "vault"): String {
        return MatrixEconomy.configuredKey(section, path, fallback)
    }

    fun configuredKeyOrNull(yaml: YamlConfiguration, path: String = "Currency"): String? {
        return MatrixEconomy.configuredKeyOrNull(yaml, path)
    }

    fun displayName(key: String?): String = MatrixEconomy.displayName(key)

    fun providerSummary(): String = MatrixEconomy.providerSummary()

    fun configuredCurrencyCount(): Int = MatrixEconomy.configuredCurrencyCount()

    fun currencyModeDistribution(): Map<String, Int> = MatrixEconomy.currencyModeDistribution()

    fun formatAmount(key: String?, amount: Double): String = MatrixEconomy.formatAmount(key, amount)

    fun balance(player: OfflinePlayer, key: String?): Double = MatrixEconomy.balance(player, key)

    fun has(player: OfflinePlayer, key: String?, amount: Double): Boolean = MatrixEconomy.has(player, key, amount)

    fun isAvailable(key: String?): Boolean = MatrixEconomy.isAvailable(key)

    fun insufficientMessage(player: Player, key: String?, needAmount: Double, extra: Map<String, String> = emptyMap()): String {
        val shortage = MatrixEconomy.shortage(player, key, needAmount, extra)
        if (shortage.denyHandled) {
            return ""
        }
        return Texts.tr(
            "@economy.errors.balance-not-enough",
            mapOf(
                "currency" to shortage.displayName,
                "need" to shortage.formattedNeed,
                "balance" to shortage.formattedBalance
            ) + extra
        )
    }

    fun withdraw(player: OfflinePlayer, key: String?, amount: Double, extra: Map<String, String> = emptyMap()): Boolean {
        return MatrixEconomy.withdraw(player, key, amount, extra)
    }

    fun deposit(player: OfflinePlayer, key: String?, amount: Double, extra: Map<String, String> = emptyMap()): Boolean {
        return MatrixEconomy.deposit(player, key, amount, extra)
    }
}
