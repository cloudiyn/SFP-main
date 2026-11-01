# 星落平原（SFP-main）功能明细

> 本文档记录**每个功能的实现细节**：入口 → 调用链 → 流程 → 数据与配置。
>
> **约定：新增功能必须同步写进本文档。** 至少补上这四项：
> ① 入口（命令 / 菜单按钮 / 事件）② 调用链（哪个类调哪个类）③ 流程与边界情况 ④ 涉及的命令、
> 权限、配置文件与数据文件。若只改了已有功能，把该章节对应部分一并更新。
> 文末的「新增功能检查清单」是要照着走一遍的。

- 插件形态：Paper 插件（`paper-plugin.yml`），Java 25，编译目标 paper-api 26.3
- 插件名（`name`）：`SFP-main` —— 决定日志前缀与数据目录（`plugins/SFP-main/`）
- 主类：`cn.starfallplain.sfpmain.SfpMain`（包名 `cn.starfallplain.sfpmain`、主类 `SfpMain`）
- 元信息文件：`src/main/resources/paper-plugin.yml`（`version` 由 `${version}` 过滤）
- 当前版本：**2.16.1**（2.16.1 修复假人攻击：根因是假人不被 tick 驱动、`attackStrengthTicker` 冻结在 0.1，用 `chargeAttack` 顶满充能并自计时攻击间隔（原 `getAttackCooldown() >= 0.95` 门槛导致一次都打不出）；不再跳过盔甲架（同一动作周期连打两次，对应原版 5 tick 内两下才碎）、攻击前按脚下方块校正 NMS `onGround`，让主手是剑时恢复原版横扫；同时优化命令提示：未知子命令先回显输入并给相似建议、缺参/非法参数给完整用法、无权限提示带权限节点、新增 `/sfp player help`、修正补全，详见 [§0.6](#06-命令提示与补全)；2.16.0 右键假人背包改成可编辑的物品栏界面（主手/副手实时可改）；`use` 动作按主手→副手依次尝试，物品耗尽时按手持物品类型从背包补货；新增拾取白名单 `pickup-whitelist`，空列表维持全部拾取；2.15.1 修复假人启用 `pickup_items` 后不拾取掉落物：动作循环扫描周围掉落物并调用原版拾取流程；2.15.0 新增 `/sfp sweep on/off` 开关及 SQLite 持久化区块例外；2.13.0 新增连锁挖矿：蹲下挖矿时按同种矿脉 BFS 连锁（上限 32 块），每块扣 0.1 饱食度，逐块走原版 `BlockBreakEvent` 以兼容领地保护，假人不参与连锁；2.12.0 把假人重做成 minecraft-fakeplayer 的效果：命令改为 `/sfp player`（简写 `/sfp fp`），新增选择 / 背包 / 设置 / 肤色 / 传送 / 经验 / 以假人身份执行命令 / 丢物品 / 交换手 / 快捷栏，以及**持续动作** attack·mine·use·move 与一次性动作 jump·sneak·sprint·look·turn·ride·sleep·wakeup·stop，右键假人开背包，个人默认配置与 PAPI 占位符；2.11.0 是一轮「自检修复」：传送粒子间隔 `particle-interval-ticks` 与延迟传送预加载真正生效、`/home` `/warp` 无参按界面偏好分流、处罚音效恢复播放、主菜单按钮上移一行、dialogUI 补齐说明按钮与全部文案外置、代码里的中文全部搬进 messages.yml，并删掉 `clean.skip-named-items` 与 `punish.messages.join-warn-notice` 两个死键；2.10.2 修复鞘翅兑换提示把 MiniMessage 标签当字面量打印的问题，价格改为 64 钻石或 8 下界残骸二选一；2.10.1 修复跨服被封禁玩家卡在「加载地形中」；2.10.0 主菜单新增「鞘翅兑换」按钮；2.9.1 新增「仅处罚」工作模式；2.9.0 菜单新增「随机传送」按钮；2.8.0 处罚数据库位置可配置；2.7.0 新增处罚系统）

---

## 0. 全局

### 0.0 工作模式（config.yml 的 `mode`）

| 值 | 说明 |
|---|---|
| `full` | 完整模式（默认）：装配全部功能模块 |
| `punish-only` | **仅处罚模式**：只启动处罚系统 + `/sfp` 管理命令 |

`punish-only` 专为**群组服**设计 —— 子服只负责执行处罚命令，不需要重复跑菜单与传送。

该模式下**不装配**的模块：菜单 / 菜单钟 / 自动扫地 / 垃圾桶 / 传送（含 tpa / rtp）/
假人 / Tab / 计分板 / 界面偏好库。具体表现为：

1. `SfpMain#onEnable` **跳过**上面对应的 `setupXxx()` 调用（不建数据库、不注册监听器、
   不生成假人实体），只留 `setupPunish()`；
2. `SfpMain#registerCommands` 只注册 7 个处罚命令 + `/sfp`，**其余命令完全不注册**
   （连「未启用」桩也不给，避免群组服玩家看到一堆无用命令）；
3. `setupPlaceholders()` 直接跳过（本插件此时没有占位符要注册）；
4. `ConfigManager#describeState` 只报处罚相关项（列出未装配的模块只会误导运维）；
5. `SelfTest#run` 只跑「配置 + 处罚」两段检查，跳过菜单 / 传送 / 模块检查；
6. `/sfp db` 改为展示**处罚库**概览（`DbDebug#punishOverview`）—— 此时传送库根本没建，
   展示它只会得到一句「未启用」。

`mode` 值无法识别时按 `full` 处理并记一条 warning。**模式改动需重启服务器生效**
（`/sfp reload` 只重载参数，不改变装配结果）。

> 群组服共用同一个处罚库：把各子服 `punish.yml` 的 `database.file` 指向同一绝对路径
> （如挂载盘位置），详见 §9.3。

### 0.1 启动流程（`SfpMain#onEnable`）

```
1) configManager = new ConfigManager(this)
     └─ 读 config.yml（GlobalConfig，走 Bukkit 的 getConfig）+ messages.yml（Messages）
     └─ loadAll()：依次 new 出 Menu/Clean/TrashBin/Teleport/Bot 五个 Config
        （每个 Config 构造时就 load() → onLoaded()，把 yml 的值缓存成字段）
2) 按开关装配模块（顺序有意义）：
     ★ punish-only 模式下跳过以下全部 setup，只留 setupPunish()
     setupTrashBin()     垃圾桶必须先于扫地（扫地要把物品塞进垃圾桶）
     setupFloorClean()   扫地：new FloorCleanManager（内部 runTaskTimer 每秒 tick）
     setupChain()        连锁挖矿：new ChainManager + 注册 ChainListener（只有监听器，无定时任务）
     setupTeleport()     传送：new TeleportManager（内含 SQLite）→ new TpaManager
                         → 注册 TeleportListener / TeleportGuiListener / TpaListener
     setupBot()          假人：new BotManager（读 bots.yml）→ startActions()（4 tick 动作任务 +
                         1 tick 移动任务）→ 注册 BotListener → runTask 延后一 tick 执行 restoreOnStart()
     setupMenu()         菜单：注册 MenuListener（菜单本身是懒构建的，无状态）
     setupMenuClock()    菜单钟：注册合成配方 + MenuClockListener + MenuClockJoinListener（依赖主菜单已启用）
     setupPunish()       处罚：new PunishManager（内含独立 SQLite punish.db）→ 注册 PunishListener
3) registerCommands()   注册全部命令（依赖上面各 manager 已就位，故必须放最后）
4) setupPlaceholders()  PAPI 可用时注册 %stf_cleantime% / %stf_cleantime_plain%
5) 打印「功能状态」汇总（ConfigManager#describeState）
```

`onDisable`：`trashBinManager.save()` → `teleportManager.shutdown()`（关 DB）→
`tpaManager.shutdown()`（取消过期任务）→ `botManager.saveData() + shutdown()` →
`menuClockManager.unregisterRecipe()`（注销菜单钟配方）→ `punishManager.shutdown()`（关处罚 DB）。

### 0.2 配置体系

| 文件 | 对应类 | 说明 |
|---|---|---|
| `config.yml` | `GlobalConfig` | 全局：`mode`（工作模式）、`debug`。**不继承 AbstractConfig**，直接走 `plugin.getConfig()` |
| `menu.yml` | `MenuConfig` | 主菜单标题/尺寸/边框/按钮 + 菜单钟（`menu-clock`）+ 鞘翅兑换（`elytra-exchange`） |
| `clean.yml` | `CleanConfig` | 自动扫地 |
| `chain.yml` | `ChainConfig` | 连锁挖矿（触发条件 / 连锁上限 / 饱食度费率 / 收录方块） |
| `trashbin.yml` | `TrashBinConfig` | 垃圾桶 |
| `teleport.yml` | `TeleportConfig` | 传送（back/home/warp/tpa/rtp + 数据库） |
| `bot.yml` | `BotConfig` | 假人 |
| `display.yml` | `DisplayConfig` | 信息显示：`tab`（列表头部/底部）+ `scoreboard`（标题/行）两段合并（display 包） |
| `punish.yml` | `PunishConfig` | 处罚系统（封禁/禁言/踢出/警告 + 数据库 + 全部处罚文案） |
| `messages.yml` | `Messages` | 所有面向玩家的文案（**不继承 AbstractConfig**） |

规则：
- 每个功能包一份配置，**包内一切可调项都从该文件读**，读取逻辑下沉到包内 `XxxConfig`。
- 键既可写全路径（`teleport.delay-seconds`）也可写短路径（`delay-seconds`），
  `AbstractConfig#normalize()` 负责兼容。
- 取值顺序：配置文件 → 代码内兜底默认值。所以**删掉某个键不会报错，只是回退到默认值**。
- 占位符统一用花括号 `{key}`（不要用 `%key%`，会与 PAPI 冲突）。
- 文本渲染优先 MiniMessage；`Messages.convertLegacy()` 兼容传统色码 `&a` / `§a`。
- **发玩家文案一律用 `Messages#send(sender, key, fallback, placeholders)`**
  （内部即 `deserialize(apply(...))`）。⚠ **不要把 `Messages.apply(...)` 的返回值（`String`）
  直接丢给 `Player#sendMessage(String)`** —— Paper 的 `sendMessage(String)` **不解析 MiniMessage**，
  只认传统 `§` 色码，结果 `<green>…</green>` 会被当字面量原样打印（2.10.2 修的就是这个）。

**启动时会自动补齐缺失键**：`AbstractConfig#mergeDefaults()` 用 jar 内同名 yml 做 defaults，
`copyDefaults(true)` 后立刻 `save()`。注意它**只补缺失键、不覆盖已存在的键** ——
所以改了 `resources/*.yml` 里**已存在**键的默认值后，服务器上的老配置文件**不会更新**，
必须手工同步（详见 §9）。

### 0.3 命令注册机制

Paper 插件不支持 `plugin.yml` 的 `commands` 段，全部命令在
`SfpMain#registerCommands()` 里通过
`getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, ...)` 注册。

- 所有命令实现 `io.papermc.paper.command.brigadier.BasicCommand`。
- **`BasicCommand#execute` 拿不到命令标签**，所以 `/home /sethome /delhome /homes`、
  `/warp /setwarp /delwarp /warps`、`/tpa /tpahere /tpaccept /tpdeny`
  都是「一个标签注册一个实例，用构造参数 `action` 区分行为」。
- **刻意不实现 `BasicCommand#permission()`**：那样无权限者眼里命令直接「不存在」、给不出提示；
  改为各 `execute` 内手动 `hasPermission` 并回 `common.no-permission`。
  代价是无权限的人在补全里仍能看到命令。
- 模块被关闭时同名命令**仍然注册**，统一回「该功能当前未启用」，避免玩家以为命令不存在。

已注册命令：`menu`(别名 `m`)、`trashbin`、`back`、`rtp`、`home`、`sethome`、`delhome`、`homes`、
`warp`、`setwarp`、`delwarp`、`warps`、`tpa`、`tpahere`、`tpaccept`、`tpdeny`、`tpaui`、
`menuui`、`sfp`（假人命令是它的子命令 `/sfp player` / `/sfp fp`，见第 6 节）、
`sfpcheck`、`sfpwarn`、`sfpkick`、`sfpban`、`sfpmute`、`sfpunban`、`sfpunmute`。

### 0.4 权限节点（`paper-plugin.yml` 的 `permissions` 段）

| 节点 | 默认 | 用于 |
|---|---|---|
| `sfpmenu.player` | true | 打开菜单、`/trashbin` |
| `sfpmenu.teleport` | true | `/back` `/rtp` `/home` `/warp` `/tpa` 等全部传送命令 |
| `sfpmenu.home.bypass-limit` | false | 绕过家数量上限 |
| `sfpmenu.warp.set` | op | `/setwarp`（也可在 teleport.yml 改空=人人可建） |
| `sfpmenu.warp.delete` | op | `/delwarp` |
| `sfpmenu.admin` | op | `/sfp`（reload / status / db / test） |
| `sfpmenu.clean.manage` | op | `/sfp sweep`（开关与例外区块） |
| `sfpmenu.bot` | op | `/sfp player` / `/sfp fp`（假人全部子命令） |
| `sfpmenu.bot.killall` | op | `/sfp player killall`（一次移除全部假人） |
| `sfpmenu.punish.check` | op | `/sfpcheck` 查询处罚记录 |
| `sfpmenu.punish.warn` | op | `/sfpwarn` 警告 |
| `sfpmenu.punish.kick` | op | `/sfpkick` 踢出 |
| `sfpmenu.punish.ban` | op | `/sfpban` 封禁 |
| `sfpmenu.punish.mute` | op | `/sfpmute` 禁言 |
| `sfpmenu.punish.unban` | op | `/sfpunban` 解封 |
| `sfpmenu.punish.unmute` | op | `/sfpunmute` 解禁 |

### 0.5 调试与自检（`debug/` 包）

| 命令 | 实现 | 作用 |
|---|---|---|
| `/sfp reload` | `SfpCommand` → `plugin.reloadAll()` | 重载配置。**开关类改动仍需重启**（监听器注册不可逆） |
| `/sfp status` | `SfpCommand` + `SelfTest#printRuntime` | 模块开关 + 各模块实时数据 |
| `/sfp db [...]` | `DbDebug` | 概览 / `tables` / `homes` / `warps` / `back` / `check` / `checkpoint` |
| `/sfp test` | `SelfTest#run` | 七段自检：配置、菜单、传送数据层、传送内容、假人记录、处罚系统、功能模块 |

- 输出文案**写死在 `debug` 包内**（诊断条目多且高度动态，不进 messages.yml），门槛 `sfpmenu.admin`。
- `/sfp db` 全是只读诊断，**刻意不提供执行任意 SQL**（前缀白名单挡不住 `SELECT 1; DROP TABLE x`
  这类多语句）。`checkpoint` 是唯一的维护动作（把 WAL 合并回主库，便于直接拷贝 .db）。
- 自检里唯一会写数据的是数据层「增删改查回归」：用哨兵 UUID + 哨兵名称，
  `finally` 里清理，不碰真实玩家数据。
- 动作解析共用 `MenuManager#resolveSlotActions / resolveAction`，
  所以**自检报出的按钮绑定就是玩家实际点击的结果**。
- 菜单段的 lore 漂移体检：`checkElytraExchange`（鞘翅价格）+ `checkLoreDrift`
  （随机传送冷却）—— 见 §1.0 末尾的说明。

**2.11.0 补齐的自检项**（此前这些「只能靠真人踩一次」）：

| 检查 | 说明 |
|---|---|
| 菜单槽位冲突 | 两个可见按钮抢同一格时 `slotActions` 是 Map，**后一个会静默覆盖前一个** → 报 [错误]；按钮占用 `player-head.slot` 会盖掉顶栏头像 → 报 [注意] |
| 新增文案键缺失 | 与「关键键」分开查：`mergeDefaults()` 不覆盖老 `messages.yml`，新键（`RECENTLY_ADDED_MESSAGE_KEYS`，目前是 2.13.0 的 `chain.*`，2.12.0 的整批 `bot.*` 假人文案，以及 2.11.0 的 `dialogui.*` / `punish.*`）缺失时提示手工补键。**必须查磁盘文件**：`Messages#has()` 在 2.11.0 改成只看玩家磁盘上的文件，否则 jar 内置默认文件一带该键就永远返回 true、检查形同虚设 |
| 失效残留键 | 老配置里若还留着上面两张表里的死键（2.11.0 的 `skip-named-items`、`messages.join-warn-notice`，以及 2.5.0 的 `worlds.only-loaded-chunks`、`trashbin.global`、`stand-on.sneak`、`allow-cross-world`、`currency-name`）→ 逐条列出提示删除 |
| 连锁挖矿配置体检 | `chain.yml` 的 `blocks` 里每个方块名都拿去 `Material.matchMaterial` 核对（拼错不抛异常，只会让连锁对该矿**静默失效**）；`max-blocks` 上限合理性；`exhaustion-per-block` 为 0 时提示「连锁白嫖」；`require.sneak` / `require.chain-bots` 关掉时提示误伤风险 |
| 扫地 × 垃圾桶冲突 | `to-trashbin: true` 但 `trashbin.enabled: false` = 清扫物直接销毁且不可取回 → 报 [注意] |
| 权限节点声明 | 代码里 `hasPermission()` 用到的 16 个 `sfpmenu.*` 是否都在 `paper-plugin.yml` 声明（用 `PluginMeta#getPermissions()` 读）→ 缺了报 [错误]（非 OP 玩家会永远没权限） |
| 假人记录体检 | `bots.yml` 里假人所在世界是否已加载（不加载则下次启动重建一直失败）；`restore-on-start: false` 也提示一次；已记录的假人数 / 已加入数 / 带持续动作的假人数一并展示 |

### 0.6 命令提示与补全

**2.16.1 统一改造**（`BotCommand` / `SfpCommand`），入口 `/sfp`、`/sfp db`、`/sfp player`：

| 场景 | 行为 |
|---|---|
| 未知子命令 | 先回显「未知子命令：{input}」，再用前缀 + Levenshtein 距离给「你是不是想输入 {command}？」（`closestMatch`，`BotCommand.java:702`，不引第三方库），最后给用法入口；不再整段刷 usage |
| 缺参 / 非法参数 | 给该子命令自己的完整用法（例如 `/sfp sweep` 参数错只打两行用法，不打印整个 `/sfp` usage） |
| 无权限 | 优先 `common.no-permission-with-node` 带权限节点（`{permission}`：`sfpmenu.admin` / `sfpmenu.clean.manage` / `sfpmenu.bot` / `sfpmenu.bot.killall`）；磁盘老 `messages.yml` 缺该键时回退旧 `common.no-permission` |
| `/sfp player help` | 新子命令，等同 `/sfp player`，已并入 `SUBCOMMANDS` 补全 |
| Tab 补全 | 未知子命令时不补假人名；`help` 第 2 参数为空；`config set` 第 4 参数补 `true/false`；`/sfp player` 的补全转交 `BotCommand#suggest`（`SfpCommand.java:95`） |

用法文案与 `messages.yml` 逐字一致（`hold` / `look` / `turn` / `config` / `config set` 的 fallback 与对应键同一字符串）；`look` 统一带「控制台必须给坐标」；`turn` 写成实际接受的形式 `turn [假人名] <度数>`；`sneak` / `sprint` 给非法开关值时报 `bot.sneak-usage` / `bot.sprint-usage`（此前静默当切换）。

**新增文案键**：`common.no-permission-with-node`、`common.unknown-subcommand-input` / `-suggest` / `-hint`、`bot.config-set-usage`、`bot.sneak-usage`、`bot.sprint-usage`；`common.unknown-subcommand` 原文保留（`PunishCommand` / `TpaCommand` 共用）。

---

## 1. 主菜单（menu 包）

**入口**：`/menu`、`/m`、菜单内子界面的「返回主菜单」按钮。

```
MenuCommand#execute
  → 玩家判断 → sfpmenu.player 判断
  → MenuManager.openMainMenu(plugin, player)
       ├─ 打开权限（menu.yml 的 permission）校验
       ├─ menu.yml 的 enabled 校验
       └─ createMainMenu() → player.openInventory(...)
```

**`createMainMenu` 构建过程**：
1. `MenuHolder`（携带 `slotActions` 映射 + MenuConfig）
2. 尺寸 = `rows * 9`（menu.yml 的 `rows`，代码夹在 1~6）
3. `applyBorder()`：按 `border.materials` 循环填充边框格（第 0/末行、第 0/末列），
   **只填 `getItem(slot) == null` 的格子**，所以按钮永远覆盖边框
4. 顶栏玩家头颅（`player-head.enabled` / `player-head.slot`）
5. 遍历 `buttons.*`，对每个按钮做三重判断后放置：
   `isFeatureEnabled(id)`（绑定模块的开关）→ `button.isVisible()`（按钮自身 enabled）
   → 槽位在 `[0, size)` 内（越界只记 warning 并跳过）

**按钮 → 动作**（`MenuManager#resolveAction`，`MenuHolder` 里是 `cmd:` 前缀的内部动作串）：

| 按钮 id | 槽位 | 图标 | 动作 |
|---|---|---|---|
| `back` | 10 | ENDER_PEARL | `actback` → 执行 `/back` |
| `home` | 12 | RED_BED | `acthome` → 打开家列表 |
| `warp` | 14 | ENDER_EYE | `actwarp` → 打开传送点列表 |
| `trashbin` | 16 | CAULDRON | `acttrashbin` → 打开垃圾桶 |
| `rtp` | 19 | SPYGLASS | `actrtp` → 执行 `/rtp` 随机传送（受 `teleport.yml` 的 `rtp.enabled` 控制） |
| `dominion` | 21 | GRASS_BLOCK | `cmd:dom`（执行 Dominion 的 `/dom`） |
| `tpa` | 23 | COMPASS | `acttpa` → 打开「选择传送目标」界面 |
| `elytra` | 28 | ELYTRA | `actelytra` → 消耗 64 钻石**或** 8 下界残骸兑换鞘翅（见 §1.0） |
| `ui-toggle` | 34 | BOOK | `acttoggleui` → 切换界面样式（dialogUI / 箱子） |

> **布局（2.11.0 调整）**：5 行的内容行是 1~3（第 0/4 行、第 0/8 列是边框）——
> 行 1 = back/home/warp/trashbin，行 2 = rtp/dominion/tpa，行 3 = elytra/ui-toggle。
> 2.10.x 的槽位整体比现在低一行（10~16 空着），已上移一行对齐 `menu.yml` 顶部的示意图。
> 改槽位时**同步改 `menu.yml` 顶部那张 ASCII 图**，否则图和实际会对不上。

- 绑定关系来自 `menu.yml` 的 `bind.*-button`（默认值就是按钮 id）。
- 动作分派在 `MenuListener#onInventoryClick`：`cmd:` 前缀 → `closeInventory()` +
  下一 tick `performCommand(...)`；内部动作 → 各自的 `handleXxx`。
- **垃圾桶入口只有一个**：早期用烈焰棒做的 `clean` 按钮和垃圾桶功能重复，已删除。
- 菜单/sub-GUI 的点击一律 `setCancelled(true)`（含拖拽），防止物品被拿走。

**界面样式（dialogUI / 箱子）**：玩家偏好存 `settings.db`（`ui_preferences` 表，默认 dialogUI），
用 `/menuui` 命令或菜单里的「界面样式」按钮切换。`MenuCommand` 按偏好分流：

- dialogUI → `ui/DialogMenu`（Paper 弹窗：`DialogType.multiAction(...).columns(2)`，
  每按钮一个 `customClick` 回调直接执行动作）
- box → `MenuManager.openMainMenu`（箱子）

两种界面**共用** `MenuManager.activeButtons / resolveAction / runAction / canOpen`，
因此按钮内容与点击结果完全一致；`MenuListener` 已经薄化成「关界面 → `runAction`」。

- **打开权限的提示文案优先取 `menu.yml` 的 `permission-message`**（MiniMessage，留空则回退
  `messages.yml` 的 `common.no-permission`）—— 这个键在 2.11.0 之前没有任何调用点。

**列表类界面也已 dialogUI 化**（1.3/1.4/1.5）：
- `ui/DialogTeleportList` —— 家列表 / 传送点列表共用的弹窗版（一页 6 条 + 上一页/返回/下一页翻页，翻页重开弹窗），点击条目传送
- `ui/DialogTpaTarget` —— 在线玩家选择弹窗版，点击发起 `/tpa`
- 二者与箱子 UI 一样按偏好分流（`MenuManager.openHomeList / openWarpList / openTpaTarget`）
- **`/home`、`/warp` 无参也走同一套分流**（2.11.0 修：此前命令入口固定开箱子界面，
  玩家偏好 dialogUI 时点命令反而看到箱子）
- **dialogUI 按钮无左右键**，列表只保留「点击 = 传送 / 发起 tpa」主动作；删除用 `/delhome /delwarp`、`tpahere` 用 `/tpahere`（箱子 UI 保留右键）
- 列表类弹窗的标题/翻页/返回**复用箱子 UI 的键**（`home.title`、`warp.title`、`tpa.gui-title`、
  `teleport.prev|next`、`common.back-to-menu`），条目与「操作选择框」的文案在 `messages.yml`
  的 `dialogui.*`（2.11.0 起不再有写死在代码里的中文）

垃圾桶（需要取物品）保持箱子 UI，不做 dialogUI。

### 1.0 鞘翅兑换（`MenuManager#exchangeElytra`）

> 2.10.0 新增。为「鞘翅垄断」问题提供一条官方兑换途径。
> 2.10.2 起支持「钻石 **或** 下界残骸」二选一，并修掉了提示文案不解析 MiniMessage 的问题。

- **入口**：主菜单的 `elytra` 按钮（slot 28，材质 `ELYTRA`）→ 内部动作 `actelytra`
  → `MenuManager#exchangeElytra(plugin, player)`。箱子 UI 与 dialogUI 共用这一条路径。
- **兑换规则**：`menu.yml` 的 `elytra-exchange.diamond-cost`（默认 **64**）个钻石
  **或** `debris-cost`（默认 **8**）个下界残骸（`ANCIENT_DEBRIS`）→ 得到 1 个鞘翅。
  **不限次数**、无冷却，也没有独立权限节点（沿用菜单的 `sfpmenu.player`）。
- **扣除顺序**：**优先扣钻石**，钻石不够再扣残骸。所以两种材料都有但只有钻石够时，
  扣的只会是钻石，不会两种各扣一点。
- **流程**（顺序不可颠倒，保证「不够就不扣」）：
  1. 分别统计存储槽位里的钻石 / 残骸，**都不够**则只发提示 + 失败音效，**不做任何扣除**；
  2. 够则 `removeItem(...)` 扣除**实际那一种**材料；
  3. `addItem(ELYTRA)` 给出鞘翅，返回的 `leftover`（背包放不下）用
     `World#dropItemNaturally` 掉在脚下，避免物品凭空消失；
  4. 成功提示 + 升级音效。
- **口径一致性（易踩坑）**：统计用的是 `getStorageContents()` 而非 `getContents()`，
  因为 Bukkit 的 `removeItem` 本身就只作用于**存储槽位**；若用 `getContents()`，
  会把副手/盔甲的钻石也数进去，出现「数够了却扣不掉」的错位。
  `PlayerInventory` 并未覆写 `getContents()`，副手属于 `getExtraContents()`，两者口径确实不同。
- **配置键**：`menu.yml` 的 `elytra-exchange.diamond-cost` / `debris-cost`（均 ≥0，填 0 = 不接受该材料）；
  `bind.elytra-button`（默认 `"elytra"`）。两者的**兼容读法**：仍会读旧的单个 `cost` 键当钻石数。
  **两个都配 0 会让按钮点了只提示「不可用」**，`/sfp test` 会专门报这一条。
- **文案**：`messages.yml` 的 `menu.elytra-success` / `menu.elytra-not-enough` /
  `menu.elytra-misconfigured`
  （新文案占位符：`{cost_need}` / `{cost_text}` / `{diamond}` / `{debris}` /
  `{diamond_have}` / `{debris_have}`；旧文案兼容别名 `{have}` / `{cost}`，见下方提示块）。
- **改价注意**：按钮的 lore 是**写死的文案**（`menu.yml` 的 `buttons.elytra.lore`），
  改价格时务必同步改 lore，否则界面显示与实际扣费不一致。
  `/sfp test` 会在 lore 数字与配置不一致时给出提示。

> **同类漂移统一由自检兜底（2.11.0）**：`SelfTest#checkElytraExchange` 查鞘翅 lore 的数字，
> `SelfTest#checkLoreDrift` 另查两处 —— 随机传送按钮 lore 的冷却 vs `rtp.cooldown-seconds`、
> 菜单钟 lore 里的「泥土」无法自动比对（代码里没有 Material→中文的映射表），改动时要手工同步。

> **2.10.2 修复的坑（务必留意）**：
> 提示文案**必须**用 `Messages#send(...)` 或 `Messages.deserialize(Messages.apply(...))` 发送，
> **不能**把 `Messages.apply(...)` 的返回值（一个 `String`）直接丢给
> `Player#sendMessage(String)` —— **Paper 的 `sendMessage(String)` 不会解析 MiniMessage**，
> 它只认传统 `§` 色码，于是 `<green>…</green>` 会被原样打印成字面量。
> 本项目里 `Messages#send(...)` 内部正是 `deserialize(apply(...))`，是发送玩家文案的标准做法。

> **占位符向后兼容（2.10.2）**：`mergeDefaults()` **只补缺失键、不覆盖已存在的键**。
> 因此从 2.10.0/2.10.1 升上来的服，线上 `messages.yml` 里**已经存在**的
> `menu.elytra-success` / `menu.elytra-not-enough` **不会被刷新成新文案**，
> 它们里面的旧占位符会继续生效。为此代码侧保留了两个兼容别名：
> - `{have}` —— 2.10.0 旧文案用的「钻石持有量」，仍会替换（不会显示成字面量）；
> - `{cost}` —— **纯数字**的本次扣除量（所以旧文案「已消耗 {cost} 个钻石」仍能正确显示）。
>
> 新文案请用：`{cost_need}`（本次要求，如「64 个钻石 或 8 个下界残骸」）、
> `{cost_text}`（实际扣除的材料描述）、`{diamond}`/`{debris}`（各自所需）、
> `{diamond_have}`/`{debris_have}`（各自持有）。
>
> `/sfp test` 的 `checkElytraPlaceholders` 会扫描 `menu.elytra-*` 文案里出现的占位符，
> 与代码实际支持的名单比对，发现「代码不会替换的占位符」时给出提示 ——
> 这类问题编译期与运行期都发现不了，只有真人点一次按钮才看得到。

### 1.1 菜单钟（`menu/MenuClockManager` + `menu/MenuClockListener`）

**入口**：把一个泥土放进合成格（无序合成）→ 得到「菜单钟」→ 手持**右键**打开菜单。

```
合成：ShapelessRecipe(键 sfp-main:menu_clock, 结果=菜单钟)
       材料来自 menu.yml 的 menu-clock.recipe.ingredients（默认 [DIRT]）
       → 启动时 MenuClockManager#registerRecipe() 注册；插件禁用时 removeRecipe()
右键：MenuClockListener#onInteract（PlayerInteractEvent）
  → 只看主手 HAND，动作须为 RIGHT_CLICK_AIR / RIGHT_CLICK_BLOCK
  → event.getItem() 经 MenuClockManager#isMenuClock 判定
  → event.setCancelled(true) → MenuManager.openMenuByPreference(plugin, player)
       （与 /menu 完全同一条路径：开关 / 权限 / dialogUI↔箱子偏好 全部一致）
```

- **识别方式用 PDC 标记**（键 `sfp-main:menu_clock_item`，BYTE=1），**不比对材质或显示名** ——
  这样改了 `material` / `name` 后，玩家背包里已有的菜单钟仍能正常打开菜单，也不会把普通钟误判为菜单钟。
- **附魔光效**用 `ItemMeta#setEnchantmentGlintOverride(true)`（Paper API），不消耗真实附魔。
- **触发范围**由 `menu-clock.trigger` 决定：`both`（默认，看向方块/空气都触发，并取消该次方块交互）
  或 `air`（只在没看向方块时触发，不干预开箱等方块交互）。
- **依赖主菜单**：`menu.yml` 的 `enabled: false` 时连菜单钟一起不注册（避免合出来的钟点了没反应）。
- **进服提示**：`MenuClockJoinListener`（2.7.0）在玩家进入时逐行发送
  `menu-clock.join-notice`（menu.yml，多行 MiniMessage），提醒「可以合成菜单钟」。
  仅在**主菜单 + 菜单钟都启用**时发送。
- 配置全在 `menu.yml` 的 `menu-clock` 段：`enabled / material / name / lore / glint / trigger /
  join-notice / recipe.enabled / recipe.ingredients`。

---

## 2. 传送系统（teleport 包）

数据存 SQLite（`teleport.db`），会话态（tpa）与随机传送冷却（rtp）存内存。详见 §8。

### 2.1 传送内核 `TeleportManager#teleport(player, target)`

所有传送（back/home/warp/tpa/rtp/GUI 点击 / dialogUI 列表）都走这里，保证行为一致：

```
1) target == null / !worldExists()   → false（世界不存在/未加载）
2) safe-location: true → findSafeLocation()
     要求：脚下是实心方块 + 身位与头部可通行；从原坐标上下交替搜索，最多 safe-search-distance 格
3) delay-seconds <= 0 → executeTeleport() 立即执行
   否则 → startDelayedTeleport()（见下）
4) executeTeleport：
     back.enabled && back.record-teleport → 记录「传送前位置」为 /back 目标
     markInternalTeleport()（1 秒窗口，见 2.2）
     player.teleport(dest)
     播放 teleport.sounds.teleport（默认 ENTITY_ENDERMAN_TELEPORT）
```

**延迟传送（delay-seconds 默认 3 秒）**：`startDelayedTeleport()` 以 **10 tick（0.5 秒）** 为步长执行 ——
- 开始时：播放 `teleport.sounds.start`（BLOCK_NOTE_BLOCK_PLING）。
- **每步（0.5 秒）**：向玩家发一条 **ActionBar 倒计时** —— 文案取 `messages.yml` 的
  `teleport.actionbar`（默认 `<yellow>剩余 <white>{time}</white> 秒</yellow>`），
  `{time}` 为剩余秒数（精确到 0.5，如「2.5」，整数则省去小数位，由 `formatSeconds` 处理）。
- **每分钟粒子的间隔由 `teleport.particle-interval-ticks` 决定**（默认 20 tick = 每秒一次）：
  步长累加到该值就刷一次脚下 PORTAL 粒子，然后清零（2.11.0 修：此前间隔写死 `% 20 == 0`，
  配置键读进来却从没用过）。
- **滴答音效固定每秒一次**（`teleport.sounds.tick`，BLOCK_NOTE_BLOCK_HAT），与粒子间隔解耦：
  把粒子间隔调成 40 只会让粒子变稀，音效不会跟着变。
- **距传送还有 1 秒时**（`PRELOAD_LEAD_TICKS = 20`）异步 `getChunkAtAsync` 预加载目标那**一个**
  区块（不加 ticket，传完自然卸载），只发一次。2.11.0 修：此前这个调用被放在「延迟结束时」
  的分支里，与 `executeTeleport` 同一次 tick 执行 —— 请求刚发出就传送，等于没有预加载。
- 到点：二次校验世界仍存在 → executeTeleport；并清空 ActionBar（`sendActionBar(empty)`）。
- 被取消时（`cancelPending`）同样清空 ActionBar，避免倒计时残留在屏幕上。

**延迟传送的打断条件**（`TeleportListener` / `TeleportManager`）：
- 玩家**跨越方块坐标**（同一方块内的转头/微位移不算）
- 玩家**受到任何伤害**（`EntityDamageEvent` → `cancelOnDamage`，提示区别于移动打断）
- 任何**非本插件**发起的传送（`PlayerTeleportEvent` 且 cause≠PLUGIN 或不在内部标记窗口内）
- 玩家退出、插件卸载时取消全部等待任务
- 玩家退出时还会 `clearTransientState()`：只清「1 秒内部传送标记」这个一次性状态；
  **刻意不清冷却** —— 冷却存的是结束时间戳，清了就等于「重登绕过冷却」

**冷却**：`getCooldownRemaining() / applyTeleportCooldown()` 由**调用方**决定是否使用。
`/back` 不检查也不施加冷却；home/warp/tpa 各自有冷却（共用 `teleportCooldowns` 这一张表），
`/rtp` 用**独立的** `rtpCooldowns`（见 §2.2）。

**传送到家/传送点的统一入口**：`teleportHome(player, name)` / `teleportWarp(player, name)`
把「存在性 / 世界 / 冷却 / 传送 / 提示」的完整逻辑抽到 TeleportManager，
命令、箱子 GUI、dialogUI 三处只调这一个方法，保证行为一致。

### 2.2 `/rtp` 随机传送（`RtpCommand` + `TeleportManager#randomTeleport`）

```
入口：/rtp（`RtpCommand`，无参数）
前置：玩家 → sfpmenu.teleport → rtp.enabled → 当前世界在 rtp.worlds 白名单内 → rtp 冷却
流程：
  1) 校验通过后进入「选点中」状态（rtpInProgress，防并发重复发起）
  2) 异步线程 findRandomLocation(worldName, origin)：
       · 取 rtp.max-attempts 次机会，每次在 ±rtp.radius 的方形范围内随机取 (x,z)
       · 若设了 rtp.min-distance，跳过离玩家太近的点
       · 确保候选区块已加载（world.getChunkAt）后，在 (x,z) 列上自上而下
         寻找第一处 isSafeSurface() 的落点（最多 rtp.max-vertical-search 格）
  3) 回主线程 → StoredLocation.of(found) → 复用 teleport(player, target)
     （经过统一的安全落点、3 秒等待、音效、粒子、受伤打断流程）
  4) 成功后施加 rtp 冷却（rtp.cooldown-seconds，默认 600 = 10 分钟）并提示
```

- **只能由玩家执行**，控制台执行提示 `common.player-only`。
- **世界白名单**：`rtp.worlds`（teleport.yml），默认仅 `world`（主世界）。
  白名单为空表示所有世界都允许；填错世界名则在该世界执行只会提示
  `rtp.world-not-allowed`。
- **独立冷却**：`rtpCooldowns` 与普通传送冷却（home/warp/tpa）**互不影响**，反之亦然。
- **选点安全判定**（`isSafeSurface`，比内核的 `isSafe` 更严格）：
  脚下必须是实心、非可穿过、非液体、非危险方块（岩浆/岩浆块/篝火/火/仙人掌/甜浆果丛/细雪），
  身位与头部必须可通行、非液体、非细雪。随机点最容易落在水面/岩浆上，故单独加强。
- **选点在异步线程**：候选区块加载会阻塞，故 `findRandomLocation` 放在
  `runTaskAsynchronously`；完成后用 `runTask` 回主线程执行传送与提示。
  玩家在选点期间掉线时静默放弃（`Bukkit.getPlayer` 取不到即 return）。
- **`/rtp` 与延迟打断**：随机传送本身走统一流程，因此在 3 秒等待期间移动/受伤
  同样会打断（与 home/warp 一致）。
- 权限：`sfpmenu.teleport`（与其余传送命令共用）。
- 配置键：`rtp.enabled` / `rtp.worlds` / `rtp.radius` / `rtp.cooldown-seconds` /
  `rtp.max-attempts` / `rtp.max-vertical-search` / `rtp.min-distance`。
- 文案键：`rtp.searching` / `rtp.in-progress` / `rtp.success` / `rtp.cooldown` /
  `rtp.world-not-allowed` / `rtp.failed`。
- **不写数据库**：随机传送是纯即时行为，冷却存内存（`rtpCooldowns`），重启即清空。

### 2.3 `/back`（`BackCommand`）

```
权限 sfpmenu.teleport → back.enabled → isStorageAvailable()
→ 从 last_locations 读该玩家记录 → worldExists() 校验
→ manager.teleport(player, target) → back.success
```

**记录时机**（`TeleportListener` + `TeleportManager`，各由 teleport.yml 的 `back.*` 控制）：

| 时机 | 触发点 | 配置键 | 默认 |
|---|---|---|---|
| 死亡 | `PlayerDeathEvent` | `record-death` | true |
| 传送前 | `executeTeleport()` 内记录（本插件传送）；非本插件传送由 `onTeleport` 记 `from` | `record-teleport` | true |
| 切换世界 | `PlayerTeleportEvent` 比较 from/to 世界名 | `record-world-change` | true |
| 退出 | `PlayerQuitEvent` | `record-quit` | false |

**关键设计**：`onTeleport` 里先判断 `internal`（`cause == PLUGIN` 且 1 秒内被 `markInternalTeleport()`
标记过）；是则**直接 return 不记录** —— 因为 `executeTeleport()` 已经记了正确的位置，
再记一次会用「传送后的目标位置」把它覆盖掉。跨世界也不再监听 `PlayerChangedWorldEvent`
（那个事件拿不到旧坐标，且会在本插件自身传送后触发）。

**只存一层**：`last_locations` 是 `ON CONFLICT(player_uuid) DO UPDATE`，每人一行覆盖写，
不是历史栈。

### 2.4 `/home` 系列（`HomeCommand`）

四个标签共用一个类，`action ∈ {home, sethome, delhome, homes}`。
统一前置：玩家 → `sfpmenu.teleport` → `home.enabled` → `isStorageAvailable()`。

| 分支 | 流程 |
|---|---|
| `home` 无参 | `MenuManager.openHomeList(...)` 按玩家偏好分流：dialogUI → `DialogTeleportList`，箱子 → `HomeListGui`（与菜单「我的家」按钮同一入口） |
| `home <名>` | 取名 → 不存在提示 → `worldExists()` → 冷却(`home.teleport-cooldown-seconds`) → `teleport()` → 施加冷却 + 提示 |
| `sethome [名]` | 设置冷却(`set-cooldown-seconds`) → 名字校验（`util/NameUtil`：**中文**/字母/数字/下划线，1~16）→ **若是新家**校验上限（`max-homes`，`sfpmenu.home.bypass-limit` 可绕）→ `HomeStore#save` → 施加设置冷却 + 提示 |
| `delhome <名>` | `HomeStore#delete`，返回 false 说明不存在 |
| `homes` | 逐条列出名字 + 坐标 |

补全：仅 `home` / `delhome` 的第一个参数补全家名。

### 2.5 `/warp` 系列（`WarpCommand`）

结构同 home，`action ∈ {warp, setwarp, delwarp, warps}`。
统一前置：玩家 → `sfpmenu.teleport` → `warp.enabled` → `isStorageAvailable()`。

- `setwarp` / `delwarp` **额外**校验 `warp.set-permission` / `warp.delete-permission`
  （teleport.yml 可配置；留空表示人人可用）。
- 传送流程：存在 → `worldExists()` → 冷却(`warp.teleport-cooldown-seconds`) → `teleport()`。
- 传送点全服共享，`warps` 表主键是 `warp_name`。
- `warp` **无参**同样是 `MenuManager.openWarpList(...)` 按偏好分流（与 home 一致）。

### 2.6 `/tpa` `/tpahere` `/tpaccept` `/tpdeny` `/tpaui`（`TpaManager` + `TpaCommand`）

**请求会话态不入库**（重启即失效；进库反而要处理过期清理）；
**界面偏好入库**（`settings.db` 的 `tpa_ui_preferences`，见 §8）。

`TpaCommand`（五个标签一个类，`action` 区分）统一前置：
玩家 → `sfpmenu.teleport` → `tpa.enabled`。

**发起**（`/tpa 名字` = 我去他那 / `/tpahere 名字` = 让他来我这）：
```
request(from, target, type)
  1) 自己 → 拒绝
  2) 发起冷却（tpa.request-cooldown-seconds）
  3) 该接收者已有请求 → 移除旧的（取消其过期任务）并通知被取代的发起者
  4) 登记 pending[target] = (from, fromName, targetName, type, expireAt, task)
     并 runTaskLater(expire-seconds)：到点若仍在 → 移除 + 通知双方已过期
  5) deliver(target, ...)：按**接收者自己**的界面偏好二选一
       DIALOG → 弹窗（multiAction：接受 / 拒绝 / 切换界面）+ 一行「手打命令」兜底
       TUI    → 聊天里带可点 [接受]/[拒绝]/[切换为弹窗] 的消息 + 手打提示
  6) 给发起者回执
```

**回应界面形式可切换（偏好存数据库）**：`/tpaui` 命令，或直接点界面里的
「切换界面」（弹窗）/「[切换为弹窗]」（聊天）按钮 —— 三者都走 `TpaManager#switchUi`：
切换 `tpa_ui_preferences`（`dialog` / `tui`，默认 `dialog`），并**立即用新形式重发**
当前待处理的那笔请求。该偏好**独立于**主菜单的 dialogUI/箱子偏好（`ui_preferences`）。

**兜底**：Dialog 是较新的客户端能力，服务器装了 ViaVersion/ViaBackwards/ViaRewind，
低版本客户端可能渲染不出弹窗，所以弹窗形式下也附一行「手打命令」提示；
任何形式都能用 `/tpaccept` `/tpdeny` 响应 —— **所有按钮最终都执行这两个命令**
（弹窗按钮是 `DialogAction.staticAction(ClickEvent.runCommand("/tpaccept 名字"))`）。

**接受**（`accept`）：
```
取出并移除 pending（名字对不上则提示；没有则提示）
→ 发起者离线？提示并结束
→ 双方提示
→ 走 TeleportManager#teleport：
     TO   → 把 from 传到 target 当前位置（冷却记在 from 身上）
     HERE → 把 target 传到 from 当前位置（冷却记在 target 身上）
→ 失败回 teleport.failed
```
**拒绝**：取出并移除 + 双方提示。

**清理**（`TpaListener` → `onQuit`）：玩家退出时，清掉①他作为接收者的请求（通知发起者）
②他作为发起者的请求（通知接收者）。`TpaManager#shutdown` 在插件卸载时取消全部过期任务。

### 2.7 传送相关界面（gui 包）

`TeleportGuiListener` 统一处理三个界面的点击（先判 `TpaTargetHolder`，再判 `TeleportListHolder`）。

**列表界面**（`TeleportListGui`，家 / 传送点共用；`HomeListGui` / `WarpListGui` 是薄封装）：

- 54 格：内容 0~44，导航行 45~53 = `45 上一页(SPECTRAL_ARROW)` / `48 返回主菜单(OAK_DOOR)` /
  `49 信息纸(PAPER)` / `53 下一页(SPECTRAL_ARROW)`，其余灰板；到首页/末页时把翻页键换成灰板+「首页/末页」
- 左键条目 = 传送；右键条目 = 删除（家直接删，传送点需 `warp.delete` 权限）
- 删除后刷新列表并**回到首页**（不保留原页码）
- 槽位常量在 `TeleportListHolder`：`SLOT_PREV/SLOT_BACK/SLOT_INFO/SLOT_NEXT`

**玩家选择界面**（`TpaTargetGui` + `TpaTargetHolder`）：

- 内容区是**在线玩家的头颅**（不含自己），lore 显示所在世界
- 左键 = `/tpa 他`（我去他那）；右键 = `/tpahere 他`（他来我这）
- 发起成功后关闭界面，等对方在弹窗/聊天里响应
- 目标离线时提示并刷新列表

「返回主菜单」统一走 `MenuManager.openMainMenu()`（内含开关 + 打开权限校验，与 `/menu` 共用），
点击时先 `closeInventory()` 再 `runTask` 延迟一 tick 打开。

---

## 3. 自动扫地（clean 包）

**入口**：`/sfp sweep on|off` 与 `/sfp sweep except add|remove <世界> <区块X> <区块Z>`（独立权限 `sfpmenu.clean.manage`）；纯定时任务由 `FloorCleanManager` 构造时启动 `runTaskTimer(20L, 20L)`（每秒 tick）。例外区块与开关持久化在 `data/sweep.db`；关闭时暂停倒计时，例外区块内物品不移除、不计数、不收入垃圾桶。

```
每秒：
  1) 若 remainingSeconds 命中 warn-seconds（如 60/30/10）且未提醒过
       → clean.broadcast.reminders 为 true 时广播 clean.reminder（{seconds}）
  2) remainingSeconds--
  3) <= 0 → performClean()，然后重置为 cycleSeconds 并清空已提醒集合
```

`performClean()`：
```
遍历 Bukkit.getWorlds()
  ├─ config.isWorldIncluded(名)：黑名单优先，白名单非空则只扫白名单内的世界
  ├─ 遍历世界内实体，只处理 Item（掉落物）
  │    └─ type 非 air 就收进 collected，然后 entity.remove()
  └─ to-trashbin && 垃圾桶可用 → trashBinManager.clear()（清掉上一轮）+ addAll(collected) + save()
                                  否则收集物直接丢弃（不可恢复）
→ 控制台日志 + broadcast.result 为 true 时广播 clean.result（带 [打开垃圾桶] 可点击按钮）
```

> **2.11.0 取消了 `skip-named-items`**：此前「有自定义显示名的物品」会被跳过（留在地上），
> 与文档写的「不收集但实体照样 remove」不一致。现在改名物品与普通掉落物**完全同待遇** ——
> 一样被清除，是否可取回只由 `to-trashbin` 决定。

- 周期由 `clean.yml` 的 `cycle-seconds` 决定，**代码强制最低 60 秒**。
- PAPI 占位符：`CleanTimePlaceholder`（identifier `stf`）暴露
  `%stf_cleantime%`（带颜色，阈值来自 `clean.placeholder.*`）与 `%stf_cleantime_plain%`。
  注册前提：PlaceholderAPI 已安装且扫地模块已启用。
- **垃圾桶只在「至下次扫地前」有效**：每轮清扫都会先 `clear()` 再收入本轮物品。

---

## 4. 垃圾桶（trashbin 包）

**入口**：`/trashbin`、菜单 `trashbin` 按钮。

```
TrashBinCommand#execute：玩家 → sfpmenu.player → manager 是否存在 → openPage(0)
TrashBinCommand.openPage(plugin, player, manager, page)（供命令与翻页共用）
  ├─ 页码夹紧 → TrashBinHolder(page, pageSize)
  ├─ 54 格标题含 {page}/{pages}
  ├─ 内容格 0~44：manager.getPage(page) 的 ItemStack 克隆
  ├─ 导航行同 §2.6（常量在 TrashBinHolder）
  └─ 打开界面 + 播放 trashbin.sounds.open
```

点击处理 `TrashBinListener`：
- 翻页 / 返回主菜单（同 §2.6）/ 信息纸无操作 / 导航区灰板忽略
- **点击物品格 = 取回**：`toGlobalIndex(page, slot)` → `takeItem(index)` →
  `player.getInventory().addItem()`，背包满则掉在脚下 → `save-immediately` 时立即写盘 →
  播放 `take` 音效 → 刷新当前页（若该页因取空而越界则回退一页）
- 所有点击与拖拽一律 cancel（**不能放入物品**）

数据：内存 `List<ItemStack>`，`trashbin-data.yml` 持久化（ItemStack 可直接序列化）。
`max-items` 超出时 `trim()` 丢弃**最旧**的（从下标 0 开始删）。
`page-size` 代码夹在 1~45。

---

## 5. 椅子（已删除，2.15.0）

椅子功能已按要求整体删除：`chair` 包、`chair.yml`、菜单 `chair` 按钮与 `MenuHolder.ACTION_CHAIR`、
`messages.yml` 的 `chair.*` 文案、`paper-plugin.yml` 的椅子权限节点、`SelfTest` 的椅子检查全部移除，
`menu.yml` 的 ASCII 布局图也不再标注「坐」。这里保留章号只为让后续章节编号稳定（0~4、6~13 不变），
不再有对应的代码与配置；如需恢复请参考 git 历史。

---

## 6. 假人（bot 包）

**入口**：`/sfp player <子命令>`（简写 `/sfp fp <子命令>`，权限 `sfpmenu.bot`，默认 op）。
子命令由 `debug/SfpCommand` 在「管理权限检查**之前**」转交 `bot/BotCommand` ——
假人有自己的权限节点，与 `/sfp` 的管理权限互不牵连。

**假人是真正的玩家实体**（NMS `ServerPlayer`），不是盔甲架之类的近似物：
它会出现在在线玩家列表里（`/list` 可见）、计入玩家数，并**天然保持周围区块加载**
（实体本身不会保区块，只有玩家或区块 ticket 才会）。

### 6.1 创建与生命周期（`NmsBotFactory` + `BotManager`）

`NmsBotFactory#spawn`（全反射，Paper 无公开 API）：
```
ServerPlayer(server, level, GameProfile(uuid,name), ClientInformation.createDefault())
Connection(PacketFlow.SERVERBOUND)          ← 本地假连接
  └─ Connection.channel（public 字段）= netty EmbeddedChannel    ← 见下
ServerGamePacketListenerImpl(server, connection, player, cookie)
CommonListenerCookie.createInitial(profile, false)
ServerPlayer.connection（public 字段）= listener
ServerPlayer.gameMode（public 字段）        ← 动作系统的入口之一
PlayerList#placeNewPlayer(connection, player, cookie)            ← 服务端自己的加入流程
```
- `EmbeddedChannel` 是「吞包黑洞」：`placeNewPlayer` 会往连接写登录包，没有 channel 会 NPE。
  它没人消费的 outbound 队列由 `BotManager` 的定时任务（每 60 秒）清空，避免堆积。
- 皮肤：从创建者 `CraftPlayer#getProfile()` 复制 `textures` 属性；`bot.yml` 的 `skin-source`
  可改为指定玩家名或 `none`（默认皮肤）；留空则看假人设置的 `skin` 项。
- UUID 由名字派生（`nameUUIDFromBytes("StarfallBot:" + 名字小写)`），所以**名字即身份**，重名直接拒绝。
- 名字规则 `[A-Za-z0-9_]{1,16}`；`max-per-player` 限制每位玩家的数量（0=不限，控制台不计入）。
- **移除**：反射调 `PlayerList#remove(ServerPlayer)`，再 `Player#remove()` 兜底。
  **不能用 `Bukkit#kick()`** —— 它依赖向客户端发断开包，假连接发不出去，实测移除不掉。
- **复活**：反射调 `PlayerList#respawn(ServerPlayer, false, RemovalReason.KILLED, RespawnReason.DEATH)`，
  复活会生成**新实体**，所以复活后要重放设置（`BotManager#applySettings`）。
  若服务端在假人死亡时已把它踢出，则只能重新 `spawn`。

`BotManager`：
- 记录写入 `bots.yml`（name/uuid/owner/world/x/y/z/yaw/pitch/**settings**/**actions**）
- `setupBot()` 里 **`runTask` 延后一 tick** 调 `restoreOnStart()` 重建（避开插件启用栈，
  此时世界已加载完毕）；单个失败只记日志
- `saveData()` 在创建/删除/改设置/开关动作/禁用时写盘；**在线假人会刷新位置**，
  重启后回到最后所在处
- `shutdown()` 停掉动作与清理任务；`startActions()` 启动周期任务（见 6.4）
- 选择（`select`）：`Map<执行者UUID, 假人名>`，只存内存；执行者退出或假人被移除时清掉

### 6.2 命令一览（38 个子命令）

| 子命令 | 说明 |
|---|---|
| `help` | 显示用法（等同不写子命令；2.16.1） |
| `spawn <名字> [世界 x y z]` | 创建假人（省略坐标 = 当前位置；控制台必须给坐标） |
| `kill [名字]` / `killall` | 移除假人 / 移除全部（后者另需 `sfpmenu.bot.killall`） |
| `list` | 列出假人：位置、在线状态、当前持续动作 |
| `select <名字>` / `selection` | 设定 / 查看默认假人（之后命令可省略名字） |
| `status [名字]` | 生命 / 饱食度 / 位置 / 游戏模式 / 持续动作 / 全部设置 / 创建者 |
| `distance [名字]` | 与假人的距离（跨世界时单独提示） |
| `invsee [名字]` | 打开假人背包（**右键假人同效**，见 6.5） |
| `skin <玩家名> [名字]` | 复制某玩家皮肤（在线直接取档案；离线走 `complete()`，需能访问 Mojang） |
| `respawn [名字]` | 复活死亡的假人 |
| `tp / tphere / tps [名字]` | 传送到假人 / 把假人拉过来 / 交换位置 |
| `expme [名字]` | 把假人的全部经验转给自己 |
| `cmd [名字] <命令...>` | 以假人身份执行命令（假人默认不是 op，可用 `/op 假人名` 提权） |
| `drop / dropstack / dropinv [名字]` | 丢手持 1 个 / 丢整叠 / 丢整个背包（储物格 + 副手） |
| `swap [名字]` / `hold <1-9> [名字]` | 主副手互换 / 切换快捷栏槽位 |
| `set [名字] <键> <true\|false>` | 改**这个假人**的设置 |
| `config list \| set <键> <值> \| clear` | **个人默认配置**（创建新假人时套用，按玩家 UUID 存 bots.yml） |
| `attack / mine / use / move [名字]` | **持续动作**开关（对齐 minecraft-fakeplayer 的 periodical actions） |
| `jump [名字]` | 跳一次 |
| `sneak / sprint [名字] [on\|off]` | 潜行 / 疾跑（省略 on\|off 则切换） |
| `look [名字] [x y z]` | 看向坐标（省略坐标 = 看向执行者） |
| `turn <度数> [名字]` | 原地转向（相对角度，正数右转） |
| `ride [名字]` | 骑上视线正前方的实体（再执行一次下车） |
| `sleep / wakeup [名字]` | 睡觉 / 起床 |
| `stop [名字]` | 停止全部持续动作 |

**参数约定**：除 `spawn` / `skin` / `hold` / `set` / `config` 外，假人名都可省略 ——
省略时用 `select` 设定的默认假人；命令里第一个参数**恰好是已存在的假人名**时会被当作假人名吃掉
（所以 `look 100 64 100`（坐标）与 `look Steve 100 64 100`（带名字）都能用）。

### 6.3 设置项（`BotSettings`）

每个假人一份（`bots.<名字>.settings`），每位玩家还有一份个人默认配置
（`player-configs.<uuid>`，创建时覆盖 `bot.yml` 的 `defaults`）。全部布尔：

| 键 | 默认 | 作用 |
|---|---|---|
| `collidable` | true | 碰撞盒。false = 其他玩家可以穿过假人（`Player#setCollidable`） |
| `invulnerable` | true | 无敌。false = 会受伤挨饿（建议配合 wolverine） |
| `wolverine` | false | 超级回复：定时把生命 / 饱食度回满 |
| `look_at_entity` | false | 持续攻击时先看向目标（配合 `attack` 自动打怪） |
| `pickup_items` | true | 自动捡起**符合 `pickup-whitelist`** 的掉落物（动作循环扫描周围物品，调用原版拾取流程） |
| `replenish` | false | 主手/副手按**原物品模板**从背包（9~35）补货：同类且未满时补满、耗尽时用模板重建；不塞不相关物品、不动另一只手与非空格 |
| `autofish` | false | 自动钓鱼：手持鱼竿时自动抛竿 → 等咬钩 → 收竿 |
| `skin` | true | 创建时使用创建者自己的皮肤 |

命令：`/sfp player set [名字] <键> <值>`（改现有假人，立即生效）、
`/sfp player config set <键> <值>`（改自己的默认值，下次创建生效）。

### 6.4 动作系统（`BotActions`）

两条周期任务：**4 tick 的动作任务**（`action-interval-ticks`，攻击/挖掘/使用/设置循环）
与 **1 tick 的移动任务**（移动需要每 tick 推进一小步，与真客户端发包频率一致）。

| 动作 | 实现方式（26.3 实测结论） |
|---|---|
| `attack` | 半径 `attack-range` 内最近的可攻击实体：优先真正的生物，没有生物才打可打碎的盔甲架（跳过其他假人、默认跳过玩家、跳过 marker 与关掉重力的盔甲架）→ `Player#attack(Entity)` + `swingMainHand()`。攻击前先 `NmsBotFactory#chargeAttack` 把 `attackStrengthTicker` 顶满（假人不 tick，原版充能永远停在 0.1，见下方实测坑 5），再 `syncOnGround` 按脚下方块把 NMS `onGround` 校正为 true，**主手是剑时会自动触发原版横扫**；盔甲架在一个动作周期内连打两次（原版要求 5 tick 内两下才碎）；攻击间隔由 `BotActions` 自计时（12 tick ÷ 动作周期，默认 3 个周期 ≈ 0.6 秒），**不再读 `getAttackCooldown()`** |
| `mine` | 自己走**体素射线**找视线上第一个实体方块 → 按硬度计时（`Block#getBreakSpeed` 返回的是**每 tick 破坏进度**，`ceil(1/进度)` 即所需 tick）→ 到点调 `ServerPlayerGameMode#destroyBlock`（掉落物 / BlockBreakEvent / 统计都走服务端正常流程，**没有原版裂纹动画**） |
| `use` | 视线命中方块 → `ServerPlayerGameMode#useItemOn`（放置 / 交互）；否则 → `#useItem`（吃 / 拉弓 / 抛竿 / 桶）。**先主手、未 consume 再试副手**（`InteractionResult#consumesAction` 判定）；用完后按 `replenish` 设置补货 |
| `move` | 按 yaw 算水平方向，每 tick 前进 0.216 格（疾跑 0.28）→ `ServerPlayer#snapTo` 改服务端坐标，由实体追踪器广播；脚/头位置被挡时尝试抬一格台阶，落脚高度按 ±5 格内的最近地面贴地 |
| `jump` | `ServerPlayer#jumpFromGround()`（实测 Motion.y = 0.42） |
| `sneak` / `sprint` | Bukkit `Player#setSneaking / setSprinting`（置位实体标志并广播） |
| `look` / `turn` | NMS `setYRot/setXRot`（追踪器会自动广播转向；`/data get entity` 可见 Rotation 变化） |
| `ride` | 视线射线取实体 → `Entity#startRiding(Entity)`；`leaveVehicle()` 下车 |
| `sleep` / `wakeup` | NMS `startSleeping(BlockPos)`（不校验床）/ `stopSleeping()` |
| `replenish` / `wolverine` | 按 `replenish-interval-ticks` / `wolverine-interval-ticks` 分频循环；补货只认主手/副手原物品类型，并只在原槽位为空/耗尽时从 9~35 找同类物品 |
| `autofish` | 找自己的 FishHook：没有 → `useItem` 抛竿（≥10 个动作 tick 冷却）；有且 `biting` → `FishHook#retrieve`；超时（`autofish-timeout-seconds`）→ 收竿重抛 |

**26.3 的实测坑（改动作前务必看）**：
1. **玩家的位移是「客户端权威」的**。给假人发 `ServerboundPlayerInputPacket` / `ServerboundMovePlayerPacket`
   都**不会**让它动（服务端只把输入当状态，位置以客户端上报为准，且没有真实客户端上报）。
   想让它走只能改服务端坐标（`snapTo`），由追踪器广播。
2. **`World#rayTraceBlocks` 在假人身上返回 null**（同样的 eyeLocation + direction，对真玩家正常）。
   所以挖掘/使用都用自己的体素射线（`BotActions#lookingAt`，0.05 格步进）。
   另外 `ServerPlayerGameMode#tick()` 在假人身上不会推进挖掘进度，挖掘只能自己计时后调 `destroyBlock`。
3. **NMS 的 `onGround` 在假人身上永远是 false**（没有客户端移动包 → 服务端收不到
   `ServerboundMovePlayerPacket`）。原版 `Player#attack` 的**横扫判定**（`isSweepAttack`）与**暴击判定**
   都读这个字段，所以假人不校正就永远打不出横扫。现在 `NmsBotFactory#syncOnGround` 按脚下方块
   （碰撞箱顶面距脚底 ≤0.1 格）反射写 `Entity#setOnGround`，在攻击前、`moveTo` 每步、
   重放设置（创建 / 重建 / 复活 / 传送）时调用。注意 `Player#tick` 只在旁观/骑乘时重置它，
   生存假人置 true 后不会被 tick 打回 false。
4. **盔甲架要连打两次才碎**：原版 `ArmorStand#hurtServer` 普通伤害分支要求 `mayBuild`，且
   `time - lastHit > 5` 时只广播「打歪」并记 `lastHit`，不扣血 —— 5 tick 内的第二下才 `brokenByPlayer()+kill`。
   所以 `attack` 命中盔甲架时同一周期连调两次 `attack`（动作周期默认 4 tick，天然落在 5 tick 窗口内）。
5. **攻击充能 `attackStrengthTicker` 冻结（假人打不出伤害的真正原因）**：这个字段只在
   `Player#tick()` 里自增（26.3 反编译 `Player.java:303`），而假人没有真实连接、
   `ServerPlayer#tick()` 不会被驱动 → `getAttackCooldown()` 永远是 0.1 左右，原版判定「没蓄满力」：
   伤害只剩两成、没有横扫，用 `>= 0.95` 当攻击门槛时**一次都打不出去**。
   现在 `NmsBotFactory#chargeAttack` 反射把 ticker 置 100（`attack()` 内部会归零），
   攻击节奏改由 `BotActions#attackReadyAt` 按动作周期自计时（默认 3 周期 ≈ 12 tick ≈ 0.6 秒）。

**`biting` 字段**是 private，反射读取时必须 `getDeclaredField` + `setAccessible`
（`Class#getField` 只找 public，找不到会走负缓存 —— 而 `ConcurrentHashMap` 不接受 null 值，
所以字段负缓存有专门的哨兵 `MISSING_FIELD`）。

### 6.5 可编辑背包界面（`BotInventoryManager` + `BotListener`）

右键假人 / `/sfp player invsee` 都打开**可编辑**的 54 格界面（`sfpmenu.bot`）。
不用 `Player#openInventory(假人背包)`：箱子界面拿不到副手槽，且直接暴露原版背包容器会
让点击落到服务端默认逻辑上（可能复制物品）。这里自己维护 `BotInventoryHolder` +
`BotInventorySession`：**展示物只是副本**，每次点击先取消事件、再从假人实时 `PlayerInventory`
读当前值做增量提交，关闭界面**不回写**，所以多个查看者、假人自己拾取/使用都不会互相覆盖。

槽位布局（改动要同步 `BotInventoryManager` 常量与 `messages.yml`）：

| 槽位 | 内容 |
|---|---|
| 0~35 | 假人储物格（`PlayerInventory` 的 0~35，实时双向） |
| 36 / 37 | 「主手」「副手」说明牌（铁剑 / 盾牌图标，只作说明） |
| 44 | 「储物格」说明牌（箱子图标） |
| **45** | **主手实槽** —— 映射到假人当前快捷栏槽（`getHeldItemSlot()`），与 0~8 里那格是同一个物品 |
| **46** | **副手实槽** —— 映射到 `PlayerInventory` 的 40 |
| 49 / 53 | 说明纸 / 关闭按钮 |

- 每 tick 刷新一次（`runTaskTimer` 1 tick），`valid()` 发现查看者掉线/失权限、假人离线/死亡/
  被重建、界面被顶掉，就提示 `bot.inventory-unavailable` 并在下一 tick 关闭。
- 支持：左右键（整叠/折半/放置/交换）、Shift（只在双方 0~35 储物格间移动，主手别名与副手
  **不会**变成第二个搬运目标）、数字键与 F 键（**只作用于上方假人槽位**，交换到查看者快捷栏/副手）。
- 明确拒绝：拖拽、双击收集、创造模式克隆、窗口外丢弃 —— 只提示 `bot.inventory-unsupported`，
  避免复制或误丢；要丢东西先取回自己背包，关界面再丢。
- **盔甲不在编辑器范围内**（假人很少穿甲）；事件全部在主线程处理，`shutdown()` 关掉所有会话。

### 6.6 PAPI 占位符（`BotPlaceholders`）

```
%fakeplayer_total%            假人总数（记录数）
%fakeplayer_online%           已加入服务端的数量
%fakeplayer_creator_<名字>%   某个假人的创建者名
%fakeplayer_actions_<名字>%   某个假人的持续动作（如 攻击|挖掘，没有则为 空）
```
装上 PlaceholderAPI 才注册（`join-classpath: true`）。


## 7. 连锁挖矿（chain 包）

**玩法**：玩家**蹲下**挖掉一块矿 → 同一条矿脉里相连的同种矿一起被挖掉，按方块量扣饱食度。
**只做矿石**，砍树不在此版本范围内（矿石有明确的「矿脉」概念，天然安全；木头没有）。

### 7.1 防误伤：为什么不会误伤建筑

Minecraft 方块**没有「自然生成 / 玩家放置」的原生标记** —— `BlockData` 里只有 `axis` 之类的轴向信息，
读不出「这根木头 / 这块矿石是人摆的还是长出来的」。所以连锁算法必须自己定义边界，
本模块用了三层闸门：

| 闸门 | 做法 | 拦住了什么 |
|---|---|---|
| **只连锁同种方块** | 从起点做 26 邻域 BFS，遇到非目标方块立刻停止扩散，**不跨越** | 矿脉旁边的建筑、装饰方块不会被带着走 |
| **蹲下才触发** | `require.sneak`（默认开） | 站着正常挖矿完全不受影响 |
| **总量上限** | `max-blocks`（默认 32） | 在巨型矿脉里不会一次挖穿几千块，避免掉落爆量与区块加载风暴 |
| **假人不参与** | `require.chain-bots`（默认关） | 假人是工具人，连锁会瞬间吃光矿脉并扣饱食度，对它没意义 |

> 关于「连锁砍树会不会把建筑的木头一起砍掉」：**如果做砍树，这个风险是真实存在的**，
> 而且不能靠「原版有标记」来规避。可行的解法是**树形几何判定**（向上扫同列木头，
> 主干高度 ≥ 阈值且其正上方有树叶才算树）+ **PDC 记录玩家放置的方块**（`BlockPlaceEvent`
> 打标记，连锁时跳过）。两者叠加才够稳。本版本没做砍树，等需要时再上。

### 7.2 触发流程（`ChainListener` + `ChainManager`）

```
BlockBreakEvent（NORMAL 优先级，ignoreCancelled）
  └─ 逐条闸门：模块启用 / 世界在范围内 / 方块在白名单 / 非假人 / 生存模式 / 蹲下 / 手持工具
      └─ collect()：26 邻域 BFS 收集同种矿（上限 max-blocks，不跨未加载区块）
          └─ affordableCount()：按当前饱食度算「最多能连锁几块」
              ├─ 0 块 → 提示「饱食度不足」，只挖玩家亲手挖的第一块
              └─ N 块 → 逐块 breakNaturally() → chargeFood() → 提示
```

**优先级选 NORMAL 而不是 MONITOR，有两个理由**：
1. **要能被取消** —— 连锁的每一块都自己发 `BlockBreakEvent`，领地保护插件（通常 HIGH/MONITOR）
   必须有机会先取消它们，否则连锁等于绕过保护；
2. **要看到最终方块** —— MONITOR 阶段方块可能已被别的逻辑改成空气。

**重入保护**：连锁内部发的 `BlockBreakEvent` 会再次进入本监听器，
用 `ThreadLocal<Boolean>` 标志阻止无限递归。

### 7.3 为什么逐块手动挖，而不是复制掉落物

连锁的每一块都走完整流程：**先发 `BlockBreakEvent` → 再 `setType(AIR)` → 最后按事件结果手动投放掉落物**。
不用 `block.breakNaturally(tool)`（它自己也会丢一遍，叠加成双份掉落）。

这样做是为了拿到三样东西，缺一不可：
- **经验** —— 原版经验来自挖方块事件的结算，手动清空方块拿不到
- **其他插件的监听** —— 领地保护、CoreProtect、统计类插件全靠这个事件，不触发等于绕过保护
- **工具耐久与采集等级** —— 钻石镐挖和普通镐挖的掉落不同，由原版结算路径决定

### 7.4 扣饱食度（`ChainConfig#exhaustionPerBlock`）

- 按**连锁额外挖掉的方块数**收费（玩家亲手挖的第一块走原版规则，不额外收费）
- 默认每块 **0.1 点**（挖 10 块约扣 1 点），累加后从 `Player#setFoodLevel` 扣
- 扣费按**实际连锁掉的数量**结算 —— 被保护插件取消掉的方块不扣钱
- 饱食度掉到 0 时 `stop-when-hungry` 会停止连锁；不足全额时部分生效并提示
- 饱和度按比例跟着降，避免出现「饿度低但饱和度满」的不一致

### 7.5 配置（`chain.yml`）

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | true | 总开关 |
| `require.sneak` | true | 必须蹲下才触发 |
| `require.require-tool` | true | 必须手持镐/斧/锹/锄 |
| `require.survival-only` | true | 创造/冒险模式不触发 |
| `require.chain-bots` | false | 假人挖矿是否也连锁（默认不） |
| `max-blocks` | 32 | 单次连锁总量上限（不是固定半径） |
| `food.exhaustion-per-block` | 0.1 | 每块扣多少饱食度 |
| `food.stop-when-hungry` | true | 扣到 0 就停 |
| `blocks` | `[]`（用内置表） | 参与连锁的 Material 名 |
| `give-experience` | true | 是否给经验 |
| `sound.*` | 开 | 连锁音效（整片一次 / 每块一次） |
| `worlds.whitelist/blacklist` | `[]` | 生效世界范围（黑名单优先） |
| `message.enabled` | true | 是否提示结果 |

**内置默认表**（`ChainDefaults`，共 15 种）：`COAL_ORE` / `DEEPSLATE_COAL_ORE` /
`COPPER_ORE` / `DEEPSLATE_COPPER_ORE` / `IRON_ORE` / `DEEPSLATE_IRON_ORE` /
`GOLD_ORE` / `DEEPSLATE_GOLD_ORE` / `REDSTONE_ORE` / `DEEPSLATE_REDSTONE_ORE` /
`LAPIS_ORE` / `DEEPSLATE_LAPIS_ORE` / `NETHER_GOLD_ORE` / `NETHER_QUARTZ_ORE` / `ANCIENT_DEBRIS`。

不收录钻石 / 青金石 / 绿宝石（单块散布的宝石，不是矿脉），也不收录泥土 / 石头 / 砂砾（地形方块）。

⚠ 表里的名字必须真实存在于 `Material` —— 拼错不抛异常，只会让连锁对该矿**静默失效**。
`/sfp test` 的「连锁挖矿」段会逐个核对（2026-10-18 首次运行时就靠它抓出了
`DEEPSLATE_REDSLONE_ORE` 的拼写错误，正确名是 `DEEPSLATE_REDSTONE_ORE`）。

## 8. 信息显示（display 包）

两块**纯展示**模块，内容全部由配置文件驱动（`display.yml` 的 `tab` / `scoreboard` 两段），
支持 MiniMessage + 内置占位符 + PlaceholderAPI 占位符，按 `refresh-ticks`（默认 20 = 每秒）刷新。

**内置占位符**（`display/DisplayText`，不依赖任何插件）：
`{player}` `{world}` `{x}` `{y}` `{z}` `{online}` `{max}` `{tps}` `{mspt}`
（`{tps}` = 最近 1 分钟 TPS、`{mspt}` = 每 tick 平均耗时，均由插件自己算**当前值**，
比 spark 的 `%spark_tps%`（一次给多个时间窗口）更适合「只看当前」的场景）

**渲染顺序（踩过坑，别改）**：
1. 先替换内置占位符
2. 把模板交给 MiniMessage 解析成 Component —— 模板自身的标签结构因此一定正确
3. 最后在**组件上**用 `Component#replaceText` 做 PAPI 替换，返回值作为纯组件插入

如果反过来（先把 PAPI 替换进字符串、再整体 MiniMessage 解析），像 spark 这类
**返回值自带颜色码（含 `§r`）**的扩展会把外层标签重置掉，`</white></gray>`
就会以字面形式显示出来。PAPI 用**反射**调用（可选依赖，直接引用会在未装 PAPI 时类加载失败），
返回值里的 `§` 色码交给 `LegacyComponentSerializer` 解析。

### 8.1 Tab 列表（`display/TabManager`）

- `display.yml` 的 `tab.header` / `tab.footer` 各是多行列表 → `Audience#sendPlayerListHeaderAndFooter`
  （注意：Paper 的 `Player` 上没有 `setPlayerListHeaderFooter`，那是旧 API）
- 每秒为所有在线玩家刷新一次

### 8.2 计分板（`display/ScoreboardManager`）

- `display.yml` 的 `scoreboard.title` + `scoreboard.lines`（最多 15 行，超出会告警并截断；`""` 表示空行）
- 每位玩家一个**独立** `Scoreboard`（`Bukkit.getScoreboardManager().getNewScoreboard()`），
  不污染服务端主计分板；行内容用 `Score#customName(Component)` 承载（entry 只是内部 key，不显示），
  并用 `Objective#numberFormat(NumberFormat.blank())` 隐藏行尾的分数数字
- 玩家退出时丢弃其计分板实例（`PlayerQuitEvent`）
- ⚠️ 其他插件若也占用右侧计分板会互相覆盖，建议只留一个

### 8.3 音效解析（`util/SoundUtil`）

配置里既支持 Bukkit 枚举名（`ENTITY_ENDERMAN_TELEPORT`）也支持注册名
（`minecraft:entity.enderman.teleport`）。查找走 `Registry.SOUNDS` + 首次构建的名称缓存 ——
**`Sound#valueOf` 已被标记为待删除，不要再用**；同理 `Nameable#setCustomName(String)`、
`Sign#getLine(int)`、`PlayerKickEvent#getReason()` 在 2.6.0 也都换成了 Adventure 版本
（`customName(Component)`、`Sign#line(int)`、`reason()`）。

## 9. 处罚系统（punish 包）

定时**封禁 / 禁言**、**踢出 / 警告**，外加查询与解除。数据存于**独立** `punish.db`
（与 `teleport.db` 完全隔离：独立文件、独立连接，删库互不牵连）。

### 9.1 命令一览

| 命令 | 目标范围 | 时间参数 | 权限 |
|---|---|---|---|
| `/sfpcheck <处罚ID 或 玩家名>` | — | — | `sfpmenu.punish.check` |
| `/sfpwarn <在线玩家> <原因>` | **仅在线** | — | `sfpmenu.punish.warn` |
| `/sfpkick <在线玩家> <原因>` | **仅在线** | — | `sfpmenu.punish.kick` |
| `/sfpban <玩家> <时间> <原因>` | 可离线 | 必填 | `sfpmenu.punish.ban` |
| `/sfpmute <玩家> <时间> <原因>` | 可离线 | 必填 | `sfpmenu.punish.mute` |
| `/sfpunban <玩家名>` | **仅数据库已封禁名单** | — | `sfpmenu.punish.unban` |
| `/sfpunmute <玩家名>` | **仅数据库已禁言名单** | — | `sfpmenu.punish.unmute` |

- **时间写法**：`7d` / `12h` / `1d2h30m` / `90s` / `perm`（永久），由 `util/DurationParser` 解析。
  单位：`w`周 `d`天 `h`时 `m`分 `s`秒，可组合、可乱序。
- **unban/unmute 的目标只来自数据库**（`PunishStore#listActiveNames`），**不使用在线玩家列表** ——
  要解的几乎都是离线玩家。补全目标也是这份名单。
- 目标解析顺序（`PunishManager#resolveTarget`）：在线玩家 → `players` 表 → `Bukkit.getOfflinePlayerIfCached`。
- 命令反馈文案走 `messages.yml` 的 `punish.*` 键；
  **踢下线整屏 / 禁言提示 / 全服广播**这类需要高度自定义的文案放 `punish.yml`（见 §9.5）。

### 9.2 数据结构（三张表，`punish.db`）

```
players            uuid(PK) / name / last_seen
                   → UUID ↔ 最近玩家名映射。处罚时记录，进服时补录。
                     离线目标选择（ban/mute 离线玩家、unban 按名找 UUID）全靠它。

punishments        punishment_id(PK) / player_uuid / player_name / type / reason /
（当前生效）        operator / created_at / expire_at
                   → 一人一类型最多一条（重新处罚会覆盖旧的）。
                     expire_at = -1 表示永久；> 0 为到期毫秒时间戳。
                     只存 BAN / MUTE（有时限的两种）。

punishment_logs    log_id(AUTOINCREMENT) / punishment_id / player_uuid / player_name /
（历史，只增不删）  type / reason / operator / created_at / expire_at / action / action_at
                   → 每次「施加 / 解除 / 到期」各写一行。action 见 PunishAction：
                     PUNISH / UNBAN / UNMUTE / EXPIRE。
                     /sfpcheck 查的就是这张表（按 punishment_id 或玩家名）。
```

- **状态值「无处罚」**：需求里的「未获得处罚」是**状态**（该玩家当前没有任何生效处罚），
  不是处罚类型。枚举 `PunishmentType` 只有 `BAN/MUTE/WARN/KICK`，其中 `isTimed()` 为 true 的
  （BAN/MUTE）才写 `punishments` 表；WARN/KICK 只写历史表。
- 表结构与迁移完全照搬 `teleport/Database` 的两步法（`SCHEMA_VERSION` + `PRAGMA user_version`
  + `migrate(from)` 追加 `if (from < N)`），当前 v1。
- 数据库不可用时静默降级：命令统一回「该功能当前未启用」，不抛异常。

### 9.3 数据库位置（可自定义 / 可接入现有库）

`punish.yml` 的 `database.file` 支持三种写法，由 `util/DatabaseFileResolver#resolve` 统一解析
（传送库 `teleport.db`、界面偏好 `settings.db` 走同一套规则）：

| 写法 | 示例 | 解析结果 |
|---|---|---|
| 纯文件名 | `punish.db` | `plugins/SFP-main/data/punish.db`（**默认**；旧版根目录同名文件首次启动自动迁入） |
| 带目录相对路径 | `data/punish.db` | `plugins/SFP-main/data/punish.db`（**保持旧语义**，不再额外加 data/） |
| 绝对路径 | `D:/mc/shared/punish.db` | 直接用该文件 |

- **绝对路径**用于把数据放到插件目录之外（挂载盘、跨服共享目录），或接入现成的处罚库。
- **父目录不存在会自动 `mkdirs()`**，避免 SQLite 报 `path to database does not exist`。
- 配置值为空 / 纯空白时回落到默认名 `punish.db`（`trim()` 后判空），即 `data/punish.db`。
- **旧版迁移**：只对「纯文件名」做迁移 —— 根目录 `punish.db` 搬到 `data/punish.db`，
  `-wal` / `-shm` 必须一起搬（WAL 里可能有未 checkpoint 的事务）。
  若新旧两处**主库都存在**，无法判断哪份更新，**拒绝启动并打 severe 日志**，
  绝不静默挑一个（同理：目标主库不存在但 data/ 里已有 `-wal`/`-shm` 也拒绝）。
  迁移中途失败会回滚已搬的 `-wal`/`-shm` 并抛异常中止启动，避免创建空库覆盖真实数据。
- **接入已有库的前提是表结构兼容**（需要 `players` / `punishments` / `punishment_logs` 三张表，
  可用 `/sfp test` 校验）。若 `PRAGMA user_version` 高于本插件版本，`initSchema()` 只记一条
  warning、**不做任何结构变更**，不会破坏原库。别家插件（LiteBans / AdvancedBan）表结构不同，
  无法直接读取，需先用外部工具转换。
- 启动日志与 `/sfp test` 都会打印**数据库的绝对路径**，便于确认接的是哪个库。
- **群组服共用同一个处罚库**：各子服把 `database.file` 指向同一绝对路径（如挂载盘位置），
  配合 `config.yml` 的 `mode: punish-only`（见 §0.0）即可让子服只跑处罚、不跑菜单与传送。
  ⚠ SQLite 在**跨机共享**（NFS/SMB）下并发写有损坏风险，多服同库建议放在**同一台机器**的
  共享目录；跨机场景请改用支持并发的数据库方案。

### 9.4 惰性到期判定（核心设计）

**不轮询、不定时扫描**。`expire_at` 只在下面这些「必要时机」被检查，
过期即删除生效记录 + 补一条 `EXPIRE` 历史日志：

1. **玩家进服**（`PunishListener#onJoin`）—— 查 BAN（未过期则踢出）、查 MUTE（提示）；
2. **玩家发言**（`PunishListener#onChat`）—— 查 MUTE；
3. **`/sfpcheck` 查询**（`PunishManager#activePunishments`）；
4. **解除时**（`PunishManager#revoke`）；
5. **插件启动时**清理一次全库过期记录（`PunishStore#purgeExpired`）。

**禁言判定在异步线程**（`AsyncChatEvent`），因此 `PunishManager` 维护一份
**内存缓存** `activeCache`（`UUID → 类型 → Punishment`，`ConcurrentHashMap`）。缓存：
- 启动时 `reloadCache()` 从库重建；
- 每次 `apply/save → cachePut`、`revoke/expire → cacheRemove` 同步更新；
- 聊天拦截走 `PunishManager#cachedMute`（**纯内存，不碰数据库连接**），只做内存级到期判定。

> 库始终是唯一真相来源，缓存只是让异步判定安全的副本。

### 9.5 文案（punish.yml）

**本模块全部面向玩家的文案都放 `punish.yml`**（不回退 messages.yml）——
「自定义踢人 / 封禁消息」是核心诉求，集中一处最好改。可用占位符：
`{player}` `{operator}` `{reason}` `{type}` `{id}` `{expire}`（剩余）`{duration}`（总时长）。
`{reason}` 会**原样作为 MiniMessage 解析**，所以处罚原因里可以直接写颜色标签。

| 键 | 用途 |
|---|---|
| `messages.kick-screen-ban` | 被封禁者登录时的整屏文字（踢下线） |
| `messages.kick-screen-kick` | 被 `/sfpkick` 踢出时的整屏文字 |
| `messages.mute-notice` | 被禁言者发言时收到的提示 |
| `sounds.punish` / `sounds.revoke` | 执行处罚 / 解除时**给执行者**播放的音效（2.11.0 起真正生效；控制台执行时跳过） |
| `broadcast.enabled` / `broadcast.format` | 执行封禁/禁言时是否全服公告、公告格式 |
| `kick-delay-ticks` | 踢出前的延迟 tick 数（默认 1；跨服环境必须 ≥1，见 §9.7） |

`messages.yml` 里的 `punish.*` 是命令执行后的**简短反馈**（已 / 已警告 XX，编号 YYY）——
2.11.0 起**所有**反馈都在这里，包括用法提示、时间格式错误、查不到玩家、`/sfpcheck` 的表头表尾
与单条历史记录行（`punish.log-line`）；`PunishCommand` 里不再有硬编码中文。

> `messages.join-warn-notice`（进服提示「有未读处罚记录」）**已在 2.11.0 删除**：
> 警告（WARN）按设计不写生效表、也没有「已读」状态，无法判定「未读」，该键从来没有调用点。
> `PunishConfig` 里的 `getJoinWarnNotice()` / `joinWarnNotice(Map)` 一并移除。

### 9.6 处罚 ID

6 位**字母数字混合**（字符集去掉了易混淆的 `0/O/1/I/l`），生成时用 `PunishStore#idExists` 查重，
碰撞则重试（`SecureRandom`）。

### 9.7 踢出时机与跨服代理（2.10.1 修复）

**所有踢人动作一律延后 `punish.yml` 的 `kick-delay-ticks`（默认 1）个 tick 再真正断开**，
不允许在事件里立刻 `kick()`。涉及三处：`PunishListener#scheduleBanKick`（进服拦截封禁）、
`PunishManager#apply`（封禁在线玩家）、`PunishManager#kick`（`/sfpkick`）。

**为什么需要延迟**（跨服环境的必踩坑）：

1. 玩家通过 Velocity 连接子服时，在「登录 / 配置阶段」尚未站稳；
2. 此时若子服直接把玩家踢掉，Velocity 的 `KickedFromServerEvent` 会判定
   `kickedDuringServerConnect() == true`，**把这次踢出当成一次连接中断**，
   转而把玩家按 `velocity.toml` 的 `try` 列表**重投到下一个子服**
   （典型链路：`vanilla` 封禁踢出 → 代理转投 `lobby` → `lobby` 又踢 → 循环）；
3. 客户端**还没收到** `ClientboundGameEventPacket` 的 `LEVEL_CHUNKS_LOAD_START` 包，
   于是加载界面卡在「**加载地形中**」不动；
4. 而服务端侧此时连接已经关闭，日志显示「玩家已断开」——
   于是表现为「**玩家卡在加载地形中，服务端却显示已断开连接**」，且玩家看不到踢出原因。

**延迟一 tick 的作用**：让本次登录先在子服内部走完，Velocity 便把它当作一次
**正常踢出**，直接断开玩家并展示 `kick-screen-ban`，「加载地形中」页随即被踢出原因覆盖。
单机（无代理）环境下 1 tick 与「立即踢」在观感上无差别，因此默认值就是 1。

> **诊断依据**：Velocity `KickedFromServerEvent` 的 `kickedDuringServerConnect()` 语义、
> `RedirectPlayer` 结果类型，以及 `try` 列表「既用于初次连接、也用于被踢后的重投」这一官方说明。
> ViaVersion 侧同源问题（#3630 / #3695）根因一致：区块加载包与 join/respawn 捆绑发送，
> 客户端重进配置阶段时该捆绑会失效，这就是「加载地形中」卡住的由来。
>
> 与 `failover-on-unexpected-server-disconnect` 无关 —— 该开关只管「非预期断连」，
> **插件主动 kick 不受其影响**，所以不能靠代理配置绕开，必须在插件侧调整踢出时机。

---

## 10. 数据与持久化

所有**数据文件一律放在插件目录的 `data/` 子目录**（`plugins/SFP-main/data/`），
与配置文件分开，便于整目录备份 / 迁移 / 加忽略规则。路径由 `util/DatabaseFileResolver` 统一解析，
旧版放在插件根目录的同名文件会在首次启动时**自动迁入 `data/`**（规则见 §9.3：
纯文件名 → `data/`；带目录相对路径与绝对路径保持原样；新旧并存拒绝启动）。

| 存储 | 文件（相对 `plugins/SFP-main/`） | 存放内容 | 代码 |
|---|---|---|---|
| SQLite | `data/teleport.db` | `homes` / `warps` / `last_locations` | `Database` + 三个 Store |
| SQLite | `data/punish.db` | `players` / `punishments` / `punishment_logs` | `PunishDatabase` + `PunishStore` |
| SQLite | `data/settings.db` | `ui_preferences`（界面样式偏好）、`tpa_ui_preferences`（传送回应界面偏好） | `UiPreferenceStore` |
| SQLite | `data/sweep.db` | 自动扫地开关、例外区块 | `SweepExceptionStore` |
| YAML | `data/trashbin-data.yml` | 垃圾桶物品列表 | `TrashBinManager` |
| YAML | `data/bots.yml` | 假人记录：名字 / UUID / 创建者 / 位置 / **设置** / **持续动作**，外加 `player-configs` 个人默认配置（用于重启重建） | `BotManager` |
| 内存 | — | tpa 请求、传送冷却、延迟传送队列、在座玩家、生效处罚缓存 | 各 Manager |

### 10.1 传送数据库分层

```
TeleportManager
  ├─ Database      连接（单连接 + synchronized getConnection/commit/close）、PRAGMA(WAL/外键)、
  │                表结构初始化与迁移
  ├─ HomeStore     homes：save / get / delete / listNames / count
  ├─ WarpStore     warps：save / get / delete / listNames
  └─ BackStore     last_locations：save / get
        └─ LocationRow（包内工具）：结果集一行 → StoredLocation
```

- `StoredLocation` 是 record（world/x/y/z/yaw/pitch），**存世界名而不是 World 引用**，
  跨重启安全；世界被删时 `toLocation()` 返回 null，调用方提示 `teleport.world-missing`。
- 全部走 `PreparedStatement`，无注入面。
- **数据库不可用时静默降级**：查询返回 null/空，写入返回 false，
  上层用 `TeleportManager#isStorageAvailable()` 判断并提示玩家。
- 主键：`homes(player_uuid, home_name)` / `warps(warp_name)` / `last_locations(player_uuid)`，
  现有查询都被索引覆盖。
- **单连接 + 同步访问，只允许在主线程使用**：`getConnection()` 本身同步，但返回的连接在锁外使用。
  当前全部调用都在主线程（命令 / 事件 / GUI / 同步调度任务）。
  **若以后改成异步保存，必须给数据访问层补同步。**

### 10.2 表结构迁移（改表结构必看）

`Database` 里有 `SCHEMA_VERSION`，存在 SQLite 的 `PRAGMA user_version`。
老库与新库的 `user_version` 都是 0，所以从 0 逐级判断能同时覆盖两种情况。

**改表结构的两步**：
1. `SCHEMA_VERSION` +1
2. 在 `migrate(int from)` 里追加 `if (from < N) { ... }` 分支

可用工具：`execute(...)`、`tableExists()`、`columnsOf()`（`PRAGMA table_info`）、
`addColumnIfMissing(表, 列, 定义)`（幂等加列）。
每一步都必须能重复执行（建表用 `IF NOT EXISTS`、加列用 `addColumnIfMissing`）。
库版本高于代码时**只告警、不做任何改动**。

SQLite 的 `ALTER TABLE` 限制：只能加列 / 改列名 / 删列（3.35+），
**不能**加「无默认值的 NOT NULL 列」，也不能改列类型或主键。

### 10.3 备份建议

正常停服后 `-wal` 会自动合并回主库，**直接拷整个 `plugins/SFP-main/data/` 目录即可**；
开服状态下想单拷传送库，先执行 `/sfp db checkpoint` 把 WAL 并回 `data/teleport.db`，
再连同 `data/` 下的 `-wal` / `-shm` 一起拷（或干脆整目录拷）。

---

## 11. 改动时的注意事项（踩坑清单）

1. **改 `resources/*.yml` 里已存在键的默认值 → 必须手工同步服务器上的同名配置**。
   `mergeDefaults()` 只补缺失键、不覆盖已有键。**删按钮/删键也一样要手工删线上的**。
   （新增键会自动补，但注意：`mergeDefaults()` 的 `save()` 只写磁盘、不改内存，
   所以它在 `save()` 之后重新 `loadConfiguration` 了一次 —— 否则首次升级时新键的叶子字段
   在内存里读不到，比如新按钮 `slot` 读成 -1。）
2. **配置类的 `onLoaded()` 不得依赖子类字段初始化器**：`AbstractConfig` 的构造器就会调用
   `load()` → `onLoaded()`，而 Java 是在 `super(...)` 返回**之后**才执行子类字段初始化器 ——
   那一刻集合字段还是 null（NPE），而且初始化器随后还会把填好的内容覆盖成空集合。
   集合类字段一律在 `onLoaded()` 内部 `new`。（2.0.3 修的启动崩溃就是这个）
3. **改表结构必须走 §10.2 的两步**，否则老库不会升级。
4. **新增菜单按钮**：只需改 `menu.yml`（`buttons.<id>` + 必要时 `bind.*-button`）。
   若要内部动作，才需要动 `MenuHolder`（加常量）+ `MenuManager`（`resolveAction` /
   `isFeatureEnabled`）+ `MenuListener`（分派）。`command:` 留空且不在 `bind.*` 里 = 点了没反应
   （`/sfp test` 会报出来；有 lore 的纯说明按钮会被识别为「仅作说明展示」）。
   改槽位别忘了同步 `menu.yml` 顶部那张 ASCII 布局图。
5. **子界面导航行布局改动要同步两处**：`TrashBinHolder` 与 `TeleportListHolder` /
   `TpaTargetHolder` 各有一份 `SLOT_*` 常量。
6. **开关类改动（enabled）无法热重载**：监听器注册不可逆，必须重启服务器；
   `/sfp reload` 只能重载参数。
7. **换 jar 前先确认服务端没在跑**（`jps -l | grep server.jar`）：
   运行中的服务端会锁住旧 jar（`mv` 报 `Device or resource busy`），
   而 `cp` 可能已经成功 → plugins 下出现两个同名插件的 jar，重启即冲突。
   正确顺序：停服 → 旧 jar 改名 `xxx.jar.bak`（Paper 只扫 `*.jar`）→ 拷新 jar → 启动。
8. **NMS 反射（假人）随 Minecraft 版本可能失效**：签名先用服务端 jar 反射核对
   （`versions/<ver>/paper-*.jar` + `libraries/**`），失败时看日志里的「假人重建失败」异常。
   假人的动作走 `ServerPlayer` / `ServerPlayerGameMode` 的直接方法，不依赖发包；
   26.3 的两个实测坑见 6.4。
9. `paper-plugin.yml` 的 `api-version` 只有**大版本**（如 `'26.1'`），
   改动它的语义是「放弃对更老版本的兼容」。

---

## 12. 已知未生效 / 预留的配置项

以下五项曾在代码中「定义了但没人用」，**2.5.0 已全部删除**（配置键 + 代码访问器 + 引用一并清理）：

| 配置项 | 原状态 |
|---|---|
| `teleport.allow-cross-world` | 内核条件被六个传 `true` 的调用点短路，永不生效（删除后 `teleport()` 签名也去掉了 `crossWorldAllowed` 参数） |
| `clean.worlds.only-loaded-chunks` | 无引用 |
| `trashbin.global` | 无引用；按玩家隔离未实现 |
| `config.yml` 的 `currency-name` | `GlobalConfig#getCurrencyName()` 无调用点，扫地广播的 `{currency}` 占位符也是死代码 |

**2.11.0 又清掉两个**（同样是「配置键 + 访问器」一起删）：

| 配置项 | 原状态 |
|---|---|
| `clean.skip-named-items` | 想表达「改名物品别扫」，但语义与文档相反（实际是留在地上），见 §3 |
| `punish.messages.join-warn-notice` | 毫无调用点，且 WARN 不写生效表、无「已读」状态，无法判定「未读」，见 §9.5 |

另有 2 个键是「写了但没接上」，**2.11.0 修好而不是删掉**：

| 配置项 | 原状态 → 现状 |
|---|---|
| `teleport.particle-interval-ticks` | 读进来后从未使用（间隔写死 20 tick）→ 现在真正控制粒子间隔 |
| `menu.yml` 的 `permission-message` | `MenuConfig#getPermissionMessage()` 无调用点 → 现在是无权限时的提示文案（留空回退 `common.no-permission`） |

> 上面这些死键如果还留在某台服务器的配置文件里，`/sfp test` 会逐条报出来（提示删除）——
> 排查依据就是 `SelfTest` 里那段按版本累积的失效键清单。

## 13. 新增功能检查清单

- [ ] `resources/` 加 `xxx.yml`（首字段 `enabled`，注释写清每个键）
- [ ] `config/module/XxxConfig.java` 继承 `AbstractConfig`，`onLoaded()` 里读值
      （集合字段务必在 `onLoaded()` 内 `new`）
- [ ] `ConfigManager`：加字段 + 访问器 + `loadAll()` 实例化 + `describeState()` 补一行
- [ ] 功能类构造器接收 `ConfigManager`，内部取 `configManager.xxx()`
- [ ] `SfpMain`：加字段、`getXxxManager()`、`setupXxx()`（按 `isEnabled()` 决定是否注册）
- [ ] 文案写进 `messages.yml`，代码用 `messages.raw/send`（不要硬编码中文到代码里，
      诊断类输出除外）
- [ ] 若要命令：写 `XxxCommand implements BasicCommand`，在 `registerCommands()` 里注册，
      权限节点补进 `paper-plugin.yml`
- [ ] 若要界面：新建 Holder + Gui，点击统一挂在对应 Listener；导航行沿用 §2.7 的槽位约定
- [ ] 数据持久化：能进 SQLite 的走 db 包（注意 §10.2 的迁移步骤）；
      小数据用 YAML；**纯会话态一律放内存**；异步线程要读的数据放内存缓存（参考 §9.4）
- [ ] 若绑定菜单按钮：`menu.yml` 加按钮 + `bind.*-button`，并按第 4 条改三个类
- [ ] **把本功能写进 features.md**（入口 / 调用链 / 流程 / 配置键 / 权限 / 数据文件）
- [ ] 构建 + 真机冒烟（`/sfp test` 应全绿；GUI 与交互需真人验证）
- [ ] **按钮 lore 里若写死了其它配置的数值**（冷却、材料数量、关键字），把比对规则加进
      `SelfTest#checkLoreDrift` / `#checkElytraExchange` —— 否则改配置后按钮上的说明会悄悄过期
- [ ] 版本号：Bug 修复 `z+1`、小内容更新 `y+1`，没有大更新不要动 `x`
