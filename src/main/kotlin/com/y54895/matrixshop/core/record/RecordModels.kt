package com.y54895.matrixshop.core.record

data class RecordEntry(
    val id: String,
    val createdAt: Long,
    val module: String,
    val type: String,
    val actor: String,
    val target: String = "",
    val moneyChange: Double = 0.0,
    val detail: String = "",
    val note: String = "",
    val adminReason: String = ""
) {

    fun involves(playerName: String): Boolean {
        val normalized = playerName.lowercase()
        return actor.lowercase() == normalized || target.lowercase() == normalized
    }

    fun matches(keyword: String): Boolean {
        val normalized = keyword.lowercase()
        return listOf(id, module, type, actor, target, detail, note, adminReason).any {
            it.lowercase().contains(normalized)
        }
    }
}

data class RecordAggregate(
    val module: String,
    val total: Double,
    val count: Int
)
