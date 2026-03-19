package com.y54895.matrixshop.core.database

import com.y54895.matrixshop.core.config.ConfigFiles
import com.y54895.matrixshop.module.auction.AuctionDeliveryRepository
import com.y54895.matrixshop.module.auction.AuctionRepository
import com.y54895.matrixshop.module.cart.CartRepository
import com.y54895.matrixshop.module.chestshop.ChestShopRepository
import com.y54895.matrixshop.module.globalmarket.GlobalMarketRepository
import com.y54895.matrixshop.module.playershop.PlayerShopRepository
import org.bukkit.configuration.file.YamlConfiguration
import taboolib.common.platform.function.info
import java.io.File

object LegacyDataMigrationService {

    fun migrateAll() {
        if (!DatabaseManager.isJdbcAvailable()) {
            return
        }
        AuctionRepository.migrateLegacyToJdbcIfNeeded()
        AuctionDeliveryRepository.migrateLegacyToJdbcIfNeeded()
        GlobalMarketRepository.migrateLegacyToJdbcIfNeeded()
        PlayerShopRepository.migrateLegacyToJdbcIfNeeded(
            defaultUnlockedSlots = playerShopConfig().getInt("Unlock.Base", 21),
            maxUnlockedSlots = playerShopConfig().getInt("Unlock.Max", 100)
        )
        CartRepository.migrateLegacyToJdbcIfNeeded()
        ChestShopRepository.migrateLegacyToJdbcIfNeeded()
        info("MatrixShop legacy JDBC import check completed.")
    }

    private fun playerShopConfig(): YamlConfiguration {
        return YamlConfiguration.loadConfiguration(File(ConfigFiles.dataFolder(), "PlayerShop/settings.yml"))
    }
}
