package com.y54895.matrixshop.module.auction

import com.y54895.matrixshop.core.config.ConfigFiles
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
    }

    fun loadAll(): MutableList<AuctionListing> {
        initialize()
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

    fun saveAll(listings: List<AuctionListing>) {
        initialize()
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
