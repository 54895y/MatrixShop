package com.y54895.matrixshop.module.playershop

import com.y54895.matrixshop.core.config.ConfigFiles
import com.y54895.matrixshop.core.database.DatabaseManager
import com.y54895.matrixshop.core.database.ItemStackCodec
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID

object PlayerShopRepository {

    private val folder: File
        get() = File(ConfigFiles.dataFolder(), "Data/player-shops")

    fun initialize() {
        folder.mkdirs()
    }

    fun migrateLegacyToJdbcIfNeeded(defaultUnlockedSlots: Int, maxUnlockedSlots: Int) {
        initialize()
        if (!DatabaseManager.isJdbcAvailable()) {
            return
        }
        val count = DatabaseManager.withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM player_shop_stores").use { result ->
                    if (result.next()) result.getInt(1) else 0
                }
            }
        } ?: return
        if (count > 0) {
            return
        }
        folder.listFiles { file -> file.isFile && file.extension.equals("yml", true) }
            ?.forEach { file ->
                val ownerId = runCatching { UUID.fromString(file.nameWithoutExtension) }.getOrNull() ?: return@forEach
                val yaml = YamlConfiguration.loadConfiguration(file)
                val ownerName = yaml.getString("owner-name", ownerId.toString()).orEmpty().ifBlank { ownerId.toString() }
                val store = loadFile(ownerId, ownerName, defaultUnlockedSlots, maxUnlockedSlots)
                saveJdbc(store)
            }
    }

    fun load(ownerId: UUID, ownerName: String, defaultUnlockedSlots: Int, maxUnlockedSlots: Int): PlayerShopStore {
        initialize()
        return if (DatabaseManager.isJdbcAvailable()) {
            loadJdbc(ownerId, ownerName, defaultUnlockedSlots, maxUnlockedSlots)
        } else {
            loadFile(ownerId, ownerName, defaultUnlockedSlots, maxUnlockedSlots)
        }
    }

    fun save(store: PlayerShopStore) {
        initialize()
        if (DatabaseManager.isJdbcAvailable()) {
            saveJdbc(store)
        } else {
            saveFile(store)
        }
    }

    fun nextFreeSlot(store: PlayerShopStore): Int? {
        val occupied = store.listings.map { it.slotIndex }.toHashSet()
        return (0 until store.unlockedSlots).firstOrNull { it !in occupied }
    }

    private fun loadJdbc(ownerId: UUID, ownerName: String, defaultUnlockedSlots: Int, maxUnlockedSlots: Int): PlayerShopStore {
        DatabaseManager.withConnection { connection ->
            val store = connection.prepareStatement(
                """
                SELECT owner_name, unlocked_slots
                FROM player_shop_stores
                WHERE owner_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, ownerId.toString())
                statement.executeQuery().use { result ->
                    if (result.next()) {
                        PlayerShopStore(
                            ownerId = ownerId,
                            ownerName = result.getString("owner_name").ifBlank { ownerName },
                            unlockedSlots = result.getInt("unlocked_slots").coerceAtLeast(1).coerceAtMost(maxUnlockedSlots)
                        )
                    } else {
                        PlayerShopStore(
                            ownerId = ownerId,
                            ownerName = ownerName,
                            unlockedSlots = defaultUnlockedSlots.coerceAtLeast(1).coerceAtMost(maxUnlockedSlots)
                        )
                    }
                }
            }
            connection.prepareStatement(
                """
                SELECT id, slot_index, price, currency, item_blob, created_at
                FROM player_shop_listings
                WHERE owner_id = ?
                ORDER BY slot_index ASC
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, ownerId.toString())
                statement.executeQuery().use { result ->
                    while (result.next()) {
                        val item = ItemStackCodec.decode(result.getString("item_blob")) ?: continue
                        store.listings += PlayerShopListing(
                            id = result.getString("id"),
                            slotIndex = result.getInt("slot_index"),
                            price = result.getDouble("price"),
                            currency = result.getString("currency"),
                            item = item,
                            createdAt = result.getLong("created_at")
                        )
                    }
                }
            }
            store
        }?.let { return it }
        return loadFile(ownerId, ownerName, defaultUnlockedSlots, maxUnlockedSlots)
    }

    private fun saveJdbc(store: PlayerShopStore) {
        val success = DatabaseManager.withConnection { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    """
                    REPLACE INTO player_shop_stores (owner_id, owner_name, unlocked_slots)
                    VALUES (?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, store.ownerId.toString())
                    statement.setString(2, store.ownerName)
                    statement.setInt(3, store.unlockedSlots)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM player_shop_listings WHERE owner_id = ?").use { delete ->
                    delete.setString(1, store.ownerId.toString())
                    delete.executeUpdate()
                }
                connection.prepareStatement(
                    """
                    INSERT INTO player_shop_listings (
                        id, owner_id, slot_index, price, currency, item_blob, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { insert ->
                    store.listings.sortedBy { it.slotIndex }.forEach { listing ->
                        insert.setString(1, listing.id)
                        insert.setString(2, store.ownerId.toString())
                        insert.setInt(3, listing.slotIndex)
                        insert.setDouble(4, listing.price)
                        insert.setString(5, listing.currency)
                        insert.setString(6, ItemStackCodec.encode(listing.item))
                        insert.setLong(7, listing.createdAt)
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
            saveFile(store)
        }
    }

    private fun loadFile(ownerId: UUID, ownerName: String, defaultUnlockedSlots: Int, maxUnlockedSlots: Int): PlayerShopStore {
        val file = storeFile(ownerId)
        if (!file.exists()) {
            return PlayerShopStore(ownerId, ownerName, defaultUnlockedSlots.coerceAtLeast(1).coerceAtMost(maxUnlockedSlots))
        }
        val yaml = YamlConfiguration.loadConfiguration(file)
        val store = PlayerShopStore(
            ownerId = ownerId,
            ownerName = yaml.getString("owner-name", ownerName).orEmpty().ifBlank { ownerName },
            unlockedSlots = yaml.getInt("unlocked-slots", defaultUnlockedSlots).coerceAtLeast(1).coerceAtMost(maxUnlockedSlots)
        )
        val listings = yaml.getConfigurationSection("listings")
        listings?.getKeys(false)?.forEach { id ->
            val section = listings.getConfigurationSection(id) ?: return@forEach
            val item = section.getItemStack("item") ?: return@forEach
            store.listings += PlayerShopListing(
                id = id,
                slotIndex = section.getInt("slot"),
                price = section.getDouble("price"),
                currency = section.getString("currency", "vault").orEmpty(),
                item = item,
                createdAt = section.getLong("created-at", System.currentTimeMillis())
            )
        }
        return store
    }

    private fun saveFile(store: PlayerShopStore) {
        val yaml = YamlConfiguration()
        yaml.set("owner-id", store.ownerId.toString())
        yaml.set("owner-name", store.ownerName)
        yaml.set("unlocked-slots", store.unlockedSlots)
        store.listings.sortedBy { it.slotIndex }.forEach { listing ->
            val base = "listings.${listing.id}"
            yaml.set("$base.slot", listing.slotIndex)
            yaml.set("$base.price", listing.price)
            yaml.set("$base.currency", listing.currency)
            yaml.set("$base.item", listing.item)
            yaml.set("$base.created-at", listing.createdAt)
        }
        yaml.save(storeFile(store.ownerId))
    }

    private fun storeFile(ownerId: UUID): File {
        return File(folder, "${ownerId}.yml")
    }
}
