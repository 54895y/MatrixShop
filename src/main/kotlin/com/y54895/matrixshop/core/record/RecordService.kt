package com.y54895.matrixshop.core.record

import com.y54895.matrixshop.core.config.ConfigFiles
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID

object RecordService {

    private lateinit var recordFile: File
    private val legacyFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val lock = Any()
    private var cache: MutableList<RecordEntry> = mutableListOf()

    fun initialize() {
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
        val entry = RecordEntry(
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
        synchronized(lock) {
            recordFile.appendText(serialize(entry) + System.lineSeparator())
            cache += entry
        }
        return entry
    }

    fun readAll(): List<RecordEntry> {
        synchronized(lock) {
            return cache.sortedByDescending { it.createdAt }
        }
    }

    fun find(recordId: String): RecordEntry? {
        synchronized(lock) {
            return cache.firstOrNull { it.id.equals(recordId, true) }
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

    private fun nextId(): String {
        return "rec-${System.currentTimeMillis().toString(36)}-${UUID.randomUUID().toString().take(8)}"
    }
}
