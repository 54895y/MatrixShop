# MatrixShop

> 中文优先的 Minecraft GUI 经济与商业插件  
> Chinese-first Minecraft GUI economy and commerce plugin

MatrixShop 将系统商店、玩家商店、全局市场、拍卖、箱子商店、面对面交易、购物车和交易记录整合到同一个项目中，面向 `Paper`、`Bukkit`、`Spigot` 和 `Folia` 服务端生态。

| 文档 | 更新日志 | Releases | 依赖 |
| --- | --- | --- | --- |
| [Docs](https://54895y.github.io/docs/matrixshop) | [Release Notes](https://54895y.github.io/docs/matrixshop/release-notes) | [GitHub Releases](https://github.com/54895y/MatrixShop/releases) | [MatrixLib](https://github.com/54895y/MatrixLib) |

## 中文简介

MatrixShop 是一款面向生存服、经济服和商业玩法场景的综合交易插件。  
当前主线重点包括：

- 统一经济模块与货币优先级
- `SystemShop/goods/*.yml` 仓库式商品定义
- `product` / `group` / `pool` 三种 SystemShop 资源模型
- `SystemShop` 定时刷新区域与后台刷新管理
- 统一命令入口、菜单框架和权限校验
- `SQLite` / `MySQL` 数据层与文件回退机制
- `bStats` 统计与部署分布遥测

## English Overview

MatrixShop is a modular commerce plugin for survival and economy servers. It provides a unified stack for shop browsing, listing, trading, checkout, and record tracking, with a Chinese-first default resource set and current documentation aligned to the `main` branch.

## 核心模块 / Core Modules

- `Economy`
- `SystemShop`
- `PlayerShop`
- `GlobalMarket`
- `Auction`
- `ChestShop`
- `Transaction`
- `Cart`
- `Record`
- `Menu`

## 兼容性测试

| 版本 | 兼容性 |
| --- | --- |
| `1.5.0` | `Paper 1.21.8` smoke boot 通过 |
| `1.5.0` | `Paper 1.21.11` smoke boot 通过 |

## SystemShop 重点

`SystemShop` 当前推荐把商品定义集中放在 `SystemShop/goods/*.yml`，再由 `SystemShop/shops/*.yml` 通过 `goods:` 引用。

支持的可复用资源：

- `product`
- `group`
- `pool`

默认示例池文件：

```text
SystemShop/goods/weapon_refresh_pool_example.yml
```

默认 `weapon` 分类中也已经带了可直接启用的刷新示例。

## 后台维护流程

当前推荐的管理员维护流：

- `/matrixshopadmin goods ui [page]`
- `/matrixshopadmin goods save <price> [buy-max] [product-id]`
- `/matrixshopadmin goods add <category> <product-id>`
- `/matrixshopadmin goods select <category> <product-id>`
- `/matrixshopadmin goods edit <price|buy-max|currency|name|item|remove> ...`
- `/matrixshopadmin refresh list [category]`
- `/matrixshopadmin refresh run <category> [icon]`

## 折扣系统

`SystemShop` 现在支持把折扣规则直接写在 `price` 键下：

```yaml
price:
  base: 420
  discounts:
    - id: vip
      percent: 10
      condition:
        - "perm 'group.vip'"
    - id: event
      amount-off: 20
    - id: night-surge
      surcharge: 8
```

支持能力：

- 兼容旧写法 `price: 420`
- 支持 `percent`、`amount-off`、`surcharge`
- 多条百分比折扣按相加计算
- 支持 Kether 条件判断
- 支持 `whitelist`、`blacklist` 控制折扣重叠
- 刷新池价格对象会和商品本体折扣规则合并

## 条件税系统

以下模块现在支持条件税配置：

- `PlayerShop`
- `GlobalMarket`
- `Auction`
- `Transaction`
- `ChestShop`

税规则当前支持：

- `Enabled`
- `Mode`
- `Value`
- `Priority`
- `Condition`

示例：

```yaml
Tax:
  Enabled: true
  Mode: percent
  Value: 3.0
  Rules:
    vip:
      Enabled: true
      Priority: 100
      Mode: percent
      Value: 1.0
      Condition:
        - "perm 'group.vip'"
```

## 构建与运行信息

- Build target: `Bukkit API 1.12.2`
- Required dependency: `MatrixLib 1.0.1`
- Supported economy backends: `Vault` / `PlayerPoints` / Placeholder-based custom currencies
- Database: `SQLite` / `MySQL`
- Optional sync layer: `Redis`

构建命令：

```bash
./gradlew build
```

运行产物：

```text
build/libs/MatrixShop-1.5.0-all.jar
```

## 文档入口

- [快速开始](https://54895y.github.io/docs/matrixshop/quick-start)
- [配置与系统](https://54895y.github.io/docs/matrixshop/configuration-structure)
- [模块总览](https://54895y.github.io/docs/matrixshop/modules-overview)
- [商店与模块详解](https://54895y.github.io/docs/matrixshop/shop-types)
- [更新日志](https://54895y.github.io/docs/matrixshop/release-notes)
- [bStats 与遥测](https://54895y.github.io/docs/matrixshop/bstats-and-telemetry)

## Search Keywords

- English: Minecraft shop plugin, GUI shop plugin, auction plugin, player market plugin, trade plugin, Vault economy plugin, Paper plugin, Folia plugin
- 中文: 商店插件, GUI 商店插件, 拍卖插件, 玩家市场插件, 交易插件, 经济插件, 中文服插件, Paper 插件, Folia 插件
