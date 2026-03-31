package com.y54895.matrixshop.core.metrics

import com.y54895.matrixshop.core.database.DatabaseManager
import com.y54895.matrixshop.core.economy.EconomyModule
import com.y54895.matrixshop.core.module.ModuleRegistry
import org.bstats.bukkit.Metrics
import org.bstats.charts.AdvancedPie
import org.bstats.charts.SimplePie
import org.bstats.charts.SingleLineChart
import org.bukkit.plugin.Plugin

object BStatsMetrics {

    private const val PLUGIN_ID = 30502

    fun initialize(plugin: Plugin): Metrics {
        return Metrics(plugin, PLUGIN_ID).apply {
            addCustomChart(SimplePie("database_backend") {
                DatabaseManager.backendName()
            })
            addCustomChart(SimplePie("configured_database_backend") {
                DatabaseManager.configuredBackendName()
            })
            addCustomChart(SingleLineChart("enabled_module_count") {
                ModuleRegistry.all().count { it.isEnabled() }
            })
            addCustomChart(AdvancedPie("enabled_modules") {
                ModuleRegistry.all()
                    .filter { it.isEnabled() }
                    .associate { it.id to 1 }
            })
            addCustomChart(SingleLineChart("systemshop_category_count") {
                ModuleRegistry.systemShop.categoryIds().size
            })
            addCustomChart(SingleLineChart("systemshop_goods_count") {
                ModuleRegistry.systemShop.goodsIds().size
            })
            addCustomChart(SingleLineChart("economy_currency_count") {
                EconomyModule.configuredCurrencyCount()
            })
            addCustomChart(AdvancedPie("economy_currency_modes") {
                EconomyModule.currencyModeDistribution()
            })
        }
    }
}
