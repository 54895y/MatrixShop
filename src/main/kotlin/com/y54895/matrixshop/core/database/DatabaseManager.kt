package com.y54895.matrixshop.core.database

import com.y54895.matrixshop.core.config.ConfigFiles
import org.bukkit.configuration.file.YamlConfiguration
import taboolib.common.platform.function.info
import taboolib.common.platform.function.warning
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

enum class DatabaseType {
    SQLITE,
    MYSQL
}

data class DatabaseSettings(
    val type: DatabaseType,
    val sqliteFile: String,
    val mysqlHost: String,
    val mysqlPort: Int,
    val mysqlDatabase: String,
    val mysqlUsername: String,
    val mysqlPassword: String
)

object DatabaseManager {

    private lateinit var settings: DatabaseSettings
    private var initialized = false
    private var jdbcAvailable = false
    private var failureReason = ""

    fun initialize() {
        settings = loadSettings(ConfigFiles.database)
        jdbcAvailable = tryInitializeJdbc()
        initialized = true
        if (jdbcAvailable) {
            info("MatrixShop database backend is active: ${backendName()}")
        } else {
            warning("MatrixShop database backend unavailable, falling back to file storage. Reason: ${failureReason.ifBlank { "unknown" }}")
        }
    }

    fun isJdbcAvailable(): Boolean {
        return initialized && jdbcAvailable
    }

    fun backendName(): String {
        return if (jdbcAvailable) settings.type.name.lowercase() else "file"
    }

    fun failureReason(): String {
        return failureReason
    }

    fun <T> withConnection(action: (Connection) -> T): T? {
        if (!isJdbcAvailable()) {
            return null
        }
        return runCatching {
            openConnection().use(action)
        }.getOrNull()
    }

    private fun tryInitializeJdbc(): Boolean {
        failureReason = ""
        return runCatching {
            when (settings.type) {
                DatabaseType.SQLITE -> Class.forName("org.sqlite.JDBC")
                DatabaseType.MYSQL -> {
                    runCatching { Class.forName("com.mysql.cj.jdbc.Driver") }
                        .recoverCatching { Class.forName("com.mysql.jdbc.Driver") }
                        .getOrThrow()
                }
            }
            openConnection().use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS record_entries (
                            id VARCHAR(64) PRIMARY KEY,
                            created_at BIGINT NOT NULL,
                            module VARCHAR(64) NOT NULL,
                            type VARCHAR(64) NOT NULL,
                            actor VARCHAR(64) NOT NULL,
                            target VARCHAR(64) NOT NULL,
                            money_change DOUBLE NOT NULL,
                            detail TEXT NOT NULL,
                            note TEXT NOT NULL,
                            admin_reason TEXT NOT NULL
                        )
                        """.trimIndent()
                    )
                }
            }
            true
        }.getOrElse {
            failureReason = it.message ?: it.javaClass.simpleName
            false
        }
    }

    private fun openConnection(): Connection {
        return when (settings.type) {
            DatabaseType.SQLITE -> {
                val sqliteFile = File(ConfigFiles.dataFolder(), settings.sqliteFile)
                sqliteFile.parentFile?.mkdirs()
                DriverManager.getConnection("jdbc:sqlite:${sqliteFile.absolutePath}")
            }
            DatabaseType.MYSQL -> {
                val url = buildString {
                    append("jdbc:mysql://")
                    append(settings.mysqlHost)
                    append(":")
                    append(settings.mysqlPort)
                    append("/")
                    append(settings.mysqlDatabase)
                    append("?useSSL=false&characterEncoding=utf8&autoReconnect=true")
                }
                DriverManager.getConnection(url, settings.mysqlUsername, settings.mysqlPassword)
            }
        }
    }

    private fun loadSettings(yaml: YamlConfiguration): DatabaseSettings {
        return DatabaseSettings(
            type = runCatching {
                DatabaseType.valueOf(yaml.getString("Database.Type", "SQLITE").orEmpty().uppercase())
            }.getOrDefault(DatabaseType.SQLITE),
            sqliteFile = yaml.getString("Database.SQLite.File", "Data/data.db").orEmpty(),
            mysqlHost = yaml.getString("Database.MySQL.Host", "127.0.0.1").orEmpty(),
            mysqlPort = yaml.getInt("Database.MySQL.Port", 3306),
            mysqlDatabase = yaml.getString("Database.MySQL.Database", "matrixshop").orEmpty(),
            mysqlUsername = yaml.getString("Database.MySQL.Username", "root").orEmpty(),
            mysqlPassword = yaml.getString("Database.MySQL.Password", "").orEmpty()
        )
    }
}
