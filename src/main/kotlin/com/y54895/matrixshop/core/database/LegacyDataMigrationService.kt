package com.y54895.matrixshop.core.database

import com.y54895.matrixshop.core.config.ConfigFiles
import com.y54895.matrixshop.core.record.RecordService
import com.y54895.matrixshop.module.auction.AuctionDeliveryRepository
import com.y54895.matrixshop.module.auction.AuctionRepository
import com.y54895.matrixshop.module.cart.CartRepository
import com.y54895.matrixshop.module.chestshop.ChestShopRepository
import com.y54895.matrixshop.module.globalmarket.GlobalMarketRepository
import com.y54895.matrixshop.module.playershop.PlayerShopRepository
import org.bukkit.configuration.file.YamlConfiguration
import taboolib.common.platform.function.info
import java.io.File

data class LegacyImportResult(
    val moduleId: String,
    val state: String,
    val importedCount: Int,
    val detail: String
) {
    fun compact(): String {
        return if (importedCount > 0) {
            "$moduleId=$importedCount"
        } else {
            "$moduleId=$state"
        }
    }
}

data class LegacyMigrationSummary(
    val results: List<LegacyImportResult>,
    val message: String
) {
    val totalImported: Int
        get() = results.sumOf { it.importedCount }

    fun compactSummary(): String {
        return results.joinToString(", ") { it.compact() }
    }
}

object LegacyDataMigrationService {

    fun migrateAll(): LegacyMigrationSummary {
        if (!DatabaseManager.isJdbcAvailable()) {
            return LegacyMigrationSummary(
                results = emptyList(),
                message = "File backend active, legacy JDBC import skipped."
            )
        }
        val results = listOf(
            RecordService.migrateLegacyToJdbcIfNeeded(),
            AuctionRepository.migrateLegacyToJdbcIfNeeded(),
            AuctionDeliveryRepository.migrateLegacyToJdbcIfNeeded(),
            GlobalMarketRepository.migrateLegacyToJdbcIfNeeded(),
            PlayerShopRepository.migrateLegacyToJdbcIfNeeded(
                defaultUnlockedSlots = playerShopConfig().getInt("Unlock.Base", 21),
                maxUnlockedSlots = playerShopConfig().getInt("Unlock.Max", 100)
            ),
            CartRepository.migrateLegacyToJdbcIfNeeded(),
            ChestShopRepository.migrateLegacyToJdbcIfNeeded()
        )
        val summary = LegacyMigrationSummary(
            results = results,
            message = buildString {
                append("Legacy import check completed. imported=")
                append(results.sumOf { it.importedCount })
                if (results.isNotEmpty()) {
                    append(" (")
                    append(results.joinToString(", ") { it.compact() })
                    append(")")
                }
            }
        )
        DatabaseManager.setMetaValue("last_legacy_import", summary.compactSummary())
        DatabaseManager.setMetaValue("last_legacy_import_total", summary.totalImported.toString())
        DatabaseManager.setMetaValue("last_legacy_import_at", System.currentTimeMillis().toString())
        info(summary.message)
        return summary
    }

    private fun playerShopConfig(): YamlConfiguration {
        return YamlConfiguration.loadConfiguration(File(ConfigFiles.dataFolder(), "PlayerShop/settings.yml"))
    }
}
