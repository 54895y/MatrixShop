package com.y54895.matrixshop.module.auction

import com.y54895.matrixshop.core.menu.MenuDefinition
import org.bukkit.inventory.ItemStack
import java.util.UUID

data class AuctionListing(
    val id: String,
    val ownerId: UUID,
    val ownerName: String,
    val mode: AuctionMode,
    val item: ItemStack,
    val startPrice: Double,
    val buyoutPrice: Double,
    val endPrice: Double,
    var currentBid: Double,
    var highestBidderId: UUID? = null,
    var highestBidderName: String = "",
    val createdAt: Long,
    var expireAt: Long,
    var extendCount: Int = 0,
    val depositPaid: Double = 0.0,
    val bidHistory: MutableList<AuctionBidEntry> = mutableListOf()
)

data class AuctionBidEntry(
    val bidderId: UUID,
    val bidderName: String,
    val amount: Double,
    val createdAt: Long
)

data class AuctionMenus(
    val auctionViews: Map<String, MenuDefinition>,
    val upload: MenuDefinition,
    val detail: MenuDefinition,
    val bid: MenuDefinition,
    val manage: MenuDefinition,
    val bids: MenuDefinition
)

data class AuctionSettings(
    val maxActive: Int,
    val defaultDuration: Int,
    val minDuration: Int,
    val maxDuration: Int,
    val englishAllowBuyout: Boolean,
    val englishMinStartPrice: Double,
    val englishStepMode: String,
    val englishStepFixed: Double,
    val englishStepPercent: Double,
    val dutchMinStartPrice: Double,
    val dutchEndPriceMin: Double,
    val dutchTickSeconds: Int,
    val depositEnabled: Boolean,
    val depositMode: String,
    val depositValue: Double,
    val depositRefundOnSell: Boolean,
    val depositRefundOnCancel: Boolean,
    val taxEnabled: Boolean,
    val taxMode: String,
    val taxValue: Double,
    val snipeEnabled: Boolean,
    val snipeTriggerSeconds: Int,
    val snipeExtendSeconds: Int,
    val snipeMaxExtendTimes: Int,
    val ownerCancelAllow: Boolean,
    val ownerCancelDenyIfHasBid: Boolean,
    val recordWriteOnCreate: Boolean,
    val recordWriteOnBid: Boolean,
    val recordWriteOnComplete: Boolean,
    val recordWriteOnCancel: Boolean
)

data class AuctionDeliveryEntry(
    val id: String,
    val ownerId: UUID,
    val ownerName: String,
    val money: Double = 0.0,
    val item: ItemStack? = null,
    val message: String,
    val createdAt: Long = System.currentTimeMillis()
)

enum class AuctionMode {
    ENGLISH,
    DUTCH
}
