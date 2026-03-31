package com.y54895.matrixshop.core.economy

import com.y54895.matrixshop.core.config.ConfigFiles
import com.y54895.matrixshop.core.module.MatrixModule
import com.y54895.matrixshop.core.text.Texts
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.command.CommandSender
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import taboolib.common.platform.function.warning
import java.io.File
import kotlin.math.ceil

object EconomyModule : MatrixModule {

    override val id: String = "economy"
    override val displayName: String = "Economy"

    private val currencies = LinkedHashMap<String, CurrencyDefinition>()

    override fun isEnabled(): Boolean {
        return true
    }

    override fun reload() {
        currencies.clear()
        VaultEconomyBridge.reload()
        PlayerPointsBridge.reload()
        val file = File(ConfigFiles.dataFolder(), "Economy/currency.yml")
        if (!file.exists()) {
            currencies["vault"] = CurrencyDefinition("vault", CurrencyMode.VAULT, "Vault", "", true)
            return
        }
        val yaml = YamlConfiguration.loadConfiguration(file)
        yaml.getKeys(false).forEach { key ->
            val section = yaml.getConfigurationSection(key) ?: return@forEach
            val definition = parseDefinition(key, section) ?: return@forEach
            currencies[definition.key.lowercase()] = definition
        }
        if (currencies.isEmpty()) {
            warning("MatrixShop economy config is empty. Fallback currency 'vault' will be used.")
            currencies["vault"] = CurrencyDefinition("vault", CurrencyMode.VAULT, "Vault", "", true)
        }
    }

    fun configuredKey(yaml: YamlConfiguration, path: String = "Currency", fallback: String = "vault"): String {
        return configuredKeyOrNull(yaml, path) ?: fallback
    }

    fun configuredKeyOrNull(yaml: YamlConfiguration, path: String = "Currency"): String? {
        return yaml.getString("$path.Key")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: yaml.getString("$path.Default")
                ?.trim()
                ?.takeIf(String::isNotBlank)
    }

    fun displayName(key: String?): String {
        val resolved = resolve(key)
        return definition(resolved)?.displayName ?: resolved
    }

    fun providerSummary(): String {
        return currencies.values.joinToString(", ") { definition ->
            "${definition.key}=${providerStatus(definition)}"
        }
    }

    fun formatAmount(key: String?, amount: Double): String {
        val definition = definition(resolve(key))
        return if (definition != null && !definition.decimal) {
            ceil(amount).toInt().toString()
        } else if (amount % 1.0 == 0.0) {
            amount.toInt().toString()
        } else {
            "%.2f".format(amount)
        }
    }

    fun balance(player: OfflinePlayer, key: String?): Double {
        val definition = definition(resolve(key)) ?: return 0.0
        return when (definition.mode) {
            CurrencyMode.VAULT -> VaultEconomyBridge.balance(player)
            CurrencyMode.PLAYERPOINTS -> PlayerPointsBridge.balance(player)
            CurrencyMode.PLACEHOLDER -> PlaceholderBridge.balance(player, definition.placeholder)
        }
    }

    fun has(player: OfflinePlayer, key: String?, amount: Double): Boolean {
        if (amount <= 0) {
            return true
        }
        return balance(player, key) >= normalizedAmount(key, amount)
    }

    fun isAvailable(key: String?): Boolean {
        val definition = definition(resolve(key)) ?: return false
        return when (definition.mode) {
            CurrencyMode.VAULT -> VaultEconomyBridge.isAvailable()
            CurrencyMode.PLAYERPOINTS -> PlayerPointsBridge.isAvailable()
            CurrencyMode.PLACEHOLDER -> definition.placeholder.isNotBlank()
        }
    }

    fun insufficientMessage(player: Player, key: String?, needAmount: Double, extra: Map<String, String> = emptyMap()): String {
        val resolved = resolve(key)
        val definition = definition(resolved)
        val balance = balance(player, resolved)
        val placeholders = buildPlaceholders(player, resolved, needAmount, balance, extra)
        if (definition != null && definition.denyActions.isNotEmpty()) {
            executeActions(player, definition.denyActions, placeholders)
            return ""
        }
        return Texts.tr(
            "@economy.errors.balance-not-enough",
            mapOf(
                "currency" to displayName(resolved),
                "need" to formatAmount(resolved, needAmount),
                "balance" to formatAmount(resolved, balance)
            ) + extra
        )
    }

    fun withdraw(player: OfflinePlayer, key: String?, amount: Double, extra: Map<String, String> = emptyMap()): Boolean {
        if (amount <= 0) {
            return true
        }
        val resolved = resolve(key)
        val definition = definition(resolved) ?: return false
        return when (definition.mode) {
            CurrencyMode.VAULT -> VaultEconomyBridge.withdraw(player, normalizedAmount(resolved, amount))
            CurrencyMode.PLAYERPOINTS -> PlayerPointsBridge.withdraw(player, normalizedAmount(resolved, amount))
            CurrencyMode.PLACEHOLDER -> {
                if (!has(player, resolved, amount)) {
                    false
                } else {
                    executeActions(player, definition.takeActions, buildPlaceholders(player, resolved, amount, balance(player, resolved), extra))
                    definition.takeActions.isNotEmpty()
                }
            }
        }
    }

    fun deposit(player: OfflinePlayer, key: String?, amount: Double, extra: Map<String, String> = emptyMap()): Boolean {
        if (amount <= 0) {
            return true
        }
        val resolved = resolve(key)
        val definition = definition(resolved) ?: return false
        return when (definition.mode) {
            CurrencyMode.VAULT -> VaultEconomyBridge.deposit(player, normalizedAmount(resolved, amount))
            CurrencyMode.PLAYERPOINTS -> PlayerPointsBridge.deposit(player, normalizedAmount(resolved, amount))
            CurrencyMode.PLACEHOLDER -> {
                executeActions(player, definition.giveActions, buildPlaceholders(player, resolved, amount, balance(player, resolved), extra))
                definition.giveActions.isNotEmpty()
            }
        }
    }

    private fun resolve(key: String?): String {
        val normalized = key?.trim()?.takeIf(String::isNotBlank)?.lowercase()
        return when {
            normalized == null -> "vault"
            currencies.containsKey(normalized) -> normalized
            else -> "vault"
        }
    }

    private fun definition(key: String): CurrencyDefinition? {
        return currencies[key.lowercase()]
    }

    private fun normalizedAmount(key: String?, amount: Double): Double {
        val resolved = resolve(key)
        val definition = definition(resolved)
        return if (definition != null && !definition.decimal) {
            ceil(amount)
        } else {
            amount
        }
    }

    private fun providerStatus(definition: CurrencyDefinition): String {
        return when (definition.mode) {
            CurrencyMode.VAULT -> VaultEconomyBridge.providerName()
            CurrencyMode.PLAYERPOINTS -> PlayerPointsBridge.providerName()
            CurrencyMode.PLACEHOLDER -> if (definition.placeholder.isNotBlank()) "placeholder" else "invalid"
        }
    }

    private fun parseDefinition(key: String, section: ConfigurationSection): CurrencyDefinition? {
        val rawMode = section.getString("Mode")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return null
        val mode = when {
            rawMode.equals("vault", true) -> CurrencyMode.VAULT
            rawMode.equals("playerpoints", true) -> CurrencyMode.PLAYERPOINTS
            else -> CurrencyMode.PLACEHOLDER
        }
        val placeholder = if (mode == CurrencyMode.PLACEHOLDER) {
            section.getString("Placeholder")
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: rawMode
        } else {
            ""
        }
        return CurrencyDefinition(
            key = key.lowercase(),
            mode = mode,
            displayName = section.getString("Display-Name", key).orEmpty(),
            symbol = section.getString("Symbol", "").orEmpty(),
            decimal = section.getBoolean("Decimal", mode != CurrencyMode.PLAYERPOINTS),
            placeholder = placeholder,
            takeActions = loadActions(section, "Take"),
            giveActions = loadActions(section, "Give"),
            denyActions = loadActions(section, "Deny")
        )
    }

    private fun loadActions(section: ConfigurationSection, key: String): List<String> {
        val directList = section.getStringList(key)
            .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
        if (directList.isNotEmpty()) {
            return directList
        }
        val directSingle = section.getString(key)?.trim()?.takeIf(String::isNotBlank)
        if (directSingle != null) {
            return listOf(directSingle)
        }
        val nestedPath = "Actions.$key"
        val nestedList = section.getStringList(nestedPath)
            .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
        if (nestedList.isNotEmpty()) {
            return nestedList
        }
        return section.getString(nestedPath)?.trim()?.takeIf(String::isNotBlank)?.let(::listOf) ?: emptyList()
    }

    private fun buildPlaceholders(
        player: OfflinePlayer,
        key: String,
        amount: Double,
        balance: Double,
        extra: Map<String, String>
    ): Map<String, String> {
        return linkedMapOf(
            "player" to (player.name ?: ""),
            "sender" to (player.name ?: ""),
            "currency" to displayName(key),
            "money" to formatAmount(key, amount),
            "amount" to formatAmount(key, amount),
            "need" to formatAmount(key, amount),
            "need-money" to formatAmount(key, amount),
            "balance" to formatAmount(key, balance)
        ) + extra
    }

    private fun executeActions(player: OfflinePlayer, actions: List<String>, placeholders: Map<String, String>) {
        actions.forEach { raw ->
            val action = Texts.apply(raw, placeholders).trim()
            when {
                action.isBlank() -> Unit
                action.startsWith("tell:", true) -> onlinePlayer(player)?.sendMessage(Texts.color(action.substringAfter(':').trim()))
                action.startsWith("player:", true) -> onlinePlayer(player)?.performCommand(action.substringAfter(':').trim().removePrefix("/"))
                action.startsWith("console:", true) -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), action.substringAfter(':').trim().removePrefix("/"))
                action.startsWith("command ", true) -> executeLegacyCommand(player, action)
                action.startsWith("tell ", true) -> executeLegacyTell(player, action)
                else -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), action.removePrefix("/"))
            }
        }
    }

    private fun executeLegacyCommand(player: OfflinePlayer, action: String) {
        val normalized = action.removePrefix("command").trim()
        val inline = normalized.removePrefix("inline").trim()
        if (inline.endsWith(" as console", true)) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), inline.removeSuffix(" as console").trim().removePrefix("/"))
        } else {
            onlinePlayer(player)?.performCommand(inline.removePrefix("/"))
        }
    }

    private fun executeLegacyTell(player: OfflinePlayer, action: String) {
        val normalized = action.removePrefix("tell").trim()
            .removePrefix("color").trim()
            .removePrefix("inline").trim()
        onlinePlayer(player)?.sendMessage(Texts.color(normalized))
    }

    private fun onlinePlayer(player: OfflinePlayer): Player? {
        return if (player is Player && player.isOnline) player else player.player
    }
}

private data class CurrencyDefinition(
    val key: String,
    val mode: CurrencyMode,
    val displayName: String,
    val symbol: String,
    val decimal: Boolean,
    val placeholder: String = "",
    val takeActions: List<String> = emptyList(),
    val giveActions: List<String> = emptyList(),
    val denyActions: List<String> = emptyList()
)

private enum class CurrencyMode {
    VAULT,
    PLAYERPOINTS,
    PLACEHOLDER
}

private object PlaceholderBridge {

    private var apiClass: Class<*>? = null

    private fun ensureClass(): Class<*>? {
        if (apiClass != null) {
            return apiClass
        }
        apiClass = runCatching { Class.forName("me.clip.placeholderapi.PlaceholderAPI") }.getOrNull()
        return apiClass
    }

    fun balance(player: OfflinePlayer, placeholder: String): Double {
        val online = if (player is Player && player.isOnline) player else player.player ?: return 0.0
        val clazz = ensureClass() ?: return 0.0
        val method = clazz.methods.firstOrNull {
            it.name == "setPlaceholders" &&
                it.parameterTypes.size == 2 &&
                Player::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                it.parameterTypes[1] == String::class.java
        } ?: return 0.0
        val raw = runCatching { method.invoke(null, online, placeholder) as? String }.getOrNull() ?: return 0.0
        return raw.toDoubleOrNull()
            ?: raw.replace(",", "").toDoubleOrNull()
            ?: Regex("""-?\d+(?:\.\d+)?""").find(raw)?.value?.toDoubleOrNull()
            ?: 0.0
    }
}

private object PlayerPointsBridge {

    private var api: Any? = null

    fun reload() {
        api = null
        val plugin = Bukkit.getPluginManager().getPlugin("PlayerPoints") ?: return
        api = runCatching { plugin.javaClass.getMethod("getAPI").invoke(plugin) }.getOrNull()
    }

    fun providerName(): String {
        return if (api != null) "PlayerPoints" else "none"
    }

    fun isAvailable(): Boolean {
        return api != null
    }

    fun balance(player: OfflinePlayer): Double {
        val provider = api ?: return 0.0
        return invokeNumber(provider, "look", player, player.uniqueId)?.toDouble() ?: 0.0
    }

    fun withdraw(player: OfflinePlayer, amount: Double): Boolean {
        val provider = api ?: return false
        return invokeBoolean(provider, "take", player, ceil(amount).toInt())
    }

    fun deposit(player: OfflinePlayer, amount: Double): Boolean {
        val provider = api ?: return false
        return invokeBoolean(provider, "give", player, ceil(amount).toInt())
    }

    private fun invokeNumber(target: Any, methodName: String, player: OfflinePlayer, uuid: java.util.UUID): Number? {
        val methods = target.javaClass.methods.filter { it.name == methodName && it.parameterTypes.size == 1 }
        methods.forEach { method ->
            val parameter = when {
                method.parameterTypes[0].isInstance(uuid) -> uuid
                method.parameterTypes[0] == String::class.java -> player.name ?: return@forEach
                else -> return@forEach
            }
            val result = runCatching { method.invoke(target, parameter) }.getOrNull()
            if (result is Number) {
                return result
            }
        }
        return null
    }

    private fun invokeBoolean(target: Any, methodName: String, player: OfflinePlayer, amount: Int): Boolean {
        val methods = target.javaClass.methods.filter { it.name == methodName && it.parameterTypes.size == 2 }
        methods.forEach { method ->
            val first = when {
                method.parameterTypes[0].isInstance(player.uniqueId) -> player.uniqueId
                method.parameterTypes[0] == String::class.java -> player.name ?: return@forEach
                else -> return@forEach
            }
            val second = when (method.parameterTypes[1]) {
                Int::class.java, Integer.TYPE -> amount
                Double::class.java, java.lang.Double.TYPE -> amount.toDouble()
                else -> return@forEach
            }
            val result = runCatching { method.invoke(target, first, second) }.getOrNull()
            if (result is Boolean) {
                return result
            }
            if (result is Number) {
                return result.toInt() >= 0
            }
        }
        return false
    }
}
