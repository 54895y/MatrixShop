package com.y54895.matrixshop.core.database

import com.y54895.matrixshop.core.config.ConfigFiles
import org.bukkit.configuration.file.YamlConfiguration
import taboolib.common.platform.function.info
import taboolib.common.platform.function.warning
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.Locale

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

data class DatabaseDiagnostics(
    val configuredBackend: String,
    val activeBackend: String,
    val target: String,
    val jdbcAvailable: Boolean,
    val schemaVersion: Int?,
    val expectedSchemaVersion: Int,
    val redisEnabled: Boolean,
    val failureReason: String,
    val tableCounts: Map<String, Int>
)

object DatabaseManager {

    private const val CURRENT_SCHEMA_VERSION = 1

    private val knownTables = listOf(
        "matrixshop_meta",
        "record_entries",
        "auction_listings",
        "auction_bids",
        "auction_deliveries",
        "global_market_listings",
        "player_shop_stores",
        "player_shop_listings",
        "cart_entries",
        "chest_shops",
        "chest_shop_signs",
        "chest_shop_history"
    )

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

    fun configuredBackendName(): String {
        ensureInitialized()
        return settings.type.name.lowercase(Locale.ROOT)
    }

    fun backendName(): String {
        ensureInitialized()
        return if (jdbcAvailable) settings.type.name.lowercase(Locale.ROOT) else "file"
    }

    fun failureReason(): String {
        return failureReason
    }

    fun targetDescription(): String {
        ensureInitialized()
        return when (settings.type) {
            DatabaseType.SQLITE -> File(ConfigFiles.dataFolder(), settings.sqliteFile).absolutePath
            DatabaseType.MYSQL -> "${settings.mysqlHost}:${settings.mysqlPort}/${settings.mysqlDatabase}"
        }
    }

    fun currentSchemaVersion(): Int? {
        if (!isJdbcAvailable()) {
            return null
        }
        return readMeta("schema_version")?.toIntOrNull()
    }

    fun expectedSchemaVersion(): Int {
        return CURRENT_SCHEMA_VERSION
    }

    fun isRedisEnabled(): Boolean {
        return ConfigFiles.database.getBoolean("Database.Redis.Enabled", false)
    }

    fun diagnostics(): DatabaseDiagnostics {
        ensureInitialized()
        return DatabaseDiagnostics(
            configuredBackend = configuredBackendName(),
            activeBackend = backendName(),
            target = targetDescription(),
            jdbcAvailable = jdbcAvailable,
            schemaVersion = currentSchemaVersion(),
            expectedSchemaVersion = expectedSchemaVersion(),
            redisEnabled = isRedisEnabled(),
            failureReason = failureReason,
            tableCounts = tableCounts()
        )
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
                initializeSchema(connection)
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

    private fun initializeSchema(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS matrixshop_meta (
                    meta_key VARCHAR(64) PRIMARY KEY,
                    meta_value TEXT NOT NULL,
                    updated_at BIGINT NOT NULL
                )
                """.trimIndent()
            )
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
        upsertMeta(connection, "schema_version", CURRENT_SCHEMA_VERSION.toString())
        upsertMeta(connection, "backend_type", settings.type.name.lowercase(Locale.ROOT))
        upsertMeta(connection, "target", targetDescription())
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

    private fun ensureInitialized() {
        if (!initialized) {
            settings = loadSettings(ConfigFiles.database)
        }
    }

    private fun readMeta(key: String): String? {
        return withConnection { connection ->
            connection.prepareStatement(
                """
                SELECT meta_value
                FROM matrixshop_meta
                WHERE meta_key = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, key)
                statement.executeQuery().use { result ->
                    if (result.next()) result.getString("meta_value") else null
                }
            }
        }
    }

    private fun tableCounts(): Map<String, Int> {
        if (!isJdbcAvailable()) {
            return emptyMap()
        }
        return withConnection { connection ->
            val existingTables = linkedSetOf<String>()
            connection.metaData.getTables(null, null, "%", arrayOf("TABLE")).use { result ->
                while (result.next()) {
                    existingTables += result.getString("TABLE_NAME").lowercase(Locale.ROOT)
                }
            }
            buildMap {
                knownTables.forEach { table ->
                    if (table.lowercase(Locale.ROOT) !in existingTables) {
                        return@forEach
                    }
                    val count = connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT COUNT(*) FROM $table").use { result ->
                            if (result.next()) result.getInt(1) else 0
                        }
                    }
                    put(table, count)
                }
            }
        } ?: emptyMap()
    }

    private fun upsertMeta(connection: Connection, key: String, value: String) {
        val exists = connection.prepareStatement(
            """
            SELECT COUNT(*)
            FROM matrixshop_meta
            WHERE meta_key = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, key)
            statement.executeQuery().use { result ->
                result.next() && result.getInt(1) > 0
            }
        }
        if (exists) {
            connection.prepareStatement(
                """
                UPDATE matrixshop_meta
                SET meta_value = ?, updated_at = ?
                WHERE meta_key = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, value)
                statement.setLong(2, System.currentTimeMillis())
                statement.setString(3, key)
                statement.executeUpdate()
            }
            return
        }
        connection.prepareStatement(
            """
            INSERT INTO matrixshop_meta (meta_key, meta_value, updated_at)
            VALUES (?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, key)
            statement.setString(2, value)
            statement.setLong(3, System.currentTimeMillis())
            statement.executeUpdate()
        }
    }
}
