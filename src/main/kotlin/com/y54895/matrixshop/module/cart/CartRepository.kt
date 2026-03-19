package com.y54895.matrixshop.module.cart

import com.y54895.matrixshop.core.config.ConfigFiles
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID

object CartRepository {

    private val folder: File
        get() = File(ConfigFiles.dataFolder(), "Data/carts")

    fun initialize() {
        folder.mkdirs()
    }

    fun load(ownerId: UUID): CartStore {
        initialize()
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

    fun save(store: CartStore) {
        initialize()
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
