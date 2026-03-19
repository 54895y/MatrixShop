package com.y54895.matrixshop.core.record

import com.y54895.matrixshop.core.database.DatabaseManager
import java.util.UUID

object RecordService {

    private lateinit var backend: RecordBackend

    fun initialize() {
        FileRecordBackend.initialize()
        DatabaseManager.initialize()
        backend = if (DatabaseManager.isJdbcAvailable()) {
            JdbcRecordBackend
        } else {
            FileRecordBackend
        }
        backend.initialize()
    }

    fun append(module: String, type: String, player: String, detail: String) {
        append(
            module = module,
            type = type,
            actor = player,
            detail = detail
        )
    }

    fun append(
        module: String,
        type: String,
        actor: String,
        target: String = "",
        moneyChange: Double = 0.0,
        detail: String = "",
        note: String = "",
        adminReason: String = ""
    ): RecordEntry {
        return backend.append(
            RecordEntry(
                id = nextId(),
                createdAt = System.currentTimeMillis(),
                module = module,
                type = type,
                actor = actor,
                target = target,
                moneyChange = moneyChange,
                detail = detail,
                note = note,
                adminReason = adminReason
            )
        )
    }

    fun readAll(): List<RecordEntry> {
        return backend.readAll()
    }

    fun find(recordId: String): RecordEntry? {
        return backend.find(recordId)
    }

    fun backendName(): String {
        return if (::backend.isInitialized) backend.backendName() else "unknown"
    }

    fun backendFailureReason(): String {
        return DatabaseManager.failureReason()
    }

    private fun nextId(): String {
        return "rec-${System.currentTimeMillis().toString(36)}-${UUID.randomUUID().toString().take(8)}"
    }
}
