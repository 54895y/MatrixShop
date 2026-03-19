# MatrixShop Development Status

这个文档用于每次对接时快速同步当前进度、边界和下一步任务。

## 使用约定

- 每次开发完成后都更新“本次完成”和“下一步任务”
- 只记录已经进入 `Code` 目录并实际提交的内容
- GitHub 只推送 `Code` 子树，不推送工作区里的其他文件

## 当前远端分支

- Branch: `codex/bootstrap-v1`
- Repo: `https://github.com/54895y/MatrixShop.git`

## 最近一次已发布进度

- Published subtree commit: `latest on codex/bootstrap-v1`
- Local commit: `latest local branch head`
- Summary:
  - `Record` 已切到 JDBC 优先 / 文件回退
  - `AuctionRepository` 已切到 JDBC 优先 / 文件回退
  - `AuctionDeliveryRepository` 已切到 JDBC 优先 / 文件回退
  - `GlobalMarketRepository` 已切到 JDBC 优先 / 文件回退
  - `PlayerShopRepository` 已切到 JDBC 优先 / 文件回退
  - `CartRepository` 已切到 JDBC 优先 / 文件回退
  - `ChestShopRepository` 已切到 JDBC 优先 / 文件回退
  - `ItemStackCodec` 已补上，用于数据库中的物品序列化
  - `StringMapCodec` 已补上，用于购物车元数据序列化

## 已完成模块状态

### 基础骨架

- 插件主类、模块注册、命令入口、GUI 基础框架已可用
- `SystemShop` 第一版已可运行
- 权限节点中心与管理员模块开关控制已落地

### 已实现业务模块

- `SystemShop`
- `PlayerShop`
- `GlobalMarket`
- `Cart`
- `Record`
- `Transaction`
- `Auction`
- `ChestShop`

### 数据层覆盖情况

- `Record`: JDBC 优先，文件回退
- `AuctionRepository`: JDBC 优先，文件回退
- `GlobalMarketRepository`: JDBC 优先，文件回退
- `PlayerShopRepository`: JDBC 优先，文件回退
- `CartRepository`: JDBC 优先，文件回退
- `ChestShopRepository`: JDBC 优先，文件回退
- `AuctionDeliveryRepository`: JDBC 优先，文件回退

## 本次完成

- 新增交接文档 `DEVELOPMENT_STATUS.md`
- 明确“只推送 Code 子树”的协作规则
- 将 `CartRepository` 迁移到 JDBC 优先 / 文件回退
- 将 `PlayerShopRepository` 迁移到 JDBC 优先 / 文件回退
- 补充 `StringMapCodec`，用于购物车元数据持久化
- 将 `AuctionDeliveryRepository` 迁移到 JDBC 优先 / 文件回退
- 将 `ChestShopRepository` 迁移到 JDBC 优先 / 文件回退

## 下一步任务

### 最高优先级

- 继续补充更多后台数据层状态输出
- 评估 `SystemShop` 是否需要保持纯静态配置，还是增加数据库层缓存/索引
- 为数据库表增加更明确的 schema/version 管理

### 第二优先级

- 评估并补齐 SQLite / MySQL 驱动打包策略
- 进入 Paper 实服联调，而不只是本地 `gradlew build`
- 为关键仓库迁移补最基本的回归测试

### 第三优先级

- 收口 `database.yml` 与实际后端能力的说明
- 视需要引入统一的事务/批量写入工具

## 对接注意事项

- 当前数据层设计是“JDBC 可用就使用数据库，不可用就自动回退到旧文件”
- 这样做的目的是先迁业务，不中断现有可运行性
- 目前还没有做真实服务器联调，所以所有结论都只基于编译通过和本地逻辑检查
