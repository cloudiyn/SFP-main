# 星落平原 SFP-main

> 「星落平原」生存服整合插件 —— 一个统一入口的 **Paper 原生插件**，把菜单、传送、自动扫地、垃圾桶收进一套配置里。
>
> Paper 26.1+ / Java 25 · 全功能可开关 · 文案全可改 · 传送数据 SQLite 持久化

---

## 目录

- [简介](#简介)
- [功能一览](#功能一览)
- [环境要求](#环境要求)
- [安装](#安装)
- [命令与权限](#命令与权限)
- [玩家用法](#玩家用法)
- [配置文件](#配置文件)
- [PlaceholderAPI 占位符](#placeholderapi-占位符)
- [数据存储](#数据存储)
- [从源码构建](#从源码构建)
- [注意事项](#注意事项)
- [许可证](#许可证)

---

## 简介

星落平原是一个为 Minecraft Java 版生存服编写的整合型插件，目标是**用一套配置管住服务器最常用的几件小事**：

- 一个统一主菜单 `/menu`，把服务器功能收进一个 GUI，玩家不用记命令；
- 一套自研传送系统 `/back` `/home` `/warp`，数据存 SQLite，重启不丢；
- 定时自动清扫地面掉落物，清扫物默认进垃圾桶可随时取回（`to-trashbin` 关闭则直接销毁），不再担心误清。

设计上有几条贯穿全项目的原则：

| 原则 | 说明 |
|------|------|
| **每个功能独立开关** | 任一模块 `enabled: false` 后不再实例化、不注册监听器，菜单入口静默隐藏 |
| **一切可调** | 开关、参数、图标、槽位、面向玩家的每一句文案，都在 yml 里，改文案无需改代码 |
| **零硬依赖** | Dominion 与 PlaceholderAPI 缺失时自动降级，插件照常启动 |
| **Paper 原生** | 描述文件为 `paper-plugin.yml`，命令走 `BasicCommand` + `LifecycleEvents.COMMANDS` |

---

## 功能一览

| 模块 | 说明 |
|------|------|
| **主菜单** | 5 行居中布局，边框 + 玩家头 + 功能按钮；按钮的开关 / 槽位 / 图标 / 名称 / 命令全在 `menu.yml` |
| **菜单钟** | 把一个泥土放进合成格即可合成的「钟」（带附魔光效），手持右键直接打开菜单，无需记命令 |
| **界面样式** | 主菜单 / 列表支持 Paper 弹窗（dialogUI）与箱子两种样式，玩家偏好存 `data/settings.db`，`/menuui` 切换 |
| **返回上一位置 `/back`** | 回到上次传送前的位置或死亡点，死亡捡尸不用跑图 |
| **个人家 `/home`** | 默认最多 5 个家，GUI 列表左键传送、右键删除，设置与传送各有冷却 |
| **公共传送点 `/warp`** | 管理员创建的全服传送点，GUI 列表同样支持左键传送 / 右键删除 |
| **玩家传送 `/tpa`** | 玩家之间互相传送，回应界面可在弹窗与聊天按钮间切换（`/tpaui`） |
| **随机传送 `/rtp`** | 在当前世界 ±50000 范围内随机找一处安全落点；默认仅主世界可用，冷却 10 分钟（全部可配置） |
| **延迟传送** | `delay-seconds > 0` 时先等待再传送，等待期间移动（跨越方块）即打断 |
| **自动扫地** | 默认 15 分钟一轮，清扫前 60/30/10 秒广播提醒，支持世界黑白名单；清扫物默认收入垃圾桶（`clean.yml` 的 `to-trashbin` 可关，关后直接销毁） |
| **垃圾桶** | 清扫物默认进垃圾桶，可点回背包；容量上限 486，超出丢弃最旧 |
| **连锁挖矿** | 蹲下挖矿时连锁同种矿脉（上限 32 块，按方块量扣饱食度；假人不参与） |
| **假人 `/sfp player`** | 创建真玩家实体：保区块 + 可驱动（攻击/挖掘/使用/移动/钓鱼/背包/传送，需 `sfpmenu.bot`）；右键假人可打开**可编辑物品栏**（主手/副手实时可改） |
| **Tab / 计分板** | Tab 头部底部与右侧计分板，内容与刷新频率全在 `display.yml` 的 `tab` / `scoreboard` 两段 |
| **处罚系统** | `/sfpwarn` `/sfpkick` `/sfpban` `/sfpmute` + `/sfpcheck` `/sfpunban` `/sfpunmute`，可按权限分级（默认仅 OP）；数据存 `data/punish.db` |
| **领地入口** | 菜单按钮直接执行 Dominion 的 `/dom`，领地管理交给专业插件 |

> **2.16.0 假人更新**：右键假人打开可编辑的物品栏界面（主手 / 副手实时修改，主手格映射当前快捷栏槽；支持左右键、Shift、数字键与 F，拖拽 / 双击 / 创造克隆 / 窗口外丢弃会被拒绝）；`use` 动作改为先主手再副手，物品耗尽时按原类型从背包 9~35 格补货；`bot.yml` 新增 `pickup-whitelist`（留空 = 全部可拾取）。

---

## 环境要求

| 项目 | 要求 |
|------|------|
| 服务端 | **Paper 26.1 或更高**（编译目标 26.3，兼容 26.1 / 26.2 / 26.3）；Paper 系如 Purpur 亦可 |
| Java | 25（编译与运行） |
| 可选依赖 | [Dominion](https://github.com/ColdeZhang/Dominion)（领地）、[PlaceholderAPI](https://github.com/PlaceholderAPI/PlaceholderAPI)（占位符） |
| 内置依赖 | `org.xerial:sqlite-jdbc`，已 shade 进 jar，**无需另外安装数据库** |

> ⚠️ 这是 **Paper 插件**（`paper-plugin.yml`，不是 `plugin.yml`）。**Spigot / CraftBukkit 无法加载**；使用了 Bukkit 调度器与 `PlayerMoveEvent`，**暂不兼容 Folia**。

依赖缺失时的行为：

- **Dominion 未安装** → 领地按钮仍显示，但点击无实际效果；
- **PlaceholderAPI 未安装** → 占位符注册整体跳过，记一条 warning，其余功能不受影响。

---

## 安装

1. 从 Releases 下载 `sfp-main-<版本>.jar`；
2. 丢进服务端的 `plugins/` 目录；
3. 启动服务器一次，插件会在 `plugins/SFP-main/` 生成全部配置文件，数据文件则生成在 `plugins/SFP-main/data/`；
4. 按需要改配置（见 [配置文件](#配置文件)），**开关类改动需重启服务器**，参数类改动 `/sfp reload` 即可；
5. `/menu` 打开菜单验货。

---

## 命令与权限

### 命令

| 命令 | 别名 | 说明 |
|------|------|------|
| `/menu` | `/m` | 打开主菜单 |
| `/menuui` | — | 切换界面样式（dialogUI 弹窗 / 箱子） |
| `/back` | — | 返回上一位置（上次传送前 / 死亡点） |
| `/rtp` | — | 随机传送（当前世界 ±50000 内随机找安全落点，默认仅主世界、冷却 10 分钟） |
| `/home` | — | 打开家列表 |
| `/home <名称>` | — | 传送到指定家 |
| `/sethome [名称]` | — | 设置家，省略名称时默认 `home` |
| `/delhome <名称>` | — | 删除家 |
| `/homes` | — | 聊天栏列出所有家 |
| `/warp` | — | 打开公共传送点列表 |
| `/warp <名称>` | — | 传送到指定传送点 |
| `/setwarp <名称>` | — | 创建传送点（需 `sfpmenu.warp.set`） |
| `/delwarp <名称>` | — | 删除传送点（需 `sfpmenu.warp.delete`） |
| `/warps` | — | 聊天栏列出所有传送点 |
| `/tpa <玩家名>` | — | 请求**传送到对方身边** |
| `/tpahere <玩家名>` | — | 请求**对方传送到你身边** |
| `/tpaccept [玩家名]` | — | 接受收到的传送请求 |
| `/tpdeny [玩家名]` | — | 拒绝收到的传送请求 |
| `/tpaui` | — | 切换传送请求的回应界面（弹窗 ↔ 聊天按钮） |
| `/trashbin` | — | 打开垃圾桶，取回被清扫的物品 |
| `/sfp reload` | — | 重载配置（`sfpmenu.admin`；开关类改动需重启） |
| `/sfp status` | — | 运行状态与各模块实时数据（`sfpmenu.admin`） |
| `/sfp db [子命令]` | — | 数据库概览，子命令 `tables` / `homes` / `warps` / `back` / `check` / `checkpoint`（`sfpmenu.admin`） |
| `/sfp test` | — | 全量自检并输出报告（`sfpmenu.admin`） |
| `/sfp sweep on|off` | — | 开启 / 关闭自动扫地（`sfpmenu.clean.manage`） |
| `/sfp sweep except add|remove <世界> <区块X> <区块Z>` | — | 维护扫地例外区块（`sfpmenu.clean.manage`） |
| `/sfp player help` | `/sfp fp …` | 假人管理：输入 help 查看全部 37 个子命令（`sfpmenu.bot`，`killall` 另需 `sfpmenu.bot.killall`） |
| `/sfpcheck <处罚ID 或 玩家名>` | — | 查询处罚记录（`sfpmenu.punish.check`） |
| `/sfpwarn <在线玩家> <原因>` | — | 警告在线玩家（`sfpmenu.punish.warn`） |
| `/sfpkick <在线玩家> <原因>` | — | 踢出在线玩家（`sfpmenu.punish.kick`） |
| `/sfpban <玩家> <时间> <原因>` | — | 封禁，可对离线玩家（`sfpmenu.punish.ban`） |
| `/sfpmute <玩家> <时间> <原因>` | — | 禁言，可对离线玩家（`sfpmenu.punish.mute`） |
| `/sfpunban <玩家名>` | — | 解除封禁，目标取自数据库（`sfpmenu.punish.unban`） |
| `/sfpunmute <玩家名>` | — | 解除禁言，目标取自数据库（`sfpmenu.punish.unmute`） |

> 处罚命令的时间写法：`7d` / `12h` / `1d2h30m` / `90s` / `perm`（永久），单位 `w`周 `d`天 `h`时 `m`分 `s`秒，可组合、可乱序。

### 权限节点

| 权限 | 默认 | 说明 |
|------|------|------|
| `sfpmenu.player` | 所有人 | 打开菜单、`/trashbin` |
| `sfpmenu.teleport` | 所有人 | `/back` `/rtp` `/home` `/warp` `/tpa` 等全部传送命令 |
| `sfpmenu.home.bypass-limit` | 无 | 绕过家数量上限 |
| `sfpmenu.warp.set` | OP | `/setwarp` |
| `sfpmenu.warp.delete` | OP | `/delwarp` |
| `sfpmenu.admin` | OP | `/sfp`（reload / status / db / test） |
| `sfpmenu.clean.manage` | OP | `/sfp sweep`（自动扫地开关与例外区块） |
| `sfpmenu.bot` | OP | `/sfp player` / `/sfp fp`（假人全部子命令） |
| `sfpmenu.bot.killall` | OP | `/sfp player killall`（一次移除全部假人） |
| `sfpmenu.punish.check` | OP | `/sfpcheck` 查询处罚记录 |
| `sfpmenu.punish.warn` | OP | `/sfpwarn` 警告 |
| `sfpmenu.punish.kick` | OP | `/sfpkick` 踢出 |
| `sfpmenu.punish.ban` | OP | `/sfpban` 封禁 |
| `sfpmenu.punish.mute` | OP | `/sfpmute` 禁言 |
| `sfpmenu.punish.unban` | OP | `/sfpunban` 解除封禁 |
| `sfpmenu.punish.unmute` | OP | `/sfpunmute` 解除禁言 |

> 共 **16 个**权限节点，全部在 `src/main/resources/paper-plugin.yml` 声明。权限检查写在各命令的 `execute` 内而非 `BasicCommand#permission()`，这样无权限的玩家会收到「你没有权限（需要 <节点>）」提示，而不是命令直接“不存在”。代价是补全列表里仍能看到命令。

---

## 玩家用法

面向玩家的完整说明见 [`玩家说明.md`](玩家说明.md)。几个要点：

- 家名只能使用字母、数字、下划线，长度 1~16；
- 传送 GUI 列表中，**左键传送、右键删除**；
- 被清扫的物品**默认收入垃圾桶**（`clean.yml` 的 `to-trashbin` 改为 `false` 后直接销毁、无法取回），垃圾桶满 486 件后才丢弃最旧的。

---

## 配置文件

配置按功能包拆分，全部位于 `plugins/SFP-main/`：

| 文件 | 对应功能 | 关键项 |
|------|---------|--------|
| `config.yml` | 全局 | `mode`（`full` 完整 / `punish-only` 仅处罚）、`debug` 调试日志 |
| `menu.yml` | 主菜单 | 标题、行数、边框材质、**每个按钮的 `enabled` / `slot` / `material` / `name` / `lore` / `command`**、菜单钟（`menu-clock`：材质 / 名称 / 光效 / 触发方式 / 合成材料） |
| `messages.yml` | 全部文案 | 所有面向玩家的提示语，支持 MiniMessage 与 `&a` 传统色码 |
| `clean.yml` | 自动扫地 | `enabled`、清扫周期、提醒时间点、世界黑白名单、`to-trashbin`（默认 `true` 收入垃圾桶，`false` 直接销毁） |
| `trashbin.yml` | 垃圾桶 | `enabled`、容量上限、音效 |
| `teleport.yml` | 传送 | 总开关、`back` / `home` / `warp` / `rtp` 各自开关、家数量上限、冷却、安全落点、延迟传送、数据库文件名 |
| `chain.yml` | 连锁挖矿 | `enabled`、触发条件、连锁上限、饱食度费率、收录方块 |
| `bot.yml` | 假人 | `enabled`、数量上限、动作间隔与攻击范围、新建假人的默认设置 |
| `display.yml` | 信息显示 | 顶层 `tab` / `scoreboard` 两段，各含 `enabled`、`refresh-ticks`、头部/底部或标题/行内容（支持内置占位符与 PAPI） |
| `punish.yml` | 处罚系统 | 处罚文案（踢下线整屏 / 禁言提示 / 全服广播）、数据库文件名 |

几条约定：

- **旧配置自动合并**：旧版的 `tab.yml` / `scoreboard.yml` 会在首次启动时合并进 `display.yml` 并删除旧文件，无需手工迁移；
- **工作模式**：`config.yml` 的 `mode: full | punish-only`。`punish-only` 专为群组子服设计 —— 只装配处罚系统与 `/sfp`，不装配菜单 / 传送 / 扫地 / 垃圾桶 / 假人 / 显示，也不注册它们的命令；**模式改动需重启生效**；
- **关闭某个功能**：对应文件 `enabled: false`。该模块不加载、不注册事件，菜单入口同步消失；
- **改配置后生效**：参数类 `/sfp reload` 即时生效；**开关类改动必须重启服务器**（监听器注册不可逆）；
- **加 / 改菜单按钮**：只编辑 `menu.yml` 的 `buttons` 段，`command` 留空即执行插件内部动作，填 `cmd:xxx` 则以玩家身份执行该命令（领地按钮就是 `cmd:dom`）；
- **占位符**：文案中使用花括号 `{key}`，不要写 `%key%`（会与 PlaceholderAPI 语法冲突）。

---

## PlaceholderAPI 占位符

需安装 PlaceholderAPI：

| 占位符 | 说明 |
|--------|------|
| `%stf_cleantime%` | 彩色倒计时：剩余 >3 分钟绿色，1~3 分钟黄色，1 分钟以内红色 |
| `%stf_cleantime_plain%` | 纯文本倒计时 |

格式形如 `x分x秒`，不足 1 分钟时只显示 `x秒`。颜色阈值可在 `clean.yml` 的 `placeholder` 段调整。

---

## 数据存储

所有数据文件统一放在插件目录的 **`data/`** 子目录（`plugins/SFP-main/data/`）：

| 文件 | 内容 |
|------|------|
| `teleport.db` | 传送数据（SQLite） |
| `punish.db` | 处罚记录（SQLite） |
| `settings.db` | 玩家界面偏好（dialogUI / 箱子、tpa 回应形式） |
| `sweep.db` | 自动扫地的区块例外 |
| `trashbin-data.yml` | 垃圾桶内容 |
| `bots.yml` | 假人记录（设置与持续动作） |

传送数据存于 **`data/teleport.db`**（SQLite），三张表：

| 表 | 内容 |
|----|------|
| `homes` | 玩家个人家 |
| `warps` | 公共传送点 |
| `last_locations` | `/back` 返回点 |

世界按名存储；目标世界不存在时会给出提示而不是报错。**删除 `teleport.db` 即清空所有传送数据。**

**旧版迁移**：旧版把数据文件直接放在插件根目录（如 `plugins/SFP-main/teleport.db`），首次启动会自动迁入 `data/`；若新旧两处同时存在同名主库，插件会**拒绝启动**并在日志打 `severe`，避免误选旧库导致数据丢失。

**备份**：正常停服后直接拷整个 `plugins/SFP-main/data/` 目录（配置文件另拷 `plugins/SFP-main/` 下的各 yml）。开服状态下想单拷传送库，先执行 `/sfp db checkpoint` 把 WAL 并回 `data/teleport.db`，再连同 `-wal` / `-shm` 一起拷（或干脆整目录拷）。数据库路径语义：**纯文件名**（如 `teleport.db`）→ 放 `data/` 下；**带目录的相对路径**保持原样（不再额外加 `data/`）；**绝对路径**原样使用（可用于跨服共享数据库）。

---

## 从源码构建

需要 JDK 25 与 Maven：

```bash
mvn clean package
```

产物在 `target/sfp-main-<版本>.jar`（约 14 MB，含 shade 进来的 SQLite 驱动）。

构建要点（改动 `pom.xml` 时留意）：

- `paper-api` 使用**显式版本号**而非版本范围，离线构建才能稳定复现；
- shade 插件需 **≥ 3.6.0** 并强制 `asm` / `asm-commons` **9.8+**，否则会报 `Unsupported class file major version 69`（Java 25 的 class 版本）；
- **不要重定位 `org.sqlite`**：SQLite 的原生加载器内部用字符串类名 `org.sqlite.core.NativeDB`，shade 不重写字符串，重定位后加载原生库会 `NoClassDefFoundError`。Paper 每插件独立类加载器，不重定位也不会冲突。

---

## 注意事项

- 本插件**只能跑在 Paper 系服务端**（Paper / Purpur 等），Spigot 与 CraftBukkit 无法加载；
- **不支持 Folia**；
- 开关类配置改动必须重启服务器，`/sfp reload` 只重载参数；
- 传送模块被关闭时，`/back` `/home` `/warp` 等命令仍会注册，但统一回复「该功能当前未启用」，避免玩家以为命令不存在；
- 跨世界 `/back` 记录统一走 `PlayerTeleportEvent` 判定，不依赖 `PlayerChangedWorldEvent`（后者拿不到旧坐标）；
- 当前版本 **2.16.1**：2.16.1 改进命令提示（未知子命令先回显输入并给出相似建议、缺参给该子命令的完整用法、无权限提示带权限节点、新增 `/sfp player help`），并修复假人攻击（不再跳过盔甲架，同一动作周期连打两次以对应原版 5 tick 内两击；攻击前按脚下方块校正落地状态，主手是剑时恢复原版横扫）。功能已可用，但仍有若干交互（tpa 弹窗、假人部分子命令等）只在测试服验证过，生产环境请先在测试服过一遍（26.3 官方目前只有 beta 构建）。

---

## 许可证

仓库尚未指定开源许可证。若你打算允许他人自由使用/二次分发，建议补一个 `LICENSE`（MIT 是 MC 插件的常见选择）；在此之前默认视为「仅允许查看源码」。
