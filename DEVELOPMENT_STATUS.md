# MatrixShop Development Status

This file is the handoff note for each development round.

## Working Rules

- Update `Completed This Round` and `Next Tasks` after each meaningful coding round.
- Only record work that is already inside `Code` and actually committed.
- Only push the `Code` subtree to GitHub. Do not push other workspace files.

## Remote Branch

- Branch: `codex/bootstrap-v1`
- Repo: [MatrixShop](https://github.com/54895y/MatrixShop.git)

## Current Summary

- Core plugin bootstrap, module registry, command entrypoints, menu framework, and permission checks are in place.
- Implemented module v1: `SystemShop`, `PlayerShop`, `GlobalMarket`, `Cart`, `Record`, `Transaction`, `Auction`, `ChestShop`.
- Runtime storage now prefers JDBC and falls back to legacy files when JDBC is unavailable.

## Data Layer Coverage

- `Record`: JDBC-first, file fallback
- `AuctionRepository`: JDBC-first, file fallback
- `AuctionDeliveryRepository`: JDBC-first, file fallback
- `GlobalMarketRepository`: JDBC-first, file fallback
- `PlayerShopRepository`: JDBC-first, file fallback
- `CartRepository`: JDBC-first, file fallback
- `ChestShopRepository`: JDBC-first, file fallback

## Completed This Round

- Added `ModuleBindings` so module command keys now come from each module `settings.yml`
- Updated `/ms` routing to resolve module entry keys from `Bindings.Commands.Bindings`
- Switched standalone `trade`, `auction`, and `chestshop` command registration to config-driven bindings at startup
- Added missing `Bindings` blocks to `SystemShop`, `PlayerShop`, `GlobalMarket`, `ChestShop`, `Cart`, and `Record` settings
- Added `shops/default.yml` packs for `Auction`, `PlayerShop`, `GlobalMarket`, and `ChestShop`
- Added `ShopMenuLoader` and moved those four modules to `shops/*.yml`-driven browse menus with legacy UI fallback
- Added optional `shop-id` support for `/ms <module> open [shop-id]` and direct `/ms <module> <shop-id>` opens on multi-shop modules
- Fixed `ProxyCommandSender` handling in `MatrixShopCommands` to avoid console-side `ClassCastException`
- Migrated `AuctionDeliveryRepository` to JDBC-first with file fallback
- Migrated `ChestShopRepository` to JDBC-first with file fallback
- Added database metadata bootstrap in `DatabaseManager`
- Added schema metadata table `matrixshop_meta`
- Added schema version tracking in `DatabaseManager`
- Added backend target description and known-table counting in `DatabaseManager`
- Expanded `/matrixshopadmin status` to show configured backend, active backend, target, schema version, Redis toggle, and table counts
- Rewrote this handoff document into stable ASCII Markdown
- Added schema migration runner with versioned database sync
- Added startup and reload-time schema sync after module initialization
- Added `/matrixshopadmin sync` for manual schema synchronization
- Added runtime table/index bootstrap for JDBC backends
- Moved plugin startup order to `RecordService -> schema sync -> module reload`
- Removed duplicate runtime `CREATE TABLE IF NOT EXISTS` blocks from JDBC repositories
- Added centralized legacy file import service for JDBC migration
- Removed repository-local automatic legacy imports from `initialize()` and `loadJdbc()`
- Moved `Record` legacy import into the same centralized migration flow
- Added batch legacy record import for JDBC
- Expanded admin sync/status output with legacy import summary
- Persisted last legacy import summary into database metadata
- Expanded admin status output with migration/import timestamps and totals
- Ran a live Paper 1.12.2 startup test with the current build
- Verified `/matrixshopadmin status` and `/matrixshopadmin sync` on live Paper 1.12.2
- Verified `ms`, `matrixshop`, `trade`, `auction`, and `chestshop` from the Paper 1.12.2 console now return player-only feedback instead of throwing exceptions

## Validation

- Local build passed with `./gradlew.bat build`
- Config-driven bindings and multi-shop refactor compiled successfully with `./gradlew.bat build`
- Live startup test passed on local `1.12.2paper`
- Live admin command test passed for `matrixshopadmin status` and `matrixshopadmin sync`
- Live console smoke test passed for `ms`, `matrixshop`, `trade`, `auction`, and `chestshop` without MatrixShop stack traces

## Known Boundaries

- `SystemShop` is still config-driven and does not use JDBC runtime storage
- Database access still uses direct JDBC helpers; there is no shared transaction helper yet
- Schema migration now exists, but there is still no richer multi-step migration history or rollback support
- Legacy file import is now centralized before module reload, but per-module import results are only stored as summary metadata
- Redis is config-visible only; actual sync/invalidation transport is not implemented
- Test environment currently uses a PlaceholderAPI build that throws `Bukkit.getAsyncScheduler()` on shutdown under Paper 1.12.2; this is external to MatrixShop
- Non-interactive stdin replay can still report `Unknown command` for the first queued console line during early startup; use a fully interactive terminal when validating admin commands
- Standalone command names still register only once at plugin startup; changing standalone binding keys requires a full restart, while `/ms` binding changes follow config reload

## Next Tasks

### Highest Priority

- Review whether `SystemShop` needs database-backed cache/index support or should remain purely config-driven
- Add more focused admin diagnostics for data-layer health and migration state
- Consider whether legacy import summaries should also expose per-module timestamps and source-file counts
- Start validating player-side command paths and one complete business flow on live Paper
- Re-run interactive admin command validation in a persistent server terminal
- Add menu actions to jump between custom `shops/*.yml` packs without relying only on command opens

### Medium Priority

- Evaluate and finalize SQLite/MySQL driver packaging strategy
- Run Paper server integration testing instead of relying only on `gradlew build`
- Add minimal regression coverage for the migrated repositories

### Lower Priority

- Align `database.yml` comments and behavior with the current JDBC-first/file-fallback design
- Introduce shared transaction or batch-write helpers if repository write paths keep growing
