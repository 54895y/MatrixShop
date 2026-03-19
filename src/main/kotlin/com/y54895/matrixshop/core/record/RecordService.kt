package com.y54895.matrixshop.core.record

import com.y54895.matrixshop.core.config.ConfigFiles
import taboolib.common.platform.function.submitAsync
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object RecordService {

    private lateinit var recordFile: File
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun initialize() {
        val dataDir = File(ConfigFiles.dataFolder(), "Data")
        dataDir.mkdirs()
        recordFile = File(dataDir, "record.log")
        if (!recordFile.exists()) {
            recordFile.createNewFile()
        }
    }

    fun append(module: String, type: String, player: String, detail: String) {
        val line = "${LocalDateTime.now().format(formatter)}\t$module\t$type\t$player\t$detail"
        submitAsync {
            recordFile.appendText(line + System.lineSeparator())
        }
    }
}
