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

## Validation

- Local build passed with `./gradlew.bat build`
- No Paper runtime integration test has been executed yet

## Known Boundaries

- `SystemShop` is still config-driven and does not use JDBC runtime storage
- Database access still uses direct JDBC helpers; there is no shared transaction helper yet
- Schema migration now exists, but there is still no richer multi-step migration history or rollback support
- Redis is config-visible only; actual sync/invalidation transport is not implemented
- Runtime behavior has been validated by build only, not by live server testing

## Next Tasks

### Highest Priority

- Review whether `SystemShop` needs database-backed cache/index support or should remain purely config-driven
- Add more focused admin diagnostics for data-layer health and migration state
- Decide whether repository-local migration triggers should also be centralized, not only table creation

### Medium Priority

- Evaluate and finalize SQLite/MySQL driver packaging strategy
- Run Paper server integration testing instead of relying only on `gradlew build`
- Add minimal regression coverage for the migrated repositories

### Lower Priority

- Align `database.yml` comments and behavior with the current JDBC-first/file-fallback design
- Introduce shared transaction or batch-write helpers if repository write paths keep growing
