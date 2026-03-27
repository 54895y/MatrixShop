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

- Moved `MatrixAuth` listener / command / placeholder / ProtocolLib registration into a dedicated local runtime coordinator instead of keeping those registrations in the plugin main class
- Added `MatrixAuthRuntimeCoordinator` to centralize enable/disable-time runtime registrations and cleanup for PlaceholderAPI and ProtocolLib hooks
- Added explicit unregister flow for `MatrixAuth` PlaceholderAPI expansion and ProtocolLib packet listener during plugin shutdown
- Revalidated `MatrixAuth` startup on local `paper-1.12.2` after the runtime-coordinator refactor
- Moved `MatrixAuth` startup/reload/disable service assembly into a dedicated local service container instead of keeping the wiring spread across the plugin main class
- Added `MatrixAuthServices` to centralize config/service construction and staged shutdown for `DataManager`, `MojangApiClient`, `EasyBotCompatHttpService`, and related runtime services
- Changed `MatrixAuth` exported service properties to resolve through the active service container, which keeps external call sites stable while shrinking main-class wiring
- Revalidated `MatrixAuth` startup on local `paper-1.12.2` after the service-container refactor
- Added shared Bukkit platform helpers to `MatrixLib` for plugin lookup and reflective command-map registration
- Moved `MatrixAuth` dynamic command registration onto the shared `MatrixLib` Bukkit command helper
- Moved `MatrixAuth` optional plugin checks (`AuthMe`, `PlaceholderAPI`, `ProtocolLib`, `EasyBot`, `Geyser`, `Floodgate`) onto the shared `MatrixLib` plugin lookup helper
- Moved `MatrixCook` primary plugin-instance resolution onto the shared `MatrixLib` plugin lookup helper
- Moved `MatrixShop` runtime plugin-instance resolution onto the shared `MatrixLib` plugin lookup helper
- Revalidated `MatrixLib + MatrixAuth + MatrixCook + MatrixShop` together on local `paper-1.12.2` after the shared Bukkit-platform migration
- Added shared JDBC runtime support to `MatrixLib`, including Hikari-backed SQLite/MySQL datasource builders
- Moved `MatrixAuth` datasource creation onto the shared `MatrixLib` JDBC factory
- Moved `MatrixCook` placed-cooker JDBC storage onto the shared `MatrixLib` JDBC factory
- Verified that runtime dependency loading for JDBC libraries still has to be declared by each plugin entrypoint under the current TabooLib packaging layout
- Restored plugin-local JDBC runtime dependency declarations for `MatrixAuth` and `MatrixCook` while keeping the datasource construction logic centralized in `MatrixLib`
- Revalidated `MatrixCook` SQLite startup and `MatrixAuth` startup on local `paper-1.12.2` after the JDBC factory migration
- Moved shared item-source hook ownership into `MatrixLib` through owner-aware hook registration and unregistration
- Added `MatrixCommonItemHooks` in `MatrixLib` for common `CraftEngine`, `ItemsAdder`, and `Oraxen` item resolution
- Changed `MatrixCook` to register common item hooks through `MatrixLib` instead of duplicating `ce / ia / ox` reflection hooks locally
- Changed `MatrixCook` `ItemData` base-item resolution to use `MatrixLib MatrixItemHooks`, which centralizes preview parsing for normal and external item sources
- Changed `MatrixShop` to register its common external item hooks through `MatrixLib MatrixCommonItemHooks` instead of keeping a second copy of the same hook implementations
- Stabilized the Gradle/Kotlin build path for this round by rebuilding `MatrixLib -> MatrixCook -> MatrixShop` sequentially after cleaning local build caches
- Deployed the current `MatrixLib`, `MatrixCook`, and `MatrixShop` jars to local `paper-1.12.2` and re-ran smoke startup validation
- Normalized the default `Menu` hub button actions to `matrixshop open <type:id>` instead of mixing direct module commands
- Added typed `/ms open <type:id>` resolution so duplicate short shop ids can be disambiguated without removing short ids
- Added explicit typed open support for `systemshop:<category>` plus typed route suggestions in ambiguity messages
- Added a new `Menu` module as a bindings-driven hub that opens other modules through configurable button actions
- Added `MenuModule` and connected it to module reload, module toggles, help output, bound-shop routing, standalone shop command registration, and `/ms open <shop-id>`
- Added `Menu/settings.yml`, `Menu/shops/main.yml`, and `Menu/ui/default.yml`
- Added `matrixshop.menu.use` permission and `menu` module binding defaults
- Added default menu hub buttons for `SystemShop`, `PlayerShop`, `GlobalMarket`, `Auction`, `Transaction`, `Cart`, and `Record`
- Added plan-aligned `Cart/ui/checkout.yml` and `Cart/ui/conflict.yml` flows and wired `/cart checkout`, `/cart checkout confirm`, and `/cart conflict`
- Changed `Cart` command handling so bound cart views now scope `clear`, `remove`, `remove_invalid`, `amount`, and checkout actions to the selected `shop-id`
- Added cart shop-view filtering through `Cart/shops/*.yml -> Options.Source-Modules`, so different cart bindings can expose different source-module subsets
- Added cart entry templating through `template` in both `Cart/shops/*.yml` and fallback `Cart/ui/cart.yml`
- Added current-price aware cart validation payloads for `system_shop`, `player_shop`, and `global_market`
- Added `SystemShopModule.currentPrice`, `PlayerShopModule.currentListingPrice`, and `GlobalMarketModule.currentListingPrice` for cart revalidation/summary rendering
- Added record shop-view filtering through `Record/shops/*.yml -> Options.Modules`, so different record bindings can expose different module subsets
- Added record filter state and `/record filter [module|all]` with click-to-cycle filter support from the main record view
- Changed `Record` browse/detail/stats flows to preserve `shop-id`, keyword, and module-filter together
- Added record list templating through `template` in `Record/shops/*.yml` and fallback `Record/ui/record.yml`
- Generalized menu slot collection so any non-empty `mode:` can be rendered by module code, which unblocks custom view modes like `checkout`, `conflict`, and `records`
- Extended shop-scoped binding routing to `Cart`, `Record`, and `ChestShop`
- Added `Cart/shops/cart.yml` and `Record/shops/record.yml` as real bindings-driven entry menus instead of UI-only defaults
- Added `Bindings` support to `ChestShop/shops/default.yml` and removed the stale `id` field from that entry pack
- Changed `/ms open <shop-id>` to also resolve `Cart`, `Record`, and `ChestShop` shop ids
- Added standalone shop-bound command registration for `Cart`, `Record`, and custom `ChestShop` shop packs
- Changed `Cart`, `Record`, and `ChestShop` command handlers to follow the same explicit `open` flow as the other shop-bound modules
- Updated player help output so `ChestShop`, `Cart`, and `Record` now render from shop-bound bindings instead of only module settings
- Changed `CartModule` to load `shops/*.yml` entry menus with legacy `ui/cart.yml` fallback
- Changed `RecordModule` to load `shops/*.yml` entry menus with legacy `ui/record.yml` fallback
- Propagated selected `shop-id` through `Record` list/detail/stats navigation and `Cart` pagination/reopen flows
- Changed the main player command so `/ms open <shop-id>` now resolves shop ids across `Auction`, `GlobalMarket`, `PlayerShop`, and `Transaction`
- Added duplicate shop-id detection for `/ms open <shop-id>` and fallback to `SystemShop` category open when no shop id matches
- Switched bound shop help examples from `/ms <binding> ...` to direct standalone `/<binding> ...` usage
- Removed `id` from default `shops/*.yml` packs and switched shop identity to the file name itself
- Updated `ShopMenuLoader` to ignore `id` keys and preserve the raw file name as `shopId`, which keeps Chinese file names intact
- Changed `Auction`, `GlobalMarket`, `PlayerShop`, and `Transaction` open flows so opening now only happens through the `open` subcommand
- Updated `/ms help` examples to show `open`-scoped entry usage for the four multi-shop modules
- Added true shop-scoped bindings for `Auction`, `GlobalMarket`, `PlayerShop`, and `Transaction`
- Switched `/ms` routing to resolve shop bindings from `shops/*.yml` before module-level bindings
- Added shop-bound standalone command registration with duplicate-name protection
- Changed `/ms help` so the four multi-shop modules now render help entries from `shops/*.yml` bindings instead of only module settings
- Added `shop_id` to `AuctionListing`, `GlobalMarketListing`, `PlayerShopStore`, and `Transaction` request/session context
- Migrated JDBC runtime schema to shop-scoped `auction_listings`, `global_market_listings`, and `player_shop_*` tables
- Added schema migration v3 for upgrading existing databases to shop-scoped tables
- Updated `AuctionRepository`, `GlobalMarketRepository`, and `PlayerShopRepository` to persist shop-scoped data in JDBC and file backends
- Changed `AuctionModule`, `GlobalMarketModule`, and `PlayerShopModule` so browse/upload/manage/purchase flows now operate on the selected shop instance
- Updated `CartModule` to preserve `shop-id` metadata for `player_shop` and `global_market` entries during validation and checkout
- Added `Transaction/shops/default.yml` and shop-scoped entry handling for trade requests
- Replaced default `shops/default.yml` packs for `Auction`, `GlobalMarket`, and `PlayerShop` with bindings-enabled shop configs
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

- `MatrixAuth` runtime-coordinator refactor compiled successfully with `./gradlew build`
- Live smoke boot passed on local `paper-1.12.2` after deploying the new `MatrixAuth`
- Smoke log confirmed `MatrixAuth` still enables successfully after moving listener / command / placeholder / ProtocolLib registration into the runtime coordinator
- `MatrixAuth` service-container refactor compiled successfully with `./gradlew build`
- Live smoke boot passed on local `paper-1.12.2` after deploying the new `MatrixAuth`
- Smoke log confirmed `MatrixAuth` still enables successfully after moving startup/reload/disable assembly into the service container
- `MatrixLib` shared Bukkit platform helper refactor compiled successfully with `./gradlew build`
- `MatrixAuth` Bukkit platform helper migration compiled successfully with `./gradlew build`
- `MatrixCook` plugin lookup migration compiled successfully with `./gradlew build`
- `MatrixShop` runtime plugin lookup migration compiled successfully with `./gradlew build`
- Live smoke boot passed on local `paper-1.12.2` after deploying the new `MatrixLib + MatrixAuth + MatrixCook + MatrixShop`
- Smoke log confirmed `MatrixAuth`, `MatrixCook`, and `MatrixShop` all enabled successfully after the shared Bukkit-platform migration
- `MatrixLib` shared JDBC factory refactor compiled successfully with `./gradlew build`
- `MatrixAuth` JDBC factory migration compiled successfully with `./gradlew build`
- `MatrixCook` JDBC factory migration compiled successfully with `./gradlew build`
- Live smoke boot passed on local `paper-1.12.2` after deploying the new `MatrixLib + MatrixAuth + MatrixCook`
- Smoke log confirmed `MatrixCook` now starts on SQLite and `MatrixAuth` now enables successfully after restoring plugin-local JDBC runtime dependency loading
- `MatrixLib` shared item-hook refactor compiled successfully with `./gradlew build`
- `MatrixCook` common item-hook migration compiled successfully with `./gradlew build`
- `MatrixShop` common item-hook migration compiled successfully with `./gradlew build`
- Live smoke boot passed on local `paper-1.12.2` after deploying the new `MatrixLib + MatrixCook + MatrixShop`
- Smoke log confirmed no new `NoClassDefFoundError`, item-hook registration failure, or startup exception after the shared hook migration
- Typed open-id routing compiled successfully with `./gradlew.bat build`
- Menu module integration compiled successfully with `./gradlew.bat build`
- Plan-aligned cart checkout/conflict + record filter/view-config refactor compiled successfully with `./gradlew.bat build`
- Local build passed with `./gradlew.bat build`
- `Cart / Record / ChestShop` shop-binding refactor compiled successfully with `./gradlew.bat build`
- `/ms open <shop-id>` command-routing refactor passed with `./gradlew.bat build`
- Filename-based shop id + open-only command refactor passed with `./gradlew.bat build`
- Multi-shop bindings + shop-scoped data-layer refactor passed with `./gradlew.bat build`
- Config-driven bindings and multi-shop refactor compiled successfully with `./gradlew.bat build`
- Live startup test passed on local `1.12.2paper`
- Live admin command test passed for `matrixshopadmin status` and `matrixshopadmin sync`
- Live console smoke test passed for `ms`, `matrixshop`, `trade`, `auction`, and `chestshop` without MatrixShop stack traces

## Known Boundaries

- `MatrixAuth` runtime registration is now localized in a dedicated coordinator, but reload still does not rebuild listener / placeholder / protocol registration paths during `reloadAll()`
- `MatrixAuth` service assembly and runtime registration are now localized behind dedicated local coordinators, but reload still only rebuilds services, not runtime registrations
- `MatrixLib` now owns reusable Bukkit plugin lookup and reflective command registration helpers, but listener registration and optional-hook business decisions still stay inside each plugin
- `MatrixLib` now owns the shared JDBC datasource factory, but JDBC runtime dependency provisioning is still plugin-local under the current TabooLib packaging model
- The current host still has a fragile Kotlin daemon/cache environment; sequential rebuilds are reliable, but parallel composite builds can corrupt local Kotlin zip caches
- `MatrixShop` currently consumes shared item hooks in menu rendering and `SystemShop`, but auction/global-market/player-shop/chestshop business data still mostly comes from real stored `ItemStack` snapshots rather than raw item-source ids
- Short `/ms open <id>` still works as before; typed `/ms open <type:id>` is only the explicit disambiguation path when ids collide
- The default `Menu` hub now routes through `matrixshop open <type:id>`, so custom packs should prefer that format over direct standalone module commands
- `/ms open <shop-id>` requires the shop id to be unique across `Auction`, `GlobalMarket`, `PlayerShop`, `Transaction`, `Cart`, `Record`, and `ChestShop`; duplicate ids now return an ambiguity message instead of guessing
- Shop ids now come only from `shops/<file-name>.yml`; renaming a shop file changes the runtime `shopId` and therefore the storage key used by shop-scoped modules
- `Auction`, `GlobalMarket`, and `PlayerShop` are now shop-scoped in storage and commands, but `ChestShop` still only uses `shops/*.yml` as alternate views, not separate shop pools
- `Cart` and `Record` now support shop-bound entry menus, per-view filters, and config templates, but their runtime data is still global per player rather than partitioned by shop id
- `Transaction` now has shop entry configs and binding-based routing, but request/trade/confirm UIs are still shared module-level templates rather than per-shop UI packs
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

- Decide whether `MatrixAuth reloadAll()` should also rebuild runtime registrations, or whether registration remains strictly enable/disable scoped
- Continue shrinking `MatrixAuth` main-class wiring by deciding whether listener/protocol/placeholder registration should also move behind a local runtime coordinator
- Continue shrinking `MatrixAuth` bootstrap/reload wiring, with the next candidate being reload-stage service assembly and shutdown cleanup helpers
- Continue consolidating plugin-local runtime wrappers into `MatrixLib`, with the next best target being `MatrixAuth` reload/bootstrap helpers instead of datasource creation
- Continue replacing duplicated plugin-local item parsing with `MatrixLib` shared hooks where raw source ids still exist
- Decide whether to move more external item-source adapters from `MatrixCook` into `MatrixLib`, or keep only the three common hooks there
- Run live Paper validation for the new `Menu` hub buttons and at least one custom `Menu/shops/*.yml` pack
- Run live Paper validation for cart checkout/conflict and record filter on at least two custom shop packs per module
- Decide whether `Cart` should gain per-shop persistence pools or remain a global player cart with filtered shop views
- Decide whether `Record` should add a true filter menu UI instead of the current command/cycle-based filter interaction
- Run live Paper validation for `/ms <shop-binding>` on at least one custom `Auction`, `GlobalMarket`, `PlayerShop`, and `Transaction` shop pack
- Add a second sample shop config for each of the four shop-scoped modules to verify duplicate binding registration and isolated content behavior
- Decide whether `Transaction` should also support per-shop `request/trade/confirm` UI overrides instead of only per-shop entry menus
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
