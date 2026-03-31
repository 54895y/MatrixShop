package com.y54895.matrixshop.module.playershop

import com.y54895.matrixshop.core.config.ConfigFiles
import com.y54895.matrixshop.core.database.DatabaseManager
import com.y54895.matrixshop.core.database.ItemStackCodec
import com.y54895.matrixshop.core.database.LegacyImportResult
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID

object PlayerShopRepository {

    private val folder: File
        get() = File(ConfigFiles.dataFolder(), "Data/player-shops")

    fun initialize() {
        folder.mkdirs()
    }

    fun migrateLegacyToJdbcIfNeeded(defaultUnlockedSlots: Int, maxUnlockedSlots: Int): LegacyImportResult {
        initialize()
        if (!DatabaseManager.isJdbcAvailable()) {
            return LegacyImportResult("player-shop", "file-backend", 0, "JDBC backend unavailable.")
        }
        val count = DatabaseManager.withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM player_shop_stores").use { result ->
                    if (result.next()) result.getInt(1) else 0
                }
            }
        } ?: return LegacyImportResult("player-shop", "failed", 0, "Unable to inspect player_shop_stores.")
        if (count > 0) {
            return LegacyImportResult("player-shop", "already-present", 0, "player_shop_stores already contains $count rows.")
        }
        val files = folder.listFiles { file -> file.isFile && file.extension.equals("yml", true) }.orEmpty().toList()
        if (files.isEmpty()) {
            return LegacyImportResult("player-shop", "no-source", 0, "No legacy player shop files found.")
        }
        files.forEach { file ->
            val ownerId = runCatching { UUID.fromString(file.nameWithoutExtension) }.getOrNull() ?: return@forEach
            val yaml = YamlConfiguration.loadConfiguration(file)
            val ownerName = yaml.getString("owner-name", ownerId.toString()).orEmpty().ifBlank { ownerId.toString() }
            val store = loadFile("default", ownerId, ownerName, defaultUnlockedSlots, maxUnlockedSlots)
            saveJdbc(store)
        }
        val imported = DatabaseManager.withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM player_shop_stores").use { result ->
                    if (result.next()) result.getInt(1) else 0
                }
            }
        } ?: 0
        return if (imported > 0) {
            LegacyImportResult("player-shop", "imported", imported, "Imported $imported player shop stores.")
        } else {
            LegacyImportResult("player-shop", "failed", 0, "Legacy player shop import did not write any rows.")
        }
    }

    fun load(shopId: String, ownerId: UUID, ownerName: String, defaultUnlockedSlots: Int, maxUnlockedSlots: Int): PlayerShopStore {
        initialize()
        return if (DatabaseManager.isJdbcAvailable()) {
            loadJdbc(shopId, ownerId, ownerName, defaultUnlockedSlots, maxUnlockedSlots)
        } else {
            loadFile(shopId, ownerId, ownerName, defaultUnlockedSlots, maxUnlockedSlots)
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

    fun resolveOwner(name: String): Pair<UUID, String>? {
        initialize()
        val normalized = name.trim()
        if (normalized.isBlank()) {
            return null
        }
        Bukkit.getPlayerExact(normalized)?.let { return it.uniqueId to it.name }
        Bukkit.getOnlinePlayers().firstOrNull { it.name.equals(normalized, true) }?.let {
            return it.uniqueId to it.name
        }
        Bukkit.getOfflinePlayers().firstOrNull { it.name?.equals(normalized, true) == true }?.let {
            return it.uniqueId to (it.name ?: normalized)
        }
        return if (DatabaseManager.isJdbcAvailable()) {
            resolveOwnerJdbc(normalized) ?: resolveOwnerFile(normalized)
        } else {
            resolveOwnerFile(normalized)
        }
    }

    private fun loadJdbc(shopId: String, ownerId: UUID, ownerName: String, defaultUnlockedSlots: Int, maxUnlockedSlots: Int): PlayerShopStore {
        DatabaseManager.withConnection { connection ->
            val normalizedShopId = shopId.trim().ifBlank { "default" }
            val store = connection.prepareStatement(
                """
                SELECT owner_name, unlocked_slots
                FROM player_shop_stores
                WHERE shop_id = ? AND owner_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, normalizedShopId)
                statement.setString(2, ownerId.toString())
                statement.executeQuery().use { result ->
                    if (result.next()) {
                        PlayerShopStore(
                            ownerId = ownerId,
                            shopId = normalizedShopId,
                            ownerName = result.getString("owner_name").ifBlank { ownerName },
                            unlockedSlots = result.getInt("unlocked_slots").coerceAtLeast(1).coerceAtMost(maxUnlockedSlots)
                        )
                    } else {
                        PlayerShopStore(
                            ownerId = ownerId,
                            shopId = normalizedShopId,
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
                WHERE shop_id = ? AND owner_id = ?
                ORDER BY slot_index ASC
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, normalizedShopId)
                statement.setString(2, ownerId.toString())
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
        return loadFile(shopId, ownerId, ownerName, defaultUnlockedSlots, maxUnlockedSlots)
    }

    private fun resolveOwnerJdbc(name: String): Pair<UUID, String>? {
        return DatabaseManager.withConnection { connection ->
            connection.prepareStatement(
                """
                SELECT owner_id, owner_name
                FROM player_shop_stores
                WHERE LOWER(owner_name) = LOWER(?)
                ORDER BY owner_name ASC
                LIMIT 1
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, name)
                statement.executeQuery().use { result ->
                    if (result.next()) {
                        UUID.fromString(result.getString("owner_id")) to result.getString("owner_name").ifBlank { name }
                    } else {
                        null
                    }
                }
            }
        }
    }

    private fun saveJdbc(store: PlayerShopStore) {
        val success = DatabaseManager.withConnection { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    """
                    REPLACE INTO player_shop_stores (shop_id, owner_id, owner_name, unlocked_slots)
                    VALUES (?, ?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, store.shopId)
                    statement.setString(2, store.ownerId.toString())
                    statement.setString(3, store.ownerName)
                    statement.setInt(4, store.unlockedSlots)
                    statement.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM player_shop_listings WHERE shop_id = ? AND owner_id = ?").use { delete ->
                    delete.setString(1, store.shopId)
                    delete.setString(2, store.ownerId.toString())
                    delete.executeUpdate()
                }
                connection.prepareStatement(
                    """
                    INSERT INTO player_shop_listings (
                        id, shop_id, owner_id, slot_index, price, currency, item_blob, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { insert ->
                    store.listings.sortedBy { it.slotIndex }.forEach { listing ->
                        insert.setString(1, listing.id)
                        insert.setString(2, store.shopId)
                        insert.setString(3, store.ownerId.toString())
                        insert.setInt(4, listing.slotIndex)
                        insert.setDouble(5, listing.price)
                        insert.setString(6, listing.currency)
                        insert.setString(7, ItemStackCodec.encode(listing.item))
                        insert.setLong(8, listing.createdAt)
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

    private fun resolveOwnerFile(name: String): Pair<UUID, String>? {
        val files = folder.walkTopDown()
            .filter { it.isFile && it.extension.equals("yml", true) }
            .toList()
        files.forEach { file ->
            val yaml = YamlConfiguration.loadConfiguration(file)
            val ownerName = yaml.getString("owner-name").orEmpty()
            if (!ownerName.equals(name, true)) {
                return@forEach
            }
            val ownerId = yaml.getString("owner-id")
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: runCatching { UUID.fromString(file.nameWithoutExtension) }.getOrNull()
                ?: return@forEach
            return ownerId to ownerName
        }
        return null
    }

    private fun loadFile(shopId: String, ownerId: UUID, ownerName: String, defaultUnlockedSlots: Int, maxUnlockedSlots: Int): PlayerShopStore {
        val normalizedShopId = shopId.trim().ifBlank { "default" }
        val file = storeFile(normalizedShopId, ownerId)
        val legacyFile = legacyStoreFile(ownerId)
        if (!file.exists() && !(normalizedShopId == "default" && legacyFile.exists())) {
            return PlayerShopStore(
                ownerId = ownerId,
                shopId = normalizedShopId,
                ownerName = ownerName,
                unlockedSlots = defaultUnlockedSlots.coerceAtLeast(1).coerceAtMost(maxUnlockedSlots)
            )
        }
        val yaml = YamlConfiguration.loadConfiguration(if (file.exists()) file else legacyFile)
        val store = PlayerShopStore(
            ownerId = ownerId,
            shopId = normalizedShopId,
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
        yaml.set("shop-id", store.shopId)
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
        yaml.save(storeFile(store.shopId, store.ownerId))
    }

    private fun storeFile(shopId: String, ownerId: UUID): File {
        val shopFolder = File(folder, shopId)
        shopFolder.mkdirs()
        return File(shopFolder, "${ownerId}.yml")
    }

    private fun legacyStoreFile(ownerId: UUID): File {
        return File(folder, "${ownerId}.yml")
    }
}
