package com.y54895.matrixshop.module.auction

import com.y54895.matrixshop.core.config.ConfigFiles
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
    }

    fun loadAll(): MutableList<AuctionDeliveryEntry> {
        initialize()
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

    fun saveAll(entries: List<AuctionDeliveryEntry>) {
        initialize()
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
