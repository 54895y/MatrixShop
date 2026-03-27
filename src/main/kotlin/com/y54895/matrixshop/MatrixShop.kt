package com.y54895.matrixshop

import com.y54895.matrixshop.core.command.MatrixShopCommands
import com.y54895.matrixshop.core.config.ConfigFiles
import com.y54895.matrixshop.core.database.DatabaseManager
import com.y54895.matrixshop.core.database.LegacyDataMigrationService
import com.y54895.matrixshop.core.economy.VaultEconomyBridge
import com.y54895.matrixshop.core.module.ModuleRegistry
import com.y54895.matrixshop.core.record.RecordService
import com.y54895.matrixshop.core.text.ConsoleVisuals
import com.y54895.matrixshop.core.text.MatrixI18n
import taboolib.common.platform.Plugin
import taboolib.common.platform.function.info
import taboolib.common.platform.function.severe

object MatrixShop : Plugin() {

    override fun onLoad() {
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
            VaultEconomyBridge.reload()
            ModuleRegistry.reload()
            MatrixShopCommands.register()
            info("MatrixShop enabled. Modules=${ModuleRegistry.enabledSummary()}")
            ConsoleVisuals.renderReady(
                backend = DatabaseManager.backendName(),
                schemaMessage = schemaResult.message,
                modules = ModuleRegistry.enabledSummary()
            )
        }.onFailure {
            ConsoleVisuals.renderFailure(it.message ?: it.javaClass.simpleName)
            severe("MatrixShop failed to start: ${it.message}")
            throw it
        }
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
        VaultEconomyBridge.reload()
        ModuleRegistry.reload()
    }
}
