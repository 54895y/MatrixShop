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
    val lastMigrationAt: String,
    val lastLegacyImport: String,
    val lastLegacyImportAt: String,
    val lastLegacyImportTotal: Int,
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
    private const val CURRENT_SCHEMA_VERSION = 3

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
        },
        DatabaseMigration(
            version = 3,
            description = "Add shop scoped runtime tables"
        ) { connection ->
            migrateRuntimeTablesToShopScoped(connection)
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
                    ensureLegacyCompatibilityObjects(connection)
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
            lastMigrationAt = readMeta("last_migration_at").orEmpty(),
            lastLegacyImport = readMeta("last_legacy_import").orEmpty(),
            lastLegacyImportAt = readMeta("last_legacy_import_at").orEmpty(),
            lastLegacyImportTotal = readMeta("last_legacy_import_total")?.toIntOrNull() ?: 0,
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
                    shop_id VARCHAR(64) NOT NULL,
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
                    shop_id VARCHAR(64) NOT NULL,
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
                    shop_id VARCHAR(64) NOT NULL,
                    owner_id VARCHAR(64) NOT NULL,
                    owner_name VARCHAR(64) NOT NULL,
                    unlocked_slots INT NOT NULL,
                    PRIMARY KEY (shop_id, owner_id)
                )
                """.trimIndent()
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS player_shop_listings (
                    id VARCHAR(64) PRIMARY KEY,
                    shop_id VARCHAR(64) NOT NULL,
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
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_auction_listings_shop_id ON auction_listings (shop_id)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_auction_listings_owner_id ON auction_listings (owner_id)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_auction_listings_expire_at ON auction_listings (expire_at)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_auction_bids_bidder_id ON auction_bids (bidder_id)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_auction_deliveries_owner_id ON auction_deliveries (owner_id)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_global_market_shop_id ON global_market_listings (shop_id)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_global_market_owner_id ON global_market_listings (owner_id)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_global_market_expire_at ON global_market_listings (expire_at)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_player_shop_stores_shop_owner ON player_shop_stores (shop_id, owner_id)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_player_shop_listings_shop_owner_slot ON player_shop_listings (shop_id, owner_id, slot_index)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_player_shop_listings_owner_slot ON player_shop_listings (owner_id, slot_index)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_cart_entries_owner_created ON cart_entries (owner_id, created_at)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_chest_shops_owner_id ON chest_shops (owner_id)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_chest_shop_signs_location ON chest_shop_signs (world, x, y, z)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_chest_shop_history_shop_created ON chest_shop_history (shop_id, created_at)")
        }
    }

    private fun migrateRuntimeTablesToShopScoped(connection: Connection) {
        if (!columnExists(connection, "auction_listings", "shop_id")) {
            recreateAuctionListingsWithShopScope(connection)
        }
        if (!columnExists(connection, "global_market_listings", "shop_id")) {
            recreateGlobalMarketListingsWithShopScope(connection)
        }
        if (!columnExists(connection, "player_shop_stores", "shop_id")) {
            recreatePlayerShopStoresWithShopScope(connection)
        }
        if (!columnExists(connection, "player_shop_listings", "shop_id")) {
            recreatePlayerShopListingsWithShopScope(connection)
        }
    }

    private fun ensureLegacyCompatibilityObjects(connection: Connection) {
        runCatching {
            ensureLegacyTransactionCompatibility(connection)
        }.onFailure {
            warning("Failed to create legacy transaction compatibility view: ${it.message}")
        }
        runCatching {
            ensureLegacyAuctionCompatibility(connection)
        }.onFailure {
            warning("Failed to create legacy auction compatibility view: ${it.message}")
        }
    }

    private fun ensureLegacyTransactionCompatibility(connection: Connection) {
        val relationType = relationType(connection, "matrixshop_transactions")
        if (relationType == "TABLE") {
            return
        }
        connection.createStatement().use { statement ->
            if (relationType == "VIEW") {
                if (settings.type == DatabaseType.SQLITE) {
                    statement.executeUpdate("DROP TRIGGER IF EXISTS trg_matrixshop_transactions_insert")
                    statement.executeUpdate("DROP TRIGGER IF EXISTS trg_matrixshop_transactions_update")
                    statement.executeUpdate("DROP TRIGGER IF EXISTS trg_matrixshop_transactions_delete")
                }
                statement.executeUpdate("DROP VIEW IF EXISTS matrixshop_transactions")
            }
            statement.executeUpdate(
                """
                CREATE VIEW matrixshop_transactions AS
                SELECT
                    id,
                    created_at,
                    created_at AS create_time,
                    module,
                    type,
                    actor,
                    actor AS player,
                    target,
                    money_change,
                    money_change AS money,
                    detail,
                    note,
                    admin_reason
                FROM record_entries
                """.trimIndent()
            )
            if (settings.type == DatabaseType.SQLITE) {
                statement.executeUpdate(
                    """
                    CREATE TRIGGER IF NOT EXISTS trg_matrixshop_transactions_insert
                    INSTEAD OF INSERT ON matrixshop_transactions
                    BEGIN
                        INSERT INTO record_entries (
                            id, created_at, module, type, actor, target, money_change, detail, note, admin_reason
                        ) VALUES (
                            COALESCE(NEW.id, 'legacy-tx-' || lower(hex(randomblob(8)))),
                            COALESCE(NEW.created_at, NEW.create_time, CAST(strftime('%s','now') AS INTEGER) * 1000),
                            COALESCE(NEW.module, ''),
                            COALESCE(NEW.type, ''),
                            COALESCE(NEW.actor, NEW.player, ''),
                            COALESCE(NEW.target, ''),
                            COALESCE(NEW.money_change, NEW.money, 0),
                            COALESCE(NEW.detail, ''),
                            COALESCE(NEW.note, ''),
                            COALESCE(NEW.admin_reason, '')
                        );
                    END
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    CREATE TRIGGER IF NOT EXISTS trg_matrixshop_transactions_update
                    INSTEAD OF UPDATE ON matrixshop_transactions
                    BEGIN
                        UPDATE record_entries
                        SET
                            created_at = COALESCE(NEW.created_at, NEW.create_time, OLD.created_at, OLD.create_time),
                            module = COALESCE(NEW.module, OLD.module),
                            type = COALESCE(NEW.type, OLD.type),
                            actor = COALESCE(NEW.actor, NEW.player, OLD.actor, OLD.player),
                            target = COALESCE(NEW.target, OLD.target),
                            money_change = COALESCE(NEW.money_change, NEW.money, OLD.money_change, OLD.money),
                            detail = COALESCE(NEW.detail, OLD.detail),
                            note = COALESCE(NEW.note, OLD.note),
                            admin_reason = COALESCE(NEW.admin_reason, OLD.admin_reason)
                        WHERE id = OLD.id;
                    END
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    CREATE TRIGGER IF NOT EXISTS trg_matrixshop_transactions_delete
                    INSTEAD OF DELETE ON matrixshop_transactions
                    BEGIN
                        DELETE FROM record_entries WHERE id = OLD.id;
                    END
                    """.trimIndent()
                )
            }
        }
    }

    private fun ensureLegacyAuctionCompatibility(connection: Connection) {
        val relationType = relationType(connection, "matrixshop_auction")
        if (relationType == "TABLE") {
            return
        }
        connection.createStatement().use { statement ->
            if (relationType == "VIEW") {
                if (settings.type == DatabaseType.SQLITE) {
                    statement.executeUpdate("DROP TRIGGER IF EXISTS trg_matrixshop_auction_insert")
                    statement.executeUpdate("DROP TRIGGER IF EXISTS trg_matrixshop_auction_update")
                    statement.executeUpdate("DROP TRIGGER IF EXISTS trg_matrixshop_auction_delete")
                }
                statement.executeUpdate("DROP VIEW IF EXISTS matrixshop_auction")
            }
            statement.executeUpdate(
                """
                CREATE VIEW matrixshop_auction AS
                SELECT
                    id,
                    shop_id,
                    shop_id AS shop,
                    owner_id,
                    owner_id AS seller_id,
                    owner_name,
                    owner_name AS seller_name,
                    owner_name AS seller,
                    mode,
                    item_blob,
                    item_blob AS item,
                    item_blob AS item_data,
                    start_price,
                    start_price AS start_money,
                    buyout_price,
                    buyout_price AS buyout_money,
                    end_price,
                    end_price AS end_money,
                    current_bid,
                    current_bid AS current_money,
                    current_bid AS top_money,
                    highest_bidder_id,
                    highest_bidder_id AS buyer_id,
                    highest_bidder_name,
                    highest_bidder_name AS buyer_name,
                    highest_bidder_name AS buyer,
                    created_at,
                    created_at AS create_time,
                    expire_at,
                    expire_at AS expire_time,
                    expire_at AS end_time,
                    extend_count,
                    deposit_paid,
                    deposit_paid AS deposit
                FROM auction_listings
                """.trimIndent()
            )
            if (settings.type == DatabaseType.SQLITE) {
                statement.executeUpdate(
                    """
                    CREATE TRIGGER IF NOT EXISTS trg_matrixshop_auction_insert
                    INSTEAD OF INSERT ON matrixshop_auction
                    BEGIN
                        INSERT INTO auction_listings (
                            id, shop_id, owner_id, owner_name, mode, item_blob, start_price, buyout_price, end_price,
                            current_bid, highest_bidder_id, highest_bidder_name, created_at, expire_at, extend_count, deposit_paid
                        ) VALUES (
                            COALESCE(NEW.id, 'legacy-auc-' || lower(hex(randomblob(8)))),
                            COALESCE(NEW.shop_id, NEW.shop, 'default'),
                            COALESCE(NEW.owner_id, NEW.seller_id, ''),
                            COALESCE(NEW.owner_name, NEW.seller_name, NEW.seller, ''),
                            COALESCE(NEW.mode, 'ENGLISH'),
                            COALESCE(NEW.item_blob, NEW.item_data, NEW.item, ''),
                            COALESCE(NEW.start_price, NEW.start_money, 0),
                            COALESCE(NEW.buyout_price, NEW.buyout_money, 0),
                            COALESCE(NEW.end_price, NEW.end_money, 0),
                            COALESCE(NEW.current_bid, NEW.current_money, NEW.top_money, 0),
                            COALESCE(NEW.highest_bidder_id, NEW.buyer_id, ''),
                            COALESCE(NEW.highest_bidder_name, NEW.buyer_name, NEW.buyer, ''),
                            COALESCE(NEW.created_at, NEW.create_time, CAST(strftime('%s','now') AS INTEGER) * 1000),
                            COALESCE(NEW.expire_at, NEW.expire_time, NEW.end_time, CAST(strftime('%s','now') AS INTEGER) * 1000),
                            COALESCE(NEW.extend_count, 0),
                            COALESCE(NEW.deposit_paid, NEW.deposit, 0)
                        );
                    END
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    CREATE TRIGGER IF NOT EXISTS trg_matrixshop_auction_update
                    INSTEAD OF UPDATE ON matrixshop_auction
                    BEGIN
                        UPDATE auction_listings
                        SET
                            shop_id = COALESCE(NEW.shop_id, NEW.shop, OLD.shop_id, OLD.shop),
                            owner_id = COALESCE(NEW.owner_id, NEW.seller_id, OLD.owner_id, OLD.seller_id),
                            owner_name = COALESCE(NEW.owner_name, NEW.seller_name, NEW.seller, OLD.owner_name, OLD.seller_name, OLD.seller),
                            mode = COALESCE(NEW.mode, OLD.mode),
                            item_blob = COALESCE(NEW.item_blob, NEW.item_data, NEW.item, OLD.item_blob, OLD.item_data, OLD.item),
                            start_price = COALESCE(NEW.start_price, NEW.start_money, OLD.start_price, OLD.start_money),
                            buyout_price = COALESCE(NEW.buyout_price, NEW.buyout_money, OLD.buyout_price, OLD.buyout_money),
                            end_price = COALESCE(NEW.end_price, NEW.end_money, OLD.end_price, OLD.end_money),
                            current_bid = COALESCE(NEW.current_bid, NEW.current_money, NEW.top_money, OLD.current_bid, OLD.current_money, OLD.top_money),
                            highest_bidder_id = COALESCE(NEW.highest_bidder_id, NEW.buyer_id, OLD.highest_bidder_id, OLD.buyer_id),
                            highest_bidder_name = COALESCE(NEW.highest_bidder_name, NEW.buyer_name, NEW.buyer, OLD.highest_bidder_name, OLD.buyer_name, OLD.buyer),
                            created_at = COALESCE(NEW.created_at, NEW.create_time, OLD.created_at, OLD.create_time),
                            expire_at = COALESCE(NEW.expire_at, NEW.expire_time, NEW.end_time, OLD.expire_at, OLD.expire_time, OLD.end_time),
                            extend_count = COALESCE(NEW.extend_count, OLD.extend_count),
                            deposit_paid = COALESCE(NEW.deposit_paid, NEW.deposit, OLD.deposit_paid, OLD.deposit)
                        WHERE id = OLD.id;
                    END
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    CREATE TRIGGER IF NOT EXISTS trg_matrixshop_auction_delete
                    INSTEAD OF DELETE ON matrixshop_auction
                    BEGIN
                        DELETE FROM auction_bids WHERE listing_id = OLD.id;
                        DELETE FROM auction_listings WHERE id = OLD.id;
                    END
                    """.trimIndent()
                )
            }
        }
    }

    private fun recreateAuctionListingsWithShopScope(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate("DROP TABLE IF EXISTS auction_listings_v3")
            statement.executeUpdate(
                """
                CREATE TABLE auction_listings_v3 (
                    id VARCHAR(64) PRIMARY KEY,
                    shop_id VARCHAR(64) NOT NULL,
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
                INSERT INTO auction_listings_v3 (
                    id, shop_id, owner_id, owner_name, mode, item_blob, start_price, buyout_price, end_price,
                    current_bid, highest_bidder_id, highest_bidder_name, created_at, expire_at, extend_count, deposit_paid
                )
                SELECT
                    id, 'default', owner_id, owner_name, mode, item_blob, start_price, buyout_price, end_price,
                    current_bid, highest_bidder_id, highest_bidder_name, created_at, expire_at, extend_count, deposit_paid
                FROM auction_listings
                """.trimIndent()
            )
            statement.executeUpdate("DROP TABLE auction_listings")
            statement.executeUpdate("ALTER TABLE auction_listings_v3 RENAME TO auction_listings")
        }
    }

    private fun recreateGlobalMarketListingsWithShopScope(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate("DROP TABLE IF EXISTS global_market_listings_v3")
            statement.executeUpdate(
                """
                CREATE TABLE global_market_listings_v3 (
                    id VARCHAR(64) PRIMARY KEY,
                    shop_id VARCHAR(64) NOT NULL,
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
                INSERT INTO global_market_listings_v3 (
                    id, shop_id, owner_id, owner_name, price, currency, item_blob, created_at, expire_at
                )
                SELECT
                    id, 'default', owner_id, owner_name, price, currency, item_blob, created_at, expire_at
                FROM global_market_listings
                """.trimIndent()
            )
            statement.executeUpdate("DROP TABLE global_market_listings")
            statement.executeUpdate("ALTER TABLE global_market_listings_v3 RENAME TO global_market_listings")
        }
    }

    private fun recreatePlayerShopStoresWithShopScope(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate("DROP TABLE IF EXISTS player_shop_stores_v3")
            statement.executeUpdate(
                """
                CREATE TABLE player_shop_stores_v3 (
                    shop_id VARCHAR(64) NOT NULL,
                    owner_id VARCHAR(64) NOT NULL,
                    owner_name VARCHAR(64) NOT NULL,
                    unlocked_slots INT NOT NULL,
                    PRIMARY KEY (shop_id, owner_id)
                )
                """.trimIndent()
            )
            statement.executeUpdate(
                """
                INSERT INTO player_shop_stores_v3 (shop_id, owner_id, owner_name, unlocked_slots)
                SELECT 'default', owner_id, owner_name, unlocked_slots
                FROM player_shop_stores
                """.trimIndent()
            )
            statement.executeUpdate("DROP TABLE player_shop_stores")
            statement.executeUpdate("ALTER TABLE player_shop_stores_v3 RENAME TO player_shop_stores")
        }
    }

    private fun recreatePlayerShopListingsWithShopScope(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate("DROP TABLE IF EXISTS player_shop_listings_v3")
            statement.executeUpdate(
                """
                CREATE TABLE player_shop_listings_v3 (
                    id VARCHAR(64) PRIMARY KEY,
                    shop_id VARCHAR(64) NOT NULL,
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
                INSERT INTO player_shop_listings_v3 (
                    id, shop_id, owner_id, slot_index, price, currency, item_blob, created_at
                )
                SELECT
                    id, 'default', owner_id, slot_index, price, currency, item_blob, created_at
                FROM player_shop_listings
                """.trimIndent()
            )
            statement.executeUpdate("DROP TABLE player_shop_listings")
            statement.executeUpdate("ALTER TABLE player_shop_listings_v3 RENAME TO player_shop_listings")
        }
    }

    private fun columnExists(connection: Connection, tableName: String, columnName: String): Boolean {
        return runCatching {
            connection.metaData.getColumns(null, null, tableName, columnName).use { result ->
                result.next()
            }
        }.getOrDefault(false)
    }

    private fun relationType(connection: Connection, relationName: String): String? {
        return runCatching {
            connection.metaData.getTables(null, null, "%", arrayOf("TABLE", "VIEW")).use { result ->
                while (result.next()) {
                    if (result.getString("TABLE_NAME").equals(relationName, true)) {
                        return@use result.getString("TABLE_TYPE")?.uppercase(Locale.ROOT)
                    }
                }
                null
            }
        }.getOrNull()
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
