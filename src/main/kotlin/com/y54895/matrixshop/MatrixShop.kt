package com.y54895.matrixshop

import com.y54895.matrixshop.core.command.MatrixShopCommands
import com.y54895.matrixshop.core.config.ConfigFiles
import com.y54895.matrixshop.core.database.DatabaseManager
import com.y54895.matrixshop.core.database.LegacyDataMigrationService
import com.y54895.matrixshop.core.economy.EconomyModule
import com.y54895.matrixshop.core.metrics.BStatsMetrics
import com.y54895.matrixshop.core.module.ModuleRegistry
import com.y54895.matrixshop.core.record.RecordService
import com.y54895.matrixshop.core.text.ConsoleVisuals
import com.y54895.matrixshop.core.text.MatrixI18n
import com.y54895.matrixshop.core.text.Texts
import com.y54895.matrixshop.core.warehouse.CommerceWarehouseBridge
import taboolib.common.platform.Plugin
import taboolib.common.platform.function.info
import taboolib.common.platform.function.severe
import taboolib.common.platform.function.warning
import taboolib.platform.BukkitPlugin

object MatrixShop : Plugin() {

    override fun onLoad() {
        ConfigFiles.ensureDefaults()
        ConfigFiles.reload()
        MatrixI18n.reload()
        ConsoleVisuals.renderBoot()
    }

    override fun onEnable() {
        runCatching {
            ConfigFiles.ensureDefaults()
            ConfigFiles.reload()
            MatrixI18n.reload()
            RecordService.initialize()
            val schemaResult = DatabaseManager.syncSchema()
            LegacyDataMigrationService.migrateAll()
            CommerceWarehouseBridge.reload()
            ModuleRegistry.reload()
            runCatching { BStatsMetrics.initialize(BukkitPlugin.getInstance()) }
                .onFailure {
                    warning("Failed to initialize bStats metrics: ${it.message ?: it.javaClass.simpleName}")
                }
            info(Texts.tr("@console.logs.enabled", mapOf("modules" to ModuleRegistry.enabledSummary())))
            ConsoleVisuals.renderReady(
                backend = DatabaseManager.backendName(),
                economy = EconomyModule.providerSummary(),
                schemaMessage = schemaResult.message,
                modules = ModuleRegistry.enabledSummary(),
                warehouse = "${CommerceWarehouseBridge.providerName()} (${CommerceWarehouseBridge.requirementSummary()})"
            )
        }.onFailure {
            ConsoleVisuals.renderFailure(it.message ?: it.javaClass.simpleName)
            severe(Texts.tr("@console.logs.start-failed", mapOf("reason" to (it.message ?: it.javaClass.simpleName))))
            throw it
        }
    }

    override fun onActive() {
        MatrixShopCommands.register()
    }

    override fun onDisable() {
        ConsoleVisuals.renderShutdown(DatabaseManager.backendName())
    }

    fun reloadPlugin() {
        ConfigFiles.reload()
        MatrixI18n.reload()
        RecordService.initialize()
        DatabaseManager.syncSchema()
        LegacyDataMigrationService.migrateAll()
        CommerceWarehouseBridge.reload()
        ModuleRegistry.reload()
    }
}
