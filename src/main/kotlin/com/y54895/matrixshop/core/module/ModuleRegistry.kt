package com.y54895.matrixshop.core.module

import com.y54895.matrixshop.core.config.ConfigFiles
import com.y54895.matrixshop.module.cart.CartModule
import com.y54895.matrixshop.module.globalmarket.GlobalMarketModule
import com.y54895.matrixshop.module.playershop.PlayerShopModule
import com.y54895.matrixshop.module.stub.StubModule
import com.y54895.matrixshop.module.systemshop.SystemShopModule

interface MatrixModule {
    val id: String
    val displayName: String
    fun isEnabled(): Boolean
    fun reload()
}

object ModuleRegistry {

    val systemShop = SystemShopModule
    val playerShop = PlayerShopModule
    val cart = CartModule
    val globalMarket = GlobalMarketModule

    private val modules = listOf<MatrixModule>(
        systemShop,
        playerShop,
        cart,
        globalMarket,
        StubModule("auction", "Auction"),
        StubModule("chestshop", "ChestShop"),
        StubModule("transaction", "Transaction"),
        StubModule("record", "Record")
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
