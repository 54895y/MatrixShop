package com.y54895.matrixshop.module.auction

import com.y54895.matrixshop.core.config.ConfigFiles
import com.y54895.matrixshop.core.database.DatabaseManager
import com.y54895.matrixshop.core.database.ItemStackCodec
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID

object AuctionDeliveryRepository {

    private val file: File
        get() = File(ConfigFiles.dataFolder(), "Data/auction/deliveries.yml")

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

    fun loadAll(): MutableList<AuctionDeliveryEntry> {
        initialize()
        return if (DatabaseManager.isJdbcAvailable()) loadAllJdbc() else loadAllFile()
    }

    fun saveAll(entries: List<AuctionDeliveryEntry>) {
        initialize()
        if (DatabaseManager.isJdbcAvailable()) {
            saveAllJdbc(entries)
        } else {
            saveAllFile(entries)
        }
    }

    private fun loadAllJdbc(): MutableList<AuctionDeliveryEntry> {
        DatabaseManager.withConnection { connection ->
            connection.prepareStatement(
                """
                SELECT id, owner_id, owner_name, money, item_blob, message, created_at
                FROM auction_deliveries
                ORDER BY created_at ASC
                """.trimIndent()
            ).use { statement ->
                statement.executeQuery().use { result ->
                    val entries = mutableListOf<AuctionDeliveryEntry>()
                    while (result.next()) {
                        entries += AuctionDeliveryEntry(
                            id = result.getString("id"),
                            ownerId = UUID.fromString(result.getString("owner_id")),
                            ownerName = result.getString("owner_name"),
                            money = result.getDouble("money"),
                            item = ItemStackCodec.decode(result.getString("item_blob")),
                            message = result.getString("message"),
                            createdAt = result.getLong("created_at")
                        )
                    }
                    entries
                }
            }
        }?.let { return it }
        return loadAllFile()
    }

    private fun saveAllJdbc(entries: List<AuctionDeliveryEntry>) {
        val success = DatabaseManager.withConnection { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                connection.prepareStatement("DELETE FROM auction_deliveries").use { it.executeUpdate() }
                connection.prepareStatement(
                    """
                    INSERT INTO auction_deliveries (
                        id, owner_id, owner_name, money, item_blob, message, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { insert ->
                    entries.sortedBy { it.createdAt }.forEach { entry ->
                        insert.setString(1, entry.id)
                        insert.setString(2, entry.ownerId.toString())
                        insert.setString(3, entry.ownerName)
                        insert.setDouble(4, entry.money)
                        insert.setString(5, ItemStackCodec.encode(entry.item))
                        insert.setString(6, entry.message)
                        insert.setLong(7, entry.createdAt)
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
            saveAllFile(entries)
        }
    }

    private fun initializeJdbc() {
        DatabaseManager.withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS auction_deliveries (
                        id VARCHAR(64) PRIMARY KEY,
                        owner_id VARCHAR(64) NOT NULL,
                        owner_name VARCHAR(64) NOT NULL,
                        money DOUBLE NOT NULL,
                        item_blob TEXT NOT NULL,
                        message TEXT NOT NULL,
                        created_at BIGINT NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
    }

    private fun migrateFileToJdbcIfNeeded() {
        val count = DatabaseManager.withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM auction_deliveries").use { result ->
                    if (result.next()) result.getInt(1) else 0
                }
            }
        } ?: return
        if (count > 0) {
            return
        }
        val entries = loadAllFile()
        if (entries.isNotEmpty()) {
            saveAllJdbc(entries)
        }
    }

    private fun loadAllFile(): MutableList<AuctionDeliveryEntry> {
        val yaml = YamlConfiguration.loadConfiguration(file)
        val result = mutableListOf<AuctionDeliveryEntry>()
        val section = yaml.getConfigurationSection("entries")
        section?.getKeys(false)?.forEach { id ->
            val child = section.getConfigurationSection(id) ?: return@forEach
            result += AuctionDeliveryEntry(
                id = id,
                ownerId = UUID.fromString(child.getString("owner-id").orEmpty()),
                ownerName = child.getString("owner-name").orEmpty(),
                money = child.getDouble("money"),
                item = child.getItemStack("item"),
                message = child.getString("message").orEmpty(),
                createdAt = child.getLong("created-at")
            )
        }
        return result
    }

    private fun saveAllFile(entries: List<AuctionDeliveryEntry>) {
        val yaml = YamlConfiguration()
        entries.sortedBy { it.createdAt }.forEach { entry ->
            val base = "entries.${entry.id}"
            yaml.set("$base.owner-id", entry.ownerId.toString())
            yaml.set("$base.owner-name", entry.ownerName)
            yaml.set("$base.money", entry.money)
            yaml.set("$base.item", entry.item)
            yaml.set("$base.message", entry.message)
            yaml.set("$base.created-at", entry.createdAt)
        }
        yaml.save(file)
    }
}
