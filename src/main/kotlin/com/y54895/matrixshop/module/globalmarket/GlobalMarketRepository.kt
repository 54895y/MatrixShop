package com.y54895.matrixshop.module.globalmarket

import com.y54895.matrixshop.core.config.ConfigFiles
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

    fun loadAll(): MutableList<GlobalMarketListing> {
        initialize()
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
            saveAll(result)
        }
        return result
    }

    fun saveAll(listings: List<GlobalMarketListing>) {
        initialize()
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
