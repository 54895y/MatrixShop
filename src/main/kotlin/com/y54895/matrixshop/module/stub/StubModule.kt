package com.y54895.matrixshop.module.stub

import com.y54895.matrixshop.core.config.ConfigFiles
import com.y54895.matrixshop.core.module.MatrixModule

class StubModule(
    override val id: String,
    override val displayName: String
) : MatrixModule {

    override fun isEnabled(): Boolean {
        return ConfigFiles.isModuleEnabled(id, false)
    }

    override fun reload() {
    }
}
