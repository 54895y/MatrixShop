package com.y54895.matrixshop.core.text

import com.y54895.matrixlib.api.console.MatrixConsoleFact
import com.y54895.matrixlib.api.console.MatrixConsoleVisuals
import taboolib.common.platform.function.pluginVersion

object ConsoleVisuals {

    private val branding = MatrixShopBranding.value

    fun renderBoot() {
        MatrixConsoleVisuals.renderBoot(
            branding = branding,
            headline = "正在加载商业模块",
            details = listOf(
                MatrixConsoleFact("功能范围", "系统商店 / 玩家市场 / 拍卖行 / 交易")
            )
        )
    }

    fun renderReady(backend: String, economy: String, schemaMessage: String, modules: String) {
        MatrixConsoleVisuals.renderReady(
            branding = branding,
            version = pluginVersion,
            details = listOf(
                MatrixConsoleFact("数据后端", backend),
                MatrixConsoleFact("经济系统", economy),
                MatrixConsoleFact("结构同步", schemaMessage),
                MatrixConsoleFact("启用模块", modules)
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
                MatrixConsoleFact("数据后端快照", backend)
            )
        )
    }
}
