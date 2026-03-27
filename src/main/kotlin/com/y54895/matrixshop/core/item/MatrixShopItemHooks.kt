package com.y54895.matrixshop.core.item

import com.y54895.matrixlib.api.item.MatrixCommonItemHooks
import com.y54895.matrixlib.api.item.MatrixItemHooks
import taboolib.common.LifeCycle
import taboolib.common.platform.Awake

@Awake
object MatrixShopItemHooks {

    private const val ownerId = "matrixshop"

    @Awake(LifeCycle.ENABLE)
    fun registerHooks() {
        MatrixCommonItemHooks.registerCommon(ownerId)
    }

    @Awake(LifeCycle.DISABLE)
    fun unregisterHooks() {
        MatrixItemHooks.unregisterOwner(ownerId)
    }
}
