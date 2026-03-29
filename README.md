# MatrixShop

面向中文服务器的模块化 GUI 商业系统插件。

MatrixShop 将系统商店、玩家商店、全球市场、拍卖、箱子商店、面对面交易、购物车与交易记录整合到同一套菜单、命令和数据结构中。默认配置已汉化，适合作为中国服的商业系统基础。

## 功能特性

- 9 个模块统一接入：菜单、系统商店、玩家商店、全球市场、拍卖、箱子商店、交易、购物车、交易记录
- 命令路由清晰：`/ms` 仅显示帮助，独立模块使用各自根命令，例如 `/gm`、`/record`、`/trade`
- 默认 GUI 与玩家可见配置均为中文，开箱即可二次配置
- 支持 `SQLite` / `MySQL`，默认使用 `SQLite`
- 可选 `Redis` 通知与同步通道
- 对接 `Vault` 经济体系，兼容常见经济插件
- 所有模块都可以在 `module.yml` 中单独启用或停用

## 运行环境

| 项目 | 说明 |
| --- | --- |
| 服务端 | 推荐 `Paper` / `Folia` |
| 已验证版本 | `Folia 1.21.11` |
| 构建目标 | `Bukkit API 1.12.2` |
| 必需依赖 | [MatrixLib](https://github.com/54895y/MatrixLib) |
| 经济依赖 | `Vault`（涉及货币功能时必需） |
| 数据库 | `SQLite`（默认） / `MySQL` |
| Redis | 可选，用于通知与同步 |
| Java | 请遵循你的服务端核心要求；插件源码编译目标为 `Java 8` |

## 安装

1. 下载 `MatrixLib` 和 `MatrixShop`，将两个 JAR 一起放入 `plugins/`
2. 如需启用金钱购买、拍卖、市场或交易中的货币功能，再安装 `Vault` 和任意 Vault 兼容经济插件
3. 启动服务器一次，生成默认配置
4. 按需编辑 `config.yml`、`database.yml`、`module.yml` 与各模块目录下的配置
5. 使用 `/matrixshopadmin reload` 重载，或直接重启服务器

## 默认模块

| 模块 | 默认入口 | 说明 |
| --- | --- | --- |
| 菜单 | `/menu` | 统一入口菜单 |
| 系统商店 | `/ms open systemshop:<分类>` | 预设分类商店，默认含 `weapon`、`food`、`material` |
| 玩家商店 | `/playershop` | 玩家个人店铺与上架 |
| 全球市场 | `/gm` | 全服公共上架与购买 |
| 拍卖 | `/auction` | 支持英式与荷兰式拍卖 |
| 箱子商店 | `/chestshop` | 基于箱子与告示牌的实体商店 |
| 面对面交易 | `/trade` | 支持物品、金币、经验交易 |
| 购物车 | `/cart` | 跨模块购物车与统一结算 |
| 交易记录 | `/record` | 交易流水、明细与统计 |

## 常用命令

### 玩家命令

| 命令 | 说明 |
| --- | --- |
| `/matrixshop` `/shop` `/ms` | 显示帮助 |
| `/ms open <target>` | 按绑定目标打开商店或分类，例如 `/ms open systemshop:weapon` |
| `/menu` | 打开主菜单 |
| `/playershop open [player]` | 打开自己的或指定玩家的店铺 |
| `/playershop upload <price> [amount]` | 上架主手物品到玩家商店 |
| `/gm open` | 打开全球市场 |
| `/gm upload <price> [amount]` | 上架主手物品到全球市场 |
| `/auction open` | 打开拍卖大厅 |
| `/auction upload <english\|dutch> <start> [buyout\|end] [seconds]` | 创建拍卖 |
| `/trade request <player>` | 向玩家发起交易 |
| `/chestshop create <buy\|sell\|dual> <price> [sell-price] [amount]` | 创建箱子商店 |
| `/cart open` | 打开购物车 |
| `/record open [keyword]` | 打开交易记录 |

### 管理命令

| 命令 | 说明 |
| --- | --- |
| `/matrixshopadmin help` | 查看管理帮助 |
| `/matrixshopadmin reload` | 重载配置与模块 |
| `/matrixshopadmin sync` | 执行结构同步与旧数据导入 |
| `/matrixshopadmin status` | 查看数据库、经济与模块状态 |
| `/matrixshopadmin module list` | 查看模块开关状态 |
| `/matrixshopadmin module <enable\|disable\|toggle> <module>` | 切换单个模块 |

## 权限节点

| 权限 | 说明 |
| --- | --- |
| `matrixshop.admin` | 管理员总权限 |
| `matrixshop.menu.use` | 使用菜单 |
| `matrixshop.systemshop.use` | 使用系统商店 |
| `matrixshop.playershop.use` | 使用玩家商店 |
| `matrixshop.playershop.sell` | 玩家商店上架 |
| `matrixshop.playershop.manage.own` | 管理自己的玩家商店 |
| `matrixshop.globalmarket.use` | 使用全球市场 |
| `matrixshop.globalmarket.sell` | 全球市场上架 |
| `matrixshop.globalmarket.manage.own` | 管理自己在全球市场的商品 |
| `matrixshop.auction.use` | 使用拍卖 |
| `matrixshop.auction.sell` | 创建拍卖 |
| `matrixshop.auction.bid` | 参与竞价 |
| `matrixshop.auction.buyout` | 一口价购买 |
| `matrixshop.chestshop.use` | 使用箱子商店 |
| `matrixshop.chestshop.create` | 创建箱子商店 |
| `matrixshop.chestshop.manage.own` | 管理自己的箱子商店 |
| `matrixshop.transaction.use` | 使用面对面交易 |
| `matrixshop.cart.use` | 使用购物车 |
| `matrixshop.cart.checkout` | 购物车结算 |
| `matrixshop.cart.clear` | 清空或移除购物车条目 |
| `matrixshop.record.use` | 查看记录 |
| `matrixshop.record.detail.self` | 查看自己的记录详情 |
| `matrixshop.record.stats.self` | 查看自己的记录统计 |

## 配置目录

```text
plugins/MatrixShop/
├─ config.yml
├─ database.yml
├─ module.yml
├─ Lang/
├─ SystemShop/
├─ PlayerShop/
├─ GlobalMarket/
├─ Auction/
├─ ChestShop/
├─ Transaction/
├─ Cart/
├─ Record/
└─ Menu/
```

- `config.yml`：基础设置、默认语言、默认系统商店分类
- `database.yml`：`SQLite` / `MySQL` / `Redis` 配置
- `module.yml`：九个模块的总开关
- 各模块目录：商店视图、菜单布局、行为和文本配置

## 数据与依赖说明

- 默认数据文件位于 `plugins/MatrixShop/Data/data.db`
- 未接入 `Vault` 时，插件仍可加载，但涉及货币扣款与发款的功能无法正常完成
- 默认语言为 `zh_CN`，玩家可见配置和 GUI 已汉化
- 购物车默认可结算系统商店、玩家商店、全球市场条目，并可跟踪拍卖条目状态

## 从源码构建

```powershell
./gradlew.bat build
```

构建产物默认输出到 `build/libs/`。当前仓库依赖 `MatrixLib API 1.0.1`。

## 反馈与支持

- GitHub 仓库：[54895y/MatrixShop](https://github.com/54895y/MatrixShop)
- Issue 反馈：[Issues](https://github.com/54895y/MatrixShop/issues)
- 发布页：[Releases](https://github.com/54895y/MatrixShop/releases)

## 许可

本项目使用 `CC0 1.0`。
