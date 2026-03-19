package com.y54895.matrixshop.module.globalmarket

import com.y54895.matrixshop.core.config.ConfigFiles
import com.y54895.matrixshop.core.database.DatabaseManager
import com.y54895.matrixshop.core.database.ItemStackCodec
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
        if (DatabaseManager.isJdbcAvailable()) {
            initializeJdbc()
            migrateFileToJdbcIfNeeded()
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
                SELECT id, owner_id, owner_name, price, currency, item_blob, created_at, expire_at
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
                        id, owner_id, owner_name, price, currency, item_blob, created_at, expire_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { insert ->
                    listings.sortedBy { it.createdAt }.forEach { listing ->
                        insert.setString(1, listing.id)
                        insert.setString(2, listing.ownerId.toString())
                        insert.setString(3, listing.ownerName)
                        insert.setDouble(4, listing.price)
                        insert.setString(5, listing.currency)
                        insert.setString(6, ItemStackCodec.encode(listing.item))
                        insert.setLong(7, listing.createdAt)
                        insert.setLong(8, listing.expireAt)
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

    private fun initializeJdbc() {
        DatabaseManager.withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS global_market_listings (
                        id VARCHAR(64) PRIMARY KEY,
                        owner_id VARCHAR(64) NOT NULL,
                        owner_name VARCHAR(64) NOT NULL,
                        price DOUBLE NOT NULL,
                        currency VARCHAR(32) NOT NULL,
                        item_blob TEXT NOT NULL,
                        created_at BIGINT NOT NULL,
                        expire_at BIGINT NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
    }

    private fun migrateFileToJdbcIfNeeded() {
        val count = DatabaseManager.withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM global_market_listings").use { result ->
                    if (result.next()) result.getInt(1) else 0
                }
            }
        } ?: return
        if (count > 0) {
            return
        }
        val fileListings = loadAllFile()
        if (fileListings.isNotEmpty()) {
            saveAllJdbc(fileListings)
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
