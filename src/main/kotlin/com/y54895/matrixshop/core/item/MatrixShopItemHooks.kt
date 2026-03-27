package com.y54895.matrixshop.core.item

import com.y54895.matrixlib.api.item.MatrixItemHook
import com.y54895.matrixlib.api.item.MatrixItemHooks
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import taboolib.common.LifeCycle
import taboolib.common.platform.Awake

@Awake
object MatrixShopItemHooks {

    private val registeredIds = linkedSetOf<String>()

    @Awake(LifeCycle.ENABLE)
    fun registerHooks() {
        registerHook("craftengine", listOf("ce"), "CraftEngine", "CraftEngine") { entry, player ->
            val parts = entry.split(":", limit = 2)
            if (parts.size != 2) {
                return@registerHook null
            }
            val keyClass = Class.forName("net.momirealms.craftengine.core.util.Key")
            val key = keyClass.getConstructor(String::class.java, String::class.java)
                .newInstance(parts[0], parts[1])
            val craftEngineItemsClass = Class.forName("net.momirealms.craftengine.bukkit.api.CraftEngineItems")
            val customItem = craftEngineItemsClass.getMethod("byId", keyClass).invoke(null, key) ?: return@registerHook null

            val built = if (player != null) {
                val engineClass = Class.forName("net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine")
                val engine = engineClass.getMethod("instance").invoke(null)
                val craftPlayer = engine.javaClass.getMethod("adapt", Player::class.java).invoke(engine, player)
                customItem.javaClass.methods.firstOrNull { it.name == "buildItemStack" && it.parameterCount == 1 }
                    ?.invoke(customItem, craftPlayer)
            } else {
                customItem.javaClass.methods.firstOrNull { it.name == "buildItemStack" && it.parameterCount == 0 }
                    ?.invoke(customItem)
            }
            built as? ItemStack
        }

        registerHook("itemsadder", listOf("ia"), "ItemsAdder", "ItemsAdder") { entry, _ ->
            val customStackClass = Class.forName("dev.lone.itemsadder.api.CustomStack")
            val customStack = customStackClass.getMethod("getInstance", String::class.java).invoke(null, entry)
                ?: return@registerHook null
            customStackClass.getMethod("getItemStack").invoke(customStack) as? ItemStack
        }

        registerHook("oraxen", listOf("ox"), "Oraxen", "Oraxen") { entry, _ ->
            val oraxenItemsClass = Class.forName("io.th0rgal.oraxen.api.OraxenItems")
            val builder = oraxenItemsClass.methods.firstOrNull {
                (it.name == "getItemById" || it.name == "getItemByItemId") &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == String::class.java
            }?.invoke(null, entry) ?: return@registerHook null
            val buildMethod = builder.javaClass.methods.firstOrNull {
                (it.name == "build" || it.name == "buildItemStack") && it.parameterCount == 0
            } ?: return@registerHook null
            buildMethod.invoke(builder) as? ItemStack
        }
    }

    @Awake(LifeCycle.DISABLE)
    fun unregisterHooks() {
        registeredIds.forEach(MatrixItemHooks::unregister)
        registeredIds.clear()
    }

    private fun registerHook(
        id: String,
        aliases: List<String>,
        sourceName: String,
        pluginName: String,
        resolver: (entry: String, player: Player?) -> ItemStack?
    ) {
        MatrixItemHooks.register(object : MatrixItemHook {
            override val id: String = id
            override val aliases: List<String> = aliases
            override val sourceName: String = sourceName

            override fun isAvailable(): Boolean {
                return Bukkit.getPluginManager().isPluginEnabled(pluginName)
            }

            override fun resolve(entry: String, player: Player?): ItemStack? {
                return runCatching {
                    resolver(entry, player)
                }.getOrNull()
            }
        })
        registeredIds += id
    }
}
