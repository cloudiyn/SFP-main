<!-- migrated-from-workbuddy -->
# WorkBuddy 迁移的长期记忆

> 迁移时间：2026-10-23，来源：E:/Dev/MC/Paper-Plugins/SFP-plugins/SFP-main/.workbuddy/memory/MEMORY.md。
> 该项目的每日工作记录仍保留在 .workbuddy/memory/，需要时按日期读取。

# SFP-main 项目长期备忘

## 定位
「星落平原」Minecraft 生存服整合插件。**Paper 插件**，Java 25，`api-version: '26.1'`，编译目标 Paper **26.3**（`26.3.build.142-beta`，无 stable 故锁构建号以便离线构建）；包名 `cn.starfallplain.sfpmain`，主类 `SfpMain`，插件名 `SFP-main`。功能：整合菜单、自动扫地、垃圾桶、传送（/back /home /warp /tpa /rtp）、假人、Tab/计分板、处罚系统；领地走 Dominion 按钮。

## 硬性约定
- **根目录 `features.md` 是功能明细唯一出处**，改功能必须同步更新并走文末检查清单。改代码前先看它。
- 注释 / 配置注释 / 玩家文案一律中文。占位符统一花括号 `{key}`（`%key%` 会与 PAPI 冲突）。
- 文本渲染优先 MiniMessage；`Messages.convertLegacy()` 兼容 `&a`/`§a`。
- ⚠ **发玩家文案一律 `Messages#send(sender, key, fallback, placeholders)`**（内部即 `deserialize(apply(...))`）。**不要把 `Messages.apply(...)` 的返回 `String` 直接丢给 `sendMessage(String)`** —— Paper 不解析 MiniMessage（只认 `§`），标签会变字面量（2.10.2 踩过）。
- ⚠ **代码里不写中文文案**（诊断类 `debug` 包例外）：dialogUI 列表的条目/操作框用 `messages.yml` 的 `dialogui.*`，命令反馈用各模块的键；2.11.0 已把 `DialogTeleportList` / `DialogTpaTarget` / `PunishCommand` / `TrashBinCommand` 里残留的硬编码清空。新增文案键必须自己在 `messages.yml` 补上，否则静默退回代码兜底。
- **UI 换界面一律延迟一 tick**（`Bukkit.getScheduler().runTask`）：不要在 `InventoryClickEvent` 回调里直接 `openInventory` / 重开弹窗（`TrashBinListener#reopen`、`TeleportGuiListener#reopenList/#reopenTpaTarget` 是现成写法）。
- **按钮 lore 里若写死了别的配置的数值**（价格、冷却、关键字），必须把比对规则加进 `SelfTest`（`checkElytraExchange` / `checkLoreDrift`）—— 否则改配置后玩家看到的说明会悄悄过期。
- **命令一律 `implements BasicCommand`**，在 `registerCommands()` 经 `LifecycleEvents.COMMANDS` 注册；`paper-plugin.yml` 不支持 `commands` 段（`plugin.yml` 已删）。
- **版本号 `x.y.z`**：Bug 修复 `z+1`；小内容 `y+1`；无大重构不动 `x`。不带 `-SNAPSHOT`。
- 配置类**不得在 `onLoaded()` 依赖子类字段初始化器**（`super()` 返回后才跑初始化器，那时集合为 null）→ **集合字段一律在 `onLoaded()` 内 `new`**。
- `mergeDefaults()` 只补缺失键，**不覆盖已存在键**；`copyDefaults(true)` 只写磁盘不改内存（已在 `mergeDefaults()` 里 `save()`+重载修掉）。→ 改 `resources/*.yml` 已有键默认值，线上老配置不会更新，须手工同步。
- `Messages#has(key)` **只看玩家磁盘上的 messages.yml**（不含 jar 内置兜底）——「老配置缺新键」这类检查必须这样才查得出来；取值用 `raw()`（有兜底）。
- **`/sfp test` 是唯一的功能体检入口**（`debug/SelfTest`），加功能时顺手想想有什么「配置错、跑起来也不报错」的情况该加进去（槽位冲突、lore 漂移、新增键缺失、死键残留、权限未声明都已覆盖）。

## 目录
`config/`(AbstractConfig/ConfigManager/GlobalConfig/Messages/module/*Config)、`menu/`(MenuCommand/Manager/Holder/Listener + MenuClock*)、`clean trashbin bot ui display debug teleport punish util`。外部依赖仅 `Dominion` + `PlaceholderAPI`（PAPI 需 `join-classpath: true`）。shade 打包 `org.xerial:sqlite-jdbc`（**不可重定位**）。
数据文件统一在 `plugins/SFP-main/data/`（`teleport.db` / `punish.db` / `settings.db` / `sweep.db` / `trashbin-data.yml` / `bots.yml`）：旧版放在插件根目录的同名文件首次启动自动迁入，若新旧两处主库并存则拒绝启动并打 `severe`；数据库路径统一走 `DatabaseFileResolver` —— **纯文件名** → `data/` 下，**带目录的相对路径**保持原样（不再额外加 `data/`），**绝对路径**原样使用。

## 新增模块 / 新增菜单动作
- 模块：`resources/xxx.yml` → `config/module/XxxConfig`（继承 AbstractConfig）→ `ConfigManager` 三处（字段+访问器+`loadAll()`+`describeState()`）→ 主类 `setupXxx()` 按 `isEnabled()` 注册 → 文案进 `messages.yml` → 命令类 + `paper-plugin.yml` 权限 → 更新 `features.md`。
- 菜单内部动作三步：① `MenuHolder` 加 `ACTION_*` ② `MenuConfig` 加 `xxxButtonId` + `bind.xxx-button` ③ `MenuManager` 三处（`resolveAction`/`runAction`/`isFeatureEnabled`）。纯命令按钮无需 `bind.*`。dialogUI 自动渲染新按钮（**含只作说明、没绑动作的按钮** —— 2.11.0 前 `activeButtons` 会把它们滤掉，导致 dialogUI 少按钮）。
- 菜单布局（`menu.yml` 5 行，内容行 1~3；边框第 0/4 行与 0/8 列，顶栏 slot4 玩家头）：**行1 `back`10 `home`12 `warp`14 `trashbin`16；行2 `rtp`19 `dominion`21 `tpa`23（23 之后空）；行3 `elytra`28 `ui-toggle`34**（2.11.0 整体上移一行，此前 10~16 空着）。改槽位必须同步 `menu.yml` 顶部 ASCII 图 + `features.md` 的按钮表。子界面导航行 45~53：`45上一页/48返回/49信息/53下一页`（改布局须同步 `TrashBinHolder`/`TeleportListHolder` 的 `SLOT_*` 常量）。

## 构建
本机 mvn 不在 PATH，用 IDEA 内置：
```bash
export JAVA_HOME="/d/Program Files/JetBrains/IntelliJ IDEA 2026.1.4/jbr"
"/d/Program Files/JetBrains/IntelliJ IDEA 2026.1.4/plugins/maven/lib/maven3/bin/mvn" -o -B clean package
```
离线 `-o` 可用；**新增依赖 / 改 `paper.version` / 改 shade 依赖时首次需联网**。
踩坑：`paper-api` 写死显式版本号；shade ≥3.6.0 且强制 `asm`/`asm-commons` 9.8（否则 major version 69）；`META-INF/MANIFEST.MF` 警告须 `<filters><exclude>`。
依赖漏洞用 `<dependencyManagement>` 覆盖版本（log4j-api 2.26.1、plexus-utils 3.6.1、commons-lang3 3.18.0、gson 2.14.0），以 `mvn dependency:tree` 为准。
**CI**：`.github/workflows/syntax-check.yml`（YAML 校验 → `mvn -B -ntp clean package`，JDK 25 + `cache: maven`）；改 `<java.version>` 同步改 workflow。

**git push 助手环境做不了**（`credential.helper=helper-selector` 需真实终端）。commit 可以，push 须用户自己来。

## 关键设计决策
- **按钮可见性**：只由自身 `enabled` 决定；若绑定功能模块则该模块也须启用（静默隐藏）。`/sfp reload` 只重载参数，开关类改动须重启。
- **工作模式（2.9.1）**：`config.yml` 的 `mode: full | punish-only`；punish-only 只装配处罚系统。群组服用。
- **tpa**：会话态不入库；每位接收者只留一个待处理请求；响应三通道冗余（Dialog 弹窗 + 聊天可点击 + 手打命令，因装了 Via）。
- **Paper Dialog API**：`Dialog` 有静态初始化，反射加载必失败 → **写试验代码直接编译**让 javac 当校验器。`DialogAction` 只有 `commandTemplate/staticAction/customClick`。
- **传送**：数据在 `data/teleport.db`（`homes`/`warps`/`last_locations`）。改表结构两步：`SCHEMA_VERSION+1` + `migrate(from)` 加 `if (from < N)`；加列用 `addColumnIfMissing()`（查 `PRAGMA table_info`，幂等）。`delay-seconds>0` 排 `pendingTeleports`，移动跨方块即打断。跨世界 `/back` **统一走 `PlayerTeleportEvent`**（别用 `PlayerChangedWorldEvent`）。
- **/rtp（2.6.2）**：配置在 `teleport.yml` 的 `rtp` 段（不建独立文件）。选点链「主线程取点 → `getChunkAtAsync` 异步载区块 → 回主线程判定」递归重试（`getChunkAt` 不能异步调）。冷却独立 `rtpCooldowns`。
- **延迟传送（2.11.0 校正）**：粒子间隔真正读 `teleport.particle-interval-ticks`（此前写死 20 tick），滴答音效固定每秒；目标区块在**距传送还有 1 秒**时异步预加载一次（`PRELOAD_LEAD_TICKS`，此前与传送同 tick 执行等于没预加载）。退出时 `clearTransientState()` 只清 1 秒内部标记，**不清冷却**（否则重登可绕过冷却）。
- **自动扫地**：2.11.0 起**没有** `skip-named-items` —— 改名物品与普通掉落物同待遇，能否取回只看 `to-trashbin`。
  **2026-10-16 用户定调**：「清扫物默认可以取回」= 保留 `to-trashbin` 开关且默认 `true` + 垃圾桶默认启用（**不做成"必先进桶"、也不给改名物品单独保底**）；线上服当时跑 2.10.2，用户选择**先不升级**（该服 `skip-named-items: true` 仍在生效，改名物品不扫），等以后换 jar 时才会拿到新行为。
- **菜单钟（2.6.1）**：`ShapelessRecipe` 键 `sfp-main:menu_clock`，靠 **PDC 标记**（`sfp-main:menu_clock_item`, BYTE=1）识别。
- **dialogUI / 箱子双界面（2.5.0）**：偏好存 `data/settings.db`（`ui_preferences` / `tpa_ui_preferences`），两界面共用 `MenuManager.activeButtons/resolveAction/runAction/canOpen`。
- **display（2.6.0）**：Tab + 右侧计分板（每人独立 `Scoreboard`，`NumberFormat.blank()` 隐数字）。**渲染顺序不能反**：内置占位符 → MiniMessage 解析 Component → **在组件上** `replaceText` 做 PAPI 替换（先 PAPI 字符串替换会让 spark 的 `§r` 重置外层标签）。
- **处罚系统（2.7.0）**：`data/punish.db` 三表；惰性到期判定 + `ConcurrentHashMap` 缓存。命令 `/sfpwarn /sfpkick /sfpban /sfpmute /sfpunban /sfpunmute`。数据库位置可配置（2.8.0 起走 `DatabaseFileResolver`：纯文件名 → `data/`，带目录相对路径保持原样，绝对路径原样）。
- **跨服踢出（2.10.1）**：封禁/踢出统一 `runTaskLater(..., max(1, kick-delay-ticks))`（默认 1 tick），执行前 `isOnline()` 复查。根因是 Velocity `KickedFromServerEvent.kickedDuringServerConnect()` 会重投到下一子服，客户端收不到 `LEVEL_CHUNKS_LOAD_START` 就卡「加载地形中」；与 `failover-on-unexpected-server-disconnect` 无关。
- **背包统计口径**：`PlayerInventory` 未覆写 `getContents()`，副手属 `getExtraContents()` → 统计与扣除**都必须用 `getStorageContents()`**。
- **Paper 26.3 废弃 API**：`Sound#valueOf`→`Registry.SOUNDS`、`Sound#getKey()`、`Nameable#setCustomName(String)`→`customName(Component)`、`Sign#getLine/line`→`getSide(Side.FRONT)`、`PlayerKickEvent#getReason()`→`reason()`。
- **假人（2.12.0 重做）**：命令统一在 `/sfp player`（简写 `/sfp fp`），`SfpCommand` 在**管理权限检查之前**转交 `BotCommand`（假人自己的 `sfpmenu.bot`）。假人不只是个占位实体：`BotActions` 有 4 tick 动作任务（attack/mine/use + wolverine/replenish/autofish）和 1 tick 移动任务；设置项 8 个存在 `data/bots.yml` 的 `settings`/`player-configs`，持续动作存 `actions`（重启后继续）。
- ⚠ **Paper 26.3 假人三个实测坑（别走回头路）**：
  ① **玩家位移是客户端权威** —— 给假人发 `ServerboundPlayerInputPacket` / `ServerboundMovePlayerPacket` 都不动（服务端只当状态），只能 `ServerPlayer#snapTo` 改坐标让追踪器广播；
  ② **`World#rayTraceBlocks` 对假人返回 null**（同样参数对真玩家正常）→ 自己写体素射线（`BotActions#lookingAt`，0.05 步进）；
  ③ **`ServerPlayerGameMode#tick()` 不推进挖掘进度** → 只能自己按 `Block#getBreakSpeed`（它返回的是**每 tick 破坏进度**，`ceil(1/进度)`）计时后调 `#destroyBlock`（无裂纹动画）。
  另：`FishingHook#biting` 是 private，要用 `getDeclaredField` + `setAccessible`；反射缓存的 `ConcurrentHashMap` 不能存 null（负缓存必须有哨兵）。
- **假人可编辑物品栏（2.16.0）**：右键假人开 `BotInventoryManager` 界面，底排 45 是「主手」别名（映射当前快捷栏槽）、46 是副手实槽，改动立即写回假人；接受左右键 / Shift / 数字键 / F，**明确拒绝拖拽、双击、创造克隆与窗口外丢弃**。`use` 动作先主手再副手，耗尽时按原类型从背包 9~35 格补货；`bot.yml` 新增 `pickup-whitelist`（空列表 = 全部可拾取）。
- **连锁挖矿（2.13.0）**：`chain` 包，只做**矿石**（砍树不做）。蹲下正常挖矿时按同种矿脉 26 邻域 BFS 扩散（`max-blocks` 默认 32），每块扣 `0.1` 饱食度（直接扣 `FoodLevel` 整数，按**实际挖掉数**结算），假人不参与（`require.chain-bots` 默认 false）。
  ⚠ **方块没有「自然生成 vs 玩家放置」的原生标记**（`BlockData` 只有 `axis`）—— 想做砍树必须叠加「树形几何判定 + PDC 记录玩家放置」两层，单靠几何不够可靠。
  ⚠ **连锁的每一块必须真正发 `BlockBreakEvent`**（再 `setType(AIR)` + 手动投放掉落物），否则丢经验、绕过领地保护/CoreProtect、丢采集等级；**不能用 `block.breakNaturally()`**（双份掉落）。监听器用 `NORMAL` 而非 `MONITOR`（要让保护插件能取消）；连锁内部发的事件会重入，靠 `ThreadLocal` 标志防递归。
  ⚠ **Paper 26.3 没有 `meta.Unbreakable`**（改 data component）；耐久正路是 `ItemStack#damage(int, LivingEntity)`，**返回新对象必须写回主手**。
- **NMS 签名不要猜**：拼 classpath + 反射打印签名（`Class.forName(name,false,loader)` 不初始化）。
- **Folia**：`folia-supported: false`。

## 测试服务端
- **线上/联调服**：`\\WIN-QCLLAKI0B0G\d\xlpy\xlpy_vanilla`（MCDR 根，`working_directory: server`，`-jar server.jar`）。本机的 `E:/Dev/DSH Workspaces/xlpy_vanilla` **已不存在**。26.3 上不兼容 `CoreProtect-CE-24.1`、`NoChatReports-2.7.8`。
- **本机一次性冒烟服（推荐，全程控制台驱动、不需要真人上线）**：`C:/Users/ran/AppData/Local/Temp/sfp-test/server`
  —— 从上面那台拷 `server.jar`（bundler）+ `libraries/` + `cache/mojang_26.3.jar`，`eula=true`，
  `server.properties` 用 `level-type=flat` + 自定义 `generator-settings`（bedrock1+stone58+dirt2+grass1 → 站立 y=62），
  `doDaylightCycle=false` 防僵尸燃烧。jar 丢 `plugins/`，启动：
  `{ sleep 45; echo "命令"; ...; echo stop; sleep 25; } | java -Xmx2G -jar server.jar nogui > run.log 2>&1`
  （`-Dstdout.encoding=UTF-8` 否则中文日志乱码）。验证手段全是原版命令：`list`、`data get entity <假人> Pos/Motion`、
  `data get block`（方块实体被挖掉会报「不是方块实体」= 已破坏）、`summon iron_golem` + 血量对比、`sfp test`。

## 版本线
2.0.x(Paper 26.3) → 2.5.x(dialogUI) → 2.6.x(菜单钟/rtp/display) → 2.7.0(处罚) → 2.8.0(数据库路径) → 2.9.0(rtp 按钮) → 2.9.1(工作模式) → 2.10.0(鞘翅兑换) → 2.10.1(跨服踢出时机) → 2.10.2(MiniMessage 修复 + 鞘翅 64 钻石/8 残骸二选一) → 2.11.0(自检修复：死配置生效/死键删除、菜单布局上移、dialogUI 补齐、文案全部外置) → 2.12.0(假人重做为 minecraft-fakeplayer 效果：`/sfp player|fp`、37 个子命令、8 项设置、持续动作 attack/mine/use/move + 一次性动作全套、右键开背包、PAPI 占位符) → 2.13.0(连锁挖矿 chain 包：蹲下挖矿 + 同种矿脉 BFS(上限 32) + 每块 0.1 饱食度 + 假人不参与) → 2.15.0(`/sfp sweep on/off` 开关 + `sweep.db` 持久化区块例外) → 2.15.1(修假人开 `pickup_items` 后不捡掉落物) → **2.16.0**(假人右键可编辑物品栏 + `use` 主手→副手与同种补货 + `bot.yml` 新增 `pickup-whitelist`)。

## 外部资产 / 待办
- **MinerTrack**（`C:/Users/ran/Desktop/MinerTrack/`，非本仓库，**只改配置不动源码**）：`xray.commands` = `sfpwarn`(VL2/4) → `sfpban 1d`(VL6) → `sfpban 7d`(VL9)；`overworld.yml` rare-ores 12 项 / `nether.yml` 5 项，`veinCountThreshold: 4`；`end.yml` 保持 `xray.enable: false`。
- 待办：tpa 弹窗未真人验证；`trashbin.yml` 按玩家隔离仅预留；传送 GUI 删条目后回首页（未保留页码，**保持现状**）；`TrashBinListener` 仅 shift 取回才发「已取回」提示（普通点击只有音效，**保持现状**，避免连点刷屏）；**2.8.0~2.12.0 全部未 commit**（`git HEAD` 仍是 2.7.0）；`.prg` 与 `.viewer.html` 里 `.prg` 未跟踪。
  假人未真人验证过的路径：右键 invsee / drop·swap·hold / skin / cmd / ride / sleep（控制台无法触发，代码里都是 Bukkit/NMS 直调）。
- 未处理（有意）：`PunishStore.idExists` 只查 `punishments` 表不查 `punishment_logs` —— 修它要动 DB 层，按用户要求「不改数据库相关」跳过。
