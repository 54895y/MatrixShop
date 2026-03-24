package com.y54895.matrixshop.module.auction

import org.bukkit.event.player.PlayerJoinEvent
import taboolib.common.platform.Awake
import taboolib.common.platform.event.SubscribeEvent

@Awake
object AuctionListener {

    @SubscribeEvent
    fun onJoin(event: PlayerJoinEvent) {
        AuctionModule.deliverPending(event.player)
    }
}
