package com.y54895.matrixshop.module.cart

import com.y54895.matrixshop.core.config.ConfigFiles
import com.y54895.matrixshop.core.database.DatabaseManager
import com.y54895.matrixshop.core.database.ItemStackCodec
import com.y54895.matrixshop.core.database.StringMapCodec
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID

object CartRepository {

    private val folder: File
        get() = File(ConfigFiles.dataFolder(), "Data/carts")

    fun initialize() {
        folder.mkdirs()
        if (DatabaseManager.isJdbcAvailable()) {
            initializeJdbc()
            migrateFilesToJdbcIfNeeded()
        }
    }

    fun load(ownerId: UUID): CartStore {
        initialize()
        return if (DatabaseManager.isJdbcAvailable()) loadJdbc(ownerId) else loadFile(ownerId)
    }

    fun save(store: CartStore) {
        initialize()
        if (DatabaseManager.isJdbcAvailable()) {
            saveJdbc(store)
        } else {
            saveFile(store)
        }
    }

    private fun loadJdbc(ownerId: UUID): CartStore {
        DatabaseManager.withConnection { connection ->
            val store = CartStore(ownerId)
            connection.prepareStatement(
                """
                SELECT
                    id, source_module, source_id, name, currency, snapshot_price, amount, owner_name,
                    item_blob, editable_amount, protected_on_clear, watch_only, created_at, metadata_blob
                FROM cart_entries
                WHERE owner_id = ?
                ORDER BY created_at ASC
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, ownerId.toString())
                statement.executeQuery().use { result ->
                    while (result.next()) {
                        val item = ItemStackCodec.decode(result.getString("item_blob")) ?: continue
                        store.entries += CartEntry(
                            id = result.getString("id"),
                            sourceModule = result.getString("source_module"),
                            sourceId = result.getString("source_id"),
                            name = result.getString("name"),
                            currency = result.getString("currency"),
                            snapshotPrice = result.getDouble("snapshot_price"),
                            amount = result.getInt("amount"),
                            ownerName = result.getString("owner_name"),
                            item = item,
                            editableAmount = result.getBoolean("editable_amount"),
                            protectedOnClear = result.getBoolean("protected_on_clear"),
                            watchOnly = result.getBoolean("watch_only"),
                            createdAt = result.getLong("created_at"),
                            metadata = StringMapCodec.decode(result.getString("metadata_blob"))
                        )
                    }
                }
            }
            store
        }?.let { return it }
        return loadFile(ownerId)
    }

    private fun saveJdbc(store: CartStore) {
        val success = DatabaseManager.withConnection { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                connection.prepareStatement("DELETE FROM cart_entries WHERE owner_id = ?").use { delete ->
                    delete.setString(1, store.ownerId.toString())
                    delete.executeUpdate()
                }
                connection.prepareStatement(
                    """
                    INSERT INTO cart_entries (
                        owner_id, id, source_module, source_id, name, currency, snapshot_price, amount, owner_name,
                        item_blob, editable_amount, protected_on_clear, watch_only, created_at, metadata_blob
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { insert ->
                    store.entries.sortedBy { it.createdAt }.forEach { entry ->
                        insert.setString(1, store.ownerId.toString())
                        insert.setString(2, entry.id)
                        insert.setString(3, entry.sourceModule)
                        insert.setString(4, entry.sourceId)
                        insert.setString(5, entry.name)
                        insert.setString(6, entry.currency)
                        insert.setDouble(7, entry.snapshotPrice)
                        insert.setInt(8, entry.amount)
                        insert.setString(9, entry.ownerName)
                        insert.setString(10, ItemStackCodec.encode(entry.item))
                        insert.setBoolean(11, entry.editableAmount)
                        insert.setBoolean(12, entry.protectedOnClear)
                        insert.setBoolean(13, entry.watchOnly)
                        insert.setLong(14, entry.createdAt)
                        insert.setString(15, StringMapCodec.encode(entry.metadata))
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

    private fun initializeJdbc() {
        DatabaseManager.withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS cart_entries (
                        owner_id VARCHAR(64) NOT NULL,
                        id VARCHAR(64) NOT NULL,
                        source_module VARCHAR(64) NOT NULL,
                        source_id VARCHAR(128) NOT NULL,
                        name VARCHAR(255) NOT NULL,
                        currency VARCHAR(32) NOT NULL,
                        snapshot_price DOUBLE NOT NULL,
                        amount INT NOT NULL,
                        owner_name VARCHAR(64) NOT NULL,
                        item_blob TEXT NOT NULL,
                        editable_amount BOOLEAN NOT NULL,
                        protected_on_clear BOOLEAN NOT NULL,
                        watch_only BOOLEAN NOT NULL,
                        created_at BIGINT NOT NULL,
                        metadata_blob TEXT NOT NULL,
                        PRIMARY KEY (owner_id, id)
                    )
                    """.trimIndent()
                )
            }
        }
    }

    private fun migrateFilesToJdbcIfNeeded() {
        val count = DatabaseManager.withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM cart_entries").use { result ->
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
                val store = loadFile(ownerId)
                saveJdbc(store)
            }
    }

    private fun loadFile(ownerId: UUID): CartStore {
        val file = File(folder, "${ownerId}.yml")
        if (!file.exists()) {
            return CartStore(ownerId)
        }
        val yaml = YamlConfiguration.loadConfiguration(file)
        val store = CartStore(ownerId)
        val section = yaml.getConfigurationSection("entries")
        section?.getKeys(false)?.forEach { id ->
            val child = section.getConfigurationSection(id) ?: return@forEach
            val item = child.getItemStack("item") ?: return@forEach
            val metadata = linkedMapOf<String, String>()
            child.getConfigurationSection("metadata")?.getKeys(false)?.forEach { key ->
                metadata[key] = child.getString("metadata.$key").orEmpty()
            }
            store.entries += CartEntry(
                id = id,
                sourceModule = child.getString("source-module", "system_shop").orEmpty(),
                sourceId = child.getString("source-id", id).orEmpty(),
                name = child.getString("name", item.type.name).orEmpty(),
                currency = child.getString("currency", "vault").orEmpty(),
                snapshotPrice = child.getDouble("snapshot-price"),
                amount = child.getInt("amount", 1),
                ownerName = child.getString("owner-name", "").orEmpty(),
                item = item,
                editableAmount = child.getBoolean("editable-amount", true),
                protectedOnClear = child.getBoolean("protected-on-clear", false),
                watchOnly = child.getBoolean("watch-only", false),
                createdAt = child.getLong("created-at", System.currentTimeMillis()),
                metadata = metadata
            )
        }
        return store
    }

    private fun saveFile(store: CartStore) {
        val yaml = YamlConfiguration()
        store.entries.sortedBy { it.createdAt }.forEach { entry ->
            val base = "entries.${entry.id}"
            yaml.set("$base.source-module", entry.sourceModule)
            yaml.set("$base.source-id", entry.sourceId)
            yaml.set("$base.name", entry.name)
            yaml.set("$base.currency", entry.currency)
            yaml.set("$base.snapshot-price", entry.snapshotPrice)
            yaml.set("$base.amount", entry.amount)
            yaml.set("$base.owner-name", entry.ownerName)
            yaml.set("$base.item", entry.item)
            yaml.set("$base.editable-amount", entry.editableAmount)
            yaml.set("$base.protected-on-clear", entry.protectedOnClear)
            yaml.set("$base.watch-only", entry.watchOnly)
            yaml.set("$base.created-at", entry.createdAt)
            entry.metadata.forEach { (key, value) ->
                yaml.set("$base.metadata.$key", value)
            }
        }
        yaml.save(File(folder, "${store.ownerId}.yml"))
    }
}
