package com.y54895.matrixshop.core.action

import com.y54895.matrixlib.api.action.ActionContext as SharedActionContext
import com.y54895.matrixlib.api.action.ActionExecutor as SharedActionExecutor

typealias ActionContext = SharedActionContext

object ActionExecutor {

    fun execute(context: ActionContext, actions: List<String>) {
        SharedActionExecutor.execute(context, actions)
    }
}
