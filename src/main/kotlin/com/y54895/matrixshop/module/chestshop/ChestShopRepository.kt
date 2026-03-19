package com.y54895.matrixshop.module.chestshop

import com.y54895.matrixshop.core.config.ConfigFiles
import com.y54895.matrixshop.core.database.DatabaseManager
import com.y54895.matrixshop.core.database.ItemStackCodec
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
        if (DatabaseManager.isJdbcAvailable()) {
            migrateFileToJdbcIfNeeded()
        }
    }

    fun loadAll(): MutableList<ChestShopShop> {
        initialize()
        return if (DatabaseManager.isJdbcAvailable()) loadAllJdbc() else loadAllFile()
    }

    fun saveAll(shops: List<ChestShopShop>) {
        initialize()
        if (DatabaseManager.isJdbcAvailable()) {
            saveAllJdbc(shops)
        } else {
            saveAllFile(shops)
        }
    }

    private fun loadAllJdbc(): MutableList<ChestShopShop> {
        DatabaseManager.withConnection { connection ->
            val signMap = HashMap<String, MutableList<ChestShopLocation>>()
            val historyMap = HashMap<String, MutableList<ChestShopHistoryEntry>>()

            connection.prepareStatement(
                """
                SELECT shop_id, sign_index, world, x, y, z
                FROM chest_shop_signs
                ORDER BY shop_id ASC, sign_index ASC
                """.trimIndent()
            ).use { statement ->
                statement.executeQuery().use { result ->
                    while (result.next()) {
                        val shopId = result.getString("shop_id")
                        signMap.computeIfAbsent(shopId) { mutableListOf() }.add(
                            ChestShopLocation(
                                world = result.getString("world"),
                                x = result.getInt("x"),
                                y = result.getInt("y"),
                                z = result.getInt("z")
                            )
                        )
                    }
                }
            }

            connection.prepareStatement(
                """
                SELECT shop_id, history_index, type, actor, amount, money, created_at, note
                FROM chest_shop_history
                ORDER BY shop_id ASC, history_index ASC
                """.trimIndent()
            ).use { statement ->
                statement.executeQuery().use { result ->
                    while (result.next()) {
                        val shopId = result.getString("shop_id")
                        historyMap.computeIfAbsent(shopId) { mutableListOf() }.add(
                            ChestShopHistoryEntry(
                                type = result.getString("type"),
                                actor = result.getString("actor"),
                                amount = result.getInt("amount"),
                                money = result.getDouble("money"),
                                createdAt = result.getLong("created_at"),
                                note = result.getString("note") ?: ""
                            )
                        )
                    }
                }
            }

            connection.prepareStatement(
                """
                SELECT
                    id, owner_id, owner_name, primary_world, primary_x, primary_y, primary_z,
                    secondary_world, secondary_x, secondary_y, secondary_z,
                    mode, buy_price, sell_price, trade_amount, item_blob, created_at
                FROM chest_shops
                ORDER BY created_at ASC
                """.trimIndent()
            ).use { statement ->
                statement.executeQuery().use { result ->
                    val shops = mutableListOf<ChestShopShop>()
                    while (result.next()) {
                        val item = ItemStackCodec.decode(result.getString("item_blob")) ?: continue
                        val id = result.getString("id")
                        val secondaryWorld = result.getString("secondary_world").orEmpty()
                        shops += ChestShopShop(
                            id = id,
                            ownerId = UUID.fromString(result.getString("owner_id")),
                            ownerName = result.getString("owner_name"),
                            primaryChest = ChestShopLocation(
                                world = result.getString("primary_world"),
                                x = result.getInt("primary_x"),
                                y = result.getInt("primary_y"),
                                z = result.getInt("primary_z")
                            ),
                            secondaryChest = if (secondaryWorld.isBlank()) null else ChestShopLocation(
                                world = secondaryWorld,
                                x = result.getInt("secondary_x"),
                                y = result.getInt("secondary_y"),
                                z = result.getInt("secondary_z")
                            ),
                            signLocations = (signMap[id] ?: mutableListOf()).toMutableList(),
                            mode = runCatching {
                                ChestShopMode.valueOf(result.getString("mode").uppercase())
                            }.getOrDefault(ChestShopMode.SELL),
                            buyPrice = result.getDouble("buy_price"),
                            sellPrice = result.getDouble("sell_price"),
                            tradeAmount = result.getInt("trade_amount"),
                            item = item,
                            createdAt = result.getLong("created_at"),
                            history = (historyMap[id] ?: mutableListOf()).toMutableList()
                        )
                    }
                    shops
                }
            }
        }?.let { return it }
        return loadAllFile()
    }

    private fun saveAllJdbc(shops: List<ChestShopShop>) {
        val success = DatabaseManager.withConnection { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                connection.prepareStatement("DELETE FROM chest_shop_signs").use { it.executeUpdate() }
                connection.prepareStatement("DELETE FROM chest_shop_history").use { it.executeUpdate() }
                connection.prepareStatement("DELETE FROM chest_shops").use { it.executeUpdate() }

                connection.prepareStatement(
                    """
                    INSERT INTO chest_shops (
                        id, owner_id, owner_name, primary_world, primary_x, primary_y, primary_z,
                        secondary_world, secondary_x, secondary_y, secondary_z,
                        mode, buy_price, sell_price, trade_amount, item_blob, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { insertShop ->
                    connection.prepareStatement(
                        """
                        INSERT INTO chest_shop_signs (
                            shop_id, sign_index, world, x, y, z
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        """.trimIndent()
                    ).use { insertSign ->
                        connection.prepareStatement(
                            """
                            INSERT INTO chest_shop_history (
                                shop_id, history_index, type, actor, amount, money, created_at, note
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                            """.trimIndent()
                        ).use { insertHistory ->
                            shops.sortedBy { it.createdAt }.forEach { shop ->
                                insertShop.setString(1, shop.id)
                                insertShop.setString(2, shop.ownerId.toString())
                                insertShop.setString(3, shop.ownerName)
                                insertShop.setString(4, shop.primaryChest.world)
                                insertShop.setInt(5, shop.primaryChest.x)
                                insertShop.setInt(6, shop.primaryChest.y)
                                insertShop.setInt(7, shop.primaryChest.z)
                                insertShop.setString(8, shop.secondaryChest?.world.orEmpty())
                                insertShop.setInt(9, shop.secondaryChest?.x ?: 0)
                                insertShop.setInt(10, shop.secondaryChest?.y ?: 0)
                                insertShop.setInt(11, shop.secondaryChest?.z ?: 0)
                                insertShop.setString(12, shop.mode.name)
                                insertShop.setDouble(13, shop.buyPrice)
                                insertShop.setDouble(14, shop.sellPrice)
                                insertShop.setInt(15, shop.tradeAmount)
                                insertShop.setString(16, ItemStackCodec.encode(shop.item))
                                insertShop.setLong(17, shop.createdAt)
                                insertShop.addBatch()

                                shop.signLocations.forEachIndexed { index, sign ->
                                    insertSign.setString(1, shop.id)
                                    insertSign.setInt(2, index)
                                    insertSign.setString(3, sign.world)
                                    insertSign.setInt(4, sign.x)
                                    insertSign.setInt(5, sign.y)
                                    insertSign.setInt(6, sign.z)
                                    insertSign.addBatch()
                                }

                                shop.history.sortedByDescending { it.createdAt }.take(50).reversed().forEachIndexed { index, entry ->
                                    insertHistory.setString(1, shop.id)
                                    insertHistory.setInt(2, index)
                                    insertHistory.setString(3, entry.type)
                                    insertHistory.setString(4, entry.actor)
                                    insertHistory.setInt(5, entry.amount)
                                    insertHistory.setDouble(6, entry.money)
                                    insertHistory.setLong(7, entry.createdAt)
                                    insertHistory.setString(8, entry.note)
                                    insertHistory.addBatch()
                                }
                            }
                            insertShop.executeBatch()
                            insertSign.executeBatch()
                            insertHistory.executeBatch()
                        }
                    }
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
            saveAllFile(shops)
        }
    }

    private fun migrateFileToJdbcIfNeeded() {
        val count = DatabaseManager.withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM chest_shops").use { result ->
                    if (result.next()) result.getInt(1) else 0
                }
            }
        } ?: return
        if (count > 0) {
            return
        }
        val shops = loadAllFile()
        if (shops.isNotEmpty()) {
            saveAllJdbc(shops)
        }
    }

    private fun loadAllFile(): MutableList<ChestShopShop> {
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

    private fun saveAllFile(shops: List<ChestShopShop>) {
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
