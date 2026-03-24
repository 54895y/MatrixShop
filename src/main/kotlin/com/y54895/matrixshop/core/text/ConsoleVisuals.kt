package com.y54895.matrixshop.core.text

import com.y54895.matrixlib.api.console.MatrixConsoleFact
import com.y54895.matrixlib.api.console.MatrixConsoleVisuals
import taboolib.common.platform.function.pluginVersion

object ConsoleVisuals {

    private val branding = MatrixShopBranding.value

    fun renderBoot() {
        MatrixConsoleVisuals.renderBoot(
            branding = branding,
            headline = "Bootstrapping commerce modules",
            details = listOf(
                MatrixConsoleFact("Focus", "system shop / market / auction / trade"),
                MatrixConsoleFact("Hint", "/matrixshop help")
            )
        )
    }

    fun renderReady(backend: String, schemaMessage: String, modules: String) {
        MatrixConsoleVisuals.renderReady(
            branding = branding,
            version = pluginVersion,
            details = listOf(
                MatrixConsoleFact("Backend", backend),
                MatrixConsoleFact("Schema", schemaMessage),
                MatrixConsoleFact("Modules", modules)
            )
        )
    }

    fun renderFailure(reason: String) {
        MatrixConsoleVisuals.renderFailure(branding, reason)
    }

    fun renderShutdown(backend: String) {
        MatrixConsoleVisuals.renderShutdown(
            branding = branding,
            details = listOf(
                MatrixConsoleFact("Backend snapshot", backend)
            )
        )
    }
}
