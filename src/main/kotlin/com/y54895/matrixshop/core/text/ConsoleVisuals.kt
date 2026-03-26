package com.y54895.matrixshop.core.text

object ConsoleVisuals {

    fun renderBoot() {
        MatrixShopRuntime.renderBoot()
    }

    fun renderReady(backend: String, schemaMessage: String, modules: String) {
        MatrixShopRuntime.renderReady(backend, schemaMessage, modules)
    }

    fun renderFailure(reason: String) {
        MatrixShopRuntime.renderFailure(reason)
    }

    fun renderShutdown(backend: String) {
        MatrixShopRuntime.renderShutdown(backend)
    }
}
