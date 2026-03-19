package com.y54895.matrixshop.core.record

import com.y54895.matrixshop.core.database.DatabaseManager
import com.y54895.matrixshop.core.database.LegacyImportResult
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

    fun migrateLegacyToJdbcIfNeeded(): LegacyImportResult {
        if (!DatabaseManager.isJdbcAvailable()) {
            return LegacyImportResult("record", "file-backend", 0, "JDBC backend unavailable.")
        }
        val existingCount = DatabaseManager.withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM record_entries").use { result ->
                    if (result.next()) result.getInt(1) else 0
                }
            }
        } ?: return LegacyImportResult("record", "failed", 0, "Unable to inspect record_entries.")
        if (existingCount > 0) {
            return LegacyImportResult("record", "already-present", 0, "record_entries already contains $existingCount rows.")
        }
        val entries = FileRecordBackend.importableEntries()
        if (entries.isEmpty()) {
            return LegacyImportResult("record", "no-source", 0, "No legacy record.log entries found.")
        }
        val imported = JdbcRecordBackend.importLegacyEntries(entries)
        return if (imported > 0) {
            LegacyImportResult("record", "imported", imported, "Imported $imported legacy record entries.")
        } else {
            LegacyImportResult("record", "failed", 0, "Legacy record import did not write any rows.")
        }
    }

    private fun nextId(): String {
        return "rec-${System.currentTimeMillis().toString(36)}-${UUID.randomUUID().toString().take(8)}"
    }
}
