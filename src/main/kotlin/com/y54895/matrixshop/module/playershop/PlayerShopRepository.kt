package com.y54895.matrixshop.module.playershop

import com.y54895.matrixshop.core.config.ConfigFiles
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID

object PlayerShopRepository {

    private val folder: File
        get() = File(ConfigFiles.dataFolder(), "Data/player-shops")

    fun initialize() {
        folder.mkdirs()
    }

    fun load(ownerId: UUID, ownerName: String, defaultUnlockedSlots: Int, maxUnlockedSlots: Int): PlayerShopStore {
        initialize()
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

    fun save(store: PlayerShopStore) {
        initialize()
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

    fun nextFreeSlot(store: PlayerShopStore): Int? {
        val occupied = store.listings.map { it.slotIndex }.toHashSet()
        return (0 until store.unlockedSlots).firstOrNull { it !in occupied }
    }

    private fun storeFile(ownerId: UUID): File {
        return File(folder, "${ownerId}.yml")
    }
}
