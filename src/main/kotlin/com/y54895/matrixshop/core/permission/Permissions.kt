package com.y54895.matrixshop.core.permission

import com.y54895.matrixshop.core.config.ConfigFiles
import com.y54895.matrixshop.core.text.Texts
import org.bukkit.command.CommandSender

object PermissionNodes {

    const val ADMIN = "matrixshop.admin"
    const val ADMIN_RELOAD = "matrixshop.admin.reload"
    const val ADMIN_SYNC = "matrixshop.admin.sync"
    const val ADMIN_STATUS = "matrixshop.admin.status"
    const val ADMIN_MODULE = "matrixshop.admin.module"
    const val ADMIN_RECORD_VIEW_OTHERS = "matrixshop.admin.record.view.others"
    const val ADMIN_RECORD_EXPORT = "matrixshop.admin.record.export"
    const val ADMIN_AUCTION_MANAGE_OTHERS = "matrixshop.admin.auction.manage.others"
    const val ADMIN_CHESTSHOP_MANAGE_OTHERS = "matrixshop.admin.chestshop.manage.others"

    const val MENU_USE = "matrixshop.menu.use"

    const val SYSTEMSHOP_USE = "matrixshop.systemshop.use"

    const val PLAYERSHOP_USE = "matrixshop.playershop.use"
    const val PLAYERSHOP_SELL = "matrixshop.playershop.sell"
    const val PLAYERSHOP_MANAGE_OWN = "matrixshop.playershop.manage.own"

    const val GLOBALMARKET_USE = "matrixshop.globalmarket.use"
    const val GLOBALMARKET_SELL = "matrixshop.globalmarket.sell"
    const val GLOBALMARKET_MANAGE_OWN = "matrixshop.globalmarket.manage.own"

    const val AUCTION_USE = "matrixshop.auction.use"
    const val AUCTION_SELL = "matrixshop.auction.sell"
    const val AUCTION_BID = "matrixshop.auction.bid"
    const val AUCTION_BUYOUT = "matrixshop.auction.buyout"
    const val AUCTION_MANAGE_OWN = "matrixshop.auction.manage.own"
    const val AUCTION_CLAIM = "matrixshop.auction.claim"

    const val CART_USE = "matrixshop.cart.use"
    const val CART_CHECKOUT = "matrixshop.cart.checkout"
    const val CART_CLEAR = "matrixshop.cart.clear"

    const val RECORD_USE = "matrixshop.record.use"
    const val RECORD_DETAIL_SELF = "matrixshop.record.detail.self"
    const val RECORD_STATS_SELF = "matrixshop.record.stats.self"

    const val TRANSACTION_USE = "matrixshop.transaction.use"

    const val CHESTSHOP_USE = "matrixshop.chestshop.use"
    const val CHESTSHOP_CREATE = "matrixshop.chestshop.create"
    const val CHESTSHOP_MANAGE_OWN = "matrixshop.chestshop.manage.own"
}

object Permissions {

    fun has(sender: CommandSender, node: String): Boolean {
        return sender.hasPermission(node) || sender.hasPermission(PermissionNodes.ADMIN)
    }

    fun deny(sender: CommandSender): Boolean {
        Texts.send(
            sender,
            ConfigFiles.config.getString(
                "messages.no-permission",
                "&cYou do not have permission to use this command."
            ).orEmpty()
        )
        return false
    }

    fun require(sender: CommandSender, node: String): Boolean {
        if (has(sender, node)) {
            return true
        }
        return deny(sender)
    }
}
