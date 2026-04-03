package com.y54895.matrixshop.module.systemshop

import com.y54895.matrixshop.core.config.ConfigFiles
import com.y54895.matrixshop.core.economy.EconomyModule
import com.y54895.matrixshop.core.menu.MenuDefinition
import com.y54895.matrixshop.core.menu.MenuLoader
import com.y54895.matrixshop.core.menu.MenuRenderer
import com.y54895.matrixshop.core.menu.MatrixMenuHolder
import com.y54895.matrixshop.core.module.MatrixModule
import com.y54895.matrixshop.core.permission.PermissionNodes
import com.y54895.matrixshop.core.permission.Permissions
import com.y54895.matrixshop.core.record.RecordService
import com.y54895.matrixshop.core.text.Texts
import com.y54895.matrixshop.core.warehouse.PlayerItemDelivery
import com.y54895.matrixshop.core.database.ItemStackCodec
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.quartz.CronExpression
import taboolib.common.platform.function.warning
import taboolib.common.platform.function.submit
import taboolib.common.platform.service.PlatformExecutor
import taboolib.module.kether.KetherShell
import taboolib.module.kether.ScriptOptions
import java.io.File
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.text.SimpleDateFormat

object SystemShopModule : MatrixModule {

    private const val MIN_FINAL_PRICE = 0.01

    override val id: String = "system-shop"
    override val displayName: String = "SystemShop"

    private lateinit var rootMenu: MenuDefinition
    private lateinit var confirmMenu: MenuDefinition
    private lateinit var goodsBrowserMenu: MenuDefinition
    private lateinit var goodsEditorMenu: MenuDefinition
    private lateinit var goodsShopsMenu: MenuDefinition
    private val categories = LinkedHashMap<String, SystemShopCategory>()
    private val goodsTemplates = LinkedHashMap<String, SystemShopProductTemplate>()
    private val goodsGroups = LinkedHashMap<String, SystemShopGoodsGroup>()
    private val goodsPools = LinkedHashMap<String, SystemShopGoodsPool>()
    private val confirmSessions = HashMap<UUID, ConfirmSession>()
    private val adminSelections = HashMap<UUID, AdminGoodsSelection>()
    private val refreshWindows = LinkedHashMap<String, SystemShopRefreshWindowState>()
    private val refreshStates = LinkedHashMap<String, SystemShopRefreshAreaState>()
    private val openCategoryViewers = HashMap<UUID, String>()
    private val renderedViews = HashMap<UUID, SystemShopRenderedView>()
    private var refreshTask: PlatformExecutor.PlatformTask? = null
    private var currencyKey: String = "vault"

    override fun isEnabled(): Boolean {
        return ConfigFiles.isModuleEnabled(id, true)
    }

    override fun reload() {
        refreshTask?.cancel()
        refreshTask = null
        if (!isEnabled()) {
            categories.clear()
            goodsTemplates.clear()
            goodsGroups.clear()
            goodsPools.clear()
            refreshWindows.clear()
            refreshStates.clear()
            openCategoryViewers.clear()
            renderedViews.clear()
            adminSelections.clear()
            return
        }
        val dataFolder = ConfigFiles.dataFolder()
        val settings = YamlConfiguration.loadConfiguration(File(dataFolder, "SystemShop/settings.yml"))
        currencyKey = EconomyModule.configuredKey(settings)
        rootMenu = MenuLoader.load(File(dataFolder, "SystemShop/ui/shop.yml"))
        confirmMenu = MenuLoader.load(File(dataFolder, "SystemShop/ui/confirm.yml"))
        goodsBrowserMenu = MenuLoader.load(File(dataFolder, "SystemShop/ui/goods-browser.yml"))
        goodsEditorMenu = MenuLoader.load(File(dataFolder, "SystemShop/ui/goods-editor.yml"))
        goodsShopsMenu = MenuLoader.load(File(dataFolder, "SystemShop/ui/goods-shops.yml"))
        categories.clear()
        goodsTemplates.clear()
        goodsGroups.clear()
        goodsPools.clear()
        refreshWindows.clear()
        refreshStates.clear()
        openCategoryViewers.clear()
        renderedViews.clear()
        adminSelections.clear()
        val goodsFolder = File(dataFolder, "SystemShop/goods")
        goodsFolder.mkdirs()
        goodsFolder.listFiles { file -> file.isFile && file.extension.equals("yml", true) }
            ?.sortedBy { it.nameWithoutExtension }
            ?.forEach { file ->
                runCatching { loadGoodsEntry(file) }.onFailure {
                    warning(Texts.tr("@system-shop.logs.goods-load-failed", mapOf("file" to file.name, "reason" to (it.message ?: it.javaClass.simpleName))))
                }
            }
        val shopFolder = File(dataFolder, "SystemShop/shops")
        shopFolder.mkdirs()
        shopFolder.listFiles { file -> file.isFile && file.extension.equals("yml", true) }
            ?.sortedBy { it.nameWithoutExtension }
            ?.forEach { file ->
                runCatching { loadCategory(file) }.onFailure {
                    warning(Texts.tr("@system-shop.logs.category-load-failed", mapOf("file" to file.name, "reason" to (it.message ?: it.javaClass.simpleName))))
                }
            }
        initializeRefreshState()
    }

    fun openMain(player: Player) {
        val placeholders = playerPlaceholders(player) + mapOf("page" to "1", "max-page" to "1")
        MenuRenderer.open(player, rootMenu, placeholders)
    }

    fun openCategory(player: Player, categoryId: String, page: Int = 1) {
        val category = categories[categoryId]
        if (category == null) {
            Texts.sendKey(player, "@system-shop.errors.category-not-found", mapOf("category" to categoryId))
            return
        }
        val renderedView = resolveRenderedView(player, category)
        val placeholders = playerPlaceholders(player) + mapOf("page" to page.toString(), "max-page" to "1")
        openCategoryViewers[player.uniqueId] = category.id
        renderedViews[player.uniqueId] = renderedView
        MenuRenderer.open(
            player = player,
            definition = category.menu,
            placeholders = placeholders,
            backAction = { openMain(player) },
            closeHandler = {
                if (openCategoryViewers[player.uniqueId] == category.id) {
                    openCategoryViewers.remove(player.uniqueId)
                }
                renderedViews.remove(player.uniqueId)
            },
            goodsRenderer = { holder, goodsSlots ->
                renderProducts(player, holder, goodsSlots, category)
            }
        )
    }

    fun categoryIds(): List<String> {
        val folder = File(ConfigFiles.dataFolder(), "SystemShop/shops")
        if (!folder.exists()) {
            return emptyList()
        }
        return folder.listFiles { file -> file.isFile && file.extension.equals("yml", true) }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            .orEmpty()
    }

    fun productIds(categoryId: String?): List<String> {
        val normalizedCategoryId = categoryId?.trim().orEmpty()
        if (normalizedCategoryId.isBlank()) {
            return emptyList()
        }
        return categories[normalizedCategoryId]?.products?.map { it.id }?.sorted().orEmpty()
    }

    fun goodsIds(): List<String> {
        return goodsReferenceIds().sorted()
    }

    fun refreshCategoryIds(): List<String> {
        return categories.values
            .filter { it.refreshAreas.isNotEmpty() }
            .map { it.id }
            .sorted()
    }

    fun refreshAreaIds(categoryId: String?): List<String> {
        val category = categories[categoryId?.trim().orEmpty()] ?: return emptyList()
        return category.refreshAreas.keys.map(Char::toString).sorted()
    }

    fun refreshOverviewLines(categoryId: String? = null): List<String> {
        val targets = if (categoryId.isNullOrBlank()) {
            categories.values.filter { it.refreshAreas.isNotEmpty() }
        } else {
            listOfNotNull(categories[categoryId.trim()])
        }
        val lines = ArrayList<String>()
        targets.forEach { category ->
            category.refreshAreas.toSortedMap(compareBy { it.toString() }).forEach { (icon, area) ->
                val areaKey = refreshAreaKey(category.id, icon)
                val window = refreshWindows[areaKey]
                val next = window?.nextRefreshAt?.takeIf { it > 0 && it != Long.MAX_VALUE }?.let(::formatRefreshTime) ?: "n/a"
                lines += Texts.color("&8[&bMatrixShop&8] &f${category.id}/${icon} &7same=&f${area.sameForPlayersInGroup} &7cron=&f${area.cron} &7next=&f$next")
                area.groups.values.forEach { group ->
                    lines += Texts.color(" &8- &f${group.id} &7enabled=&f${group.enabled} &7random=&f${group.randomRefresh} &7pick=&f${group.pick}")
                }
            }
        }
        return lines
    }

    fun forceRefresh(categoryId: String?, iconRaw: String? = null): ModuleOperationResult {
        val normalizedCategoryId = categoryId?.trim().orEmpty()
        if (normalizedCategoryId.isBlank()) {
            return ModuleOperationResult(false, Texts.tr("@system-shop.errors.refresh-usage"))
        }
        val category = categories[normalizedCategoryId]
            ?: return ModuleOperationResult(false, Texts.tr("@system-shop.errors.category-not-found", mapOf("category" to normalizedCategoryId)))
        val targets = if (iconRaw.isNullOrBlank()) {
            category.refreshAreas.entries.map { entry -> entry.key to entry.value }
        } else {
            val icon = iconRaw.trim().firstOrNull()
                ?: return ModuleOperationResult(false, Texts.tr("@system-shop.errors.refresh-usage"))
            val area = category.refreshAreas[icon]
                ?: return ModuleOperationResult(false, Texts.tr("@system-shop.errors.refresh-area-not-found", mapOf("category" to normalizedCategoryId, "icon" to iconRaw)))
            listOf(icon to area)
        }
        if (targets.isEmpty()) {
            return ModuleOperationResult(false, Texts.tr("@system-shop.errors.refresh-none"))
        }
        val now = System.currentTimeMillis()
        targets.forEach { (icon, area) ->
            val areaKey = refreshAreaKey(category.id, icon)
            refreshWindows[areaKey] = SystemShopRefreshWindowState(
                version = now,
                lastRefreshAt = now,
                nextRefreshAt = nextRefreshTime(area, Date(now))
            )
            val areaState = refreshStates.computeIfAbsent(areaKey) { SystemShopRefreshAreaState() }
            if (area.sameForPlayersInGroup) {
                area.groups.values.filter { it.enabled }.forEach { group ->
                    val groupState = areaState.groups.computeIfAbsent(group.id) { SystemShopRefreshGroupState() }
                    groupState.sharedSnapshot = buildSnapshot(category, area, group, now)
                }
            } else {
                areaState.groups.values.forEach { state -> state.playerSnapshots.clear() }
            }
        }
        saveRefreshState()
        refreshOpenCategories(setOf(category.id))
        val iconLabel = iconRaw?.takeIf(String::isNotBlank) ?: "*"
        return ModuleOperationResult(true, Texts.tr("@system-shop.success.refreshed", mapOf("category" to category.id, "icon" to iconLabel)))
    }

    fun openGoodsBrowser(player: Player, page: Int = 1) {
        val entries = goodsTemplates.values.sortedBy { it.id }
        val goodsSlots = goodsSlots(goodsBrowserMenu).ifEmpty { return }
        val maxPage = ((entries.size + goodsSlots.size - 1) / goodsSlots.size).coerceAtLeast(1)
        val currentPage = page.coerceIn(1, maxPage)
        val pageEntries = entries.drop((currentPage - 1) * goodsSlots.size).take(goodsSlots.size)
        val placeholders = playerPlaceholders(player) + mapOf(
            "page" to currentPage.toString(),
            "max-page" to maxPage.toString(),
            "total-goods" to entries.size.toString()
        )
        MenuRenderer.open(
            player = player,
            definition = goodsBrowserMenu,
            placeholders = placeholders,
            goodsRenderer = { holder, slots ->
                renderGoodsBrowser(player, holder, pageEntries, slots, currentPage, maxPage)
            }
        )
    }

    fun currentAdminSelection(player: Player): AdminGoodsSelection? {
        return adminSelections[player.uniqueId]
    }

    fun adjustSavedGoodsPrice(productId: String, delta: Double): ModuleOperationResult {
        val template = findGoodsTemplate(productId)
            ?: return ModuleOperationResult(false, Texts.tr("@system-shop.errors.admin-goods-not-found", mapOf("product" to productId)))
        val yaml = YamlConfiguration.loadConfiguration(template.source.configFile)
        val nextPrice = (template.basePrice + delta).coerceAtLeast(0.0)
        setBasePrice(yaml, null, nextPrice)
        saveCategoryYaml(template.source.configFile, yaml)?.let { return saveFailedResult(it) }
        reload()
        return ModuleOperationResult(true, "")
    }

    fun adjustSavedGoodsLimit(productId: String, delta: Int): ModuleOperationResult {
        val template = findGoodsTemplate(productId)
            ?: return ModuleOperationResult(false, Texts.tr("@system-shop.errors.admin-goods-not-found", mapOf("product" to productId)))
        val yaml = YamlConfiguration.loadConfiguration(template.source.configFile)
        val nextLimit = (template.buyMax + delta).coerceAtLeast(1)
        setProductValue(yaml, null, "buy-max", nextLimit)
        saveCategoryYaml(template.source.configFile, yaml)?.let { return saveFailedResult(it) }
        reload()
        return ModuleOperationResult(true, "")
    }

    fun syncSavedGoodsFromHand(player: Player, productId: String): ModuleOperationResult {
        val template = findGoodsTemplate(productId)
            ?: return ModuleOperationResult(false, Texts.tr("@system-shop.errors.admin-goods-not-found", mapOf("product" to productId)))
        val inHand = player.inventory.itemInMainHand?.clone() ?: ItemStack(Material.AIR)
        if (inHand.type == Material.AIR || inHand.amount <= 0) {
            return ModuleOperationResult(false, Texts.tr("@system-shop.errors.hold-item"))
        }
        val yaml = YamlConfiguration.loadConfiguration(template.source.configFile)
        writeProductSnapshot(
            yaml = yaml,
            basePath = null,
            stack = inHand,
            price = template.basePrice,
            buyMax = template.buyMax
        )
        saveCategoryYaml(template.source.configFile, yaml)?.let { return saveFailedResult(it) }
        reload()
        return ModuleOperationResult(true, Texts.tr("@system-shop.success.admin-updated", mapOf("field" to Texts.tr("@system-shop.words.field-item"), "product" to productId)))
    }

    fun saveGoodsFromHand(player: Player, price: Double?, buyMax: Int?, productId: String?): ModuleOperationResult {
        if (price == null || price < 0.0) {
            return ModuleOperationResult(false, Texts.tr("@system-shop.errors.admin-save-usage"))
        }
        val inHand = player.inventory.itemInMainHand?.clone() ?: ItemStack(Material.AIR)
        if (inHand.type == Material.AIR || inHand.amount <= 0) {
            return ModuleOperationResult(false, Texts.tr("@system-shop.errors.hold-item"))
        }
        val resolvedProductId = nextProductId(goodsReferenceIds(), inHand, productId)
        val goodsFolder = File(ConfigFiles.dataFolder(), "SystemShop/goods")
        goodsFolder.mkdirs()
        val goodsFile = File(goodsFolder, "$resolvedProductId.yml")
        val goodsYaml = YamlConfiguration()
        goodsYaml.set("id", resolvedProductId)
        writeProductSnapshot(
            yaml = goodsYaml,
            basePath = null,
            stack = inHand,
            price = price,
            buyMax = buyMax
        )
        runCatching { goodsYaml.save(goodsFile) }.getOrElse {
            return ModuleOperationResult(false, Texts.tr("@system-shop.errors.save-failed", mapOf("reason" to (it.message ?: it.javaClass.simpleName))))
        }
        if (isEnabled()) {
            reload()
        }
        val displayName = inHand.itemMeta?.displayName?.takeIf(String::isNotBlank) ?: defaultProductName(inHand)
        return ModuleOperationResult(
            true,
            Texts.tr(
                "@system-shop.success.admin-saved",
                mapOf(
                    "product" to resolvedProductId,
                    "name" to displayName,
                    "price" to price.toString()
                )
            )
        )
    }

    fun addGoodsToCategory(categoryId: String?, productId: String?): ModuleOperationResult {
        val normalizedCategoryId = categoryId?.trim().orEmpty()
        val normalizedProductId = productId?.trim().orEmpty()
        if (normalizedCategoryId.isBlank() || normalizedProductId.isBlank()) {
            return ModuleOperationResult(false, Texts.tr("@system-shop.errors.admin-link-usage"))
        }
        val referencedGoods = resolveGoodsReferenceId(normalizedProductId)
            ?: return ModuleOperationResult(false, Texts.tr("@system-shop.errors.admin-goods-not-found", mapOf("product" to normalizedProductId)))
        val categoryFile = File(ConfigFiles.dataFolder(), "SystemShop/shops/$normalizedCategoryId.yml")
        if (!categoryFile.exists()) {
            return ModuleOperationResult(false, Texts.tr("@system-shop.errors.category-not-found", mapOf("category" to normalizedCategoryId)))
        }
        val yaml = YamlConfiguration.loadConfiguration(categoryFile)
        val categoryCurrencyKey = EconomyModule.configuredKey(yaml, "Currency", currencyKey)
        val goodsReferences = ensureGoodsReferencesMode(categoryFile, yaml, normalizedCategoryId)
        if (goodsReferences.any { it.equals(referencedGoods, true) }) {
            return ModuleOperationResult(false, Texts.tr("@system-shop.errors.admin-product-already-linked", mapOf("category" to normalizedCategoryId, "product" to referencedGoods)))
        }
        goodsReferences += referencedGoods
        writeGoodsReferences(yaml, goodsReferences)
        saveCategoryYaml(categoryFile, yaml)?.let { return saveFailedResult(it) }
        if (isEnabled()) {
            reload()
        }
        val template = findGoodsTemplate(referencedGoods)
        return if (template != null) {
            ModuleOperationResult(
                true,
                Texts.tr(
                    "@system-shop.success.admin-linked",
                    mapOf(
                        "category" to normalizedCategoryId,
                        "product" to referencedGoods,
                        "name" to template.name,
                        "price" to EconomyModule.formatAmount(template.resolve(categoryCurrencyKey).currency, template.basePrice)
                    )
                )
            )
        } else {
            ModuleOperationResult(
                true,
                Texts.tr(
                    "@system-shop.success.admin-linked-group",
                    mapOf(
                        "category" to normalizedCategoryId,
                        "product" to referencedGoods
                    )
                )
            )
        }
    }

    fun removeGoodsFromCategory(categoryId: String?, productId: String?): ModuleOperationResult {
        val normalizedCategoryId = categoryId?.trim().orEmpty()
        val normalizedProductId = productId?.trim().orEmpty()
        if (normalizedCategoryId.isBlank() || normalizedProductId.isBlank()) {
            return ModuleOperationResult(false, Texts.tr("@system-shop.errors.admin-link-usage"))
        }
        val categoryFile = File(ConfigFiles.dataFolder(), "SystemShop/shops/$normalizedCategoryId.yml")
        if (!categoryFile.exists()) {
            return ModuleOperationResult(false, Texts.tr("@system-shop.errors.category-not-found", mapOf("category" to normalizedCategoryId)))
        }
        val yaml = YamlConfiguration.loadConfiguration(categoryFile)
        val goodsReferences = ensureGoodsReferencesMode(categoryFile, yaml, normalizedCategoryId)
        val removed = goodsReferences.removeAll { it.equals(normalizedProductId, true) }
        if (!removed) {
            return ModuleOperationResult(false, Texts.tr("@system-shop.errors.admin-product-not-found", mapOf("category" to normalizedCategoryId, "product" to normalizedProductId)))
        }
        writeGoodsReferences(yaml, goodsReferences)
        saveCategoryYaml(categoryFile, yaml)?.let { return saveFailedResult(it) }
        reload()
        return ModuleOperationResult(true, Texts.tr("@system-shop.success.admin-removed", mapOf("category" to normalizedCategoryId, "product" to normalizedProductId)))
    }

    fun selectAdminProduct(player: Player, categoryId: String?, productId: String?): ModuleOperationResult {
        val normalizedCategoryId = categoryId?.trim().orEmpty()
        val normalizedProductId = productId?.trim().orEmpty()
        if (normalizedCategoryId.isBlank() || normalizedProductId.isBlank()) {
            return ModuleOperationResult(false, Texts.tr("@system-shop.errors.admin-select-usage"))
        }
        val product = findProduct(normalizedCategoryId, normalizedProductId)
            ?: return ModuleOperationResult(false, Texts.tr("@system-shop.errors.admin-product-not-found", mapOf("category" to normalizedCategoryId, "product" to normalizedProductId)))
        adminSelections[player.uniqueId] = AdminGoodsSelection(normalizedCategoryId, normalizedProductId)
        return ModuleOperationResult(
            true,
            Texts.tr(
                "@system-shop.success.admin-selected",
                mapOf(
                    "category" to normalizedCategoryId,
                    "product" to normalizedProductId,
                    "name" to product.name
                )
            )
        )
    }

    fun openGoodsEditor(player: Player, productId: String, browserPage: Int = 1) {
        val template = findGoodsTemplate(productId)
        if (template == null) {
            Texts.sendKey(player, "@system-shop.errors.admin-goods-not-found", mapOf("product" to productId))
            return
        }
        primeAdminSelection(player, productId)
        val listedShops = listedCategoryIds(productId)
        val placeholders = playerPlaceholders(player) + mapOf(
            "product-id" to template.id,
            "price" to trimDouble(template.basePrice),
            "limit" to template.buyMax.toString(),
            "shop-count" to listedShops.size.toString(),
            "shops" to listedShops.ifEmpty { listOf("未上架") }.joinToString(", ")
        )
        MenuRenderer.open(
            player = player,
            definition = goodsEditorMenu,
            placeholders = placeholders,
            backAction = { openGoodsBrowser(player, browserPage) },
            goodsRenderer = { holder, slots ->
                renderGoodsEditor(player, holder, slots, template.id, browserPage, listedShops)
            }
        )
    }

    fun openGoodsShopSelector(player: Player, productId: String, browserPage: Int = 1, page: Int = 1) {
        val template = findGoodsTemplate(productId)
        if (template == null) {
            Texts.sendKey(player, "@system-shop.errors.admin-goods-not-found", mapOf("product" to productId))
            return
        }
        val entries = categories.values.sortedBy { it.id }
        val goodsSlots = goodsSlots(goodsShopsMenu).ifEmpty { return }
        val maxPage = ((entries.size + goodsSlots.size - 1) / goodsSlots.size).coerceAtLeast(1)
        val currentPage = page.coerceIn(1, maxPage)
        val pageEntries = entries.drop((currentPage - 1) * goodsSlots.size).take(goodsSlots.size)
        val listedShops = listedCategoryIds(productId)
        val placeholders = playerPlaceholders(player) + mapOf(
            "product-id" to template.id,
            "shop-count" to listedShops.size.toString(),
            "page" to currentPage.toString(),
            "max-page" to maxPage.toString()
        )
        MenuRenderer.open(
            player = player,
            definition = goodsShopsMenu,
            placeholders = placeholders,
            backAction = { openGoodsEditor(player, productId, browserPage) },
            goodsRenderer = { holder, slots ->
                renderGoodsShopSelector(player, holder, slots, pageEntries, productId, browserPage, currentPage, maxPage)
            }
        )
    }

    fun quickEditSelected(
        player: Player,
        fieldRaw: String?,
        values: List<String>
    ): ModuleOperationResult {
        val selection = adminSelections[player.uniqueId]
            ?: return ModuleOperationResult(false, Texts.tr("@system-shop.errors.admin-selection-required"))
        val field = fieldRaw?.trim()?.lowercase().orEmpty()
        if (field.isBlank()) {
            return ModuleOperationResult(false, Texts.tr("@system-shop.errors.admin-edit-usage"))
        }
        val category = categories[selection.categoryId]
            ?: return ModuleOperationResult(false, Texts.tr("@system-shop.errors.category-not-found", mapOf("category" to selection.categoryId)))
        val product = findProduct(selection.categoryId, selection.productId)
            ?: return ModuleOperationResult(false, Texts.tr("@system-shop.errors.admin-product-not-found", mapOf("category" to selection.categoryId, "product" to selection.productId)))
        val yaml = YamlConfiguration.loadConfiguration(product.source.configFile)
        val basePath = product.source.configPath
        val section = resolveProductSection(yaml, basePath)
            ?: return ModuleOperationResult(false, Texts.tr("@system-shop.errors.admin-product-not-found", mapOf("category" to selection.categoryId, "product" to selection.productId)))
        if (!product.source.configFile.exists()) {
            return ModuleOperationResult(false, Texts.tr("@system-shop.errors.admin-product-not-found", mapOf("category" to selection.categoryId, "product" to selection.productId)))
        }
        when (field) {
            "price" -> {
                val price = values.firstOrNull()?.toDoubleOrNull()
                    ?: return ModuleOperationResult(false, Texts.tr("@system-shop.errors.admin-edit-usage"))
                if (price < 0.0) {
                    return ModuleOperationResult(false, Texts.tr("@system-shop.errors.admin-edit-usage"))
                }
                setBasePrice(yaml, basePath, price)
                saveCategoryYaml(product.source.configFile, yaml)?.let { return saveFailedResult(it) }
                reload()
                adminSelections[player.uniqueId] = selection
                return ModuleOperationResult(true, Texts.tr("@system-shop.success.admin-updated", mapOf("field" to Texts.tr("@system-shop.words.field-price"), "product" to selection.productId)))
            }
            "buy-max", "limit" -> {
                val buyMax = values.firstOrNull()?.toIntOrNull()
                    ?: return ModuleOperationResult(false, Texts.tr("@system-shop.errors.admin-edit-usage"))
                if (buyMax <= 0) {
                    return ModuleOperationResult(false, Texts.tr("@system-shop.errors.admin-edit-usage"))
                }
                setProductValue(yaml, basePath, "buy-max", buyMax)
                saveCategoryYaml(product.source.configFile, yaml)?.let { return saveFailedResult(it) }
                reload()
                adminSelections[player.uniqueId] = selection
                return ModuleOperationResult(true, Texts.tr("@system-shop.success.admin-updated", mapOf("field" to Texts.tr("@system-shop.words.field-buy-max"), "product" to selection.productId)))
            }
            "currency" -> {
                val currency = values.firstOrNull()?.trim().orEmpty()
                if (currency.isBlank()) {
                    return ModuleOperationResult(false, Texts.tr("@system-shop.errors.admin-edit-usage"))
                }
                if (currency.equals("default", true) || currency.equals("inherit", true) || currency.equals("reset", true)) {
                    setProductValue(yaml, basePath, "currency", null)
                } else {
                    setProductValue(yaml, basePath, "currency", currency)
                }
                saveCategoryYaml(product.source.configFile, yaml)?.let { return saveFailedResult(it) }
                reload()
                adminSelections[player.uniqueId] = selection
                return ModuleOperationResult(true, Texts.tr("@system-shop.success.admin-updated", mapOf("field" to Texts.tr("@system-shop.words.field-currency"), "product" to selection.productId)))
            }
            "name" -> {
                val name = values.joinToString(" ").trim()
                if (name.isBlank()) {
                    return ModuleOperationResult(false, Texts.tr("@system-shop.errors.admin-edit-usage"))
                }
                setProductValue(yaml, basePath, "name", name)
                saveCategoryYaml(product.source.configFile, yaml)?.let { return saveFailedResult(it) }
                reload()
                adminSelections[player.uniqueId] = selection
                return ModuleOperationResult(true, Texts.tr("@system-shop.success.admin-updated", mapOf("field" to Texts.tr("@system-shop.words.field-name"), "product" to selection.productId)))
            }
            "item", "sync-hand", "hand" -> {
                val inHand = player.inventory.itemInMainHand?.clone() ?: ItemStack(Material.AIR)
                if (inHand.type == Material.AIR || inHand.amount <= 0) {
                    return ModuleOperationResult(false, Texts.tr("@system-shop.errors.hold-item"))
                }
                writeProductSnapshot(
                    yaml = yaml,
                    basePath = basePath,
                    stack = inHand,
                    price = parsePriceConfig(section, "price", product.basePrice, sectionDisplayTarget(product.source.configFile, basePath)).base,
                    buyMax = section.getInt("buy-max", product.buyMax)
                )
                saveCategoryYaml(product.source.configFile, yaml)?.let { return saveFailedResult(it) }
                reload()
                adminSelections[player.uniqueId] = selection
                return ModuleOperationResult(true, Texts.tr("@system-shop.success.admin-updated", mapOf("field" to Texts.tr("@system-shop.words.field-item"), "product" to selection.productId)))
            }
            "remove", "delete" -> {
                when (product.source.type) {
                    SystemShopProductSourceType.INLINE -> {
                        yaml.set(basePath, null)
                        saveCategoryYaml(product.source.configFile, yaml)?.let { return saveFailedResult(it) }
                    }
                    SystemShopProductSourceType.REFERENCE -> {
                        val categoryYaml = YamlConfiguration.loadConfiguration(category.shopFile)
                        val goodsReferences = readGoodsReferences(categoryYaml).toMutableList()
                        val removed = goodsReferences.removeAll { it.equals(product.id, true) }
                        if (!removed) {
                            return ModuleOperationResult(false, Texts.tr("@system-shop.errors.admin-product-not-found", mapOf("category" to selection.categoryId, "product" to selection.productId)))
                        }
                        writeGoodsReferences(categoryYaml, goodsReferences)
                        saveCategoryYaml(category.shopFile, categoryYaml)?.let { return saveFailedResult(it) }
                    }
                }
                adminSelections.remove(player.uniqueId)
                reload()
                return ModuleOperationResult(true, Texts.tr("@system-shop.success.admin-removed", mapOf("category" to selection.categoryId, "product" to selection.productId)))
            }
            else -> return ModuleOperationResult(false, Texts.tr("@system-shop.errors.admin-edit-unknown-field", mapOf("field" to field)))
        }
    }

    fun adjustConfirmAmount(player: Player, delta: Int) {
        val session = confirmSessions[player.uniqueId]
        if (session == null) {
            Texts.sendKey(player, "@system-shop.errors.no-confirm-purchase")
            return
        }
        val product = session.product
        session.amount = (session.amount + delta).coerceIn(1, product.buyMax.coerceAtLeast(1))
        openConfirm(player, session.categoryId, product)
    }

    fun confirmPurchase(player: Player) {
        val session = confirmSessions[player.uniqueId]
        if (session == null) {
            Texts.sendKey(player, "@system-shop.errors.no-confirm-purchase")
            return
        }
        val result = purchaseProduct(player, session.categoryId, session.product, session.amount, true)
        if (result.success) {
            confirmSessions.remove(player.uniqueId)
        } else if (result.message.isNotBlank()) {
            Texts.send(player, result.message)
        }
    }

    fun currentSelection(player: Player): SystemShopSelection? {
        val session = confirmSessions[player.uniqueId] ?: return null
        return SystemShopSelection(
            categoryId = session.categoryId,
            productId = session.productId,
            product = session.product,
            amount = session.amount.coerceIn(1, session.product.buyMax.coerceAtLeast(1))
        )
    }

    fun snapshot(categoryId: String, productId: String): SystemShopProduct? {
        return findProduct(categoryId, productId)
    }

    fun validateProduct(categoryId: String, productId: String, amount: Int): ModuleOperationResult {
        val category = categories[categoryId]
        val product = findProduct(categoryId, productId)
        if (category == null || product == null) {
            return ModuleOperationResult(false, Texts.tr("@system-shop.errors.product-invalid"))
        }
        return validateSnapshotProduct(product, amount)
    }

    fun validateSnapshotProduct(product: SystemShopProduct, amount: Int): ModuleOperationResult {
        val safeAmount = amount.coerceAtLeast(1)
        if (safeAmount > product.buyMax.coerceAtLeast(1)) {
            return ModuleOperationResult(false, Texts.tr("@system-shop.errors.amount-over-limit"))
        }
        return ModuleOperationResult(true, "")
    }

    fun currentPrice(categoryId: String, productId: String): Double? {
        return findProduct(categoryId, productId)?.price
    }

    fun purchaseDirect(
        player: Player,
        categoryId: String,
        productId: String,
        amount: Int,
        closeInventoryOnSuccess: Boolean = false
    ): ModuleOperationResult {
        val category = categories[categoryId]
        val product = findProduct(categoryId, productId)?.let { resolveProductForPlayer(player, categoryId, it) }
        if (category == null || product == null) {
            confirmSessions.remove(player.uniqueId)
            return ModuleOperationResult(false, Texts.tr("@system-shop.errors.product-invalid"))
        }
        return purchaseProduct(player, categoryId, product, amount, closeInventoryOnSuccess)
    }

    fun purchaseSnapshot(
        player: Player,
        categoryId: String,
        product: SystemShopProduct,
        amount: Int,
        closeInventoryOnSuccess: Boolean = false
    ): ModuleOperationResult {
        return purchaseProduct(player, categoryId, product, amount, closeInventoryOnSuccess)
    }

    private fun purchaseProduct(
        player: Player,
        categoryId: String,
        product: SystemShopProduct,
        amount: Int,
        closeInventoryOnSuccess: Boolean = false
    ): ModuleOperationResult {
        val category = categories[categoryId]
        if (category == null) {
            confirmSessions.remove(player.uniqueId)
            return ModuleOperationResult(false, Texts.tr("@system-shop.errors.product-invalid"))
        }
        val safeAmount = amount.coerceIn(1, product.buyMax.coerceAtLeast(1))
        val total = product.price * safeAmount
        if (total > 0 && !EconomyModule.isAvailable(product.currency)) {
            return ModuleOperationResult(false, Texts.tr("@economy.errors.currency-unavailable", mapOf("currency" to EconomyModule.displayName(product.currency))))
        }
        if (!EconomyModule.has(player, product.currency, total)) {
            return ModuleOperationResult(false, EconomyModule.insufficientMessage(player, product.currency, total))
        }
        val purchaseStacks = product.toPurchasedItem(safeAmount)
        if (!PlayerItemDelivery.canDeliver(player, purchaseStacks)) {
            return ModuleOperationResult(false, Texts.tr("@system-shop.errors.inventory-no-space"))
        }
        if (!EconomyModule.withdraw(player, product.currency, total)) {
            return ModuleOperationResult(false, Texts.tr("@economy.errors.withdraw-failed", mapOf("currency" to EconomyModule.displayName(product.currency))))
        }
        PlayerItemDelivery.deliverOrStore(
            player = player,
            stacks = purchaseStacks,
            sourceModule = "system_shop",
            sourceId = "${category.id}:${product.id}",
            reason = "purchase",
            allowDropWhenUnavailable = true
        )
        if (closeInventoryOnSuccess) {
            player.closeInventory()
        }
        Texts.sendKey(
            player,
            "@system-shop.success.purchase",
            mapOf(
                "name" to product.name,
                "amount" to safeAmount.toString(),
                "total" to EconomyModule.formatAmount(product.currency, total)
            )
        )
        RecordService.append(
            module = "system_shop",
            type = "purchase",
            actor = player.name,
            moneyChange = -total,
            detail = "category=${category.id};product=${product.id};amount=$safeAmount;base=${trimDouble(product.basePrice)};price=${trimDouble(product.price)};discounts=${product.appliedDiscounts.joinToString(",") { it.id }.ifBlank { "none" }};total=${trimDouble(total)}"
        )
        return ModuleOperationResult(true, "")
    }

    fun openConfirm(player: Player, categoryId: String, productId: String) {
        val product = findProduct(categoryId, productId)?.let { resolveProductForPlayer(player, categoryId, it) } ?: return
        openConfirm(player, categoryId, product)
    }

    fun openConfirm(player: Player, categoryId: String, product: SystemShopProduct) {
        val session = confirmSessions.compute(player.uniqueId) { _, current ->
            if (current == null || current.categoryId != categoryId || current.productId != product.id) {
                ConfirmSession(categoryId, product.id, product, 1)
            } else {
                current.product = product
                current
            }
        }!!
        val total = product.price * session.amount
        val placeholders = playerPlaceholders(player) + mapOf(
            "name" to product.name,
            "amount" to session.amount.toString(),
            "base-price" to EconomyModule.formatAmount(product.currency, product.basePrice),
            "price" to EconomyModule.formatAmount(product.currency, product.price),
            "total-price" to EconomyModule.formatAmount(product.currency, total),
            "currency" to EconomyModule.displayName(product.currency),
            "discount-count" to product.discountCount.toString(),
            "discount-summary" to discountSummary(product)
        )
        MenuRenderer.open(
            player = player,
            definition = confirmMenu,
            placeholders = placeholders,
            backAction = { openCategory(player, categoryId) },
            goodsRenderer = { holder, slots ->
                renderConfirmSlots(holder, slots, product, placeholders)
            }
        )
    }

    private fun renderProducts(player: Player, holder: MatrixMenuHolder, goodsSlots: List<Int>, category: SystemShopCategory) {
        val slotProducts = renderedViews[player.uniqueId]?.slotProducts ?: resolveCategorySlotProducts(player, category)
        slotProducts.forEach { (slot, product) ->
            val placeholders = mapOf(
                "name" to product.name,
                "base-price" to EconomyModule.formatAmount(product.currency, product.basePrice),
                "price" to EconomyModule.formatAmount(product.currency, product.price),
                "currency" to EconomyModule.displayName(product.currency),
                "limit" to product.buyMax.toString(),
                "has_currency" to EconomyModule.formatAmount(product.currency, EconomyModule.balance(player, product.currency)),
                "discount-count" to product.discountCount.toString(),
                "discount-summary" to discountSummary(product),
                "lore" to ""
            )
            val displayLore = if (category.menu.template.lore.isNotEmpty()) {
                Texts.apply(category.menu.template.lore + product.lore, placeholders)
            } else {
                Texts.apply(product.lore, placeholders)
            }.toMutableList()
            if (Permissions.has(player, PermissionNodes.ADMIN_GOODS)) {
                displayLore += Texts.colorKey("@system-shop.lore.admin-edit", mapOf("category" to category.id, "product" to product.id))
            }
            val item = product.toItemStack(Texts.apply(category.menu.template.name, placeholders), displayLore)
            holder.inventory.setItem(slot, item)
            holder.handlers[slot] = { event ->
                if (Permissions.has(player, PermissionNodes.ADMIN_GOODS) && event.isShiftClick && event.isRightClick) {
                    adminSelections[player.uniqueId] = AdminGoodsSelection(category.id, product.id)
                    Texts.sendKey(
                        player,
                        "@system-shop.success.admin-selected",
                        mapOf("category" to category.id, "product" to product.id, "name" to product.name)
                    )
                    Texts.sendKey(player, "@system-shop.hints.admin-edit-price")
                    Texts.sendKey(player, "@system-shop.hints.admin-edit-limit")
                    Texts.sendKey(player, "@system-shop.hints.admin-edit-item")
                } else {
                    openConfirm(player, category.id, product)
                }
            }
        }
    }

    private fun renderConfirmSlots(holder: MatrixMenuHolder, slots: List<Int>, product: SystemShopProduct, placeholders: Map<String, String>) {
        slots.forEach { slot ->
            val preview = product.toItemStack(Texts.apply("&f${product.name}", placeholders), Texts.apply(product.lore, placeholders))
            holder.inventory.setItem(slot, preview)
        }
    }

    private fun renderGoodsBrowser(
        player: Player,
        holder: MatrixMenuHolder,
        entries: List<SystemShopProductTemplate>,
        slots: List<Int>,
        currentPage: Int,
        maxPage: Int
    ) {
        entries.forEachIndexed { index, template ->
            val slot = slots[index]
            val listedShops = listedCategoryIds(template.id)
            val item = goodsTemplatePreview(
                template = template,
                displayName = template.name,
                displayLore = listOf(
                    "&7商品ID: &f${template.id}",
                    "&7基础价格: &e${trimDouble(template.basePrice)}",
                    "&7限购: &f${template.buyMax}",
                    "&7上架商店: &f${listedShops.size}",
                    "&7分类: &f${listedShops.ifEmpty { listOf("未上架") }.joinToString(", ")}",
                    "",
                    "&e左键进入编辑"
                )
            )
            holder.inventory.setItem(slot, item)
            holder.handlers[slot] = { openGoodsEditor(player, template.id, currentPage) }
        }
        buttonSlot(goodsBrowserMenu, 'P')?.let { holder.handlers[it] = { openGoodsBrowser(player, (currentPage - 1).coerceAtLeast(1)) } }
        buttonSlot(goodsBrowserMenu, 'N')?.let { holder.handlers[it] = { openGoodsBrowser(player, (currentPage + 1).coerceAtMost(maxPage)) } }
    }

    private fun renderGoodsEditor(
        player: Player,
        holder: MatrixMenuHolder,
        slots: List<Int>,
        productId: String,
        browserPage: Int,
        listedShops: List<String>
    ) {
        val template = findGoodsTemplate(productId) ?: return
        val previewLore = listOf(
            "&7商品ID: &f${template.id}",
            "&7基础价格: &e${trimDouble(template.basePrice)}",
            "&7限购: &f${template.buyMax}",
            "&7上架商店: &f${listedShops.ifEmpty { listOf("未上架") }.joinToString(", ")}"
        )
        slots.forEach { slot ->
            holder.inventory.setItem(slot, goodsTemplatePreview(template, template.name, previewLore))
        }
        buttonSlot(goodsEditorMenu, 'P')?.let { slot ->
            holder.handlers[slot] = { event ->
                val delta = when {
                    event.isShiftClick && event.isRightClick -> -10.0
                    event.isShiftClick && event.isLeftClick -> 10.0
                    event.isRightClick -> -1.0
                    else -> 1.0
                }
                val result = adjustSavedGoodsPrice(productId, delta)
                if (!result.success && result.message.isNotBlank()) {
                    Texts.send(player, result.message)
                }
                openGoodsEditor(player, productId, browserPage)
            }
        }
        buttonSlot(goodsEditorMenu, 'L')?.let { slot ->
            holder.handlers[slot] = { event ->
                val delta = when {
                    event.isShiftClick && event.isRightClick -> -8
                    event.isShiftClick && event.isLeftClick -> 8
                    event.isRightClick -> -1
                    else -> 1
                }
                val result = adjustSavedGoodsLimit(productId, delta)
                if (!result.success && result.message.isNotBlank()) {
                    Texts.send(player, result.message)
                }
                openGoodsEditor(player, productId, browserPage)
            }
        }
        buttonSlot(goodsEditorMenu, 'I')?.let { slot ->
            holder.handlers[slot] = {
                val result = syncSavedGoodsFromHand(player, productId)
                if (result.message.isNotBlank()) {
                    Texts.send(player, result.message)
                }
                openGoodsEditor(player, productId, browserPage)
            }
        }
        buttonSlot(goodsEditorMenu, 'S')?.let { slot ->
            holder.handlers[slot] = { openGoodsShopSelector(player, productId, browserPage, 1) }
        }
    }

    private fun renderGoodsShopSelector(
        player: Player,
        holder: MatrixMenuHolder,
        slots: List<Int>,
        entries: List<SystemShopCategory>,
        productId: String,
        browserPage: Int,
        currentPage: Int,
        maxPage: Int
    ) {
        val listedShops = listedCategoryIds(productId)
        entries.forEachIndexed { index, category ->
            val listed = listedShops.any { it.equals(category.id, true) }
            val item = ItemStack(if (listed) Material.EMERALD_BLOCK else Material.REDSTONE_BLOCK)
            item.itemMeta = item.itemMeta?.apply {
                MenuRenderer.decorate(
                    this,
                    Texts.color(if (listed) "&a${category.id}" else "&c${category.id}"),
                    Texts.apply(
                        listOf(
                            if (listed) "&7当前状态: &a已上架" else "&7当前状态: &c未上架",
                            "&7左键切换状态",
                            "&7商店货币: &f${category.currencyKey}"
                        ),
                        emptyMap()
                    )
                )
            }
            val slot = slots[index]
            holder.inventory.setItem(slot, item)
            holder.handlers[slot] = {
                val result = if (listed) removeGoodsFromCategory(category.id, productId) else addGoodsToCategory(category.id, productId)
                if (result.message.isNotBlank()) {
                    Texts.send(player, result.message)
                }
                openGoodsShopSelector(player, productId, browserPage, currentPage)
            }
        }
        buttonSlot(goodsShopsMenu, 'P')?.let { holder.handlers[it] = { openGoodsShopSelector(player, productId, browserPage, (currentPage - 1).coerceAtLeast(1)) } }
        buttonSlot(goodsShopsMenu, 'N')?.let { holder.handlers[it] = { openGoodsShopSelector(player, productId, browserPage, (currentPage + 1).coerceAtMost(maxPage)) } }
    }

    private fun resolveCategorySlotProducts(player: Player, category: SystemShopCategory): Map<Int, SystemShopProduct> {
        val goodsSlotsByIcon = goodsSlotsByIcon(category.menu)
        val result = linkedMapOf<Int, SystemShopProduct>()
        val staticSymbols = goodsSlotsByIcon.keys.filter { icon -> category.refreshAreas[icon] == null }
        val staticSlots = staticSymbols.flatMap { goodsSlotsByIcon[it].orEmpty() }
        val staticProducts = category.products.iterator()
        staticSlots.forEach { slot ->
            if (staticProducts.hasNext()) {
                result[slot] = resolveProductForPlayer(player, category.id, staticProducts.next())
            }
        }
        category.refreshAreas.forEach { (icon, area) ->
            val slots = goodsSlotsByIcon[icon].orEmpty()
            val products = resolveRefreshProducts(player, category, area)
            slots.forEachIndexed { index, slot ->
                products.getOrNull(index)?.let { result[slot] = resolveProductForPlayer(player, category.id, it) }
            }
        }
        return result
    }

    private fun resolveRenderedView(player: Player, category: SystemShopCategory): SystemShopRenderedView {
        return SystemShopRenderedView(
            categoryId = category.id,
            slotProducts = resolveCategorySlotProducts(player, category)
        )
    }

    private fun goodsSlotsByIcon(definition: MenuDefinition): Map<Char, List<Int>> {
        val slots = linkedMapOf<Char, MutableList<Int>>()
        definition.layout.forEachIndexed { row, line ->
            line.forEachIndexed { column, char ->
                val icon = definition.icons[char] ?: return@forEachIndexed
                if (icon.mode.equals("goods", true)) {
                    slots.computeIfAbsent(char) { mutableListOf() } += row * 9 + column
                }
            }
        }
        return slots
    }

    private fun resolveRefreshProducts(player: Player, category: SystemShopCategory, area: SystemShopRefreshArea): List<SystemShopProduct> {
        if (!area.enabled) {
            return emptyList()
        }
        val group = selectRefreshGroup(player, category.id, area) ?: return emptyList()
        val areaKey = refreshAreaKey(category.id, area.iconKey)
        val areaState = refreshStates.computeIfAbsent(areaKey) { SystemShopRefreshAreaState() }
        val groupState = areaState.groups.computeIfAbsent(group.id) { SystemShopRefreshGroupState() }
        val window = refreshWindows.computeIfAbsent(areaKey) { buildCurrentRefreshWindow(area) }
        return if (area.sameForPlayersInGroup) {
            val snapshot = groupState.sharedSnapshot
            if (snapshot == null || snapshot.version != window.version) {
                val generated = buildSnapshot(category, area, group, window.version)
                groupState.sharedSnapshot = generated
                saveRefreshState()
                generated.products
            } else {
                snapshot.products
            }
        } else {
            val playerKey = player.uniqueId.toString()
            val snapshot = groupState.playerSnapshots[playerKey]
            if (snapshot == null || snapshot.version != window.version) {
                val generated = buildSnapshot(category, area, group, window.version)
                groupState.playerSnapshots[playerKey] = generated
                saveRefreshState()
                generated.products
            } else {
                snapshot.products
            }
        }
    }

    private fun selectRefreshGroup(player: Player, categoryId: String, area: SystemShopRefreshArea): SystemShopRefreshGroup? {
        val enabledGroups = area.groups.values.filter { it.enabled }
        if (enabledGroups.isEmpty()) {
            return null
        }
        enabledGroups.firstOrNull { it.id.equals("default", true) && it.matchScript.isEmpty() }?.let { defaultGroup ->
            enabledGroups.filter { it.matchScript.isNotEmpty() }.forEach { group ->
                if (matchesRefreshGroup(player, categoryId, area.iconKey, group)) {
                    return group
                }
            }
            return defaultGroup
        }
        enabledGroups.forEach { group ->
            if (group.matchScript.isEmpty() || matchesRefreshGroup(player, categoryId, area.iconKey, group)) {
                return group
            }
        }
        return enabledGroups.firstOrNull()
    }

    private fun matchesRefreshGroup(player: Player, categoryId: String, iconKey: Char, group: SystemShopRefreshGroup): Boolean {
        if (group.matchScript.isEmpty()) {
            return false
        }
        return runCatching {
            val options = ScriptOptions.builder()
                .sender(player)
                .set("player", player)
                .set("shop_id", categoryId)
                .set("icon_key", iconKey.toString())
                .set("group_id", group.id)
                .detailError(true)
                .build()
            val result = KetherShell.eval(group.matchScript, options).get(3, TimeUnit.SECONDS)
            when (result) {
                is Boolean -> result
                is Number -> result.toInt() != 0
                else -> result?.toString()?.equals("true", true) == true
            }
        }.getOrElse {
            warning(
                Texts.tr(
                    "@system-shop.logs.refresh-group-script-failed",
                    mapOf(
                        "shop" to categoryId,
                        "icon" to iconKey.toString(),
                        "group" to group.id,
                        "reason" to (it.message ?: it.javaClass.simpleName)
                    )
                )
            )
            false
        }
    }

    private fun buildSnapshot(
        category: SystemShopCategory,
        area: SystemShopRefreshArea,
        group: SystemShopRefreshGroup,
        version: Long
    ): SystemShopResolvedSnapshot {
        val products = if (group.randomRefresh) {
            val pool = group.inlinePool ?: group.poolRef?.let(::findGoodsPool)
            if (pool == null || pool.entries.isEmpty()) {
                expandGoodsReferences(group.fallbackRefs, category.currencyKey, category.id)
                    .mapIndexed { index, product ->
                        product.copy(
                            id = refreshProductId(area.iconKey, group.id, product.goodsId, index),
                            refreshArea = area.iconKey,
                            refreshGroupId = group.id,
                            sameForPlayersInGroup = area.sameForPlayersInGroup
                        )
                    }
            } else {
                pickPoolProducts(category, area, group, pool)
            }
        } else {
            expandGoodsReferences(group.goodsRefs.ifEmpty { group.fallbackRefs }, category.currencyKey, category.id)
                .mapIndexed { index, product ->
                    product.copy(
                        id = refreshProductId(area.iconKey, group.id, product.goodsId, index),
                        refreshArea = area.iconKey,
                        refreshGroupId = group.id,
                        sameForPlayersInGroup = area.sameForPlayersInGroup
                    )
                }
        }
        return SystemShopResolvedSnapshot(version = version, products = products)
    }

    private fun pickPoolProducts(
        category: SystemShopCategory,
        area: SystemShopRefreshArea,
        group: SystemShopRefreshGroup,
        pool: SystemShopGoodsPool
    ): List<SystemShopProduct> {
        val remaining = buildPoolCandidates(category, area, group, pool).toMutableList()
        val selected = ArrayList<SystemShopProduct>()
        val target = group.pick.coerceAtLeast(1).coerceAtMost(remaining.size)
        while (selected.size < target && remaining.isNotEmpty()) {
            val totalWeight = remaining.sumOf { it.first.coerceAtLeast(1) }
            var random = kotlin.random.Random.nextInt(totalWeight)
            val iterator = remaining.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                random -= entry.first.coerceAtLeast(1)
                if (random < 0) {
                    selected += entry.second
                    iterator.remove()
                    break
                }
            }
        }
        return selected
    }

    private fun buildPoolCandidates(
        category: SystemShopCategory,
        area: SystemShopRefreshArea,
        group: SystemShopRefreshGroup,
        pool: SystemShopGoodsPool
    ): List<Pair<Int, SystemShopProduct>> {
        val candidates = ArrayList<Pair<Int, SystemShopProduct>>()
        pool.entries.forEach { entry ->
            val bases = expandGoodsReferences(listOf(entry.goodsId), category.currencyKey, category.id)
            if (bases.isEmpty()) {
                warning(Texts.tr("@system-shop.logs.goods-ref-missing", mapOf("shop" to category.id, "goods" to entry.goodsId)))
            }
            bases.forEachIndexed { baseIndex, base ->
                candidates += entry.weight to buildRefreshProduct(category, area, group, base, entry, candidates.size + baseIndex)
            }
        }
        return candidates
    }

    private fun buildRefreshProduct(
        category: SystemShopCategory,
        area: SystemShopRefreshArea,
        group: SystemShopRefreshGroup,
        base: SystemShopProduct,
        entry: SystemShopGoodsPoolEntry,
        index: Int
    ): SystemShopProduct {
        val rawItem = entry.item?.clone() ?: base.item?.clone()
        val resolvedCurrency = entry.configuredCurrency?.trim()?.takeIf(String::isNotBlank)
            ?: base.currency
            .trim()
            .takeIf(String::isNotBlank)
            ?: category.currencyKey
        val mergedPriceConfig = mergePriceConfig(base.priceConfig, entry.priceConfig)
        return SystemShopProduct(
            id = refreshProductId(area.iconKey, group.id, base.goodsId, index),
            goodsId = base.goodsId,
            material = entry.item?.type?.name ?: base.material,
            amount = entry.amount ?: base.amount,
            name = entry.name ?: base.name,
            lore = entry.lore ?: base.lore,
            priceConfig = mergedPriceConfig,
            price = mergedPriceConfig.base,
            currency = resolvedCurrency,
            buyMax = entry.buyMax ?: base.buyMax,
            item = rawItem,
            source = base.source,
            refreshArea = area.iconKey,
            refreshGroupId = group.id,
            sameForPlayersInGroup = area.sameForPlayersInGroup
        )
    }

    private fun expandGoodsReferences(goodsRefs: List<String>, categoryCurrencyKey: String, shopId: String): List<SystemShopProduct> {
        return goodsRefs.flatMap { goodsId ->
            resolveGoodsReference(goodsId, categoryCurrencyKey, shopId)
        }
    }

    private fun refreshAreaKey(categoryId: String, iconKey: Char): String {
        return "${categoryId.lowercase(Locale.ROOT)}:$iconKey"
    }

    private fun refreshProductId(iconKey: Char, groupId: String, goodsId: String, index: Int): String {
        return "refresh_${iconKey}_${normalizeProductId(groupId) ?: groupId}_${goodsId}_$index"
    }

    private fun buildCurrentRefreshWindow(area: SystemShopRefreshArea): SystemShopRefreshWindowState {
        val now = System.currentTimeMillis()
        val next = nextRefreshTime(area, Date(now))
        return SystemShopRefreshWindowState(
            version = next,
            lastRefreshAt = now,
            nextRefreshAt = next
        )
    }

    private fun nextRefreshTime(area: SystemShopRefreshArea, after: Date): Long {
        val expression = CronExpression(area.cron)
        expression.timeZone = TimeZone.getTimeZone(area.timezone)
        return expression.getNextValidTimeAfter(after)?.time ?: Long.MAX_VALUE
    }

    private fun initializeRefreshState() {
        loadRefreshState()
        categories.values.forEach { category ->
            category.refreshAreas.forEach { (iconKey, area) ->
                val areaKey = refreshAreaKey(category.id, iconKey)
                val window = rollRefreshWindow(area, refreshWindows[areaKey])
                refreshWindows[areaKey] = window
                val areaState = refreshStates.computeIfAbsent(areaKey) { SystemShopRefreshAreaState() }
                if (area.sameForPlayersInGroup) {
                    area.groups.values
                        .filter { it.enabled }
                        .forEach { group ->
                            val groupState = areaState.groups.computeIfAbsent(group.id) { SystemShopRefreshGroupState() }
                            val snapshot = groupState.sharedSnapshot
                            if (snapshot == null || snapshot.version != window.version) {
                                groupState.sharedSnapshot = buildSnapshot(category, area, group, window.version)
                            }
                        }
                }
            }
        }
        saveRefreshState()
        refreshTask = submit(period = 20L) {
            tickRefreshWindows()
        }
    }

    private fun tickRefreshWindows() {
        val changedCategories = linkedSetOf<String>()
        categories.values.forEach { category ->
            category.refreshAreas.forEach { (iconKey, area) ->
                val areaKey = refreshAreaKey(category.id, iconKey)
                val previous = refreshWindows[areaKey]
                val rolled = rollRefreshWindow(area, previous)
                refreshWindows[areaKey] = rolled
                if (previous == null || previous.version != rolled.version) {
                    changedCategories += category.id
                }
            }
        }
        if (changedCategories.isNotEmpty()) {
            saveRefreshState()
            refreshOpenCategories(changedCategories)
        }
    }

    private fun rollRefreshWindow(area: SystemShopRefreshArea, current: SystemShopRefreshWindowState?): SystemShopRefreshWindowState {
        var state = current ?: buildCurrentRefreshWindow(area)
        val now = System.currentTimeMillis()
        while (now >= state.nextRefreshAt && state.nextRefreshAt != Long.MAX_VALUE) {
            val last = state.nextRefreshAt
            val next = nextRefreshTime(area, Date(last))
            state = SystemShopRefreshWindowState(
                version = next,
                lastRefreshAt = last,
                nextRefreshAt = next
            )
        }
        return state
    }

    private fun refreshOpenCategories(changedCategories: Set<String>) {
        val viewers = openCategoryViewers.toMap()
        viewers.forEach { (viewerId, categoryId) ->
            if (!changedCategories.contains(categoryId)) {
                return@forEach
            }
            val player = Bukkit.getPlayer(viewerId) ?: return@forEach
            if (!player.isOnline) {
                openCategoryViewers.remove(viewerId)
                renderedViews.remove(viewerId)
                return@forEach
            }
            openCategory(player, categoryId)
        }
    }

    private fun loadRefreshState() {
        val file = refreshStateFile()
        if (!file.exists()) {
            return
        }
        val yaml = YamlConfiguration.loadConfiguration(file)
        yaml.getConfigurationSection("windows")?.getKeys(false)?.forEach { areaKey ->
            val section = yaml.getConfigurationSection("windows.$areaKey") ?: return@forEach
            refreshWindows[areaKey] = SystemShopRefreshWindowState(
                version = section.getLong("version"),
                lastRefreshAt = section.getLong("last-refresh-at"),
                nextRefreshAt = section.getLong("next-refresh-at")
            )
        }
        yaml.getConfigurationSection("states")?.getKeys(false)?.forEach { areaKey ->
            val areaSection = yaml.getConfigurationSection("states.$areaKey") ?: return@forEach
            val areaState = SystemShopRefreshAreaState()
            areaSection.getConfigurationSection("groups")?.getKeys(false)?.forEach { groupId ->
                val groupSection = areaSection.getConfigurationSection("groups.$groupId") ?: return@forEach
                val groupState = SystemShopRefreshGroupState()
                deserializeSnapshot(groupSection.getConfigurationSection("shared"))?.let { groupState.sharedSnapshot = it }
                groupSection.getConfigurationSection("players")?.getKeys(false)?.forEach { playerId ->
                    val playerSection = groupSection.getConfigurationSection("players.$playerId") ?: return@forEach
                    deserializeSnapshot(playerSection)?.let { groupState.playerSnapshots[playerId] = it }
                }
                areaState.groups[groupId] = groupState
            }
            refreshStates[areaKey] = areaState
        }
    }

    private fun saveRefreshState() {
        val yaml = YamlConfiguration()
        refreshWindows.forEach { (areaKey, window) ->
            yaml.set("windows.$areaKey.version", window.version)
            yaml.set("windows.$areaKey.last-refresh-at", window.lastRefreshAt)
            yaml.set("windows.$areaKey.next-refresh-at", window.nextRefreshAt)
        }
        refreshStates.forEach { (areaKey, areaState) ->
            areaState.groups.forEach { (groupId, groupState) ->
                serializeSnapshot(yaml, "states.$areaKey.groups.$groupId.shared", groupState.sharedSnapshot)
                groupState.playerSnapshots.forEach { (playerId, snapshot) ->
                    serializeSnapshot(yaml, "states.$areaKey.groups.$groupId.players.$playerId", snapshot)
                }
            }
        }
        saveCategoryYaml(refreshStateFile(), yaml)
    }

    private fun serializeSnapshot(yaml: YamlConfiguration, basePath: String, snapshot: SystemShopResolvedSnapshot?) {
        if (snapshot == null) {
            return
        }
        yaml.set("$basePath.version", snapshot.version)
        snapshot.products.forEachIndexed { index, product ->
            val path = "$basePath.products.$index"
            yaml.set("$path.id", product.id)
            yaml.set("$path.goods-id", product.goodsId)
            yaml.set("$path.material", product.material)
            yaml.set("$path.amount", product.amount)
            yaml.set("$path.name", product.name)
            yaml.set("$path.lore", product.lore)
            writePriceConfig(yaml, "$path.price", product.priceConfig)
            yaml.set("$path.currency", product.currency)
            yaml.set("$path.buy-max", product.buyMax)
            yaml.set("$path.item", ItemStackCodec.encode(product.item))
            yaml.set("$path.refresh-area", product.refreshArea?.toString())
            yaml.set("$path.refresh-group-id", product.refreshGroupId)
            yaml.set("$path.same-for-players-in-group", product.sameForPlayersInGroup)
        }
    }

    private fun deserializeSnapshot(section: ConfigurationSection?): SystemShopResolvedSnapshot? {
        if (section == null || !section.contains("version")) {
            return null
        }
        val products = section.getConfigurationSection("products")?.getKeys(false)?.mapNotNull { key ->
            val productSection = section.getConfigurationSection("products.$key") ?: return@mapNotNull null
            val goodsId = productSection.getString("goods-id").orEmpty()
            val id = productSection.getString("id", goodsId).orEmpty()
            if (goodsId.isBlank() || id.isBlank()) {
                return@mapNotNull null
            }
            val material = productSection.getString("material", "STONE").orEmpty()
            val sourceTemplate = findGoodsTemplate(goodsId)
            val priceConfig = parsePriceConfig(productSection, "price", 0.0, "refresh-state:${productSection.currentPath}")
            SystemShopProduct(
                id = id,
                goodsId = goodsId,
                material = material,
                amount = productSection.getInt("amount", 1),
                name = productSection.getString("name", goodsId).orEmpty(),
                lore = productSection.getStringList("lore"),
                priceConfig = priceConfig,
                price = priceConfig.base,
                currency = productSection.getString("currency", currencyKey).orEmpty(),
                buyMax = productSection.getInt("buy-max", 64),
                item = ItemStackCodec.decode(productSection.getString("item")),
                source = sourceTemplate?.source ?: SystemShopProductSource(
                    type = SystemShopProductSourceType.REFERENCE,
                    configFile = refreshStateFile(),
                    configPath = null
                ),
                refreshArea = productSection.getString("refresh-area")?.firstOrNull(),
                refreshGroupId = productSection.getString("refresh-group-id"),
                sameForPlayersInGroup = productSection.getBoolean("same-for-players-in-group", true)
            )
        }.orEmpty()
        return SystemShopResolvedSnapshot(
            version = section.getLong("version"),
            products = products
        )
    }

    private fun refreshStateFile(): File {
        val folder = File(ConfigFiles.dataFolder(), "SystemShop")
        folder.mkdirs()
        return File(folder, "refresh-state.yml")
    }

    private fun loadCategory(file: File) {
        val yaml = YamlConfiguration.loadConfiguration(file)
        val id = yaml.getString("id", file.nameWithoutExtension).orEmpty()
        val menu = MenuLoader.load(file)
        val categoryCurrencyKey = EconomyModule.configuredKey(yaml, "Currency", currencyKey)
        val refreshAreas = parseRefreshAreas(yaml, id)
        val inlineGoods = yaml.getConfigurationSection("goods")
        val products = if (inlineGoods != null) {
            inlineGoods.getKeys(false).mapNotNull { key ->
                val section = inlineGoods.getConfigurationSection(key) ?: return@mapNotNull null
                parseProductTemplate(
                    id = key,
                    section = section,
                    source = SystemShopProductSource(
                        type = SystemShopProductSourceType.INLINE,
                        configFile = file,
                        configPath = "goods.$key"
                    )
                ).resolve(categoryCurrencyKey)
            }
        } else {
            readGoodsReferences(yaml).flatMap { goodsId ->
                resolveGoodsReference(goodsId, categoryCurrencyKey, id)
            }
        }
        categories[id] = SystemShopCategory(
            id = id,
            menu = menu,
            currencyKey = categoryCurrencyKey,
            products = products,
            refreshAreas = refreshAreas,
            shopFile = file
        )
    }

    private fun parseProductTemplate(
        id: String,
        section: ConfigurationSection,
        source: SystemShopProductSource
    ): SystemShopProductTemplate {
        val configuredItem = section.getItemStack("item")?.clone()
        val priceConfig = parsePriceConfig(section, "price", 0.0, sectionDisplayTarget(source.configFile, source.configPath))
        return SystemShopProductTemplate(
            id = id,
            material = section.getString("material", configuredItem?.type?.name ?: "STONE").orEmpty(),
            amount = section.getInt("amount", configuredItem?.amount ?: 1),
            name = section.getString("name", configuredItem?.itemMeta?.displayName ?: id).orEmpty(),
            lore = section.getStringList("lore").ifEmpty { configuredItem?.itemMeta?.lore ?: emptyList() },
            priceConfig = priceConfig,
            configuredCurrency = section.getString("currency")
                ?.trim()
                ?.takeIf(String::isNotBlank),
            buyMax = section.getInt("buy-max", 64),
            item = configuredItem,
            source = source
        )
    }

    private fun loadGoodsEntry(file: File) {
        val yaml = YamlConfiguration.loadConfiguration(file)
        val resolvedId = normalizeProductId(yaml.getString("id", file.nameWithoutExtension)) ?: file.nameWithoutExtension
        if ((goodsReferenceIds() + goodsPools.keys).any { it.equals(resolvedId, true) }) {
            warning(Texts.tr("@system-shop.logs.goods-id-duplicate", mapOf("id" to resolvedId, "file" to file.name)))
            return
        }
        if (isGoodsPoolDefinition(yaml)) {
            goodsPools[resolvedId] = parseGoodsPool(resolvedId, yaml, file)
        } else if (isGoodsGroupDefinition(yaml)) {
            goodsGroups[resolvedId] = parseGoodsGroup(resolvedId, yaml, file)
        } else {
            goodsTemplates[resolvedId] = parseProductTemplate(
                id = resolvedId,
                section = yaml,
                source = SystemShopProductSource(
                    type = SystemShopProductSourceType.REFERENCE,
                    configFile = file,
                    configPath = null
                )
            )
        }
    }

    private fun ensureGoodsReferencesMode(categoryFile: File, yaml: YamlConfiguration, categoryId: String): MutableList<String> {
        val inlineGoods = yaml.getConfigurationSection("goods") ?: return readGoodsReferences(yaml).toMutableList()
        val references = ArrayList<String>()
        inlineGoods.getKeys(false).forEach { key ->
            val section = inlineGoods.getConfigurationSection(key) ?: return@forEach
            val template = parseProductTemplate(
                id = key,
                section = section,
                source = SystemShopProductSource(
                    type = SystemShopProductSourceType.INLINE,
                    configFile = categoryFile,
                    configPath = "goods.$key"
                )
            )
            references += persistGoodsTemplate(template, key, categoryId)
        }
        writeGoodsReferences(yaml, references)
        return references.toMutableList()
    }

    private fun persistGoodsTemplate(
        template: SystemShopProductTemplate,
        preferredId: String,
        categoryId: String
    ): String {
        val baseId = normalizeProductId(preferredId) ?: "product"
        val prefixedBase = "${normalizeProductId(categoryId) ?: "shop"}_$baseId"
        val candidates = linkedSetOf(baseId, prefixedBase)
        candidates.forEach { candidate ->
            resolveExistingOrWriteGoodsTemplate(candidate, template)?.let { return it }
        }
        var index = 2
        while (true) {
            resolveExistingOrWriteGoodsTemplate("${prefixedBase}_$index", template)?.let { return it }
            index++
        }
    }

    private fun resolveExistingOrWriteGoodsTemplate(candidateId: String, template: SystemShopProductTemplate): String? {
        val goodsFolder = File(ConfigFiles.dataFolder(), "SystemShop/goods")
        goodsFolder.mkdirs()
        val goodsFile = File(goodsFolder, "$candidateId.yml")
        if (!goodsFile.exists()) {
            writeGoodsTemplateFile(goodsFile, candidateId, template)
            return candidateId
        }
        val existingYaml = YamlConfiguration.loadConfiguration(goodsFile)
        val existing = parseProductTemplate(
            id = candidateId,
            section = existingYaml,
            source = SystemShopProductSource(
                type = SystemShopProductSourceType.REFERENCE,
                configFile = goodsFile,
                configPath = null
            )
        )
        return if (goodsTemplatesEquivalent(existing, template)) candidateId else null
    }

    private fun writeGoodsTemplateFile(file: File, productId: String, template: SystemShopProductTemplate) {
        val yaml = YamlConfiguration()
        yaml.set("id", productId)
        yaml.set("item", template.item?.clone())
        yaml.set("material", template.material)
        yaml.set("amount", template.amount)
        writePriceConfig(yaml, "price", template.priceConfig)
        yaml.set("buy-max", template.buyMax)
        yaml.set("name", template.name)
        yaml.set("lore", template.lore)
        yaml.set("currency", template.configuredCurrency)
        saveCategoryYaml(file, yaml)?.let { throw it }
    }

    private fun goodsTemplatesEquivalent(left: SystemShopProductTemplate, right: SystemShopProductTemplate): Boolean {
        return left.material.equals(right.material, true) &&
            left.amount == right.amount &&
            left.name == right.name &&
            left.lore == right.lore &&
            left.priceConfig == right.priceConfig &&
            left.configuredCurrency == right.configuredCurrency &&
            left.buyMax == right.buyMax &&
            itemsEquivalent(left.item, right.item)
    }

    private fun itemsEquivalent(left: ItemStack?, right: ItemStack?): Boolean {
        if (left == null && right == null) {
            return true
        }
        if (left == null || right == null) {
            return false
        }
        val leftCopy = left.clone()
        val rightCopy = right.clone()
        val sameAmount = leftCopy.amount == rightCopy.amount
        leftCopy.amount = 1
        rightCopy.amount = 1
        return sameAmount && leftCopy.isSimilar(rightCopy)
    }

    private fun goodsReferenceIds(): List<String> {
        return (goodsTemplates.keys + goodsGroups.keys).distinctBy { it.lowercase() }
    }

    private fun resolveGoodsReferenceId(productId: String): String? {
        return goodsReferenceIds().firstOrNull { it.equals(productId, true) }
    }

    private fun findGoodsTemplate(productId: String): SystemShopProductTemplate? {
        return goodsTemplates.entries.firstOrNull { it.key.equals(productId, true) }?.value
    }

    private fun findGoodsGroup(groupId: String): SystemShopGoodsGroup? {
        return goodsGroups.entries.firstOrNull { it.key.equals(groupId, true) }?.value
    }

    private fun findGoodsPool(poolId: String): SystemShopGoodsPool? {
        return goodsPools.entries.firstOrNull { it.key.equals(poolId, true) }?.value
    }

    private fun listedCategoryIds(productId: String): List<String> {
        return categories.values
            .filter { category -> category.products.any { it.id.equals(productId, true) } }
            .map { it.id }
            .sorted()
    }

    private fun primeAdminSelection(player: Player, productId: String) {
        val firstCategory = listedCategoryIds(productId).firstOrNull()
        if (firstCategory == null) {
            adminSelections.remove(player.uniqueId)
        } else {
            adminSelections[player.uniqueId] = AdminGoodsSelection(firstCategory, productId)
        }
    }

    private fun goodsTemplatePreview(
        template: SystemShopProductTemplate,
        displayName: String,
        displayLore: List<String>
    ): ItemStack {
        val stack = (template.item?.clone()
            ?: ItemStack(Material.matchMaterial(template.material) ?: Material.STONE, template.amount.coerceAtLeast(1))).apply {
            amount = template.amount.coerceAtLeast(1)
        }
        stack.itemMeta = stack.itemMeta?.apply {
            MenuRenderer.decorate(this, Texts.color(displayName), displayLore.map(Texts::color))
        }
        return stack
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

    private fun isGoodsGroupDefinition(yaml: YamlConfiguration): Boolean {
        val kind = yaml.getString("Kind")
            ?.trim()
            ?.ifBlank { null }
            ?: yaml.getString("kind")
                ?.trim()
                ?.ifBlank { null }
        return when {
            kind.equals("group", true) -> true
            kind.equals("product", true) -> false
            yaml.isList("entries") -> true
            yaml.isList("goods") -> true
            else -> false
        }
    }

    private fun parseGoodsGroup(id: String, yaml: YamlConfiguration, file: File): SystemShopGoodsGroup {
        val entries = yaml.getStringList("entries")
            .ifEmpty { yaml.getStringList("goods") }
            .mapNotNull { entry ->
                entry?.trim()?.takeIf(String::isNotBlank)
            }
        return SystemShopGoodsGroup(id = id, entries = entries, sourceFile = file)
    }

    private fun isGoodsPoolDefinition(yaml: YamlConfiguration): Boolean {
        val kind = yaml.getString("Kind")
            ?.trim()
            ?.ifBlank { null }
            ?: yaml.getString("kind")
                ?.trim()
                ?.ifBlank { null }
        return when {
            kind.equals("pool", true) -> true
            kind.equals("product", true) || kind.equals("group", true) -> false
            yaml.isConfigurationSection("entries") -> true
            else -> false
        }
    }

    private fun parseGoodsPool(id: String, yaml: YamlConfiguration, file: File): SystemShopGoodsPool {
        val entriesSection = yaml.getConfigurationSection("entries")
        val entries = entriesSection?.getKeys(false)?.mapNotNull { entryId ->
            val section = entriesSection.getConfigurationSection(entryId) ?: return@mapNotNull null
            val goodsId = section.getString("goods")?.trim().orEmpty()
            if (goodsId.isBlank()) {
                null
            } else {
                SystemShopGoodsPoolEntry(
                    id = entryId,
                    goodsId = goodsId,
                    weight = section.getInt("weight", 1).coerceAtLeast(1),
                    amount = section.getInt("amount").takeIf { section.contains("amount") },
                    priceConfig = parsePriceConfigOrNull(section, "price", "pool:${file.name}:$entryId"),
                    configuredCurrency = section.getString("currency")?.trim()?.takeIf(String::isNotBlank),
                    buyMax = section.getInt("buy-max").takeIf { section.contains("buy-max") },
                    name = section.getString("name")?.takeIf(String::isNotBlank),
                    lore = section.getStringList("lore").takeIf { it.isNotEmpty() },
                    item = section.getItemStack("item")?.clone()
                )
            }
        }.orEmpty()
        return SystemShopGoodsPool(id = id, entries = entries, sourceFile = file)
    }

    private fun parseRefreshAreas(yaml: YamlConfiguration, shopId: String): Map<Char, SystemShopRefreshArea> {
        val iconSection = yaml.getConfigurationSection("icons") ?: return emptyMap()
        val direct = LinkedHashMap<Char, SystemShopRefreshArea>()
        val refs = LinkedHashMap<Char, Char>()
        iconSection.getKeys(false).forEach { key ->
            val child = iconSection.getConfigurationSection(key) ?: return@forEach
            if (!child.getString("mode").equals("goods", true)) {
                return@forEach
            }
            val iconKey = key.first()
            val ref = child.getString("refresh-ref")
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.firstOrNull()
            if (ref != null) {
                refs[iconKey] = ref
                return@forEach
            }
            val refreshSection = child.getConfigurationSection("refresh") ?: return@forEach
            parseRefreshArea(iconKey, refreshSection, shopId)?.let {
                direct[iconKey] = it
            }
        }
        refs.forEach { (icon, target) ->
            val targetArea = direct[target]
            if (targetArea == null) {
                warning(Texts.tr("@system-shop.logs.refresh-ref-missing", mapOf("shop" to shopId, "icon" to icon.toString(), "target" to target.toString())))
            } else {
                direct[icon] = targetArea.copy(iconKey = icon)
            }
        }
        return direct
    }

    private fun parseRefreshArea(
        iconKey: Char,
        section: ConfigurationSection,
        shopId: String
    ): SystemShopRefreshArea? {
        val cron = section.getString("Cron")?.trim().orEmpty()
        if (cron.isBlank()) {
            return null
        }
        if (!runCatching { CronExpression(cron) }.isSuccess) {
            warning(Texts.tr("@system-shop.logs.refresh-cron-invalid", mapOf("shop" to shopId, "icon" to iconKey.toString(), "cron" to cron)))
            return null
        }
        val timezoneId = section.getString("Timezone", "Asia/Shanghai").orEmpty().ifBlank { "Asia/Shanghai" }
        if (!validTimezone(timezoneId)) {
            warning(Texts.tr("@system-shop.logs.refresh-timezone-invalid", mapOf("shop" to shopId, "icon" to iconKey.toString(), "timezone" to timezoneId)))
            return null
        }
        val groupsSection = section.getConfigurationSection("groups") ?: return null
        val groups = linkedMapOf<String, SystemShopRefreshGroup>()
        groupsSection.getKeys(false).forEach { groupId ->
            val groupSection = groupsSection.getConfigurationSection(groupId) ?: return@forEach
            groups[groupId] = SystemShopRefreshGroup(
                id = groupId,
                enabled = groupSection.getBoolean("Enabled", true),
                matchScript = readStringList(groupSection, "Match-Script", "match-script"),
                randomRefresh = groupSection.getBoolean("Random-Refresh", groupSection.contains("Pool") || groupSection.contains("Pool-Ref")),
                pick = groupSection.getInt("Pick", 1).coerceAtLeast(1),
                goodsRefs = readStringList(groupSection, "Goods", "goods"),
                poolRef = groupSection.getString("Pool-Ref")?.trim()?.takeIf(String::isNotBlank),
                inlinePool = groupSection.getConfigurationSection("Pool")?.let { parseInlinePool(shopId, iconKey, groupId, it) },
                fallbackRefs = readStringList(groupSection, "Fallback", "fallback")
            )
        }
        if (groups.isEmpty()) {
            return null
        }
        return SystemShopRefreshArea(
            iconKey = iconKey,
            enabled = section.getBoolean("Enabled", true),
            cron = cron,
            timezone = timezoneId,
            sameForPlayersInGroup = section.getBoolean("Same-For-Players-In-Group", true),
            groups = LinkedHashMap(groups)
        )
    }

    private fun parseInlinePool(
        shopId: String,
        iconKey: Char,
        groupId: String,
        section: ConfigurationSection
    ): SystemShopGoodsPool {
        val yaml = YamlConfiguration()
        section.getKeys(false).forEach { key ->
            yaml.set(key, section.get(key))
        }
        return parseGoodsPool("${shopId}_${iconKey}_${groupId}_inline", yaml, File(ConfigFiles.dataFolder(), "SystemShop/shops/$shopId.yml"))
    }

    private fun readStringList(section: ConfigurationSection, vararg paths: String): List<String> {
        paths.forEach { path ->
            if (section.isList(path)) {
                return section.getStringList(path).mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
            }
            val single = section.getString(path)?.trim()?.takeIf(String::isNotBlank)
            if (single != null) {
                return listOf(single)
            }
        }
        return emptyList()
    }

    private fun validTimezone(id: String): Boolean {
        return runCatching { TimeZone.getTimeZone(id) }.getOrNull()?.id?.isNotBlank() == true
    }

    private fun resolveGoodsReference(
        goodsId: String,
        categoryCurrencyKey: String,
        shopId: String,
        chain: List<String> = emptyList()
    ): List<SystemShopProduct> {
        val normalized = goodsId.trim()
        if (normalized.isBlank()) {
            return emptyList()
        }
        if (chain.any { it.equals(normalized, true) }) {
            warning(
                Texts.tr(
                    "@system-shop.logs.goods-group-loop",
                    mapOf("chain" to (chain + normalized).joinToString(" -> "))
                )
            )
            return emptyList()
        }
        val template = findGoodsTemplate(normalized)
        if (template != null) {
            return listOf(template.resolve(categoryCurrencyKey))
        }
        val group = findGoodsGroup(normalized)
        if (group != null) {
            return group.entries.flatMap { entry ->
                val resolved = resolveGoodsReference(entry, categoryCurrencyKey, shopId, chain + group.id)
                if (resolved.isEmpty() && findGoodsTemplate(entry) == null && findGoodsGroup(entry) == null) {
                    warning(
                        Texts.tr(
                            "@system-shop.logs.goods-group-ref-missing",
                            mapOf("group" to group.id, "goods" to entry)
                        )
                    )
                }
                resolved
            }
        }
        warning(Texts.tr("@system-shop.logs.goods-ref-missing", mapOf("shop" to shopId, "goods" to normalized)))
        return emptyList()
    }

    private fun findProduct(categoryId: String, productId: String): SystemShopProduct? {
        return categories[categoryId]?.products?.firstOrNull { it.id.equals(productId, true) }
    }

    private fun resolveProductSection(yaml: YamlConfiguration, basePath: String?): ConfigurationSection? {
        return if (basePath.isNullOrBlank()) {
            yaml
        } else {
            yaml.getConfigurationSection(basePath)
        }
    }

    private fun readGoodsReferences(yaml: YamlConfiguration): List<String> {
        val raw = yaml.get("goods") ?: return emptyList()
        return when (raw) {
            is String -> listOfNotNull(raw.trim().takeIf(String::isNotBlank))
            is Iterable<*> -> raw.mapNotNull { entry ->
                entry?.toString()?.trim()?.takeIf(String::isNotBlank)
            }
            is Array<*> -> raw.mapNotNull { entry ->
                entry?.toString()?.trim()?.takeIf(String::isNotBlank)
            }
            else -> emptyList()
        }
    }

    private fun writeGoodsReferences(yaml: YamlConfiguration, references: List<String>) {
        yaml.set("goods", references.distinctBy { it.lowercase() })
    }

    private fun resolveProductForPlayer(player: Player, categoryId: String, product: SystemShopProduct): SystemShopProduct {
        val resolvedPrice = resolvePriceForPlayer(player, categoryId, product)
        return product.copy(
            price = resolvedPrice.final,
            appliedDiscounts = resolvedPrice.appliedDiscounts
        )
    }

    private fun resolvePriceForPlayer(player: Player, categoryId: String, product: SystemShopProduct): SystemShopResolvedPrice {
        val base = product.basePrice.coerceAtLeast(0.0)
        if (product.priceConfig.discounts.isEmpty()) {
            return SystemShopResolvedPrice(
                base = base,
                final = base.coerceAtLeast(MIN_FINAL_PRICE),
                appliedDiscounts = emptyList(),
                percentTotal = 0.0,
                amountOffTotal = 0.0,
                surchargeTotal = 0.0
            )
        }
        val applied = ArrayList<SystemShopAppliedDiscount>()
        val appliedRulePairs = ArrayList<Pair<String, SystemShopDiscountRule>>()
        var percentTotal = 0.0
        var amountOffTotal = 0.0
        var surchargeTotal = 0.0
        product.priceConfig.discounts.withIndex()
            .sortedWith(compareByDescending<IndexedValue<SystemShopDiscountRule>> { it.value.priority }.thenBy { it.index })
            .forEach { indexed ->
                val rule = indexed.value
                val ruleId = rule.id?.takeIf(String::isNotBlank) ?: "discount_${indexed.index + 1}"
                if (!rule.isEffective() ||
                    !shouldApplyDiscount(player, categoryId, product, rule, indexed.index) ||
                    !canStackDiscount(ruleId, rule, appliedRulePairs)
                ) {
                    return@forEach
                }
                percentTotal += rule.percent
                amountOffTotal += rule.amountOff
                surchargeTotal += rule.surcharge
                applied += SystemShopAppliedDiscount(
                    id = ruleId,
                    percent = rule.percent,
                    amountOff = rule.amountOff,
                    surcharge = rule.surcharge
                )
                appliedRulePairs += ruleId to rule
            }
        val cappedPercent = percentTotal.coerceIn(0.0, 100.0)
        val final = (base - (base * cappedPercent / 100.0) - amountOffTotal + surchargeTotal)
            .coerceAtLeast(MIN_FINAL_PRICE)
        return SystemShopResolvedPrice(
            base = base,
            final = final,
            appliedDiscounts = applied,
            percentTotal = cappedPercent,
            amountOffTotal = amountOffTotal,
            surchargeTotal = surchargeTotal
        )
    }

    private fun shouldApplyDiscount(
        player: Player,
        categoryId: String,
        product: SystemShopProduct,
        rule: SystemShopDiscountRule,
        index: Int
    ): Boolean {
        if (rule.condition.isEmpty()) {
            return true
        }
        val ruleId = rule.id?.takeIf(String::isNotBlank) ?: "discount_${index + 1}"
        return runCatching {
            val options = ScriptOptions.builder()
                .sender(player)
                .set("player", player)
                .set("category", categoryId)
                .set("category_id", categoryId)
                .set("product", product.id)
                .set("product_id", product.id)
                .set("goodsId", product.goodsId)
                .set("goods_id", product.goodsId)
                .set("basePrice", product.basePrice)
                .set("base_price", product.basePrice)
                .set("currency", product.currency)
                .set("discount_id", ruleId)
                .detailError(true)
                .build()
            val result = KetherShell.eval(rule.condition, options).get(3, TimeUnit.SECONDS)
            when (result) {
                is Boolean -> result
                is Number -> result.toInt() != 0
                else -> result?.toString()?.equals("true", true) == true
            }
        }.getOrElse {
            warning(
                Texts.tr(
                    "@system-shop.logs.discount-condition-failed",
                    mapOf(
                        "rule" to ruleId,
                        "product" to product.id,
                        "reason" to (it.message ?: it.javaClass.simpleName)
                    )
                )
            )
            false
        }
    }

    private fun canStackDiscount(
        ruleId: String,
        rule: SystemShopDiscountRule,
        selected: List<Pair<String, SystemShopDiscountRule>>
    ): Boolean {
        if (selected.isEmpty()) {
            return true
        }
        val normalizedRuleId = ruleId.lowercase(Locale.ROOT)
        val whitelist = rule.whitelist.map { it.lowercase(Locale.ROOT) }.toSet()
        val blacklist = rule.blacklist.map { it.lowercase(Locale.ROOT) }.toSet()
        return selected.all { (selectedId, selectedRule) ->
            val normalizedSelectedId = selectedId.lowercase(Locale.ROOT)
            val selectedWhitelist = selectedRule.whitelist.map { it.lowercase(Locale.ROOT) }.toSet()
            val selectedBlacklist = selectedRule.blacklist.map { it.lowercase(Locale.ROOT) }.toSet()
            when {
                blacklist.contains(normalizedSelectedId) -> false
                selectedBlacklist.contains(normalizedRuleId) -> false
                whitelist.isNotEmpty() && !whitelist.contains(normalizedSelectedId) -> false
                selectedWhitelist.isNotEmpty() && !selectedWhitelist.contains(normalizedRuleId) -> false
                else -> true
            }
        }
    }

    private fun discountSummary(product: SystemShopProduct): String {
        if (product.appliedDiscounts.isEmpty()) {
            return ""
        }
        return product.appliedDiscounts.joinToString("; ") { applied ->
            val parts = ArrayList<String>()
            if (applied.percent > 0.0) {
                parts += "-${trimDouble(applied.percent)}%"
            }
            if (applied.amountOff > 0.0) {
                parts += "-${trimDouble(applied.amountOff)}"
            }
            if (applied.surcharge > 0.0) {
                parts += "+${trimDouble(applied.surcharge)}"
            }
            "${applied.id}(${parts.joinToString(", ")})"
        }
    }

    private fun parsePriceConfig(
        section: ConfigurationSection,
        path: String,
        defaultBase: Double,
        target: String
    ): SystemShopPriceConfig {
        val fallback = defaultBase.coerceAtLeast(0.0)
        if (!section.contains(path)) {
            return SystemShopPriceConfig(fallback)
        }
        val nested = section.getConfigurationSection(path)
        if (nested != null) {
            val base = if (nested.contains("base")) {
                nested.getDouble("base").coerceAtLeast(0.0)
            } else {
                warning(Texts.tr("@system-shop.logs.price-config-invalid", mapOf("target" to target, "reason" to "missing price.base")))
                fallback
            }
            return SystemShopPriceConfig(
                base = base,
                discounts = parseDiscountRules(nested, target)
            )
        }
        val raw = section.get(path)
        return when (raw) {
            is Number -> SystemShopPriceConfig(raw.toDouble().coerceAtLeast(0.0))
            is String -> raw.toDoubleOrNull()?.let { SystemShopPriceConfig(it.coerceAtLeast(0.0)) } ?: run {
                warning(Texts.tr("@system-shop.logs.price-config-invalid", mapOf("target" to target, "reason" to "invalid scalar price")))
                SystemShopPriceConfig(fallback)
            }
            else -> {
                warning(Texts.tr("@system-shop.logs.price-config-invalid", mapOf("target" to target, "reason" to "unsupported price value")))
                SystemShopPriceConfig(fallback)
            }
        }
    }

    private fun parsePriceConfigOrNull(
        section: ConfigurationSection,
        path: String,
        target: String
    ): SystemShopPriceConfig? {
        if (!section.contains(path)) {
            return null
        }
        return parsePriceConfig(section, path, 0.0, target)
    }

    private fun parseDiscountRules(section: ConfigurationSection, target: String): List<SystemShopDiscountRule> {
        val raw = section.getList("discounts") ?: return emptyList()
        return raw.mapIndexedNotNull { index, entry ->
            if (entry !is Map<*, *>) {
                warning(Texts.tr("@system-shop.logs.price-config-invalid", mapOf("target" to target, "reason" to "discount #${index + 1} is not a map")))
                return@mapIndexedNotNull null
            }
            parseDiscountRule(entry, index, target)
        }
    }

    private fun parseDiscountRule(
        raw: Map<*, *>,
        index: Int,
        target: String
    ): SystemShopDiscountRule? {
        val enabled = mapBoolean(raw, "enabled") ?: true
        val percent = (mapDouble(raw, "percent") ?: 0.0).coerceAtLeast(0.0)
        val amountOff = (mapDouble(raw, "amount-off", "amountOff") ?: 0.0).coerceAtLeast(0.0)
        val surcharge = (mapDouble(raw, "surcharge") ?: 0.0).coerceAtLeast(0.0)
        if (percent <= 0.0 && amountOff <= 0.0 && surcharge <= 0.0) {
            warning(Texts.tr("@system-shop.logs.price-config-invalid", mapOf("target" to target, "reason" to "discount #${index + 1} has no numeric effect")))
            return null
        }
        return SystemShopDiscountRule(
            id = mapString(raw, "id")?.takeIf(String::isNotBlank),
            enabled = enabled,
            priority = (mapDouble(raw, "priority") ?: 0.0).toInt(),
            condition = mapStringList(raw, "condition", "Condition"),
            whitelist = mapStringList(raw, "whitelist", "white-list", "whiteList", "Whitelist"),
            blacklist = mapStringList(raw, "blacklist", "black-list", "blackList", "Blacklist"),
            percent = percent,
            amountOff = amountOff,
            surcharge = surcharge
        )
    }

    private fun mergePriceConfig(base: SystemShopPriceConfig, override: SystemShopPriceConfig?): SystemShopPriceConfig {
        if (override == null) {
            return base
        }
        return SystemShopPriceConfig(
            base = override.base.coerceAtLeast(0.0),
            discounts = base.discounts + override.discounts
        )
    }

    private fun writePriceConfig(yaml: YamlConfiguration, path: String, priceConfig: SystemShopPriceConfig) {
        if (priceConfig.discounts.isEmpty()) {
            yaml.set(path, priceConfig.base)
            return
        }
        yaml.set(path, null)
        yaml.set("$path.base", priceConfig.base)
        yaml.set(
            "$path.discounts",
            priceConfig.discounts.map { rule ->
                linkedMapOf<String, Any>().apply {
                    rule.id?.takeIf(String::isNotBlank)?.let { put("id", it) }
                    if (!rule.enabled) {
                        put("enabled", false)
                    }
                    if (rule.priority != 0) {
                        put("priority", rule.priority)
                    }
                    if (rule.condition.isNotEmpty()) {
                        put("condition", rule.condition)
                    }
                    if (rule.whitelist.isNotEmpty()) {
                        put("whitelist", rule.whitelist)
                    }
                    if (rule.blacklist.isNotEmpty()) {
                        put("blacklist", rule.blacklist)
                    }
                    if (rule.percent > 0.0) {
                        put("percent", rule.percent)
                    }
                    if (rule.amountOff > 0.0) {
                        put("amount-off", rule.amountOff)
                    }
                    if (rule.surcharge > 0.0) {
                        put("surcharge", rule.surcharge)
                    }
                }
            }
        )
    }

    private fun mapString(raw: Map<*, *>, vararg keys: String): String? {
        return mapValue(raw, *keys)?.toString()?.trim()?.takeIf(String::isNotBlank)
    }

    private fun mapDouble(raw: Map<*, *>, vararg keys: String): Double? {
        val value = mapValue(raw, *keys) ?: return null
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        }
    }

    private fun mapBoolean(raw: Map<*, *>, vararg keys: String): Boolean? {
        val value = mapValue(raw, *keys) ?: return null
        return when (value) {
            is Boolean -> value
            is String -> when (value.trim().lowercase(Locale.ROOT)) {
                "true", "yes", "y", "1" -> true
                "false", "no", "n", "0" -> false
                else -> null
            }
            is Number -> value.toInt() != 0
            else -> null
        }
    }

    private fun mapStringList(raw: Map<*, *>, vararg keys: String): List<String> {
        val value = mapValue(raw, *keys) ?: return emptyList()
        return when (value) {
            is Iterable<*> -> value.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
            is Array<*> -> value.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
            else -> value.toString().trim().takeIf(String::isNotBlank)?.let(::listOf).orEmpty()
        }
    }

    private fun mapValue(raw: Map<*, *>, vararg keys: String): Any? {
        val normalizedKeys = keys.map { it.trim().lowercase(Locale.ROOT) }
        return raw.entries.firstOrNull { entry ->
            normalizedKeys.contains(entry.key?.toString()?.trim()?.lowercase(Locale.ROOT))
        }?.value
    }

    private fun sectionDisplayTarget(file: File, basePath: String?): String {
        return if (basePath.isNullOrBlank()) file.name else "${file.name}#$basePath"
    }

    private fun writeProductSnapshot(
        yaml: YamlConfiguration,
        basePath: String?,
        stack: ItemStack,
        price: Double,
        buyMax: Int?
    ) {
        val meta = stack.itemMeta
        setProductValue(yaml, basePath, "item", stack.clone())
        setProductValue(yaml, basePath, "material", stack.type.name)
        setProductValue(yaml, basePath, "amount", stack.amount)
        setBasePrice(yaml, basePath, price)
        setProductValue(yaml, basePath, "buy-max", (buyMax ?: stack.maxStackSize).coerceAtLeast(1))
        setProductValue(yaml, basePath, "name", meta?.displayName?.takeIf(String::isNotBlank) ?: defaultProductName(stack))
        setProductValue(yaml, basePath, "lore", meta?.lore ?: emptyList<String>())
    }

    private fun setBasePrice(yaml: YamlConfiguration, basePath: String?, value: Double) {
        val path = if (basePath.isNullOrBlank()) "price" else "$basePath.price"
        if (yaml.isConfigurationSection(path)) {
            yaml.set("$path.base", value)
        } else {
            yaml.set(path, value)
        }
    }

    private fun setProductValue(yaml: YamlConfiguration, basePath: String?, key: String, value: Any?) {
        val path = if (basePath.isNullOrBlank()) key else "$basePath.$key"
        yaml.set(path, value)
    }

    private fun playerPlaceholders(player: Player): Map<String, String> {
        return mapOf("player" to player.name, "money" to EconomyModule.formatAmount(currencyKey, EconomyModule.balance(player, currencyKey)))
    }

    private fun trimDouble(value: Double): String {
        return if (value % 1.0 == 0.0) value.toInt().toString() else "%.2f".format(value)
    }

    private fun formatRefreshTime(epochMillis: Long): String {
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss").apply {
                timeZone = TimeZone.getTimeZone("Asia/Shanghai")
            }.format(Date(epochMillis))
        }.getOrDefault(epochMillis.toString())
    }

    private fun canFit(currentContents: List<ItemStack>, incoming: List<ItemStack>): Boolean {
        val virtual = ArrayList<ItemStack?>()
        currentContents.forEach { virtual += it.clone() }
        while (virtual.size < 36) {
            virtual += null
        }
        incoming.forEach { incomingStack ->
            var remaining = incomingStack.amount
            virtual.forEachIndexed { index, content ->
                if (remaining <= 0) {
                    return@forEachIndexed
                }
                if (content != null && content.isSimilar(incomingStack) && content.amount < content.maxStackSize) {
                    val free = content.maxStackSize - content.amount
                    val take = remaining.coerceAtMost(free)
                    content.amount += take
                    remaining -= take
                    virtual[index] = content
                }
            }
            while (remaining > 0) {
                val emptyIndex = virtual.indexOfFirst { it == null || it.type == org.bukkit.Material.AIR }
                if (emptyIndex == -1) {
                    return false
                }
                val placed = incomingStack.clone()
                placed.amount = remaining.coerceAtMost(placed.maxStackSize)
                virtual[emptyIndex] = placed
                remaining -= placed.amount
            }
        }
        return true
    }

    private fun nextProductId(existingIds: Collection<String>, inHand: ItemStack, preferredId: String?): String {
        fun containsId(target: String): Boolean {
            return existingIds.any { it.equals(target, true) }
        }
        val requested = normalizeProductId(preferredId)
        if (requested != null) {
            if (!containsId(requested)) {
                return requested
            }
            var duplicateIndex = 2
            while (containsId("${requested}_$duplicateIndex")) {
                duplicateIndex++
            }
            return "${requested}_$duplicateIndex"
        }
        val base = normalizeProductId(inHand.type.name.lowercase()) ?: "product"
        if (!containsId(base)) {
            return base
        }
        var index = 2
        while (containsId("${base}_$index")) {
            index++
        }
        return "${base}_$index"
    }

    private fun normalizeProductId(raw: String?): String? {
        val normalized = raw?.trim().orEmpty()
        if (normalized.isBlank()) {
            return null
        }
        return normalized
            .replace('.', '_')
            .replace(':', '_')
            .replace('/', '_')
            .replace('\\', '_')
            .replace(' ', '_')
    }

    private fun defaultProductName(item: ItemStack): String {
        return item.type.name.lowercase()
            .split('_')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
    }

    private fun saveCategoryYaml(file: File, yaml: YamlConfiguration): Throwable? {
        return runCatching { yaml.save(file) }.exceptionOrNull()
    }

    private fun saveFailedResult(throwable: Throwable): ModuleOperationResult {
        return ModuleOperationResult(false, Texts.tr("@system-shop.errors.save-failed", mapOf("reason" to (throwable.message ?: throwable.javaClass.simpleName))))
    }
}

data class AdminGoodsSelection(
    val categoryId: String,
    val productId: String
)
