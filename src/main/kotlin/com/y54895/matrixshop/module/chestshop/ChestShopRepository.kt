package com.y54895.matrixshop.module.chestshop

import com.y54895.matrixshop.core.config.ConfigFiles
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID

object ChestShopRepository {

    private val file: File
        get() = File(ConfigFiles.dataFolder(), "Data/chest-shop/shops.yml")

    fun initialize() {
        file.parentFile.mkdirs()
        if (!file.exists()) {
            file.createNewFile()
        }
    }

    fun loadAll(): MutableList<ChestShopShop> {
        initialize()
        val yaml = YamlConfiguration.loadConfiguration(file)
        val result = mutableListOf<ChestShopShop>()
        val section = yaml.getConfigurationSection("shops")
        section?.getKeys(false)?.forEach { id ->
            val child = section.getConfigurationSection(id) ?: return@forEach
            val item = child.getItemStack("item") ?: return@forEach
            val primary = child.getConfigurationSection("primary-chest") ?: return@forEach
            val shop = ChestShopShop(
                id = id,
                ownerId = UUID.fromString(child.getString("owner-id").orEmpty()),
                ownerName = child.getString("owner-name").orEmpty(),
                primaryChest = primary.readLocation(),
                secondaryChest = child.getConfigurationSection("secondary-chest")?.readLocation(),
                mode = runCatching {
                    ChestShopMode.valueOf(child.getString("mode", "SELL").orEmpty().uppercase())
                }.getOrDefault(ChestShopMode.SELL),
                buyPrice = child.getDouble("buy-price"),
                sellPrice = child.getDouble("sell-price"),
                tradeAmount = child.getInt("trade-amount", item.amount.coerceAtLeast(1)),
                item = item,
                createdAt = child.getLong("created-at", System.currentTimeMillis())
            )
            val signs = child.getConfigurationSection("signs")
            signs?.getKeys(false)?.forEach { key ->
                shop.signLocations += signs.getConfigurationSection(key)?.readLocation() ?: return@forEach
            }
            val history = child.getConfigurationSection("history")
            history?.getKeys(false)?.sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }?.forEach { key ->
                val entry = history.getConfigurationSection(key) ?: return@forEach
                shop.history += ChestShopHistoryEntry(
                    type = entry.getString("type").orEmpty(),
                    actor = entry.getString("actor").orEmpty(),
                    amount = entry.getInt("amount"),
                    money = entry.getDouble("money"),
                    createdAt = entry.getLong("created-at"),
                    note = entry.getString("note", "").orEmpty()
                )
            }
            result += shop
        }
        return result
    }

    fun saveAll(shops: List<ChestShopShop>) {
        initialize()
        val yaml = YamlConfiguration()
        shops.sortedBy { it.createdAt }.forEach { shop ->
            val base = "shops.${shop.id}"
            yaml.set("$base.owner-id", shop.ownerId.toString())
            yaml.set("$base.owner-name", shop.ownerName)
            yaml.set("$base.mode", shop.mode.name)
            yaml.set("$base.buy-price", shop.buyPrice)
            yaml.set("$base.sell-price", shop.sellPrice)
            yaml.set("$base.trade-amount", shop.tradeAmount)
            yaml.set("$base.item", shop.item)
            yaml.set("$base.created-at", shop.createdAt)
            shop.primaryChest.writeLocation(yaml, "$base.primary-chest")
            shop.secondaryChest?.writeLocation(yaml, "$base.secondary-chest")
            shop.signLocations.forEachIndexed { index, location ->
                location.writeLocation(yaml, "$base.signs.$index")
            }
            shop.history.sortedByDescending { it.createdAt }.take(50).reversed().forEachIndexed { index, entry ->
                val historyBase = "$base.history.$index"
                yaml.set("$historyBase.type", entry.type)
                yaml.set("$historyBase.actor", entry.actor)
                yaml.set("$historyBase.amount", entry.amount)
                yaml.set("$historyBase.money", entry.money)
                yaml.set("$historyBase.created-at", entry.createdAt)
                yaml.set("$historyBase.note", entry.note)
            }
        }
        yaml.save(file)
    }

    private fun org.bukkit.configuration.ConfigurationSection.readLocation(): ChestShopLocation {
        return ChestShopLocation(
            world = getString("world").orEmpty(),
            x = getInt("x"),
            y = getInt("y"),
            z = getInt("z")
        )
    }

    private fun ChestShopLocation.writeLocation(yaml: YamlConfiguration, base: String) {
        yaml.set("$base.world", world)
        yaml.set("$base.x", x)
        yaml.set("$base.y", y)
        yaml.set("$base.z", z)
    }
}
