# MatrixShop

**Keywords:** Minecraft shop plugin, GUI shop plugin, auction plugin, player market plugin, trade plugin, Vault economy plugin, Paper plugin, Folia plugin, Bukkit plugin,  Minecraft plugin

MatrixShop is a Chinese-first Minecraft GUI economy and commerce plugin for Paper, Bukkit, Spigot and Folia. It combines system shop, player shop, global market, auction, chest shop, face-to-face trade, shopping cart and transaction records in one project.

**中文关键词：** 商店插件, GUI 商店插件, 拍卖插件, 玩家市场插件, 交易插件, 经济插件, Vault 商店插件, 中文服插件, Paper 插件, Folia 插件

## Discoverability

- English: Minecraft shop plugin, GUI shop, player market, auction house, chest shop, trade plugin, Vault economy, Chinese server plugin
- 中文: 商店系统, 玩家商店, 全局市场, 拍卖行, 箱子商店, 面对面交易, 中文 GUI 商城, 中文服经济插件

## Core Modules

- Economy
- System Shop
- Player Shop
- Global Market
- Auction
- Chest Shop
- Face-to-face Trade
- Shopping Cart
- Transaction Record
- Unified Menu Entry

## Runtime Targets

- Recommended server: `Paper` / `Folia`
- Build target: `Bukkit API 1.12.2`
- Required dependency: [MatrixLib](https://github.com/54895y/MatrixLib)
- Supported economy backends: `Vault` / `PlayerPoints` / Placeholder-based custom currencies
- Database: `SQLite` / `MySQL`
- Optional sync layer: `Redis`

## Why MatrixShop

- Chinese-first GUI economy plugin for Minecraft servers
- Unified command and menu structure for multiple commerce modules
- Built for Chinese communities that want one plugin to cover shop, market, auction and trade workflows
- Default configs are Chinese-oriented and ready for further customization

## Economy Model

MatrixShop now uses a dedicated `Economy` module for currency definitions.

Priority order:

1. Product-level currency
2. Shop-level currency
3. Module-level currency
4. Fallback to `vault`

Default currency config path:

```text
plugins/MatrixShop/Economy/currency.yml
```

This means you can keep one unified currency registry and let business modules only reference a currency key.

## Source Build

```powershell
./gradlew.bat build
```

The deployable runtime artifact is:

```text
build/libs/MatrixShop-1.2.0-all.jar
```

Current source dependency:

- `com.y54895.matrixlib:matrixlib-api:1.0.1`

## Telemetry

MatrixShop now includes [bStats](https://bstats.org/) telemetry support with plugin id `30502`.

Collected charts are configuration and module level only:

- active database backend
- configured database backend
- enabled module count
- enabled module distribution
- SystemShop category count
- SystemShop goods count
- configured economy currency count
- configured economy currency mode distribution

It does not submit player names, transaction records, item contents, chat messages, or database credentials.

## Links

- GitHub Repo: [https://github.com/54895y/MatrixShop](https://github.com/54895y/MatrixShop)
- Docs: [https://54895y.github.io/docs/matrixshop](https://54895y.github.io/docs/matrixshop)
- Changelog: [CHANGELOG.md](./CHANGELOG.md)
- Release Notes 1.2.0: [https://54895y.github.io/docs/matrixshop/release-notes-1-2-0](https://54895y.github.io/docs/matrixshop/release-notes-1-2-0)
- Issues: [https://github.com/54895y/MatrixShop/issues](https://github.com/54895y/MatrixShop/issues)
- Releases: [https://github.com/54895y/MatrixShop/releases](https://github.com/54895y/MatrixShop/releases)
- Required dependency: [MatrixLib](https://github.com/54895y/MatrixLib)
