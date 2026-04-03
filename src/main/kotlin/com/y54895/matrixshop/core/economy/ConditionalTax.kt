package com.y54895.matrixshop.core.economy

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player
import taboolib.common.platform.function.warning
import taboolib.module.kether.KetherShell
import taboolib.module.kether.ScriptOptions
import java.util.Locale
import java.util.concurrent.TimeUnit

data class ConditionalTaxRule(
    val id: String,
    val enabled: Boolean,
    val priority: Int,
    val mode: String?,
    val value: Double?,
    val condition: List<String>
)

data class ConditionalTaxConfig(
    val enabled: Boolean,
    val mode: String,
    val value: Double,
    val rules: List<ConditionalTaxRule> = emptyList()
)

data class ConditionalTaxResult(
    val amount: Double,
    val mode: String,
    val value: Double,
    val ruleId: String?
)

object ConditionalTaxEngine {

    fun parse(
        root: ConfigurationSection,
        path: String,
        defaultEnabled: Boolean,
        defaultMode: String,
        defaultValue: Double,
        legacyPercentPath: String? = null
    ): ConditionalTaxConfig {
        val section = root.getConfigurationSection(path)
        if (section == null) {
            if (legacyPercentPath != null && root.contains(legacyPercentPath)) {
                val value = root.getDouble(legacyPercentPath, defaultValue).coerceAtLeast(0.0)
                return ConditionalTaxConfig(
                    enabled = value > 0.0,
                    mode = "percent",
                    value = value
                )
            }
            return ConditionalTaxConfig(
                enabled = defaultEnabled,
                mode = defaultMode,
                value = defaultValue.coerceAtLeast(0.0)
            )
        }
        val rulesSection = section.getConfigurationSection("Rules")
        val rules = rulesSection?.getKeys(false)?.map { id ->
            val ruleSection = rulesSection.getConfigurationSection(id)!!
            ConditionalTaxRule(
                id = id,
                enabled = ruleSection.getBoolean("Enabled", true),
                priority = ruleSection.getInt("Priority", 0),
                mode = ruleSection.getString("Mode")
                    ?.trim()
                    ?.takeIf(String::isNotBlank),
                value = ruleSection.get("Value")?.let { raw ->
                    when (raw) {
                        is Number -> raw.toDouble()
                        is String -> raw.trim().toDoubleOrNull()
                        else -> null
                    }
                }?.coerceAtLeast(0.0),
                condition = readStringList(ruleSection, "Condition", "Conditions", "condition", "conditions")
            )
        }.orEmpty()
        return ConditionalTaxConfig(
            enabled = section.getBoolean("Enabled", defaultEnabled),
            mode = section.getString("Mode", defaultMode).orEmpty().ifBlank { defaultMode },
            value = section.getDouble("Value", defaultValue).coerceAtLeast(0.0),
            rules = rules
        )
    }

    fun compute(
        config: ConditionalTaxConfig,
        amount: Double,
        actor: Player?,
        moduleId: String,
        context: Map<String, Any?> = emptyMap()
    ): ConditionalTaxResult {
        val normalizedAmount = amount.coerceAtLeast(0.0)
        if (normalizedAmount <= 0.0 || !config.enabled) {
            return ConditionalTaxResult(0.0, config.mode, config.value, null)
        }
        val matchedRule = config.rules
            .sortedWith(compareByDescending<ConditionalTaxRule> { it.priority }.thenBy { it.id })
            .firstOrNull { rule ->
                rule.enabled && matches(actor, moduleId, rule, context)
            }
        val mode = matchedRule?.mode ?: config.mode
        val value = matchedRule?.value ?: config.value
        val taxAmount = when (mode.lowercase(Locale.ROOT)) {
            "percent", "rate" -> normalizedAmount * value / 100.0
            "fixed", "flat" -> value
            else -> value
        }.coerceAtLeast(0.0).coerceAtMost(normalizedAmount)
        return ConditionalTaxResult(
            amount = taxAmount,
            mode = mode,
            value = value,
            ruleId = matchedRule?.id
        )
    }

    private fun matches(
        actor: Player?,
        moduleId: String,
        rule: ConditionalTaxRule,
        context: Map<String, Any?>
    ): Boolean {
        if (rule.condition.isEmpty()) {
            return true
        }
        if (actor == null) {
            return false
        }
        return runCatching {
            val builder = ScriptOptions.builder()
                .sender(actor)
                .set("player", actor)
                .set("actor", actor)
                .detailError(true)
            context.forEach { (key, value) ->
                if (value != null) {
                    builder.set(key, value)
                }
            }
            val result = KetherShell.eval(rule.condition, builder.build()).get(3, TimeUnit.SECONDS)
            when (result) {
                is Boolean -> result
                is Number -> result.toInt() != 0
                else -> result?.toString()?.equals("true", true) == true
            }
        }.getOrElse {
            warning("MatrixShop tax rule [$moduleId:${rule.id}] failed to execute: ${it.message ?: it.javaClass.simpleName}")
            false
        }
    }

    private fun readStringList(section: ConfigurationSection, vararg paths: String): List<String> {
        paths.forEach { path ->
            if (section.isList(path)) {
                return section.getStringList(path).mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
            }
            val single = section.getString(path)?.trim()?.takeIf(String::isNotBlank)
            if (single != null) {
                return listOf(single)
            }
        }
        return emptyList()
    }
}
