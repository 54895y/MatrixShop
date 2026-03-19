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
    val schemaCurrent: Boolean,
    val pendingMigrations: List<Int>,
    val redisEnabled: Boolean,
    val lastMigration: String,
    val lastLegacyImport: String,
    val failureReason: String,
    val tableCounts: Map<String, Int>
)

data class DatabaseSchemaSyncResult(
    val backend: String,
    val startedVersion: Int?,
    val finalVersion: Int?,
    val appliedVersions: List<Int>,
    val success: Boolean,
    val message: String
)

private data class DatabaseMigration(
    val version: Int,
    val description: String,
    val action: (Connection) -> Unit
)

object DatabaseManager {

    private const val BASE_SCHEMA_VERSION = 1
    private const val CURRENT_SCHEMA_VERSION = 2

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

    private val migrations = listOf(
        DatabaseMigration(
            version = 2,
            description = "Create runtime tables and indexes"
        ) { connection ->
            ensureRuntimeTables(connection)
            ensureRuntimeIndexes(connection)
        }
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

    fun syncSchema(): DatabaseSchemaSyncResult {
        ensureInitialized()
        if (!isJdbcAvailable()) {
            return DatabaseSchemaSyncResult(
                backend = backendName(),
                startedVersion = null,
                finalVersion = null,
                appliedVersions = emptyList(),
                success = true,
                message = "File backend active, schema sync skipped."
            )
        }
        return runCatching {
            openConnection().use { connection ->
                val previousAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    bootstrapCoreSchema(connection)
                    var currentVersion = readMeta(connection, "schema_version")?.toIntOrNull() ?: BASE_SCHEMA_VERSION
                    val startedVersion = currentVersion
                    if (currentVersion > CURRENT_SCHEMA_VERSION) {
                        connection.rollback()
                        connection.autoCommit = previousAutoCommit
                        return DatabaseSchemaSyncResult(
                            backend = backendName(),
                            startedVersion = startedVersion,
                            finalVersion = currentVersion,
                            appliedVersions = emptyList(),
                            success = false,
                            message = "Database schema version $currentVersion is newer than supported $CURRENT_SCHEMA_VERSION."
                        )
                    }
                    val applied = mutableListOf<Int>()
                    migrations
                        .sortedBy { it.version }
                        .filter { it.version > currentVersion }
                        .forEach { migration ->
                            migration.action(connection)
                            currentVersion = migration.version
                            upsertMeta(connection, "schema_version", currentVersion.toString())
                            upsertMeta(connection, "last_migration", "v${migration.version} ${migration.description}")
                            upsertMeta(connection, "last_migration_at", System.currentTimeMillis().toString())
                            applied += migration.version
                        }
                    connection.commit()
                    connection.autoCommit = previousAutoCommit
                    DatabaseSchemaSyncResult(
                        backend = backendName(),
                        startedVersion = startedVersion,
                        finalVersion = currentVersion,
                        appliedVersions = applied,
                        success = true,
                        message = if (applied.isEmpty()) {
                            "Schema already current."
                        } else {
                            "Applied schema migrations: ${applied.joinToString(", ")}."
                        }
                    )
                } catch (ex: Exception) {
                    runCatching { connection.rollback() }
                    connection.autoCommit = previousAutoCommit
                    throw ex
                }
            }
        }.getOrElse {
            failureReason = it.message ?: it.javaClass.simpleName
            DatabaseSchemaSyncResult(
                backend = backendName(),
                startedVersion = currentSchemaVersion(),
                finalVersion = currentSchemaVersion(),
                appliedVersions = emptyList(),
                success = false,
                message = failureReason.ifBlank { "Schema sync failed." }
            )
        }.also { result ->
            if (result.success && result.appliedVersions.isNotEmpty()) {
                info("MatrixShop database schema synced: ${result.appliedVersions.joinToString(", ")}")
            }
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

    fun metaValue(key: String): String? {
        if (!isJdbcAvailable()) {
            return null
        }
        return readMeta(key)
    }

    fun setMetaValue(key: String, value: String) {
        if (!isJdbcAvailable()) {
            return
        }
        withConnection { connection ->
            upsertMeta(connection, key, value)
        }
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
        val schemaVersion = currentSchemaVersion()
        val pendingMigrations = pendingMigrationVersions(schemaVersion)
        return DatabaseDiagnostics(
            configuredBackend = configuredBackendName(),
            activeBackend = backendName(),
            target = targetDescription(),
            jdbcAvailable = jdbcAvailable,
            schemaVersion = schemaVersion,
            expectedSchemaVersion = expectedSchemaVersion(),
            schemaCurrent = schemaVersion == null || pendingMigrations.isEmpty(),
            pendingMigrations = pendingMigrations,
            redisEnabled = isRedisEnabled(),
            lastMigration = readMeta("last_migration").orEmpty(),
            lastLegacyImport = readMeta("last_legacy_import").orEmpty(),
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
                bootstrapCoreSchema(connection)
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

    private fun bootstrapCoreSchema(connection: Connection) {
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
        if (readMeta(connection, "schema_version").isNullOrBlank()) {
            upsertMeta(connection, "schema_version", BASE_SCHEMA_VERSION.toString())
        }
        upsertMeta(connection, "backend_type", settings.type.name.lowercase(Locale.ROOT))
        upsertMeta(connection, "target", targetDescription())
    }

    private fun ensureRuntimeTables(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS auction_listings (
                    id VARCHAR(64) PRIMARY KEY,
                    owner_id VARCHAR(64) NOT NULL,
                    owner_name VARCHAR(64) NOT NULL,
                    mode VARCHAR(16) NOT NULL,
                    item_blob TEXT NOT NULL,
                    start_price DOUBLE NOT NULL,
                    buyout_price DOUBLE NOT NULL,
                    end_price DOUBLE NOT NULL,
                    current_bid DOUBLE NOT NULL,
                    highest_bidder_id VARCHAR(64) NOT NULL,
                    highest_bidder_name VARCHAR(64) NOT NULL,
                    created_at BIGINT NOT NULL,
                    expire_at BIGINT NOT NULL,
                    extend_count INT NOT NULL,
                    deposit_paid DOUBLE NOT NULL
                )
                """.trimIndent()
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS auction_bids (
                    listing_id VARCHAR(64) NOT NULL,
                    bid_index INT NOT NULL,
                    bidder_id VARCHAR(64) NOT NULL,
                    bidder_name VARCHAR(64) NOT NULL,
                    amount DOUBLE NOT NULL,
                    created_at BIGINT NOT NULL,
                    PRIMARY KEY (listing_id, bid_index)
                )
                """.trimIndent()
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS auction_deliveries (
                    id VARCHAR(64) PRIMARY KEY,
                    owner_id VARCHAR(64) NOT NULL,
                    owner_name VARCHAR(64) NOT NULL,
                    money DOUBLE NOT NULL,
                    item_blob TEXT NOT NULL,
                    message TEXT NOT NULL,
                    created_at BIGINT NOT NULL
                )
                """.trimIndent()
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS global_market_listings (
                    id VARCHAR(64) PRIMARY KEY,
                    owner_id VARCHAR(64) NOT NULL,
                    owner_name VARCHAR(64) NOT NULL,
                    price DOUBLE NOT NULL,
                    currency VARCHAR(32) NOT NULL,
                    item_blob TEXT NOT NULL,
                    created_at BIGINT NOT NULL,
                    expire_at BIGINT NOT NULL
                )
                """.trimIndent()
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS player_shop_stores (
                    owner_id VARCHAR(64) PRIMARY KEY,
                    owner_name VARCHAR(64) NOT NULL,
                    unlocked_slots INT NOT NULL
                )
                """.trimIndent()
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS player_shop_listings (
                    id VARCHAR(64) PRIMARY KEY,
                    owner_id VARCHAR(64) NOT NULL,
                    slot_index INT NOT NULL,
                    price DOUBLE NOT NULL,
                    currency VARCHAR(32) NOT NULL,
                    item_blob TEXT NOT NULL,
                    created_at BIGINT NOT NULL
                )
                """.trimIndent()
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS cart_entries (
                    owner_id VARCHAR(64) NOT NULL,
                    id VARCHAR(64) NOT NULL,
                    source_module VARCHAR(64) NOT NULL,
                    source_id VARCHAR(128) NOT NULL,
                    name VARCHAR(255) NOT NULL,
                    currency VARCHAR(32) NOT NULL,
                    snapshot_price DOUBLE NOT NULL,
                    amount INT NOT NULL,
                    owner_name VARCHAR(64) NOT NULL,
                    item_blob TEXT NOT NULL,
                    editable_amount BOOLEAN NOT NULL,
                    protected_on_clear BOOLEAN NOT NULL,
                    watch_only BOOLEAN NOT NULL,
                    created_at BIGINT NOT NULL,
                    metadata_blob TEXT NOT NULL,
                    PRIMARY KEY (owner_id, id)
                )
                """.trimIndent()
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS chest_shops (
                    id VARCHAR(64) PRIMARY KEY,
                    owner_id VARCHAR(64) NOT NULL,
                    owner_name VARCHAR(64) NOT NULL,
                    primary_world VARCHAR(128) NOT NULL,
                    primary_x INT NOT NULL,
                    primary_y INT NOT NULL,
                    primary_z INT NOT NULL,
                    secondary_world VARCHAR(128) NOT NULL,
                    secondary_x INT NOT NULL,
                    secondary_y INT NOT NULL,
                    secondary_z INT NOT NULL,
                    mode VARCHAR(16) NOT NULL,
                    buy_price DOUBLE NOT NULL,
                    sell_price DOUBLE NOT NULL,
                    trade_amount INT NOT NULL,
                    item_blob TEXT NOT NULL,
                    created_at BIGINT NOT NULL
                )
                """.trimIndent()
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS chest_shop_signs (
                    shop_id VARCHAR(64) NOT NULL,
                    sign_index INT NOT NULL,
                    world VARCHAR(128) NOT NULL,
                    x INT NOT NULL,
                    y INT NOT NULL,
                    z INT NOT NULL,
                    PRIMARY KEY (shop_id, sign_index)
                )
                """.trimIndent()
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS chest_shop_history (
                    shop_id VARCHAR(64) NOT NULL,
                    history_index INT NOT NULL,
                    type VARCHAR(32) NOT NULL,
                    actor VARCHAR(64) NOT NULL,
                    amount INT NOT NULL,
                    money DOUBLE NOT NULL,
                    created_at BIGINT NOT NULL,
                    note TEXT NOT NULL,
                    PRIMARY KEY (shop_id, history_index)
                )
                """.trimIndent()
            )
        }
    }

    private fun ensureRuntimeIndexes(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_record_entries_created_at ON record_entries (created_at)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_record_entries_actor ON record_entries (actor)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_record_entries_module ON record_entries (module)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_auction_listings_owner_id ON auction_listings (owner_id)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_auction_listings_expire_at ON auction_listings (expire_at)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_auction_bids_bidder_id ON auction_bids (bidder_id)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_auction_deliveries_owner_id ON auction_deliveries (owner_id)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_global_market_owner_id ON global_market_listings (owner_id)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_global_market_expire_at ON global_market_listings (expire_at)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_player_shop_listings_owner_slot ON player_shop_listings (owner_id, slot_index)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_cart_entries_owner_created ON cart_entries (owner_id, created_at)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_chest_shops_owner_id ON chest_shops (owner_id)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_chest_shop_signs_location ON chest_shop_signs (world, x, y, z)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_chest_shop_history_shop_created ON chest_shop_history (shop_id, created_at)")
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

    private fun ensureInitialized() {
        if (!initialized) {
            settings = loadSettings(ConfigFiles.database)
        }
    }

    private fun pendingMigrationVersions(schemaVersion: Int?): List<Int> {
        if (schemaVersion == null) {
            return emptyList()
        }
        return migrations
            .map { it.version }
            .sorted()
            .filter { it > schemaVersion }
    }

    private fun readMeta(key: String): String? {
        return withConnection { connection ->
            readMeta(connection, key)
        }
    }

    private fun readMeta(connection: Connection, key: String): String? {
        return connection.prepareStatement(
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
