package com.y54895.matrixshop.core.menu

import com.y54895.matrixshop.core.action.ActionContext
import com.y54895.matrixshop.core.action.ActionExecutor
import com.y54895.matrixshop.core.text.Texts
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import taboolib.common.platform.Awake
import taboolib.common.platform.event.SubscribeEvent
import java.io.File
import java.util.UUID

data class MenuDefinition(
    val title: List<String>,
    val layout: List<String>,
    val icons: Map<Char, MenuIcon>,
    val template: MenuTemplate = MenuTemplate("&f{name}", emptyList())
)

data class MenuIcon(
    val material: String,
    val name: String = " ",
    val lore: List<String> = emptyList(),
    val amount: Int = 1,
    val mode: String? = null,
    val actions: Map<String, List<String>> = emptyMap()
)

data class MenuTemplate(
    val name: String,
    val lore: List<String>
)

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
        val yaml = YamlConfiguration.loadConfiguration(file)
        val title = when {
            yaml.isList("Title") -> yaml.getStringList("Title")
            yaml.contains("Title") -> listOf(yaml.getString("Title").orEmpty())
            else -> listOf("&8MatrixShop")
        }
        val layout = yaml.getStringList("layout")
        val icons = loadIcons(yaml.getConfigurationSection("icons"))
        val templateSection = yaml.getConfigurationSection("template")
        val template = MenuTemplate(
            templateSection?.getString("name", "&f{name}").orEmpty(),
            templateSection?.getStringList("lore") ?: emptyList()
        )
        return MenuDefinition(title, layout, icons, template)
    }

    private fun loadIcons(section: ConfigurationSection?): Map<Char, MenuIcon> {
        if (section == null) {
            return emptyMap()
        }
        return section.getKeys(false).associate { key ->
            val child = section.getConfigurationSection(key)!!
            key.first() to MenuIcon(
                material = child.getString("material", "STONE").orEmpty(),
                name = child.getString("name", " ").orEmpty(),
                lore = child.getStringList("lore"),
                amount = child.getInt("amount", 1),
                mode = child.getString("mode"),
                actions = loadActions(child.getConfigurationSection("actions"))
            )
        }
    }

    private fun loadActions(section: ConfigurationSection?): Map<String, List<String>> {
        if (section == null) {
            return emptyMap()
        }
        return section.getKeys(false).associateWith { key ->
            when {
                section.isList(key) -> section.getStringList(key)
                section.contains(key) -> listOf(section.getString(key).orEmpty())
                else -> emptyList()
            }
        }
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
        val title = Texts.apply(definition.title.firstOrNull().orEmpty(), placeholders)
        val holder = MatrixMenuHolder(player.uniqueId, backAction)
        val inventory = Bukkit.createInventory(holder, definition.layout.size * 9, title)
        holder.backingInventory = inventory
        holder.closeHandler = closeHandler
        val goodsSlots = ArrayList<Int>()
        definition.layout.forEachIndexed { row, line ->
            line.toCharArray().forEachIndexed { column, symbol ->
                val slot = row * 9 + column
                val icon = definition.icons[symbol] ?: return@forEachIndexed
                if (!icon.mode.isNullOrBlank()) {
                    goodsSlots += slot
                    return@forEachIndexed
                }
                inventory.setItem(slot, buildIcon(icon, placeholders))
                if (icon.actions.isNotEmpty()) {
                    holder.handlers[slot] = { event ->
                        ActionExecutor.execute(
                            ActionContext(player, placeholders, holder.backAction),
                            icon.actions[actionKey(event.click)].orEmpty()
                        )
                    }
                }
            }
        }
        goodsRenderer?.invoke(holder, goodsSlots)
        player.openInventory(inventory)
    }

    fun buildIcon(icon: MenuIcon, placeholders: Map<String, String>): ItemStack {
        val material = Material.matchMaterial(icon.material) ?: Material.STONE
        val stack = ItemStack(material, icon.amount.coerceAtLeast(1))
        val meta = stack.itemMeta
        if (meta != null) {
            decorate(meta, Texts.apply(icon.name, placeholders), Texts.apply(icon.lore, placeholders))
            stack.itemMeta = meta
        }
        return stack
    }

    fun decorate(meta: ItemMeta, name: String, lore: List<String>) {
        meta.setDisplayName(name)
        meta.lore = lore
        meta.addItemFlags(*ItemFlag.values())
    }

    private fun actionKey(click: ClickType): String {
        return when (click) {
            ClickType.RIGHT -> "right"
            ClickType.SHIFT_LEFT -> "shift_left"
            ClickType.SHIFT_RIGHT -> "shift_right"
            ClickType.MIDDLE -> "middle"
            else -> "left"
        }
    }
}

@Awake
object MenuListener {

    @SubscribeEvent
    fun onClick(event: InventoryClickEvent) {
        val holder = event.inventory.holder as? MatrixMenuHolder ?: return
        val player = event.whoClicked as? Player ?: return
        if (!holder.isViewer(player)) {
            event.isCancelled = true
            return
        }
        if (event.clickedInventory == null || event.clickedInventory != event.view.topInventory) {
            return
        }
        event.isCancelled = true
        holder.handlers[event.rawSlot]?.invoke(event)
    }

    @SubscribeEvent
    fun onClose(event: InventoryCloseEvent) {
        val holder = event.inventory.holder as? MatrixMenuHolder ?: return
        holder.closeHandler?.invoke()
    }
}
