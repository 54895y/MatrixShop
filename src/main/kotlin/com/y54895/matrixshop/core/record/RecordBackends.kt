package com.y54895.matrixshop.core.record

import com.y54895.matrixshop.core.config.ConfigFiles
import com.y54895.matrixshop.core.database.DatabaseManager
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64

interface RecordBackend {
    fun initialize()
    fun append(entry: RecordEntry): RecordEntry
    fun readAll(): List<RecordEntry>
    fun find(recordId: String): RecordEntry?
    fun backendName(): String
}

object FileRecordBackend : RecordBackend {

    private lateinit var recordFile: File
    private val legacyFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val lock = Any()
    private var cache: MutableList<RecordEntry> = mutableListOf()

    override fun initialize() {
        val dataDir = File(ConfigFiles.dataFolder(), "Data")
        dataDir.mkdirs()
        recordFile = File(dataDir, "record.log")
        if (!recordFile.exists()) {
            recordFile.createNewFile()
        }
        synchronized(lock) {
            cache = loadFromDisk().toMutableList()
        }
    }

    override fun append(entry: RecordEntry): RecordEntry {
        synchronized(lock) {
            recordFile.appendText(serialize(entry) + System.lineSeparator())
            cache += entry
        }
        return entry
    }

    override fun readAll(): List<RecordEntry> {
        synchronized(lock) {
            return cache.sortedByDescending { it.createdAt }
        }
    }

    override fun find(recordId: String): RecordEntry? {
        synchronized(lock) {
            return cache.firstOrNull { it.id.equals(recordId, true) }
        }
    }

    override fun backendName(): String {
        return "file"
    }

    fun importableEntries(): List<RecordEntry> {
        synchronized(lock) {
            return cache.toList()
        }
    }

    private fun loadFromDisk(): List<RecordEntry> {
        return recordFile.readLines()
            .mapIndexedNotNull { index, line -> parse(line, index) }
            .sortedByDescending { it.createdAt }
    }

    private fun parse(line: String, index: Int): RecordEntry? {
        if (line.isBlank()) {
            return null
        }
        if (line.startsWith("V2\t")) {
            val parts = line.split('\t')
            if (parts.size < 11) {
                return null
            }
            return RecordEntry(
                id = parts[1],
                createdAt = parts[2].toLongOrNull() ?: 0L,
                module = parts[3],
                type = parts[4],
                actor = parts[5],
                target = parts[6],
                moneyChange = parts[7].toDoubleOrNull() ?: 0.0,
                detail = decode(parts[8]),
                note = decode(parts[9]),
                adminReason = decode(parts[10])
            )
        }
        val parts = line.split('\t', limit = 5)
        if (parts.size < 5) {
            return null
        }
        val createdAt = runCatching {
            LocalDateTime.parse(parts[0], legacyFormatter)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrElse { 0L }
        return RecordEntry(
            id = "legacy-$index",
            createdAt = createdAt,
            module = parts[1],
            type = parts[2],
            actor = parts[3],
            detail = parts[4]
        )
    }

    private fun serialize(entry: RecordEntry): String {
        return listOf(
            "V2",
            entry.id,
            entry.createdAt.toString(),
            entry.module,
            entry.type,
            entry.actor,
            entry.target,
            entry.moneyChange.toString(),
            encode(entry.detail),
            encode(entry.note),
            encode(entry.adminReason)
        ).joinToString("\t")
    }

    private fun encode(value: String): String {
        return Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    }

    private fun decode(value: String): String {
        return runCatching {
            String(Base64.getDecoder().decode(value), Charsets.UTF_8)
        }.getOrDefault(value)
    }
}

object JdbcRecordBackend : RecordBackend {

    private val lock = Any()

    override fun initialize() {
        // Legacy import is coordinated by LegacyDataMigrationService.
    }

    override fun append(entry: RecordEntry): RecordEntry {
        synchronized(lock) {
            DatabaseManager.withConnection { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO record_entries (
                        id, created_at, module, type, actor, target, money_change, detail, note, admin_reason
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, entry.id)
                    statement.setLong(2, entry.createdAt)
                    statement.setString(3, entry.module)
                    statement.setString(4, entry.type)
                    statement.setString(5, entry.actor)
                    statement.setString(6, entry.target)
                    statement.setDouble(7, entry.moneyChange)
                    statement.setString(8, entry.detail)
                    statement.setString(9, entry.note)
                    statement.setString(10, entry.adminReason)
                    statement.executeUpdate()
                }
            }
        }
        return entry
    }

    override fun readAll(): List<RecordEntry> {
        return synchronized(lock) {
            DatabaseManager.withConnection { connection ->
                connection.prepareStatement(
                    """
                    SELECT id, created_at, module, type, actor, target, money_change, detail, note, admin_reason
                    FROM record_entries
                    ORDER BY created_at DESC
                    """.trimIndent()
                ).use { statement ->
                    statement.executeQuery().use { result ->
                        val entries = ArrayList<RecordEntry>()
                        while (result.next()) {
                            entries += RecordEntry(
                                id = result.getString("id"),
                                createdAt = result.getLong("created_at"),
                                module = result.getString("module"),
                                type = result.getString("type"),
                                actor = result.getString("actor"),
                                target = result.getString("target"),
                                moneyChange = result.getDouble("money_change"),
                                detail = result.getString("detail"),
                                note = result.getString("note"),
                                adminReason = result.getString("admin_reason")
                            )
                        }
                        entries
                    }
                }
            } ?: emptyList()
        }
    }

    override fun find(recordId: String): RecordEntry? {
        return synchronized(lock) {
            DatabaseManager.withConnection { connection ->
                connection.prepareStatement(
                    """
                    SELECT id, created_at, module, type, actor, target, money_change, detail, note, admin_reason
                    FROM record_entries
                    WHERE id = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, recordId)
                    statement.executeQuery().use { result ->
                        if (!result.next()) {
                            return@use null
                        }
                        RecordEntry(
                            id = result.getString("id"),
                            createdAt = result.getLong("created_at"),
                            module = result.getString("module"),
                            type = result.getString("type"),
                            actor = result.getString("actor"),
                            target = result.getString("target"),
                            moneyChange = result.getDouble("money_change"),
                            detail = result.getString("detail"),
                            note = result.getString("note"),
                            adminReason = result.getString("admin_reason")
                        )
                    }
                }
            }
        }
    }

    override fun backendName(): String {
        return DatabaseManager.backendName()
    }

    fun importLegacyEntries(entries: List<RecordEntry>): Int {
        if (!DatabaseManager.isJdbcAvailable() || entries.isEmpty()) {
            return 0
        }
        return synchronized(lock) {
            DatabaseManager.withConnection { connection ->
                val previousAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    connection.prepareStatement(
                        """
                        INSERT INTO record_entries (
                            id, created_at, module, type, actor, target, money_change, detail, note, admin_reason
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent()
                    ).use { statement ->
                        entries.forEach { entry ->
                            statement.setString(1, entry.id)
                            statement.setLong(2, entry.createdAt)
                            statement.setString(3, entry.module)
                            statement.setString(4, entry.type)
                            statement.setString(5, entry.actor)
                            statement.setString(6, entry.target)
                            statement.setDouble(7, entry.moneyChange)
                            statement.setString(8, entry.detail)
                            statement.setString(9, entry.note)
                            statement.setString(10, entry.adminReason)
                            statement.addBatch()
                        }
                        statement.executeBatch()
                    }
                    connection.commit()
                    connection.autoCommit = previousAutoCommit
                    entries.size
                } catch (ex: Exception) {
                    runCatching { connection.rollback() }
                    connection.autoCommit = previousAutoCommit
                    0
                }
            } ?: 0
        }
    }
}
