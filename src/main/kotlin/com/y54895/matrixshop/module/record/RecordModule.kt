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
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

object RecordModule : MatrixModule {

    override val id: String = "record"
    override val displayName: String = "Record"

    private lateinit var settings: RecordSettings
    private lateinit var menus: RecordMenus
    private var viewConfigs: Map<String, RecordViewConfig> = emptyMap()
    private val viewStates = HashMap<UUID, RecordViewState>()
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
        viewConfigs = loadViewConfigs()
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

    fun currentKeyword(player: Player, shopId: String? = null): String? {
        return currentState(player, resolvedShopId(shopId)).keyword
    }

    fun currentFilter(player: Player, shopId: String? = null): String? {
        return currentState(player, resolvedShopId(shopId)).moduleFilter
    }

    fun open(player: Player, keyword: String? = null, page: Int = 1, shopId: String? = null, moduleFilter: String? = null) {
        if (!ensureReady(player)) {
            return
        }
        val selectedShop = ShopMenuLoader.resolve(menus.recordViews, shopId)
        val normalizedFilter = normalizeModuleFilter(moduleFilter)
        rememberState(player, selectedShop.id, keyword, normalizedFilter)
        val allEntries = visibleEntries(player, keyword, selectedShop.id, normalizedFilter)
        val goodsSlots = goodsSlots(selectedShop.definition)
        val maxPage = ((allEntries.size + goodsSlots.size - 1) / goodsSlots.size).coerceAtLeast(1)
        val currentPage = page.coerceIn(1, maxPage)
        val pageEntries = allEntries.drop((currentPage - 1) * goodsSlots.size).take(goodsSlots.size)
        val actorEntries = actorEntries(player, selectedShop.id, normalizedFilter)
        MenuRenderer.open(
            player = player,
            definition = selectedShop.definition,
            placeholders = mapOf(
                "shop-id" to selectedShop.id,
                "page" to currentPage.toString(),
                "max-page" to maxPage.toString(),
                "size" to allEntries.size.toString(),
                "keyword" to (keyword?.ifBlank { "all" } ?: "all"),
                "module-filter" to (normalizedFilter ?: "all"),
                "income-total" to trimDouble(actorEntries.filter { it.moneyChange > 0 }.sumOf { it.moneyChange }),
                "expense-total" to trimDouble(actorEntries.filter { it.moneyChange < 0 }.sumOf { -it.moneyChange })
            ),
            goodsRenderer = { holder, slots ->
                renderEntries(player, holder, selectedShop.definition, pageEntries, slots, currentPage, keyword, normalizedFilter, selectedShop.id)
                wireRecordControls(player, holder, selectedShop.definition, currentPage, maxPage, keyword, normalizedFilter, selectedShop.id)
            }
        )
    }

    fun openDetail(player: Player, recordId: String, keyword: String? = null, page: Int = 1, shopId: String? = null, moduleFilter: String? = null) {
        if (!ensureReady(player)) {
            return
        }
        val entry = visibleEntries(player, null, resolvedShopId(shopId), normalizeModuleFilter(moduleFilter))
            .firstOrNull { it.id.equals(recordId, true) }
        if (entry == null) {
            Texts.sendKey(player, "@record.errors.not-found", mapOf("record" to recordId))
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
            backAction = { open(player, keyword, page, shopId, moduleFilter) },
            goodsRenderer = { holder, _ ->
                renderDetail(holder, entry)
                wireDetailControls(player, holder, keyword, page, shopId, moduleFilter)
            }
        )
    }

    fun openIncome(player: Player, page: Int = 1, shopId: String? = null, keyword: String? = null, moduleFilter: String? = null) {
        openStats(player, page, positive = true, shopId = shopId, keyword = keyword, moduleFilter = moduleFilter)
    }

    fun openExpense(player: Player, page: Int = 1, shopId: String? = null, keyword: String? = null, moduleFilter: String? = null) {
        openStats(player, page, positive = false, shopId = shopId, keyword = keyword, moduleFilter = moduleFilter)
    }

    fun applyFilter(player: Player, moduleFilter: String?, shopId: String? = null, keyword: String? = null) {
        val selectedShop = ShopMenuLoader.resolve(menus.recordViews, shopId)
        val normalized = normalizeModuleFilter(moduleFilter)
        if (normalized != null && normalized !in availableModules(player, selectedShop.id)) {
            Texts.sendKey(player, "@record.errors.filter-module-unknown", mapOf("module" to normalized))
            return
        }
        open(player, keyword, 1, selectedShop.id, normalized)
    }

    fun cycleFilter(player: Player, shopId: String? = null) {
        val selectedShop = ShopMenuLoader.resolve(menus.recordViews, shopId)
        val modules = availableModules(player, selectedShop.id)
        if (modules.isEmpty()) {
            Texts.sendKey(player, "@record.errors.no-filter-modules")
            return
        }
        val state = currentState(player, selectedShop.id)
        val next = when (val current = normalizeModuleFilter(state.moduleFilter)) {
            null -> modules.first()
            else -> modules.getOrNull(modules.indexOf(current) + 1)
        }
        val applied = next ?: ""
        val display = if (applied.isBlank()) Texts.tr("@commands.words.all") else applied
        applyFilter(player, applied, selectedShop.id, state.keyword)
        Texts.sendKey(player, "@record.success.filter-applied", mapOf("module" to display))
    }

    private fun openStats(player: Player, page: Int, positive: Boolean, shopId: String?, keyword: String?, moduleFilter: String?) {
        if (!ensureReady(player)) {
            return
        }
        val definition = if (positive) menus.income else menus.expense
        val selectedShop = ShopMenuLoader.resolve(menus.recordViews, shopId)
        val normalizedFilter = normalizeModuleFilter(moduleFilter)
        val allStats = aggregates(player, positive, selectedShop.id, normalizedFilter)
        val goodsSlots = goodsSlots(definition)
        val maxPage = ((allStats.size + goodsSlots.size - 1) / goodsSlots.size).coerceAtLeast(1)
        val currentPage = page.coerceIn(1, maxPage)
        val pageEntries = allStats.drop((currentPage - 1) * goodsSlots.size).take(goodsSlots.size)
        val total = allStats.sumOf { it.total }
        MenuRenderer.open(
            player = player,
            definition = definition,
            placeholders = mapOf(
                "shop-id" to selectedShop.id,
                "page" to currentPage.toString(),
                "max-page" to maxPage.toString(),
                "total" to trimDouble(total),
                "count" to allStats.sumOf { it.count }.toString(),
                "module-filter" to (normalizedFilter ?: "all")
            ),
            backAction = { open(player, keyword, shopId = selectedShop.id, moduleFilter = normalizedFilter) },
            goodsRenderer = { holder, slots ->
                renderStats(holder, slots, pageEntries, positive)
                wireStatsControls(player, holder, definition, currentPage, maxPage, positive, selectedShop.id, keyword, normalizedFilter)
            }
        )
    }

    private fun renderEntries(
        player: Player,
        holder: MatrixMenuHolder,
        definition: MenuDefinition,
        entries: List<RecordEntry>,
        slots: List<Int>,
        currentPage: Int,
        keyword: String?,
        moduleFilter: String?,
        shopId: String
    ) {
        entries.forEachIndexed { index, entry ->
            val slot = slots[index]
            val item = buildRecordListItem(entry, player, definition)
            holder.backingInventory.setItem(slot, item)
            holder.handlers[slot] = {
                openDetail(player, entry.id, keyword, currentPage, shopId, moduleFilter)
            }
        }
    }

    private fun renderDetail(holder: MatrixMenuHolder, entry: RecordEntry) {
        val slot = buttonSlot(menus.detail, 'I') ?: return
        val item = ItemStack(recordMaterial(entry)).apply {
            itemMeta = itemMeta?.apply {
                MenuRenderer.decorate(
                    this,
                    Texts.colorKey("@record.lore.summary-title", mapOf("module" to moduleDisplay(entry.module), "type" to entry.type)),
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
                    Texts.colorKey("@record.lore.stats-title", mapOf("module" to moduleDisplay(aggregate.module))),
                    listOf(
                        Texts.colorKey("@record.lore.entries", mapOf("count" to aggregate.count.toString())),
                        Texts.colorKey("@record.lore.total", mapOf("color" to if (positive) "&a" else "&c", "total" to trimDouble(aggregate.total)))
                    )
                )
            }
            }
            holder.backingInventory.setItem(slot, item)
        }
    }

    private fun wireRecordControls(
        player: Player,
        holder: MatrixMenuHolder,
        definition: MenuDefinition,
        currentPage: Int,
        maxPage: Int,
        keyword: String?,
        moduleFilter: String?,
        shopId: String
    ) {
        buttonSlot(definition, 'P')?.let { slot ->
            holder.handlers[slot] = { open(player, keyword, (currentPage - 1).coerceAtLeast(1), shopId, moduleFilter) }
        }
        buttonSlot(definition, 'N')?.let { slot ->
            holder.handlers[slot] = { open(player, keyword, (currentPage + 1).coerceAtMost(maxPage), shopId, moduleFilter) }
        }
        buttonSlot(definition, 'F')?.let { slot ->
            holder.handlers[slot] = { cycleFilter(player, shopId) }
        }
        buttonSlot(definition, 'I')?.let { slot ->
            holder.handlers[slot] = { openIncome(player, shopId = shopId, keyword = keyword, moduleFilter = moduleFilter) }
        }
        buttonSlot(definition, 'E')?.let { slot ->
            holder.handlers[slot] = { openExpense(player, shopId = shopId, keyword = keyword, moduleFilter = moduleFilter) }
        }
    }

    private fun wireDetailControls(player: Player, holder: MatrixMenuHolder, keyword: String?, page: Int, shopId: String?, moduleFilter: String?) {
        buttonSlot(menus.detail, 'L')?.let { slot ->
            holder.handlers[slot] = { open(player, keyword, page, shopId, moduleFilter) }
        }
        buttonSlot(menus.detail, 'I')?.let { slot ->
            holder.handlers.remove(slot)
        }
        buttonSlot(menus.detail, 'S')?.let { slot ->
            holder.handlers[slot] = { openIncome(player, shopId = shopId, keyword = keyword, moduleFilter = moduleFilter) }
        }
        buttonSlot(menus.detail, 'E')?.let { slot ->
            holder.handlers[slot] = { openExpense(player, shopId = shopId, keyword = keyword, moduleFilter = moduleFilter) }
        }
    }

    private fun wireStatsControls(
        player: Player,
        holder: MatrixMenuHolder,
        definition: MenuDefinition,
        currentPage: Int,
        maxPage: Int,
        positive: Boolean,
        shopId: String?,
        keyword: String?,
        moduleFilter: String?
    ) {
        buttonSlot(definition, 'P')?.let { slot ->
            holder.handlers[slot] = { openStats(player, (currentPage - 1).coerceAtLeast(1), positive, shopId, keyword, moduleFilter) }
        }
        buttonSlot(definition, 'N')?.let { slot ->
            holder.handlers[slot] = { openStats(player, (currentPage + 1).coerceAtMost(maxPage), positive, shopId, keyword, moduleFilter) }
        }
        buttonSlot(definition, 'L')?.let { slot ->
            holder.handlers[slot] = { open(player, keyword, shopId = shopId, moduleFilter = moduleFilter) }
        }
        buttonSlot(definition, 'X')?.let { slot ->
            holder.handlers[slot] = { openStats(player, 1, !positive, shopId, keyword, moduleFilter) }
        }
    }

    private fun buildListLore(entry: RecordEntry, player: Player): List<String> {
        val lore = mutableListOf(
            Texts.colorKey("@record.lore.time", mapOf("time" to timeFormatter.format(Instant.ofEpochMilli(entry.createdAt)))),
            Texts.colorKey("@record.lore.actor", mapOf("actor" to entry.actor))
        )
        if (entry.target.isNotBlank()) {
            lore += Texts.colorKey("@record.lore.target", mapOf("target" to entry.target))
        }
        if (entry.moneyChange != 0.0) {
            val prefix = if (entry.moneyChange > 0) "&a+" else "&c"
            lore += Texts.colorKey("@record.lore.money", mapOf("prefix" to prefix, "money" to trimDouble(entry.moneyChange)))
        }
        lore += detailLines(entry.detail, 3)
        if (entry.adminReason.isNotBlank() && (settings.showAdminReason || player.hasPermission("matrixshop.admin.record.view.others"))) {
            lore += Texts.colorKey("@record.lore.reason", mapOf("reason" to entry.adminReason))
        }
        lore += Texts.colorKey("@record.lore.id", mapOf("id" to entry.id))
        lore += Texts.colorKey("@record.lore.left-detail")
        return lore
    }

    private fun buildDetailLore(entry: RecordEntry): List<String> {
        val lore = mutableListOf(
            Texts.colorKey("@record.lore.id", mapOf("id" to entry.id)),
            Texts.colorKey("@record.lore.module", mapOf("module" to moduleDisplay(entry.module))),
            Texts.colorKey("@record.lore.type", mapOf("type" to entry.type)),
            Texts.colorKey("@record.lore.time", mapOf("time" to timeFormatter.format(Instant.ofEpochMilli(entry.createdAt)))),
            Texts.colorKey("@record.lore.actor", mapOf("actor" to entry.actor))
        )
        if (entry.target.isNotBlank()) {
            lore += Texts.colorKey("@record.lore.target", mapOf("target" to entry.target))
        }
        if (entry.moneyChange != 0.0) {
            val prefix = if (entry.moneyChange > 0) "&a+" else "&c"
            lore += Texts.colorKey("@record.lore.money", mapOf("prefix" to prefix, "money" to trimDouble(entry.moneyChange)))
        }
        lore += detailLines(entry.detail, 6)
        if (entry.note.isNotBlank()) {
            lore += Texts.colorKey("@record.lore.note", mapOf("note" to entry.note))
        }
        if (entry.adminReason.isNotBlank()) {
            lore += Texts.colorKey("@record.lore.reason", mapOf("reason" to entry.adminReason))
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
            .map { Texts.colorKey("@record.lore.detail-line", mapOf("line" to it)) }
    }

    private fun visibleEntries(player: Player, keyword: String?, shopId: String, moduleFilter: String?): List<RecordEntry> {
        val visible = if (player.hasPermission("matrixshop.admin.record.view.others")) {
            RecordService.readAll()
        } else {
            RecordService.readAll().filter { it.involves(player.name) }
        }
        val config = viewConfigs[normalizeKey(shopId)] ?: RecordViewConfig()
        return visible
            .filter { config.allowedModules.isEmpty() || config.allowedModules.contains(it.module.lowercase()) }
            .filter { moduleFilter.isNullOrBlank() || it.module.equals(moduleFilter, true) }
            .filter { keyword.isNullOrBlank() || it.matches(keyword) }
            .sortedByDescending { it.createdAt }
    }

    private fun actorEntries(player: Player, shopId: String, moduleFilter: String?): List<RecordEntry> {
        val config = viewConfigs[normalizeKey(shopId)] ?: RecordViewConfig()
        return RecordService.readAll()
            .filter { it.actor.equals(player.name, true) }
            .filter { config.allowedModules.isEmpty() || config.allowedModules.contains(it.module.lowercase()) }
            .filter { moduleFilter.isNullOrBlank() || it.module.equals(moduleFilter, true) }
    }

    private fun aggregates(player: Player, positive: Boolean, shopId: String, moduleFilter: String?): List<RecordAggregate> {
        return actorEntries(player, shopId, moduleFilter)
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
            Texts.sendKey(player, "@record.errors.module-disabled")
            return false
        }
        return true
    }

    private fun loadSettings(): RecordSettings {
        val yaml = YamlConfiguration.loadConfiguration(File(ConfigFiles.dataFolder(), "Record/settings.yml"))
        val aliases = linkedMapOf<String, String>()
        yaml.getConfigurationSection("Module-Alias")?.getKeys(false)?.forEach { key ->
            aliases[key.lowercase()] = yaml.getString("Module-Alias.$key").orEmpty()
        }
        return RecordSettings(
            showAdminReason = yaml.getBoolean("View.Player.Show-Admin-Reason", true),
            moduleAliases = aliases
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
        settings.moduleAliases[module.lowercase()]?.takeIf(String::isNotBlank)?.let { return it }
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

    private fun buildRecordListItem(entry: RecordEntry, player: Player, definition: MenuDefinition): ItemStack {
        val placeholders = mapOf(
            "id" to entry.id,
            "module" to moduleDisplay(entry.module),
            "type" to entry.type,
            "status" to if (entry.moneyChange >= 0.0) "income" else "expense",
            "time" to timeFormatter.format(Instant.ofEpochMilli(entry.createdAt)),
            "actor" to entry.actor,
            "target" to entry.target,
            "money" to trimDouble(kotlin.math.abs(entry.moneyChange)),
            "tax" to "0",
            "note" to entry.note,
            "admin-reason" to entry.adminReason,
            "admin_reason" to entry.adminReason
        )
        val item = ItemStack(recordMaterial(entry))
        val meta = item.itemMeta ?: return item
        val name = if (definition.template.name.isNotBlank()) {
            Texts.apply(definition.template.name, placeholders)
        } else {
            Texts.colorKey("@record.lore.summary-title", mapOf("module" to moduleDisplay(entry.module), "type" to entry.type))
        }
        val lore = if (definition.template.lore.isNotEmpty()) {
            Texts.apply(definition.template.lore, placeholders)
        } else {
            buildListLore(entry, player)
        }
        MenuRenderer.decorate(meta, name, lore)
        item.itemMeta = meta
        return item
    }

    private fun availableModules(player: Player, shopId: String): List<String> {
        val config = viewConfigs[normalizeKey(shopId)] ?: RecordViewConfig()
        val visible = if (player.hasPermission("matrixshop.admin.record.view.others")) {
            RecordService.readAll()
        } else {
            RecordService.readAll().filter { it.involves(player.name) }
        }
        return visible
            .filter { config.allowedModules.isEmpty() || config.allowedModules.contains(it.module.lowercase()) }
            .map { it.module.lowercase() }
            .distinct()
            .sorted()
    }

    private fun loadViewConfigs(): Map<String, RecordViewConfig> {
        val folder = File(ConfigFiles.dataFolder(), "Record/shops")
        if (!folder.exists()) {
            return emptyMap()
        }
        return folder.listFiles { file -> file.isFile && file.extension.equals("yml", true) }
            .orEmpty()
            .associate { file ->
                val yaml = YamlConfiguration.loadConfiguration(file)
                normalizeKey(file.nameWithoutExtension) to RecordViewConfig(
                    allowedModules = readModuleSet(
                        yaml,
                        "Options.Modules",
                        "Options.View.Modules",
                        "View.Modules",
                        "Filters.Modules"
                    )
                )
            }
    }

    private fun readModuleSet(yaml: YamlConfiguration, vararg paths: String): Set<String> {
        paths.forEach { path ->
            if (yaml.isList(path)) {
                return yaml.getStringList(path)
                    .mapNotNull { it?.trim()?.takeIf(String::isNotBlank)?.lowercase() }
                    .toSet()
            }
        }
        return emptySet()
    }

    private fun normalizeModuleFilter(value: String?): String? {
        return value?.trim()
            ?.takeIf(String::isNotBlank)
            ?.lowercase()
            ?.takeUnless { it == "all" }
    }

    private fun normalizeKey(value: String?): String {
        return value?.trim()?.ifBlank { "default" }?.lowercase() ?: "default"
    }

    private fun resolvedShopId(shopId: String?): String {
        return ShopMenuLoader.resolve(menus.recordViews, shopId).id
    }

    private fun rememberState(player: Player, shopId: String, keyword: String?, moduleFilter: String?) {
        viewStates[player.uniqueId] = RecordViewState(shopId = shopId, keyword = keyword, moduleFilter = moduleFilter)
    }

    private fun currentState(player: Player, shopId: String): RecordViewState {
        val state = viewStates[player.uniqueId]
        return if (state != null && state.shopId.equals(shopId, true)) {
            state
        } else {
            RecordViewState(shopId = shopId)
        }
    }

    private fun trimDouble(value: Double): String {
        return if (value % 1.0 == 0.0) value.toInt().toString() else "%.2f".format(value)
    }
}

private data class RecordSettings(
    val showAdminReason: Boolean,
    val moduleAliases: Map<String, String> = emptyMap()
)

private data class RecordMenus(
    val recordViews: LinkedHashMap<String, ConfiguredShopMenu>,
    val detail: MenuDefinition,
    val income: MenuDefinition,
    val expense: MenuDefinition
)

private data class RecordViewConfig(
    val allowedModules: Set<String> = emptySet()
)

private data class RecordViewState(
    val shopId: String,
    val keyword: String? = null,
    val moduleFilter: String? = null
)
