package com.y54895.matrixshop.module.auction

import com.y54895.matrixshop.core.config.ConfigFiles
import com.y54895.matrixshop.core.database.DatabaseManager
import com.y54895.matrixshop.core.database.ItemStackCodec
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID

object AuctionRepository {

    private val file: File
        get() = File(ConfigFiles.dataFolder(), "Data/auction/listings.yml")

    fun initialize() {
        file.parentFile.mkdirs()
        if (!file.exists()) {
            file.createNewFile()
        }
        if (DatabaseManager.isJdbcAvailable()) {
            migrateFileToJdbcIfNeeded()
        }
    }

    fun loadAll(): MutableList<AuctionListing> {
        initialize()
        return if (DatabaseManager.isJdbcAvailable()) loadAllJdbc() else loadAllFile()
    }

    fun saveAll(listings: List<AuctionListing>) {
        initialize()
        if (DatabaseManager.isJdbcAvailable()) {
            saveAllJdbc(listings)
        } else {
            saveAllFile(listings)
        }
    }

    private fun loadAllJdbc(): MutableList<AuctionListing> {
        DatabaseManager.withConnection { connection ->
            val bidMap = HashMap<String, MutableList<AuctionBidEntry>>()
            connection.prepareStatement(
                """
                SELECT listing_id, bidder_id, bidder_name, amount, created_at
                FROM auction_bids
                ORDER BY listing_id, bid_index ASC
                """.trimIndent()
            ).use { statement ->
                statement.executeQuery().use { result ->
                    while (result.next()) {
                        val listingId = result.getString("listing_id")
                        val bid = AuctionBidEntry(
                            bidderId = UUID.fromString(result.getString("bidder_id")),
                            bidderName = result.getString("bidder_name"),
                            amount = result.getDouble("amount"),
                            createdAt = result.getLong("created_at")
                        )
                        bidMap.computeIfAbsent(listingId) { mutableListOf() }.add(bid)
                    }
                }
            }
            connection.prepareStatement(
                """
                SELECT
                    id, owner_id, owner_name, mode, item_blob, start_price, buyout_price, end_price,
                    current_bid, highest_bidder_id, highest_bidder_name, created_at, expire_at, extend_count, deposit_paid
                FROM auction_listings
                ORDER BY created_at DESC
                """.trimIndent()
            ).use { statement ->
                statement.executeQuery().use { result ->
                    val listings = mutableListOf<AuctionListing>()
                    while (result.next()) {
                        val item = ItemStackCodec.decode(result.getString("item_blob")) ?: continue
                        val id = result.getString("id")
                        listings += AuctionListing(
                            id = id,
                            ownerId = UUID.fromString(result.getString("owner_id")),
                            ownerName = result.getString("owner_name"),
                            mode = runCatching {
                                AuctionMode.valueOf(result.getString("mode").uppercase())
                            }.getOrDefault(AuctionMode.ENGLISH),
                            item = item,
                            startPrice = result.getDouble("start_price"),
                            buyoutPrice = result.getDouble("buyout_price"),
                            endPrice = result.getDouble("end_price"),
                            currentBid = result.getDouble("current_bid"),
                            highestBidderId = result.getString("highest_bidder_id")?.takeIf { it.isNotBlank() }?.let { UUID.fromString(it) },
                            highestBidderName = result.getString("highest_bidder_name") ?: "",
                            createdAt = result.getLong("created_at"),
                            expireAt = result.getLong("expire_at"),
                            extendCount = result.getInt("extend_count"),
                            depositPaid = result.getDouble("deposit_paid"),
                            bidHistory = (bidMap[id] ?: mutableListOf()).toMutableList()
                        )
                    }
                    listings
                }
            }
        }?.let { return it }
        return loadAllFile()
    }

    private fun saveAllJdbc(listings: List<AuctionListing>) {
        val success = DatabaseManager.withConnection { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                connection.prepareStatement("DELETE FROM auction_bids").use { it.executeUpdate() }
                connection.prepareStatement("DELETE FROM auction_listings").use { it.executeUpdate() }
                connection.prepareStatement(
                    """
                    INSERT INTO auction_listings (
                        id, owner_id, owner_name, mode, item_blob, start_price, buyout_price, end_price,
                        current_bid, highest_bidder_id, highest_bidder_name, created_at, expire_at, extend_count, deposit_paid
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { insertListing ->
                    connection.prepareStatement(
                        """
                        INSERT INTO auction_bids (
                            listing_id, bid_index, bidder_id, bidder_name, amount, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        """.trimIndent()
                    ).use { insertBid ->
                        listings.sortedBy { it.createdAt }.forEach { listing ->
                            insertListing.setString(1, listing.id)
                            insertListing.setString(2, listing.ownerId.toString())
                            insertListing.setString(3, listing.ownerName)
                            insertListing.setString(4, listing.mode.name)
                            insertListing.setString(5, ItemStackCodec.encode(listing.item))
                            insertListing.setDouble(6, listing.startPrice)
                            insertListing.setDouble(7, listing.buyoutPrice)
                            insertListing.setDouble(8, listing.endPrice)
                            insertListing.setDouble(9, listing.currentBid)
                            insertListing.setString(10, listing.highestBidderId?.toString().orEmpty())
                            insertListing.setString(11, listing.highestBidderName)
                            insertListing.setLong(12, listing.createdAt)
                            insertListing.setLong(13, listing.expireAt)
                            insertListing.setInt(14, listing.extendCount)
                            insertListing.setDouble(15, listing.depositPaid)
                            insertListing.addBatch()
                            listing.bidHistory.sortedBy { it.createdAt }.forEachIndexed { index, bid ->
                                insertBid.setString(1, listing.id)
                                insertBid.setInt(2, index)
                                insertBid.setString(3, bid.bidderId.toString())
                                insertBid.setString(4, bid.bidderName)
                                insertBid.setDouble(5, bid.amount)
                                insertBid.setLong(6, bid.createdAt)
                                insertBid.addBatch()
                            }
                        }
                        insertListing.executeBatch()
                        insertBid.executeBatch()
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
            saveAllFile(listings)
        }
    }

    private fun migrateFileToJdbcIfNeeded() {
        val count = DatabaseManager.withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM auction_listings").use { result ->
                    if (result.next()) result.getInt(1) else 0
                }
            }
        } ?: return
        if (count > 0) {
            return
        }
        val fileListings = loadAllFile()
        if (fileListings.isNotEmpty()) {
            saveAllJdbc(fileListings)
        }
    }

    private fun loadAllFile(): MutableList<AuctionListing> {
        val yaml = YamlConfiguration.loadConfiguration(file)
        val result = mutableListOf<AuctionListing>()
        val section = yaml.getConfigurationSection("listings")
        section?.getKeys(false)?.forEach { id ->
            val child = section.getConfigurationSection(id) ?: return@forEach
            val item = child.getItemStack("item") ?: return@forEach
            val mode = runCatching { AuctionMode.valueOf(child.getString("mode", "ENGLISH").orEmpty().uppercase()) }
                .getOrDefault(AuctionMode.ENGLISH)
            val listing = AuctionListing(
                id = id,
                ownerId = UUID.fromString(child.getString("owner-id").orEmpty()),
                ownerName = child.getString("owner-name").orEmpty(),
                mode = mode,
                item = item,
                startPrice = child.getDouble("start-price"),
                buyoutPrice = child.getDouble("buyout-price"),
                endPrice = child.getDouble("end-price"),
                currentBid = child.getDouble("current-bid"),
                highestBidderId = child.getString("highest-bidder-id")?.takeIf { it.isNotBlank() }?.let { UUID.fromString(it) },
                highestBidderName = child.getString("highest-bidder-name", "").orEmpty(),
                createdAt = child.getLong("created-at"),
                expireAt = child.getLong("expire-at"),
                extendCount = child.getInt("extend-count"),
                depositPaid = child.getDouble("deposit-paid")
            )
            val bids = child.getConfigurationSection("bids")
            bids?.getKeys(false)?.sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }?.forEach { key ->
                val bid = bids.getConfigurationSection(key) ?: return@forEach
                listing.bidHistory += AuctionBidEntry(
                    bidderId = UUID.fromString(bid.getString("bidder-id").orEmpty()),
                    bidderName = bid.getString("bidder-name").orEmpty(),
                    amount = bid.getDouble("amount"),
                    createdAt = bid.getLong("created-at")
                )
            }
            result += listing
        }
        return result
    }

    private fun saveAllFile(listings: List<AuctionListing>) {
        val yaml = YamlConfiguration()
        listings.sortedBy { it.createdAt }.forEach { listing ->
            val base = "listings.${listing.id}"
            yaml.set("$base.owner-id", listing.ownerId.toString())
            yaml.set("$base.owner-name", listing.ownerName)
            yaml.set("$base.mode", listing.mode.name)
            yaml.set("$base.item", listing.item)
            yaml.set("$base.start-price", listing.startPrice)
            yaml.set("$base.buyout-price", listing.buyoutPrice)
            yaml.set("$base.end-price", listing.endPrice)
            yaml.set("$base.current-bid", listing.currentBid)
            yaml.set("$base.highest-bidder-id", listing.highestBidderId?.toString())
            yaml.set("$base.highest-bidder-name", listing.highestBidderName)
            yaml.set("$base.created-at", listing.createdAt)
            yaml.set("$base.expire-at", listing.expireAt)
            yaml.set("$base.extend-count", listing.extendCount)
            yaml.set("$base.deposit-paid", listing.depositPaid)
            listing.bidHistory.sortedBy { it.createdAt }.forEachIndexed { index, bid ->
                val bidBase = "$base.bids.$index"
                yaml.set("$bidBase.bidder-id", bid.bidderId.toString())
                yaml.set("$bidBase.bidder-name", bid.bidderName)
                yaml.set("$bidBase.amount", bid.amount)
                yaml.set("$bidBase.created-at", bid.createdAt)
            }
        }
        yaml.save(file)
    }
}
