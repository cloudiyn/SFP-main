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
- 当前版本：**2.11.0**（2.11.0 是一轮「自检修复」：传送粒子间隔 `particle-interval-ticks` 与延迟传送预加载真正生效、`/home` `/warp` 无参按界面偏好分流、处罚音效恢复播放、主菜单按钮上移一行、dialogUI 补齐说明按钮与全部文案外置、代码里的中文全部搬进 messages.yml，并删掉 `clean.skip-named-items` 与 `punish.messages.join-warn-notice` 两个死键；2.10.2 修复鞘翅兑换提示把 MiniMessage 标签当字面量打印的问题，价格改为 64 钻石或 8 下界残骸二选一；2.10.1 修复跨服被封禁玩家卡在「加载地形中」；2.10.0 主菜单新增「鞘翅兑换」按钮；2.9.1 新增「仅处罚」工作模式；2.9.0 菜单新增「随机传送」按钮；2.8.0 处罚数据库位置可配置；2.7.0 新增处罚系统）

---

## 0. 全局

### 0.0 工作模式（config.yml 的 `mode`）

| 值 | 说明 |
|---|---|
| `full` | 完整模式（默认）：装配全部功能模块 |
| `punish-only` | **仅处罚模式**：只启动处罚系统 + `/sfp` 管理命令 |

`punish-only` 专为**群组服**设计 —— 子服只负责执行处罚命令，不需要重复跑菜单与传送。

该模式下**不装配**的模块：菜单 / 菜单钟 / 自动扫地 / 垃圾桶 / 椅子 / 传送（含 tpa / rtp）/
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
> （如挂载盘位置），详见 §8.3。

### 0.1 启动流程（`SfpMain#onEnable`）

```
1) configManager = new ConfigManager(this)
     └─ 读 config.yml（GlobalConfig，走 Bukkit 的 getConfig）+ messages.yml（Messages）
     └─ loadAll()：依次 new 出 Menu/Clean/TrashBin/Chair/Teleport/Bot 六个 Config
        （每个 Config 构造时就 load() → onLoaded()，把 yml 的值缓存成字段）
2) 按开关装配模块（顺序有意义）：
     ★ punish-only 模式下跳过以下全部 setup，只留 setupPunish()
     setupTrashBin()     垃圾桶必须先于扫地（扫地要把物品塞进垃圾桶）
     setupFloorClean()   扫地：new FloorCleanManager（内部 runTaskTimer 每秒 tick）
     setupChair()        椅子：new ChairManager + 注册 ChairListener
     setupTeleport()     传送：new TeleportManager（内含 SQLite）→ new TpaManager
                         → 注册 TeleportListener / TeleportGuiListener / TpaListener
     setupBot()          假人：new BotManager，并 runTask 延后一 tick 执行 restoreOnStart()
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
| `trashbin.yml` | `TrashBinConfig` | 垃圾桶 |
| `chair.yml` | `ChairConfig` | 椅子 |
| `teleport.yml` | `TeleportConfig` | 传送（back/home/warp/tpa/rtp + 数据库） |
| `bot.yml` | `BotConfig` | 假人 |
| `tab.yml` | `TabConfig` | Tab 列表头部/底部（display 包） |
| `scoreboard.yml` | `ScoreboardConfig` | 计分板（display 包） |
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
`warp`、`setwarp`、`delwarp`、`warps`、`tpa`、`tpahere`、`tpaccept`、`tpdeny`、`tpaui`、`bot`、
`menuui`、`sfp`、`sfpcheck`、`sfpwarn`、`sfpkick`、`sfpban`、`sfpmute`、`sfpunban`、`sfpunmute`。

### 0.4 权限节点（`paper-plugin.yml` 的 `permissions` 段）

| 节点 | 默认 | 用于 |
|---|---|---|
| `sfpmenu.player` | true | 打开菜单、`/trashbin` |
| `sfpmenu.teleport` | true | `/back` `/rtp` `/home` `/warp` `/tpa` 等全部传送命令 |
| `sfpmenu.home.bypass-limit` | false | 绕过家数量上限 |
| `sfpmenu.warp.set` | op | `/setwarp`（也可在 teleport.yml 改空=人人可建） |
| `sfpmenu.warp.delete` | op | `/delwarp` |
| `sfpmenu.admin` | op | `/sfp`（reload / status / db / test） |
| `sfpmenu.bot` | op | `/bot` |
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
  （随机传送冷却、椅子关键字）—— 见 §1.0 末尾的说明。

**2.11.0 补齐的自检项**（此前这些「只能靠真人踩一次」）：

| 检查 | 说明 |
|---|---|
| 菜单槽位冲突 | 两个可见按钮抢同一格时 `slotActions` 是 Map，**后一个会静默覆盖前一个** → 报 [错误]；按钮占用 `player-head.slot` 会盖掉顶栏头像 → 报 [注意] |
| 新增文案键缺失 | 与「关键键」分开查：`mergeDefaults()` 不覆盖老 `messages.yml`，新键（`RECENTLY_ADDED_MESSAGE_KEYS`，目前是 2.11.0 的 `dialogui.*` / `punish.*` / `bot.usage-coords`）缺失时提示手工补键。**必须查磁盘文件**：`Messages#has()` 在 2.11.0 改成只看玩家磁盘上的文件，否则 jar 内置默认文件一带该键就永远返回 true、检查形同虚设 |
| 失效残留键 | 老配置里若还留着 `clean.yml` 的 `skip-named-items`、`punish.yml` 的 `messages.join-warn-notice` → 提示「已失效、建议删除」 |
| 扫地 × 垃圾桶冲突 | `to-trashbin: true` 但 `trashbin.enabled: false` = 清扫物直接销毁且不可取回 → 报 [注意] |
| 权限节点声明 | 代码里 `hasPermission()` 用到的 14 个 `sfpmenu.*` 是否都在 `paper-plugin.yml` 声明（用 `PluginMeta#getPermissions()` 读）→ 缺了报 [错误]（非 OP 玩家会永远没权限） |
| 假人记录体检 | `bots.yml` 里假人所在世界是否已加载（不加载则下次启动重建一直失败）；`restore-on-start: false` 也提示一次 |

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
| `chair` | 25 | OAK_SIGN | 无绑定（**纯说明按钮**，靠 lore 介绍告示牌椅子） |
| `elytra` | 28 | ELYTRA | `actelytra` → 消耗 64 钻石**或** 8 下界残骸兑换鞘翅（见 §1.0） |
| `ui-toggle` | 34 | BOOK | `acttoggleui` → 切换界面样式（dialogUI / 箱子） |

> **布局（2.11.0 调整）**：5 行的内容行是 1~3（第 0/4 行、第 0/8 列是边框）——
> 行 1 = back/home/warp/trashbin，行 2 = rtp/dominion/tpa/chair，行 3 = elytra/ui-toggle。
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

- **说明性按钮（没绑动作、只有 lore 的，如 `chair`）在两种界面里都会出现**：
  箱子 UI 里它占一个格、点了没反应；dialogUI 里给 `customClick` 挂一个空回调，效果一致
  （2.11.0 修：此前 `activeButtons` 会把它滤掉，导致 dialogUI 少一个按钮）。
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
> 椅子说明按钮 lore 的关键字 vs `chair.yml` 的 `sign.left/right-keyword`。
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

**入口**：无命令，纯定时任务。`FloorCleanManager` 构造时启动 `runTaskTimer(20L, 20L)`（每秒 tick）。

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

## 5. 椅子（chair 包）

**入口**：玩家在「左右各一块木质告示牌、第一行分别是配置关键字」的方块上右键。

**椅子判定**（`ChairManager`）：
```
isChairBlock(block) → getChairFacingYaw(block) != null
  findLeftSignFace：四个水平方向找相邻方块
     ├─ 是允许木种的告示牌（材质以 allowed-woods 里某个前缀开头，且以 _SIGN/_WALL_SIGN/
     │   _HANGING_SIGN/_WALL_HANGING_SIGN 结尾）
     ├─ 第一行匹配 left-keyword
     └─ 对面方块是同类告示牌且第一行匹配 right-keyword
  → 由左侧告示牌朝向推出玩家应朝的方向（getFacingFromLeft + getYawFromFacing）
```

**坐下**（`ChairListener#onPlayerInteract`，仅主手 RIGHT_CLICK_BLOCK）：
1. 已坐着且点的是**当前座位**且 `stand-on.reclick` → 起身
2. `isChairBlock` 判定通过：
   - `isBlockInUse` → 提示 `chair.occupied` 并取消事件
   - 否则取消事件（阻止放置/交互）→ `sitPlayer()`

`sitPlayer()`：按方块实际高度算坐姿（`seat-height-offset`）→ 生成**隐形小盔甲架**
（`setGravity(false) / setVisible(false) / setInvulnerable(true) / setCustomName("stf-chair")`）
→ `addPassenger(player)` → 记入 `sittingPlayers` / `chairBlocks` → 提示 `chair.sit`。

**起身**（`standPlayer()` 移除盔甲架并清理两张表）触发点：

| 触发 | 事件 | 配置 |
|---|---|---|
| 潜行 | `EntityDismountEvent`（潜行会自动从乘客位下来） | — （无条件） |
| 再次右键同一座位 | `PlayerInteractEvent` | `stand-on.reclick` |
| 破坏椅子或相邻方块 | `BlockBreakEvent`（椅子方块或其相邻 1 格） | `stand-on.break` |
| 传送离开 | `PlayerTeleportEvent` | `stand-on.teleport` |
| 退出游戏 | `PlayerQuitEvent` | — |

另外 `onPlayerKick` 会**取消**「flying / 飞行 / moving too fast / 移动过快」这几种踢出，
避免原版反作弊把坐在盔甲架上的玩家误踢。

**插件卸载（`SfpMain#onDisable` → `ChairManager#shutdown`）**：把所有还在座上的玩家扶起来、
移除盔甲架、清空两张表。2.11.0 补：此前没有这一步，玩家坐着时重载/禁用插件会留下
**孤儿盔甲架**（映射随实例丢弃，实体再也回收不掉，只能重启服务端）。

---

## 6. 假人（bot 包）

**入口**：`/bot create|remove|list|removeall`（`sfpmenu.bot`，默认 op）。

**假人是真正的玩家实体**（NMS `ServerPlayer`），不是盔甲架之类的近似物：
它会出现在在线玩家列表里（`/list` 可见）、计入玩家数，并**天然保持周围区块加载**
（实体本身不会保区块，只有玩家或区块 ticket 才会）。

`NmsBotFactory#spawn`（全反射，Paper 无公开 API）：
```
ServerPlayer(server, level, GameProfile(uuid,name), ClientInformation.createDefault())
Connection(PacketFlow.SERVERBOUND)          ← 本地假连接
  └─ Connection.channel（public 字段）= netty EmbeddedChannel    ← 见下
ServerGamePacketListenerImpl(server, connection, player, cookie)
CommonListenerCookie.createInitial(profile, false)
ServerPlayer.connection（public 字段）= listener
PlayerList#placeNewPlayer(connection, player, cookie)            ← 服务端自己的加入流程
```
- `EmbeddedChannel` 是「吞包黑洞」：`placeNewPlayer` 会往连接写登录包，没有 channel 会 NPE。
  它没人消费的 outbound 队列由 `BotManager` 的定时任务（每 60 秒）清空，避免堆积。
- 皮肤：从创建者 `CraftPlayer#getProfile()` 复制 `textures` 属性；`bot.yml` 的 `skin-source`
  可改为指定玩家名或 `none`（默认皮肤）。
- UUID 由名字派生（`nameUUIDFromBytes("StarfallBot:" + 名字小写)`），所以**名字即身份**，重名直接拒绝。
- 名字规则 `[A-Za-z0-9_]{1,16}`；`max-per-player` 限制每位玩家的数量（0=不限，控制台不计入）。

`BotManager`：
- 记录写入 `bots.yml`（name/uuid/owner/world/x/y/z/yaw/pitch）
- `setupBot()` 里 **`runTask` 延后一 tick** 调 `restoreOnStart()` 重建（避开插件启用栈，
  此时世界已加载完毕）；单个失败只记日志
- `shutdown()` 停掉清理任务；`saveData()` 在创建/删除/禁用时写盘

**移除**：反射调 `PlayerList#remove(ServerPlayer)`，再 `Player#remove()` 兜底。
**不能用 `Bukkit#kick()`** —— 它依赖向客户端发断开包，假连接发不出去，实测移除不掉。

---

## 7. 信息显示（display 包）

两块**纯展示**模块，内容全部由配置文件驱动（`tab.yml` / `scoreboard.yml`），
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

### 7.1 Tab 列表（`display/TabManager`）

- `tab.yml` 的 `header` / `footer` 各是多行列表 → `Audience#sendPlayerListHeaderAndFooter`
  （注意：Paper 的 `Player` 上没有 `setPlayerListHeaderFooter`，那是旧 API）
- 每秒为所有在线玩家刷新一次

### 7.2 计分板（`display/ScoreboardManager`）

- `scoreboard.yml` 的 `title` + `lines`（最多 15 行，超出会告警并截断；`""` 表示空行）
- 每位玩家一个**独立** `Scoreboard`（`Bukkit.getScoreboardManager().getNewScoreboard()`），
  不污染服务端主计分板；行内容用 `Score#customName(Component)` 承载（entry 只是内部 key，不显示），
  并用 `Objective#numberFormat(NumberFormat.blank())` 隐藏行尾的分数数字
- 玩家退出时丢弃其计分板实例（`PlayerQuitEvent`）
- ⚠️ 其他插件若也占用右侧计分板会互相覆盖，建议只留一个

### 7.3 音效解析（`util/SoundUtil`）

配置里既支持 Bukkit 枚举名（`ENTITY_ENDERMAN_TELEPORT`）也支持注册名
（`minecraft:entity.enderman.teleport`）。查找走 `Registry.SOUNDS` + 首次构建的名称缓存 ——
**`Sound#valueOf` 已被标记为待删除，不要再用**；同理 `Nameable#setCustomName(String)`、
`Sign#getLine(int)`、`PlayerKickEvent#getReason()` 在 2.6.0 也都换成了 Adventure 版本
（`customName(Component)`、`Sign#line(int)`、`reason()`）。

## 8. 处罚系统（punish 包）

定时**封禁 / 禁言**、**踢出 / 警告**，外加查询与解除。数据存于**独立** `punish.db`
（与 `teleport.db` 完全隔离：独立文件、独立连接，删库互不牵连）。

### 8.1 命令一览

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
  **踢下线整屏 / 禁言提示 / 全服广播**这类需要高度自定义的文案放 `punish.yml`（见 §8.4）。

### 8.2 数据结构（三张表，`punish.db`）

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

### 8.3 数据库位置（可自定义 / 可接入现有库）

`punish.yml` 的 `database.file` 支持三种写法，由 `PunishDatabase#resolveDbFile` 解析：

| 写法 | 示例 | 解析结果 |
|---|---|---|
| 文件名 | `punish.db` | `plugins/SFP-main/punish.db`（默认，与旧行为一致） |
| 相对路径 | `data/punish.db` | `plugins/SFP-main/data/punish.db` |
| 绝对路径 | `D:/mc/shared/punish.db` | 直接用该文件 |

- **绝对路径**用于把数据放到插件目录之外（挂载盘、跨服共享目录），或接入现成的处罚库。
- **父目录不存在会自动 `mkdirs()`**，避免 SQLite 报 `path to database does not exist`。
- 配置值为空 / 纯空白时回落到 `punish.db`（`trim()` 后判空）。
- **接入已有库的前提是表结构兼容**（需要 `players` / `punishments` / `punishment_logs` 三张表，
  可用 `/sfp test` 校验）。若 `PRAGMA user_version` 高于本插件版本，`initSchema()` 只记一条
  warning、**不做任何结构变更**，不会破坏原库。别家插件（LiteBans / AdvancedBan）表结构不同，
  无法直接读取，需先用外部工具转换。
- 启动日志与 `/sfp test` 都会打印**数据库的绝对路径**，便于确认接的是哪个库。
- **群组服共用同一个处罚库**：各子服把 `database.file` 指向同一绝对路径（如挂载盘位置），
  配合 `config.yml` 的 `mode: punish-only`（见 §0.0）即可让子服只跑处罚、不跑菜单与传送。
  ⚠ SQLite 在**跨机共享**（NFS/SMB）下并发写有损坏风险，多服同库建议放在**同一台机器**的
  共享目录；跨机场景请改用支持并发的数据库方案。

### 8.4 惰性到期判定（核心设计）

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

### 8.5 文案（punish.yml）

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
| `kick-delay-ticks` | 踢出前的延迟 tick 数（默认 1；跨服环境必须 ≥1，见 §8.7） |

`messages.yml` 里的 `punish.*` 是命令执行后的**简短反馈**（已 / 已警告 XX，编号 YYY）——
2.11.0 起**所有**反馈都在这里，包括用法提示、时间格式错误、查不到玩家、`/sfpcheck` 的表头表尾
与单条历史记录行（`punish.log-line`）；`PunishCommand` 里不再有硬编码中文。

> `messages.join-warn-notice`（进服提示「有未读处罚记录」）**已在 2.11.0 删除**：
> 警告（WARN）按设计不写生效表、也没有「已读」状态，无法判定「未读」，该键从来没有调用点。
> `PunishConfig` 里的 `getJoinWarnNotice()` / `joinWarnNotice(Map)` 一并移除。

### 8.6 处罚 ID

6 位**字母数字混合**（字符集去掉了易混淆的 `0/O/1/I/l`），生成时用 `PunishStore#idExists` 查重，
碰撞则重试（`SecureRandom`）。

### 8.7 踢出时机与跨服代理（2.10.1 修复）

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

## 9. 数据与持久化

| 存储 | 文件 | 存放内容 | 代码 |
|---|---|---|---|
| SQLite | `teleport.db` | `homes` / `warps` / `last_locations` | `Database` + 三个 Store |
| SQLite | `punish.db` | `players` / `punishments` / `punishment_logs` | `PunishDatabase` + `PunishStore` |
| SQLite | `settings.db` | `ui_preferences`（界面样式偏好）、`tpa_ui_preferences`（传送回应界面偏好） | `UiPreferenceStore` |
| YAML | `trashbin-data.yml` | 垃圾桶物品列表 | `TrashBinManager` |
| YAML | `bots.yml` | 假人记录（用于重启重建） | `BotManager` |
| 内存 | — | tpa 请求、传送冷却、延迟传送队列、在座玩家、生效处罚缓存 | 各 Manager |

### 9.1 传送数据库分层

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

### 9.2 表结构迁移（改表结构必看）

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

### 9.3 备份建议

正常停服后 `-wal` 会自动合并回 `teleport.db`，**直接拷 `teleport.db` 即可**；
开服状态下想拷贝，先执行 `/sfp db checkpoint`。

---

## 10. 改动时的注意事项（踩坑清单）

1. **改 `resources/*.yml` 里已存在键的默认值 → 必须手工同步服务器上的同名配置**。
   `mergeDefaults()` 只补缺失键、不覆盖已有键。**删按钮/删键也一样要手工删线上的**。
   （新增键会自动补，但注意：`mergeDefaults()` 的 `save()` 只写磁盘、不改内存，
   所以它在 `save()` 之后重新 `loadConfiguration` 了一次 —— 否则首次升级时新键的叶子字段
   在内存里读不到，比如新按钮 `slot` 读成 -1。）
2. **配置类的 `onLoaded()` 不得依赖子类字段初始化器**：`AbstractConfig` 的构造器就会调用
   `load()` → `onLoaded()`，而 Java 是在 `super(...)` 返回**之后**才执行子类字段初始化器 ——
   那一刻集合字段还是 null（NPE），而且初始化器随后还会把填好的内容覆盖成空集合。
   集合类字段一律在 `onLoaded()` 内部 `new`。（2.0.3 修的启动崩溃就是这个）
3. **改表结构必须走 §8.2 的两步**，否则老库不会升级。
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
9. `paper-plugin.yml` 的 `api-version` 只有**大版本**（如 `'26.1'`），
   改动它的语义是「放弃对更老版本的兼容」。

---

## 11. 已知未生效 / 预留的配置项

以下五项曾在代码中「定义了但没人用」，**2.5.0 已全部删除**（配置键 + 代码访问器 + 引用一并清理）：

| 配置项 | 原状态 |
|---|---|
| `teleport.allow-cross-world` | 内核条件被六个传 `true` 的调用点短路，永不生效（删除后 `teleport()` 签名也去掉了 `crossWorldAllowed` 参数） |
| `chair.stand-on.sneak` | 无引用；潜行起身实为 `EntityDismountEvent` 无条件处理 |
| `clean.worlds.only-loaded-chunks` | 无引用 |
| `trashbin.global` | 无引用；按玩家隔离未实现 |
| `config.yml` 的 `currency-name` | `GlobalConfig#getCurrencyName()` 无调用点，扫地广播的 `{currency}` 占位符也是死代码 |

**2.11.0 又清掉两个**（同样是「配置键 + 访问器」一起删）：

| 配置项 | 原状态 |
|---|---|
| `clean.skip-named-items` | 想表达「改名物品别扫」，但语义与文档相反（实际是留在地上），见 §3 |
| `punish.messages.join-warn-notice` | 毫无调用点，且 WARN 不写生效表、无「已读」状态，无法判定「未读」，见 §8.5 |

另有 2 个键是「写了但没接上」，**2.11.0 修好而不是删掉**：

| 配置项 | 原状态 → 现状 |
|---|---|
| `teleport.particle-interval-ticks` | 读进来后从未使用（间隔写死 20 tick）→ 现在真正控制粒子间隔 |
| `menu.yml` 的 `permission-message` | `MenuConfig#getPermissionMessage()` 无调用点 → 现在是无权限时的提示文案（留空回退 `common.no-permission`） |

## 12. 新增功能检查清单

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
- [ ] 若要界面：新建 Holder + Gui，点击统一挂在对应 Listener；导航行沿用 §2.6 的槽位约定
- [ ] 数据持久化：能进 SQLite 的走 db 包（注意 §9.2 的迁移步骤）；
      小数据用 YAML；**纯会话态一律放内存**；异步线程要读的数据放内存缓存（参考 §8.3）
- [ ] 若绑定菜单按钮：`menu.yml` 加按钮 + `bind.*-button`，并按第 4 条改三个类
- [ ] **把本功能写进 features.md**（入口 / 调用链 / 流程 / 配置键 / 权限 / 数据文件）
- [ ] 构建 + 真机冒烟（`/sfp test` 应全绿；GUI 与交互需真人验证）
- [ ] **按钮 lore 里若写死了其它配置的数值**（冷却、材料数量、关键字），把比对规则加进
      `SelfTest#checkLoreDrift` / `#checkElytraExchange` —— 否则改配置后按钮上的说明会悄悄过期
- [ ] 版本号：Bug 修复 `z+1`、小内容更新 `y+1`，没有大更新不要动 `x`
