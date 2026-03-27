package com.y54895.matrixshop.core.text

import com.y54895.matrixlib.api.console.MatrixConsoleFact
import com.y54895.matrixlib.api.runtime.MatrixPluginRuntime
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import taboolib.common.platform.function.pluginVersion
import java.util.function.Supplier

object MatrixShopRuntime : MatrixPluginRuntime(
    pluginProvider = Supplier {
        Bukkit.getPluginManager().getPlugin("MatrixShop") as? JavaPlugin
            ?: error("MatrixShop plugin instance is not available yet.")
    },
    runtimeBranding = MatrixShopBranding.value
) {

    fun renderBoot() {
        super.renderBoot(
            headline = "Bootstrapping commerce modules",
            details = listOf(
                MatrixConsoleFact("Focus", "system shop / market / auction / trade"),
                MatrixConsoleFact("Hint", "/matrixshop help")
            )
        )
    }

    fun renderReady(backend: String, schemaMessage: String, modules: String) {
        super.renderReady(
            version = pluginVersion,
            details = listOf(
                MatrixConsoleFact("Backend", backend),
                MatrixConsoleFact("Schema", schemaMessage),
                MatrixConsoleFact("Modules", modules)
            )
        )
    }

    fun renderShutdown(backend: String) {
        super.renderShutdown(
            details = listOf(
                MatrixConsoleFact("Backend snapshot", backend)
            )
        )
    }
}
