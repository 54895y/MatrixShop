package com.y54895.matrixshop.module.globalmarket

import com.y54895.matrixshop.core.config.ConfigFiles
import com.y54895.matrixshop.core.database.DatabaseManager
import com.y54895.matrixshop.core.database.ItemStackCodec
import com.y54895.matrixshop.core.database.LegacyImportResult
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID

object GlobalMarketRepository {

    private val file: File
        get() = File(ConfigFiles.dataFolder(), "Data/global-market/listings.yml")

    fun initialize() {
        file.parentFile.mkdirs()
        if (!file.exists()) {
            file.createNewFile()
        }
    }

    fun migrateLegacyToJdbcIfNeeded(): LegacyImportResult {
        initialize()
        if (!DatabaseManager.isJdbcAvailable()) {
            return LegacyImportResult("global-market", "file-backend", 0, "JDBC backend unavailable.")
        }
        val count = DatabaseManager.withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM global_market_listings").use { result ->
                    if (result.next()) result.getInt(1) else 0
                }
            }
        } ?: return LegacyImportResult("global-market", "failed", 0, "Unable to inspect global_market_listings.")
        if (count > 0) {
            return LegacyImportResult("global-market", "already-present", 0, "global_market_listings already contains $count rows.")
        }
        val fileListings = loadAllFile()
        if (fileListings.isEmpty()) {
            return LegacyImportResult("global-market", "no-source", 0, "No legacy global market listings found.")
        }
        saveAllJdbc(fileListings)
        val imported = DatabaseManager.withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM global_market_listings").use { result ->
                    if (result.next()) result.getInt(1) else 0
                }
            }
        } ?: 0
        return if (imported > 0) {
            LegacyImportResult("global-market", "imported", imported, "Imported $imported global market listings.")
        } else {
            LegacyImportResult("global-market", "failed", 0, "Legacy global market import did not write any rows.")
        }
    }

    fun loadAll(): MutableList<GlobalMarketListing> {
        initialize()
        return if (DatabaseManager.isJdbcAvailable()) loadAllJdbc() else loadAllFile()
    }

    fun saveAll(listings: List<GlobalMarketListing>) {
        initialize()
        if (DatabaseManager.isJdbcAvailable()) {
            saveAllJdbc(listings)
        } else {
            saveAllFile(listings)
        }
    }

    private fun loadAllJdbc(): MutableList<GlobalMarketListing> {
        val now = System.currentTimeMillis()
        DatabaseManager.withConnection { connection ->
            connection.prepareStatement("DELETE FROM global_market_listings WHERE expire_at > 0 AND expire_at <= ?").use { delete ->
                delete.setLong(1, now)
                delete.executeUpdate()
            }
            connection.prepareStatement(
                """
                SELECT id, shop_id, owner_id, owner_name, price, currency, item_blob, created_at, expire_at
                FROM global_market_listings
                ORDER BY created_at DESC
                """.trimIndent()
            ).use { statement ->
                statement.executeQuery().use { result ->
                    val listings = mutableListOf<GlobalMarketListing>()
                    while (result.next()) {
                        val item = ItemStackCodec.decode(result.getString("item_blob")) ?: continue
                        listings += GlobalMarketListing(
                            id = result.getString("id"),
                            shopId = result.getString("shop_id").orEmpty().ifBlank { "default" },
                            ownerId = UUID.fromString(result.getString("owner_id")),
                            ownerName = result.getString("owner_name"),
                            price = result.getDouble("price"),
                            currency = result.getString("currency"),
                            item = item,
                            createdAt = result.getLong("created_at"),
                            expireAt = result.getLong("expire_at")
                        )
                    }
                    listings
                }
            }
        }?.let { return it }
        return loadAllFile()
    }

    private fun saveAllJdbc(listings: List<GlobalMarketListing>) {
        val success = DatabaseManager.withConnection { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                connection.prepareStatement("DELETE FROM global_market_listings").use { delete ->
                    delete.executeUpdate()
                }
                connection.prepareStatement(
                    """
                    INSERT INTO global_market_listings (
                        id, shop_id, owner_id, owner_name, price, currency, item_blob, created_at, expire_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { insert ->
                    listings.sortedBy { it.createdAt }.forEach { listing ->
                        insert.setString(1, listing.id)
                        insert.setString(2, listing.shopId)
                        insert.setString(3, listing.ownerId.toString())
                        insert.setString(4, listing.ownerName)
                        insert.setDouble(5, listing.price)
                        insert.setString(6, listing.currency)
                        insert.setString(7, ItemStackCodec.encode(listing.item))
                        insert.setLong(8, listing.createdAt)
                        insert.setLong(9, listing.expireAt)
                        insert.addBatch()
                    }
                    insert.executeBatch()
                }
                connection.commit()
                connection.autoCommit = previousAutoCommit
                true
            } catch (ex: Exception) {
                runCatching { connection.rollback() }
                connection.autoCommit = previousAutoCommit
                false
            }
        } ?: false
        if (!success) {
            saveAllFile(listings)
        }
    }

    private fun loadAllFile(): MutableList<GlobalMarketListing> {
        val yaml = YamlConfiguration.loadConfiguration(file)
        val now = System.currentTimeMillis()
        val result = mutableListOf<GlobalMarketListing>()
        val section = yaml.getConfigurationSection("listings")
        section?.getKeys(false)?.forEach { id ->
            val child = section.getConfigurationSection(id) ?: return@forEach
            val item = child.getItemStack("item") ?: return@forEach
            val expireAt = child.getLong("expire-at", 0L)
            if (expireAt > 0L && expireAt <= now) {
                return@forEach
            }
            result += GlobalMarketListing(
                id = id,
                shopId = child.getString("shop-id", "default").orEmpty().ifBlank { "default" },
                ownerId = UUID.fromString(child.getString("owner-id").orEmpty()),
                ownerName = child.getString("owner-name").orEmpty(),
                price = child.getDouble("price"),
                currency = child.getString("currency", "vault").orEmpty(),
                item = item,
                createdAt = child.getLong("created-at", now),
                expireAt = expireAt
            )
        }
        if (section != null && result.size != section.getKeys(false).size) {
            saveAllFile(result)
        }
        return result
    }

    private fun saveAllFile(listings: List<GlobalMarketListing>) {
        val yaml = YamlConfiguration()
        listings.sortedBy { it.createdAt }.forEach { listing ->
            val base = "listings.${listing.id}"
            yaml.set("$base.shop-id", listing.shopId)
            yaml.set("$base.owner-id", listing.ownerId.toString())
            yaml.set("$base.owner-name", listing.ownerName)
            yaml.set("$base.price", listing.price)
            yaml.set("$base.currency", listing.currency)
            yaml.set("$base.item", listing.item)
            yaml.set("$base.created-at", listing.createdAt)
            yaml.set("$base.expire-at", listing.expireAt)
        }
        yaml.save(file)
    }
}
