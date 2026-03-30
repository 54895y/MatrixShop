package com.y54895.matrixshop.core.module

import com.y54895.matrixshop.core.config.ConfigFiles
import com.y54895.matrixshop.core.economy.EconomyModule
import com.y54895.matrixshop.module.auction.AuctionModule
import com.y54895.matrixshop.module.cart.CartModule
import com.y54895.matrixshop.module.chestshop.ChestShopModule
import com.y54895.matrixshop.module.globalmarket.GlobalMarketModule
import com.y54895.matrixshop.module.menu.MenuModule
import com.y54895.matrixshop.module.playershop.PlayerShopModule
import com.y54895.matrixshop.module.record.RecordModule
import com.y54895.matrixshop.module.systemshop.SystemShopModule
import com.y54895.matrixshop.module.transaction.TransactionModule

interface MatrixModule {
    val id: String
    val displayName: String
    fun isEnabled(): Boolean
    fun reload()
}

object ModuleRegistry {

    val economy = EconomyModule
    val auction = AuctionModule
    val chestShop = ChestShopModule
    val menu = MenuModule
    val systemShop = SystemShopModule
    val playerShop = PlayerShopModule
    val cart = CartModule
    val globalMarket = GlobalMarketModule
    val record = RecordModule
    val transaction = TransactionModule

    private val modules = listOf<MatrixModule>(
        economy,
        auction,
        chestShop,
        menu,
        systemShop,
        playerShop,
        cart,
        globalMarket,
        record,
        transaction
    )

    fun all(): List<MatrixModule> {
        return modules
    }

    fun reload() {
        modules.forEach { module ->
            if (module.isEnabled()) {
                module.reload()
            }
        }
    }

    fun enabledSummary(): String {
        return modules.joinToString(", ") {
            "${it.id}=${if (it.isEnabled()) "on" else "off"}"
        }
    }

    fun moduleStates(): List<String> {
        return modules.map {
            "&7- &f${it.displayName}: ${if (it.isEnabled()) "&aenabled" else "&cdisabled"}"
        }
    }

    fun isEnabled(moduleId: String): Boolean {
        return ConfigFiles.isModuleEnabled(moduleId)
    }
}
