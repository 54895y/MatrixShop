# Changelog

All notable changes to MatrixShop will be documented in this file.

The format is based on Keep a Changelog, and this project follows Semantic Versioning for release tags.

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
