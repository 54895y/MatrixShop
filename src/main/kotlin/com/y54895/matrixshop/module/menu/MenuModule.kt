package com.y54895.matrixshop.module.menu

import com.y54895.matrixshop.core.config.ConfigFiles
import com.y54895.matrixshop.core.menu.ConfiguredShopMenu
import com.y54895.matrixshop.core.menu.MenuRenderer
import com.y54895.matrixshop.core.menu.ShopMenuLoader
import com.y54895.matrixshop.core.menu.ShopMenuSelection
import com.y54895.matrixshop.core.module.MatrixModule
import com.y54895.matrixshop.core.text.Texts
import org.bukkit.entity.Player

object MenuModule : MatrixModule {

    override val id: String = "menu"
    override val displayName: String = "Menu"

    private lateinit var menus: LinkedHashMap<String, ConfiguredShopMenu>

    override fun isEnabled(): Boolean {
        return ConfigFiles.isModuleEnabled(id, true)
    }

    override fun reload() {
        if (!isEnabled()) {
            return
        }
        menus = ShopMenuLoader.load("Menu", "default.yml")
    }

    fun hasShopView(shopId: String?): Boolean {
        return ShopMenuLoader.contains(menus, shopId)
    }

    fun helpEntries(): List<ShopMenuSelection> {
        return ShopMenuLoader.helpEntries(menus)
    }

    fun allShopEntries(): List<ShopMenuSelection> {
        return ShopMenuLoader.allEntries(menus)
    }

    fun standaloneEntries(): List<ShopMenuSelection> {
        return ShopMenuLoader.standaloneEntries(menus)
    }

    fun open(player: Player, shopId: String? = null) {
        if (!isEnabled() || !::menus.isInitialized) {
            Texts.sendKey(player, "@menu.errors.module-disabled")
            return
        }
        val selected = ShopMenuLoader.resolve(menus, shopId)
        MenuRenderer.open(
            player = player,
            definition = selected.definition,
            placeholders = mapOf(
                "player" to player.name,
                "shop-id" to selected.id
            )
        )
    }
}
