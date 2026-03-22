package com.y54895.matrixshop.module.record

import com.y54895.matrixshop.core.config.ConfigFiles
import com.y54895.matrixshop.core.menu.ConfiguredShopMenu
import com.y54895.matrixshop.core.menu.MenuDefinition
import com.y54895.matrixshop.core.menu.MenuLoader
import com.y54895.matrixshop.core.menu.MenuRenderer
import com.y54895.matrixshop.core.menu.MatrixMenuHolder
import com.y54895.matrixshop.core.menu.ShopMenuLoader
import com.y54895.matrixshop.core.menu.ShopMenuSelection
import com.y54895.matrixshop.core.module.MatrixModule
import com.y54895.matrixshop.core.record.RecordAggregate
import com.y54895.matrixshop.core.record.RecordEntry
import com.y54895.matrixshop.core.record.RecordService
import com.y54895.matrixshop.core.text.Texts
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object RecordModule : MatrixModule {

    override val id: String = "record"
    override val displayName: String = "Record"

    private lateinit var settings: RecordSettings
    private lateinit var menus: RecordMenus
    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    override fun isEnabled(): Boolean {
        return ConfigFiles.isModuleEnabled(id, true)
    }

    override fun reload() {
        if (!isEnabled()) {
            return
        }
        val dataFolder = ConfigFiles.dataFolder()
        settings = loadSettings()
        menus = RecordMenus(
            recordViews = ShopMenuLoader.load("Record", "record.yml"),
            detail = MenuLoader.load(File(dataFolder, "Record/ui/detail.yml")),
            income = MenuLoader.load(File(dataFolder, "Record/ui/income.yml")),
            expense = MenuLoader.load(File(dataFolder, "Record/ui/expense.yml"))
        )
    }

    fun hasShopView(shopId: String?): Boolean {
        return ShopMenuLoader.contains(menus.recordViews, shopId)
    }

    fun helpEntries(): List<ShopMenuSelection> {
        return ShopMenuLoader.helpEntries(menus.recordViews)
    }

    fun allShopEntries(): List<ShopMenuSelection> {
        return ShopMenuLoader.allEntries(menus.recordViews)
    }

    fun standaloneEntries(): List<ShopMenuSelection> {
        return ShopMenuLoader.standaloneEntries(menus.recordViews)
    }

    fun open(player: Player, keyword: String? = null, page: Int = 1, shopId: String? = null) {
        if (!ensureReady(player)) {
            return
        }
        val selectedShop = ShopMenuLoader.resolve(menus.recordViews, shopId)
        val allEntries = visibleEntries(player, keyword)
        val goodsSlots = goodsSlots(selectedShop.definition)
        val maxPage = ((allEntries.size + goodsSlots.size - 1) / goodsSlots.size).coerceAtLeast(1)
        val currentPage = page.coerceIn(1, maxPage)
        val pageEntries = allEntries.drop((currentPage - 1) * goodsSlots.size).take(goodsSlots.size)
        val actorEntries = actorEntries(player)
        MenuRenderer.open(
            player = player,
            definition = selectedShop.definition,
            placeholders = mapOf(
                "shop-id" to selectedShop.id,
                "page" to currentPage.toString(),
                "max-page" to maxPage.toString(),
                "size" to allEntries.size.toString(),
                "keyword" to (keyword?.ifBlank { "all" } ?: "all"),
                "income-total" to trimDouble(actorEntries.filter { it.moneyChange > 0 }.sumOf { it.moneyChange }),
                "expense-total" to trimDouble(actorEntries.filter { it.moneyChange < 0 }.sumOf { -it.moneyChange })
            ),
            goodsRenderer = { holder, slots ->
                renderEntries(player, holder, pageEntries, slots, currentPage, keyword, selectedShop.id)
                wireRecordControls(player, holder, selectedShop.definition, currentPage, maxPage, keyword, selectedShop.id)
            }
        )
    }

    fun openDetail(player: Player, recordId: String, keyword: String? = null, page: Int = 1, shopId: String? = null) {
        if (!ensureReady(player)) {
            return
        }
        val entry = visibleEntries(player, null).firstOrNull { it.id.equals(recordId, true) }
        if (entry == null) {
            Texts.send(player, "&cRecord not found or not visible: &f$recordId")
            return
        }
        MenuRenderer.open(
            player = player,
            definition = menus.detail,
            placeholders = mapOf(
                "record-id" to entry.id,
                "module" to moduleDisplay(entry.module),
                "type" to entry.type,
                "time" to timeFormatter.format(Instant.ofEpochMilli(entry.createdAt))
            ),
            backAction = { open(player, keyword, page, shopId) },
            goodsRenderer = { holder, _ ->
                renderDetail(holder, entry)
                wireDetailControls(player, holder, keyword, page, shopId)
            }
        )
    }

    fun openIncome(player: Player, page: Int = 1, shopId: String? = null) {
        openStats(player, page, positive = true, shopId = shopId)
    }

    fun openExpense(player: Player, page: Int = 1, shopId: String? = null) {
        openStats(player, page, positive = false, shopId = shopId)
    }

    private fun openStats(player: Player, page: Int, positive: Boolean, shopId: String?) {
        if (!ensureReady(player)) {
            return
        }
        val definition = if (positive) menus.income else menus.expense
        val allStats = aggregates(player, positive)
        val goodsSlots = goodsSlots(definition)
        val maxPage = ((allStats.size + goodsSlots.size - 1) / goodsSlots.size).coerceAtLeast(1)
        val currentPage = page.coerceIn(1, maxPage)
        val pageEntries = allStats.drop((currentPage - 1) * goodsSlots.size).take(goodsSlots.size)
        val total = allStats.sumOf { it.total }
        MenuRenderer.open(
            player = player,
            definition = definition,
            placeholders = mapOf(
                "page" to currentPage.toString(),
                "max-page" to maxPage.toString(),
                "total" to trimDouble(total),
                "count" to allStats.sumOf { it.count }.toString()
            ),
            backAction = { open(player, shopId = shopId) },
            goodsRenderer = { holder, slots ->
                renderStats(holder, slots, pageEntries, positive)
                wireStatsControls(player, holder, definition, currentPage, maxPage, positive, shopId)
            }
        )
    }

    private fun renderEntries(
        player: Player,
        holder: MatrixMenuHolder,
        entries: List<RecordEntry>,
        slots: List<Int>,
        currentPage: Int,
        keyword: String?,
        shopId: String
    ) {
        entries.forEachIndexed { index, entry ->
            val slot = slots[index]
            val item = ItemStack(recordMaterial(entry)).apply {
                itemMeta = itemMeta?.apply {
                    MenuRenderer.decorate(
                        this,
                        Texts.color("&f${moduleDisplay(entry.module)} &7/ &b${entry.type}"),
                        buildListLore(entry, player)
                    )
                }
            }
            holder.backingInventory.setItem(slot, item)
            holder.handlers[slot] = {
                openDetail(player, entry.id, keyword, currentPage, shopId)
            }
        }
    }

    private fun renderDetail(holder: MatrixMenuHolder, entry: RecordEntry) {
        val slot = buttonSlot(menus.detail, 'I') ?: return
        val item = ItemStack(recordMaterial(entry)).apply {
            itemMeta = itemMeta?.apply {
                MenuRenderer.decorate(
                    this,
                    Texts.color("&f${moduleDisplay(entry.module)} &7/ &b${entry.type}"),
                    buildDetailLore(entry)
                )
            }
        }
        holder.backingInventory.setItem(slot, item)
    }

    private fun renderStats(holder: MatrixMenuHolder, slots: List<Int>, entries: List<RecordAggregate>, positive: Boolean) {
        entries.forEachIndexed { index, aggregate ->
            val slot = slots[index]
            val item = ItemStack(if (positive) Material.EMERALD else Material.REDSTONE).apply {
                itemMeta = itemMeta?.apply {
                    MenuRenderer.decorate(
                        this,
                        Texts.color("&f${moduleDisplay(aggregate.module)}"),
                        listOf(
                            Texts.color("&7Entries: &f${aggregate.count}"),
                            Texts.color("&7Total: ${if (positive) "&a" else "&c"}${trimDouble(aggregate.total)}")
                        )
                    )
                }
            }
            holder.backingInventory.setItem(slot, item)
        }
    }

    private fun wireRecordControls(player: Player, holder: MatrixMenuHolder, definition: MenuDefinition, currentPage: Int, maxPage: Int, keyword: String?, shopId: String) {
        buttonSlot(definition, 'P')?.let { slot ->
            holder.handlers[slot] = { open(player, keyword, (currentPage - 1).coerceAtLeast(1), shopId) }
        }
        buttonSlot(definition, 'N')?.let { slot ->
            holder.handlers[slot] = { open(player, keyword, (currentPage + 1).coerceAtMost(maxPage), shopId) }
        }
        buttonSlot(definition, 'I')?.let { slot ->
            holder.handlers[slot] = { openIncome(player, shopId = shopId) }
        }
        buttonSlot(definition, 'E')?.let { slot ->
            holder.handlers[slot] = { openExpense(player, shopId = shopId) }
        }
    }

    private fun wireDetailControls(player: Player, holder: MatrixMenuHolder, keyword: String?, page: Int, shopId: String?) {
        buttonSlot(menus.detail, 'L')?.let { slot ->
            holder.handlers[slot] = { open(player, keyword, page, shopId) }
        }
        buttonSlot(menus.detail, 'I')?.let { slot ->
            holder.handlers.remove(slot)
        }
        buttonSlot(menus.detail, 'S')?.let { slot ->
            holder.handlers[slot] = { openIncome(player, shopId = shopId) }
        }
        buttonSlot(menus.detail, 'E')?.let { slot ->
            holder.handlers[slot] = { openExpense(player, shopId = shopId) }
        }
    }

    private fun wireStatsControls(
        player: Player,
        holder: MatrixMenuHolder,
        definition: MenuDefinition,
        currentPage: Int,
        maxPage: Int,
        positive: Boolean,
        shopId: String?
    ) {
        buttonSlot(definition, 'P')?.let { slot ->
            holder.handlers[slot] = { openStats(player, (currentPage - 1).coerceAtLeast(1), positive, shopId) }
        }
        buttonSlot(definition, 'N')?.let { slot ->
            holder.handlers[slot] = { openStats(player, (currentPage + 1).coerceAtMost(maxPage), positive, shopId) }
        }
        buttonSlot(definition, 'L')?.let { slot ->
            holder.handlers[slot] = { open(player, shopId = shopId) }
        }
        buttonSlot(definition, 'X')?.let { slot ->
            holder.handlers[slot] = { openStats(player, 1, !positive, shopId) }
        }
    }

    private fun buildListLore(entry: RecordEntry, player: Player): List<String> {
        val lore = mutableListOf(
            Texts.color("&7Time: &f${timeFormatter.format(Instant.ofEpochMilli(entry.createdAt))}"),
            Texts.color("&7Actor: &f${entry.actor}")
        )
        if (entry.target.isNotBlank()) {
            lore += Texts.color("&7Target: &f${entry.target}")
        }
        if (entry.moneyChange != 0.0) {
            val prefix = if (entry.moneyChange > 0) "&a+" else "&c"
            lore += Texts.color("&7Money: $prefix${trimDouble(entry.moneyChange)}")
        }
        lore += detailLines(entry.detail, 3)
        if (entry.adminReason.isNotBlank() && (settings.showAdminReason || player.hasPermission("matrixshop.admin.record.view.others"))) {
            lore += Texts.color("&7Reason: &f${entry.adminReason}")
        }
        lore += Texts.color("&7ID: &f${entry.id}")
        lore += Texts.color("&eLeft click to view detail")
        return lore
    }

    private fun buildDetailLore(entry: RecordEntry): List<String> {
        val lore = mutableListOf(
            Texts.color("&7ID: &f${entry.id}"),
            Texts.color("&7Module: &f${moduleDisplay(entry.module)}"),
            Texts.color("&7Type: &f${entry.type}"),
            Texts.color("&7Time: &f${timeFormatter.format(Instant.ofEpochMilli(entry.createdAt))}"),
            Texts.color("&7Actor: &f${entry.actor}")
        )
        if (entry.target.isNotBlank()) {
            lore += Texts.color("&7Target: &f${entry.target}")
        }
        if (entry.moneyChange != 0.0) {
            val prefix = if (entry.moneyChange > 0) "&a+" else "&c"
            lore += Texts.color("&7Money: $prefix${trimDouble(entry.moneyChange)}")
        }
        lore += detailLines(entry.detail, 6)
        if (entry.note.isNotBlank()) {
            lore += Texts.color("&7Note: &f${entry.note}")
        }
        if (entry.adminReason.isNotBlank()) {
            lore += Texts.color("&7Reason: &f${entry.adminReason}")
        }
        return lore
    }

    private fun detailLines(detail: String, maxLines: Int): List<String> {
        if (detail.isBlank()) {
            return emptyList()
        }
        return detail.split(';')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(maxLines)
            .map { Texts.color("&7- &f$it") }
    }

    private fun visibleEntries(player: Player, keyword: String?): List<RecordEntry> {
        val visible = if (player.hasPermission("matrixshop.admin.record.view.others")) {
            RecordService.readAll()
        } else {
            RecordService.readAll().filter { it.involves(player.name) }
        }
        return visible.filter { keyword.isNullOrBlank() || it.matches(keyword) }
            .sortedByDescending { it.createdAt }
    }

    private fun actorEntries(player: Player): List<RecordEntry> {
        return RecordService.readAll().filter { it.actor.equals(player.name, true) }
    }

    private fun aggregates(player: Player, positive: Boolean): List<RecordAggregate> {
        return actorEntries(player)
            .filter { if (positive) it.moneyChange > 0 else it.moneyChange < 0 }
            .groupBy { it.module }
            .map { (module, entries) ->
                RecordAggregate(
                    module = module,
                    total = entries.sumOf { kotlin.math.abs(it.moneyChange) },
                    count = entries.size
                )
            }
            .sortedByDescending { it.total }
    }

    private fun ensureReady(player: Player): Boolean {
        if (!isEnabled() || !::menus.isInitialized) {
            Texts.send(player, "&cRecord module is disabled.")
            return false
        }
        return true
    }

    private fun loadSettings(): RecordSettings {
        val yaml = YamlConfiguration.loadConfiguration(File(ConfigFiles.dataFolder(), "Record/settings.yml"))
        return RecordSettings(
            showAdminReason = yaml.getBoolean("View.Player.Show-Admin-Reason", true)
        )
    }

    private fun goodsSlots(definition: MenuDefinition): List<Int> {
        val slots = ArrayList<Int>()
        definition.layout.forEachIndexed { row, line ->
            line.forEachIndexed { column, char ->
                val icon = definition.icons[char] ?: return@forEachIndexed
                if (icon.mode.equals("goods", true)) {
                    slots += row * 9 + column
                }
            }
        }
        return slots
    }

    private fun buttonSlot(definition: MenuDefinition, symbol: Char): Int? {
        definition.layout.forEachIndexed { row, line ->
            line.forEachIndexed { column, char ->
                if (char == symbol) {
                    return row * 9 + column
                }
            }
        }
        return null
    }

    private fun recordMaterial(entry: RecordEntry): Material {
        return when {
            entry.moneyChange > 0 -> Material.EMERALD
            entry.moneyChange < 0 -> Material.REDSTONE
            entry.module.equals("cart", true) -> Material.CHEST
            else -> Material.PAPER
        }
    }

    private fun moduleDisplay(module: String): String {
        return when (module.lowercase()) {
            "system_shop" -> "SystemShop"
            "player_shop" -> "PlayerShop"
            "global_market" -> "GlobalMarket"
            "cart" -> "Cart"
            "auction" -> "Auction"
            "transaction" -> "Transaction"
            "chestshop" -> "ChestShop"
            else -> module
        }
    }

    private fun trimDouble(value: Double): String {
        return if (value % 1.0 == 0.0) value.toInt().toString() else "%.2f".format(value)
    }
}

private data class RecordSettings(
    val showAdminReason: Boolean
)

private data class RecordMenus(
    val recordViews: LinkedHashMap<String, ConfiguredShopMenu>,
    val detail: MenuDefinition,
    val income: MenuDefinition,
    val expense: MenuDefinition
)
