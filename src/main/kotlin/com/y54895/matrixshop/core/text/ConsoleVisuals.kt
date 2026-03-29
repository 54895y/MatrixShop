package com.y54895.matrixshop.core.text

import com.y54895.matrixlib.api.console.MatrixConsoleFact
import com.y54895.matrixlib.api.console.MatrixConsoleVisuals
import taboolib.common.platform.function.pluginVersion

object ConsoleVisuals {

    private val branding = MatrixShopBranding.value

    fun renderBoot() {
        MatrixConsoleVisuals.renderBoot(
            branding = branding,
            headline = Texts.tr("@console.boot.headline"),
            details = listOf(
                MatrixConsoleFact("GitHub", "https://github.com/54895y/MatrixShop")
            ),
            includeDefaultDetails = false
        )
    }

    fun renderReady(backend: String, economy: String, schemaMessage: String, modules: String) {
        MatrixConsoleVisuals.renderReady(
            branding = branding,
            version = pluginVersion,
            details = listOf(
                MatrixConsoleFact(Texts.tr("@console.ready.backend-label"), backend),
                MatrixConsoleFact(Texts.tr("@console.ready.economy-label"), economy),
                MatrixConsoleFact(Texts.tr("@console.ready.schema-label"), schemaMessage),
                MatrixConsoleFact(Texts.tr("@console.ready.modules-label"), modules)
            ),
            includeDefaultDetails = false
        )
    }

    fun renderFailure(reason: String) {
        MatrixConsoleVisuals.renderFailure(branding, reason)
    }

    fun renderShutdown(backend: String) {
        MatrixConsoleVisuals.renderShutdown(
            branding = branding,
            details = listOf(
                MatrixConsoleFact(Texts.tr("@console.shutdown.backend-label"), backend)
            )
        )
    }
}
