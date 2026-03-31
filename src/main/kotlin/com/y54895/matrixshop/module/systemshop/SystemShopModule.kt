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
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import taboolib.common.platform.function.warning
import java.io.File
import java.util.UUID

object SystemShopModule : MatrixModule {

    override val id: String = "system-shop"
    override val displayName: String = "SystemShop"

    private lateinit var rootMenu: MenuDefinition
    private lateinit var confirmMenu: MenuDefinition
    private lateinit var goodsBrowserMenu: MenuDefinition
    private lateinit var goodsEditorMenu: MenuDefinition
    private lateinit var goodsShopsMenu: MenuDefinition
    private val categories = LinkedHashMap<String, SystemShopCategory>()
    private val goodsTemplates = LinkedHashMap<String, SystemShopProductTemplate>()
    private val confirmSessions = HashMap<UUID, ConfirmSession>()
    private val adminSelections = HashMap<UUID, AdminGoodsSelection>()
    private var currencyKey: String = "vault"

    override fun isEnabled(): Boolean {
        return ConfigFiles.isModuleEnabled(id, true)
    }

    override fun reload() {
        if (!isEnabled()) {
            categories.clear()
            goodsTemplates.clear()
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
        adminSelections.clear()
        val goodsFolder = File(dataFolder, "SystemShop/goods")
        goodsFolder.mkdirs()
        goodsFolder.listFiles { file -> file.isFile && file.extension.equals("yml", true) }
            ?.sortedBy { it.nameWithoutExtension }
            ?.forEach { file ->
                runCatching { loadGoodsTemplate(file) }.onFailure {
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
        val placeholders = playerPlaceholders(player) + mapOf("page" to page.toString(), "max-page" to "1")
        MenuRenderer.open(
            player = player,
            definition = category.menu,
            placeholders = placeholders,
            backAction = { openMain(player) },
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
        return goodsTemplates.keys.sorted()
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
        val nextPrice = (template.price + delta).coerceAtLeast(0.0)
        setProductValue(yaml, null, "price", nextPrice)
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
            price = template.price,
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
        val resolvedProductId = nextProductId(goodsTemplates.keys, inHand, productId)
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
        val referencedGoods = goodsTemplates.entries.firstOrNull { it.key.equals(normalizedProductId, true) }
            ?: return ModuleOperationResult(false, Texts.tr("@system-shop.errors.admin-goods-not-found", mapOf("product" to normalizedProductId)))
        val categoryFile = File(ConfigFiles.dataFolder(), "SystemShop/shops/$normalizedCategoryId.yml")
        if (!categoryFile.exists()) {
            return ModuleOperationResult(false, Texts.tr("@system-shop.errors.category-not-found", mapOf("category" to normalizedCategoryId)))
        }
        val yaml = YamlConfiguration.loadConfiguration(categoryFile)
        val categoryCurrencyKey = EconomyModule.configuredKey(yaml, "Currency", currencyKey)
        val goodsReferences = ensureGoodsReferencesMode(categoryFile, yaml, normalizedCategoryId)
        if (goodsReferences.any { it.equals(referencedGoods.key, true) }) {
            return ModuleOperationResult(false, Texts.tr("@system-shop.errors.admin-product-already-linked", mapOf("category" to normalizedCategoryId, "product" to referencedGoods.key)))
        }
        goodsReferences += referencedGoods.key
        writeGoodsReferences(yaml, goodsReferences)
        saveCategoryYaml(categoryFile, yaml)?.let { return saveFailedResult(it) }
        if (isEnabled()) {
            reload()
        }
        return ModuleOperationResult(
            true,
            Texts.tr(
                "@system-shop.success.admin-linked",
                mapOf(
                    "category" to normalizedCategoryId,
                    "product" to referencedGoods.key,
                    "name" to referencedGoods.value.name,
                    "price" to EconomyModule.formatAmount(referencedGoods.value.resolve(categoryCurrencyKey).currency, referencedGoods.value.price)
                )
            )
        )
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
            "price" to trimDouble(template.price),
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
                setProductValue(yaml, basePath, "price", price)
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
                    price = section.getDouble("price", product.price),
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
        val product = findProduct(session.categoryId, session.productId) ?: run {
            confirmSessions.remove(player.uniqueId)
            Texts.sendKey(player, "@system-shop.errors.product-invalid")
            return
        }
        session.amount = (session.amount + delta).coerceIn(1, product.buyMax.coerceAtLeast(1))
        openConfirm(player, session.categoryId, session.productId)
    }

    fun confirmPurchase(player: Player) {
        val session = confirmSessions[player.uniqueId]
        if (session == null) {
            Texts.sendKey(player, "@system-shop.errors.no-confirm-purchase")
            return
        }
        val result = purchaseDirect(player, session.categoryId, session.productId, session.amount, true)
        if (result.success) {
            confirmSessions.remove(player.uniqueId)
        } else if (result.message.isNotBlank()) {
            Texts.send(player, result.message)
        }
    }

    fun currentSelection(player: Player): SystemShopSelection? {
        val session = confirmSessions[player.uniqueId] ?: return null
        val product = findProduct(session.categoryId, session.productId) ?: return null
        return SystemShopSelection(
            categoryId = session.categoryId,
            productId = session.productId,
            product = product,
            amount = session.amount.coerceIn(1, product.buyMax.coerceAtLeast(1))
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
        val product = findProduct(categoryId, productId)
        if (category == null || product == null) {
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
        if (!canFit(player.inventory.contents.filterNotNull(), purchaseStacks)) {
            return ModuleOperationResult(false, Texts.tr("@system-shop.errors.inventory-no-space"))
        }
        if (!EconomyModule.withdraw(player, product.currency, total)) {
            return ModuleOperationResult(false, Texts.tr("@economy.errors.withdraw-failed", mapOf("currency" to EconomyModule.displayName(product.currency))))
        }
        purchaseStacks.forEach { player.inventory.addItem(it) }
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
            detail = "category=${category.id};product=${product.id};amount=$safeAmount;total=${trimDouble(total)}"
        )
        return ModuleOperationResult(true, "")
    }

    fun openConfirm(player: Player, categoryId: String, productId: String) {
        val product = findProduct(categoryId, productId) ?: return
        val session = confirmSessions.compute(player.uniqueId) { _, current ->
            if (current == null || current.categoryId != categoryId || current.productId != productId) {
                ConfirmSession(categoryId, productId, 1)
            } else {
                current
            }
        }!!
        val total = product.price * session.amount
        val placeholders = playerPlaceholders(player) + mapOf(
            "name" to product.name,
            "amount" to session.amount.toString(),
            "price" to EconomyModule.formatAmount(product.currency, product.price),
            "total-price" to EconomyModule.formatAmount(product.currency, total),
            "currency" to EconomyModule.displayName(product.currency)
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
        category.products.take(goodsSlots.size).forEachIndexed { index, product ->
            val slot = goodsSlots[index]
            val placeholders = mapOf(
                "name" to product.name,
                "price" to EconomyModule.formatAmount(product.currency, product.price),
                "currency" to EconomyModule.displayName(product.currency),
                "limit" to product.buyMax.toString(),
                "has_currency" to EconomyModule.formatAmount(product.currency, EconomyModule.balance(player, product.currency)),
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
                    openConfirm(player, category.id, product.id)
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
                    "&7价格: &e${trimDouble(template.price)}",
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
            "&7价格: &e${trimDouble(template.price)}",
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

    private fun loadCategory(file: File) {
        val yaml = YamlConfiguration.loadConfiguration(file)
        val id = yaml.getString("id", file.nameWithoutExtension).orEmpty()
        val menu = MenuLoader.load(file)
        val categoryCurrencyKey = EconomyModule.configuredKey(yaml, "Currency", currencyKey)
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
            readGoodsReferences(yaml).mapNotNull { goodsId ->
                val template = goodsTemplates.entries.firstOrNull { it.key.equals(goodsId, true) }?.value
                if (template == null) {
                    warning(Texts.tr("@system-shop.logs.goods-ref-missing", mapOf("shop" to id, "goods" to goodsId)))
                    null
                } else {
                    template.resolve(categoryCurrencyKey)
                }
            }
        }
        categories[id] = SystemShopCategory(id = id, menu = menu, currencyKey = categoryCurrencyKey, products = products, shopFile = file)
    }

    private fun parseProductTemplate(
        id: String,
        section: ConfigurationSection,
        source: SystemShopProductSource
    ): SystemShopProductTemplate {
        val configuredItem = section.getItemStack("item")?.clone()
        return SystemShopProductTemplate(
            id = id,
            material = section.getString("material", configuredItem?.type?.name ?: "STONE").orEmpty(),
            amount = section.getInt("amount", configuredItem?.amount ?: 1),
            name = section.getString("name", configuredItem?.itemMeta?.displayName ?: id).orEmpty(),
            lore = section.getStringList("lore").ifEmpty { configuredItem?.itemMeta?.lore ?: emptyList() },
            price = section.getDouble("price", 0.0),
            configuredCurrency = section.getString("currency")
                ?.trim()
                ?.takeIf(String::isNotBlank),
            buyMax = section.getInt("buy-max", 64),
            item = configuredItem,
            source = source
        )
    }

    private fun loadGoodsTemplate(file: File) {
        val yaml = YamlConfiguration.loadConfiguration(file)
        val resolvedId = normalizeProductId(yaml.getString("id", file.nameWithoutExtension)) ?: file.nameWithoutExtension
        if (goodsTemplates.keys.any { it.equals(resolvedId, true) }) {
            warning(Texts.tr("@system-shop.logs.goods-id-duplicate", mapOf("id" to resolvedId, "file" to file.name)))
            return
        }
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
        yaml.set("price", template.price)
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
            left.price == right.price &&
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

    private fun findGoodsTemplate(productId: String): SystemShopProductTemplate? {
        return goodsTemplates.entries.firstOrNull { it.key.equals(productId, true) }?.value
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
        setProductValue(yaml, basePath, "price", price)
        setProductValue(yaml, basePath, "buy-max", (buyMax ?: stack.maxStackSize).coerceAtLeast(1))
        setProductValue(yaml, basePath, "name", meta?.displayName?.takeIf(String::isNotBlank) ?: defaultProductName(stack))
        setProductValue(yaml, basePath, "lore", meta?.lore ?: emptyList<String>())
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
