# Changelog

All notable changes to MatrixShop will be documented in this file.

The format is based on Keep a Changelog, and this project follows Semantic Versioning for release tags.

## [1.5.0] - 2026-04-03

### Added

- Added a conditional tax system for `PlayerShop`, `GlobalMarket`, `Auction`, `Transaction`, and `ChestShop`.
- Added tax rule support for `Enabled`, `Mode`, `Value`, `Priority`, and Kether `Condition`.
- Added legacy-compatible tax parsing for `GlobalMarket` `Listing.Tax-Percent`.

### Changed

- `PlayerShop`, `GlobalMarket`, and `Auction` now resolve seller-side tax from a shared conditional tax engine instead of fixed inline math.
- `Transaction` now applies money tax per offer direction and records net received amounts.
- `ChestShop` now supports tax on both player-buy and player-sell flows.
- Updated bundled settings and docs to show the new tax rule structure.

### Validated

- Verified `./gradlew build`.
- Verified docs site `npm run build`.
- Verified smoke boot on `paper-1.21.8`.
- Verified smoke boot on `paper-1.21.11`.
- Verified `paper-1.21.11` can load conditional tax configs with Kether `Condition` rules for `PlayerShop`, `GlobalMarket`, `Auction`, `Transaction`, and `ChestShop`.

## [1.4.0] - 2026-04-03

### Added

- Added a `SystemShop` discount system under the `price` key with backward-compatible scalar and object forms.
- Added support for `percent`, `amount-off`, and `surcharge` discount rules.
- Added `whitelist` and `blacklist` controls for discount stacking.
- Added Kether-based discount conditions with player and product context.
- Added discount-aware placeholders for `SystemShop` rendering, including base price and discount summary data.

### Changed

- `SystemShop` now resolves final purchase price per player before menu display, confirm view, and direct purchase.
- `SystemShop` refresh pool price overrides can now use price objects and merge discounts with the base goods definition.
- `Cart` now locks `SystemShop` snapshot price to the discounted price at add-to-cart time.
- Expanded the docs and default resource examples to cover the new discount format.

### Validated

- Verified `./gradlew build`.
- Verified docs site `npm run build`.
- Verified smoke boot on `paper-1.21.8`.
- Verified smoke boot on `paper-1.21.11`.
- Verified `paper-1.21.11` can load an inline `SystemShop` `price` object with `percent`, `amount-off`, `surcharge`, and Kether `condition` fields without startup warnings.

## [1.3.0] - 2026-04-02

### Added

- Added `SystemShop` goods repository flow with reusable `product`, `group`, and `pool` resources under `SystemShop/goods/*.yml`.
- Added admin goods browser and editor entry points through:
  - `/matrixshopadmin goods ui [page]`
  - `/matrixshopadmin goods save <price> [buy-max] [product-id]`
  - `/matrixshopadmin goods add <category> <product-id>`
  - `/matrixshopadmin goods select <category> <product-id>`
  - `/matrixshopadmin goods edit <price|buy-max|currency|name|item|remove> ...`
- Added `SystemShop` refresh foundation with cron-driven goods areas, player-group matching, shared or per-player snapshots, and admin refresh commands.
- Added shaded `bStats` metrics integration for deployment and module usage telemetry.

### Changed

- Reworked default `SystemShop` resources so categories reference standalone goods files instead of repeating inline definitions.
- Expanded the official docs and repository README to cover goods repository management, refresh configuration, telemetry, and the current release entry points.
- Updated default `weapon` shop resources with a commented refresh example that can be enabled directly.

### Validated

- Verified `./gradlew build` on the `1.3.0` release source.
- Verified smoke boot on `paper-1.21.8`.
- Verified smoke boot on `paper-1.21.11`.

## [1.2.0] - 2026-03-31

### Added

- Added admin quick listing for `SystemShop` categories through `/matrixshopadmin goods add <category> <price> [buy-max] [product-id]`.
- Added admin quick editing for `SystemShop` products through:
  - `/matrixshopadmin goods select <category> <product-id>`
  - `/matrixshopadmin goods edit <price|buy-max|currency|name|item|remove> ...`
- Added in-menu admin selection for `SystemShop` products with `Shift + Right Click`.

### Changed

- `SystemShop` product definitions can now preserve the full configured item stack for admin-added goods instead of rebuilding only from material data.
- Updated release documentation for the new admin product maintenance flow.

### Validated

- Verified the full admin flow with a real client on `paper-1.12.2`:
  - `/give`
  - `goods add`
  - `goods select`
  - `goods edit price`
  - `goods edit buy-max`
  - `goods edit name`
  - `goods edit remove`

## [1.1.1] - 2026-03-31

### Added

- Added a formal `CHANGELOG.md` to the repository.
- Added a dedicated `1.1.1` release note entry to the documentation site.

### Changed

- Updated the repository README and docs entry points so the current release notes are easier to find.

### Fixed

- Replaced deprecated skull owner assignment in the transaction request preview with a compatibility wrapper.
- Reworked PlayerShop owner lookup by player name to avoid deprecated direct name-based offline lookup and to resolve from online players, cached offline profiles, and existing shop data.
- Finalized the documentation and release materials for the 1.1.x line.

## [1.1.0] - 2026-03-31

### Added

- Added the mandatory `Economy` core module and unified currency registry.
- Added currency resolution priority: product-level, shop-level, module-level, then fallback to `vault`.
- Added configurable multiline help blocks for default module commands.
- Added `Help-Key` and `Hint-Keys` support for bindings-driven help and hint output.
- Added release-ready documentation for economy, bindings help, and configuration examples.

### Changed

- Simplified `ChestShop`, `Cart`, and `Record` configuration structure to use module-level UI entry models where applicable.
- Standardized public-facing language, command feedback, and module status output for production release.
- Expanded official documentation coverage in the `54895y.github.io` site and aligned it with current default resources.

### Fixed

- Fixed shop currency inheritance so an undefined shop currency now falls back to module-level configuration before `vault`.
- Fixed standalone help behavior for `Cart` and `Record`.
- Fixed remaining release-path compatibility issues in default command help and resource merging.

## [1.0.0] - 2026-03-29

### Added

- Initial public release of MatrixShop.
- Added `SystemShop`, `PlayerShop`, `GlobalMarket`, `Auction`, `ChestShop`, `Transaction`, `Cart`, `Record`, and `Menu`.
- Added unified bindings, command routing, menu framework, record system, and JDBC-first storage layer.
- Added initial documentation site and default Chinese-first resource set.
