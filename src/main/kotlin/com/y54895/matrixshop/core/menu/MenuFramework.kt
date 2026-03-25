package com.y54895.matrixshop.core.menu

import com.y54895.matrixlib.api.menu.MenuDefinition as SharedMenuDefinition
import com.y54895.matrixlib.api.menu.MenuIcon as SharedMenuIcon
import com.y54895.matrixlib.api.menu.MenuLoader as SharedMenuLoader
import com.y54895.matrixlib.api.menu.MenuRenderer as SharedMenuRenderer
import com.y54895.matrixlib.api.menu.MenuTemplate as SharedMenuTemplate
import java.io.File
import java.util.UUID
import java.util.function.BiConsumer
import java.util.function.Consumer
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder

typealias MenuDefinition = SharedMenuDefinition
typealias MenuIcon = SharedMenuIcon
typealias MenuTemplate = SharedMenuTemplate

class MatrixMenuHolder(
    private val owner: UUID,
    val backAction: (() -> Unit)? = null
) : InventoryHolder {

    lateinit var backingInventory: Inventory
    val handlers = HashMap<Int, (InventoryClickEvent) -> Unit>()
    var closeHandler: (() -> Unit)? = null

    override fun getInventory(): Inventory {
        return backingInventory
    }

    fun isViewer(player: Player): Boolean {
        return owner == player.uniqueId
    }
}

object MenuLoader {

    fun load(file: File): MenuDefinition {
        return SharedMenuLoader.load(file, defaultTitle = listOf("&8MatrixShop"))
    }
}

object MenuRenderer {

    fun open(
        player: Player,
        definition: MenuDefinition,
        placeholders: Map<String, String>,
        backAction: (() -> Unit)? = null,
        goodsRenderer: ((holder: MatrixMenuHolder, goodsSlots: List<Int>) -> Unit)? = null,
        closeHandler: (() -> Unit)? = null
    ) {
        SharedMenuRenderer.open(
            player = player,
            definition = definition,
            placeholders = placeholders,
            backAction = backAction?.let { Runnable { it() } },
            goodsRenderer = BiConsumer { sharedHolder, goodsSlots ->
                val localHolder = MatrixMenuHolder(player.uniqueId, backAction)
                localHolder.backingInventory = sharedHolder.backingInventory
                localHolder.closeHandler = closeHandler
                goodsRenderer?.invoke(localHolder, goodsSlots)
                localHolder.handlers.forEach { (slot, handler) ->
                    sharedHolder.handlers[slot] = Consumer<InventoryClickEvent> { event ->
                        handler(event)
                    }
                }
                sharedHolder.closeHandler = localHolder.closeHandler?.let { Runnable { it() } }
            },
            closeHandler = closeHandler?.let { Runnable { it() } }
        )
    }

    fun buildIcon(icon: MenuIcon, placeholders: Map<String, String>): org.bukkit.inventory.ItemStack {
        return SharedMenuRenderer.buildIcon(icon, placeholders)
    }

    fun decorate(meta: org.bukkit.inventory.meta.ItemMeta, name: String, lore: List<String>) {
        SharedMenuRenderer.decorate(meta, name, lore)
    }
}
