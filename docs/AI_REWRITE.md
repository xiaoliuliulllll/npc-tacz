# npc-tacz 战斗 / 寻路重构说明（v4 架构）

本文件记录 2026-11-19 的大规模重构：AI 层从头重写，明确模块边界，方便后续继续调参。

> 版权：本重构所有代码均为本项目原创；TACZ（永恒枪械工坊：零）只作为**运行时依赖**
> 通过其公开 API（IGun / IGunOperator / TimelessAPI / 事件）调用，不包含任何第三方
> 模组的代码、贴图、模型或动画资源。士兵 GLB 模型为朋友原创作品，仅使用其自带动画。

## 1. 模块划分

```
com.npctacz.entity
├── WastelandNpcEntity          基类：数据/派系/装备/掉落/存档/动画同步 + 对外 API
├── SoldierNpc / BanditNpc / RebelNpc / BountyHunterNpc
│                             子类：数值表、阵营、伤害公式（少量差异用覆盖实现）
├── NpcSight                    视线判定（玻璃/树叶/栏杆可看穿）
├── ai
│   ├── NpcAmmoState            弹药与武器状态机（远程 ↔ 近战、补弹、掉落弹匣）
│   ├── NpcCombatHelper         索敌评分、友军射界、掩体/侧翼/走位点
│   └── NpcNavigator            寻路辅助（卡住绕行、跳跃、梯子、落水回岸）
└── goal
    ├── NpcTargetGoal           索敌（带粘性，避免目标抖动）
    ├── NpcRangedCombatGoal     远程交火：走位 + 点射 + 掩体 + 友军射界
    ├── NpcMeleeCombatGoal      近战冲锋（弹尽后）
    ├── NpcDoorBreakGoal        开门/破门/清障
    ├── NpcPatrolGoal           巡逻/夜间回家
    └── InvestigateSoundGoal    循枪声搜索
```

旧版 `GunAttackGoal / GunMeleeGoal / FactionTargetGoal / NpcDoorGoal / BreachGoal /
NightShelterGoal / NpcWanderGoal / ShelterCache` 已删除。

## 2. 弹药状态机（NpcAmmoState）

状态：`RANGED`（有枪有弹）↔ `MELEE`（弹匣+备弹都空）。

- 生成：弹匣装满 + 备弹 = 难度上限（士兵 D1-D4 = 60/120/180/300）
- 射击：走 TACZ `IGunOperator`；每枪从弹匣扣 1；弹匣空 → 从备弹换弹
- 补弹：远程每 15s、近战每 5s 补一次 `getRefillAmount()` 到备弹
- 切近战：把当前枪完整 `ItemStack`（含配件/NBT）存 `savedGun`，换铁剑，清冷却停寻路
- 切回枪械：恢复**同一把枪** + 从备弹装弹匣 + 播换弹动画 + 清冷却
- 掉落：击杀掉枪的弹匣随机装 50%~100%；近战状态死亡也会掉记住的枪

TACZ 冷却判定使用 `GunTime.nowMillis() - baseTimestamp` 相对时间；本模组按同一公式把
`shootTimestamp` 推到冷却窗口之前，否则会永久 `COOL_DOWN` 一发打不出。

## 3. 索敌（NpcTargetGoal + NpcCombatHelper）

- 玩家：只在非创造/非旁观时敌对，感知半径 `max(96, 扫描范围)`
- 生物：敌对阵营 NPC + `Enemy`（僵尸/骷髅/史莱姆/恶魂/幻翼…）
- 评分：距离 + 视线 + 当前目标粘性 + 刚打过自己的敌人 + 低血量
- 目标超出 `扫描范围 × 2.5` 才放弃，减少目标来回切换造成的原地抖动

## 4. 远程交火（NpcRangedCombatGoal）

- 无视线 → 找侧翼点（可看到目标的位置）→ 找不到就直冲
- 有视线 → 距离 > 射程×1.05 压上；< 理想距离×0.55 后撤；否则横向走位
- 射击：反应时间（默认 4 tick）后点射，每轮 `burstShots` 发，轮间停顿
- 友军射界：目标方向 11° 内、且比目标更近的友军 → 不开火，改走位（每 10 tick 查一次）
- 掩体：血量 < 40% 或弹匣空时寻找能挡住目标视线的站立点，进入掩体再补弹
- 瞄准：目标眼睛位置 + 少量提前量 → 计算 yaw/pitch，按难度加散布

## 5. 寻路（NpcNavigator + NpcDoorBreakGoal）

- 重算节奏：目标移动超过 2 格或走完路径才重算（8/12/20 tick 按距离分档）
- 卡住检测：每 10 tick 采样位移；卡住 → 跳跃 → 侧向绕行点；落水 → 往最近岸上走
- 梯子/藤蔓：目标在上方时自动上爬
- 破门：木门/栅栏门/木活板门直接开；铁门/铁活板门换铁镐挖（20~40 tick，掉落铁门）；
  玻璃板/玻璃/树叶挡路时打碎
- 门只在"有目标且被挡住"时才处理，不会平时乱拆

## 6. 配置

首次启动会自动生成 `config/npc-tacz-common.toml`，**不需要手动添加任何配置项**。
可调项分组：

- `combat`：reactionTicks、burstShots、burstPauseTicks、accuracyMultiplier、
  spreadDegrees、friendlyFireSafety、useCover、coverSearchRadius、
  coverHealthThreshold、aggressionMultiplier
- `ammo`：refillSecondsRanged、refillSecondsMelee、meleeWeapon
- `pathfinding`：pathStuckJump、stuckDetourTicks、breakDoors、
  ironDoorBreakTicksMin/Max
- `targeting`：targetScanIntervalTicks、targetSwitchCost

## 7. 性能

- 索敌：有目标时不扫描；无目标才每 20 tick 扫一次
- 视线：每 4 tick 才做一次射线采样
- 友军射界：每 10 tick 一次
- 掩体搜索：仅在需要掩体且冷却结束时（40 tick）做
- 渲染：60 格外的士兵不渲染；手部矩阵/朝向矩阵预计算
- 远处（96 格无玩家）NPC 进入弱运行：不接战、不追玩家、慢速巡逻

## 8. 自动化测试工具

- `tools/launch_test.ps1`：用版本 json 重建启动命令，quickPlay 到测试存档
- 测试存档：`D:\mc\.minecraft\versions\npc测试\saves\npc_rewrite_test`
  （复制自玩家存档，带数据包 `rewrite_test`：自动搭墙/木门/铁门、生成士兵/劫匪/叛军、
  定时用 `/data get` 把位置/血量/弹药打到日志）
- 测试日志关键字：`[RW]`（数据包）、`[npc-tacz]`（模组：equip/shoot/state/death/bullet）

## 9. 已知后续可做

- 玩家被瞄准/被锁定的提示（可选）
- NPC 小队协同（同阵营共享目标）
- 枪械类型细分的战术（狙击手后撤、霰弹压近）
- 难度对点射节奏/走位频率的进一步细分

## 10. 2026-11-19 自动化实测记录（npc_rewrite_test）

用 `tools/setup_test_world.ps1` 生成测试世界 + `tools/launch_test.ps1` 离线启动 quickPlay 到测试存档，实测结果：

**关键根因（本轮修复）**：TACZ 的 `ModernKineticGunScriptAPI.shootOnce()` 在
`IGunOperator.getCacheProperty() == null` 时直接 return，**不会生成子弹**。玩家由 TACZ
自己维护该缓存，NPC 不是玩家所以从未建立。此前的 `shot fired` 日志只代表
`LivingEntityShoot.shoot` 返回 SUCCESS（冷却/弹药检查通过），并不代表子弹真的发射。
修复：调用官方 `AttachmentPropertyManager.postChangeEvent(npc, gunStack)` 建立缓存。

修复后的日志证据：

```
[npc-tacz] bullet entity spawned: type=tacz:bullet owner=SOLDIER at=BlockPos{x=-44, y=70, z=-234}
[npc-tacz] npc bullet hit: attacker=SOLDIER victim=劫匪 damage=27.300001
[npc-tacz] NPC died: faction=BANDIT killer=士兵
[npc-tacz] trace: faction=SOLDIER state=RANGED mag=16 ... target=DSHTest dist=8.0
[npc-tacz] trace: ... dist=17.2 pos={x=-47, y=70, z=-235}   ← 距离保持/侧向走位
[npc-tacz] trace: ... stuck=true                            ← 卡住检测触发
```

- 子弹生成、命中伤害、击杀链路全部打通（14 次击杀 / 5 次命中样本 / 42+ 次开火）
- NPC 会锁定玩家、保持 13~17 格优势距离、横向走位、贴脸时后撤
- 寻路在门/墙环境中能移动并恢复卡住状态
- 未出现 `Ticking entity` 崩溃（此前构造期 navigator 为 null 的崩溃已修复）

**平衡调整**：对玩家伤害按难度封顶（D1 6 / D2 9 / D3 12 / D4 15）并按护甲再减伤；
NPC 之间伤害上限 14×0.7，避免一枪互秒，让对射能持续几秒。

**已知未完全自动化验证**：单机失焦暂停导致长时间“弹药耗尽→切近战→补弹→切回”
循环的端到端计时验证不稳定；该流程可通过 `/tacznpc debug_ammo 2 0`（调试命令，
设置最近 NPC 的弹匣/备弹）在游戏内快速验证：打空弹匣 → 换铁剑 → 约 5 秒补弹 →
切回原枪。
## 11. 2026-11-19 第二轮优化：视线 / 寻路 / 动作 / 指令

**视线（NpcSight 重写）**
- 改为 3D DDA 体素步进（Amanatides & Woo），只访问射线真正穿过的格子，比 0.5 格采样少很多
  `getBlockState` 调用；
- 可看穿：空气、水、树叶、玻璃/染色玻璃/遮光玻璃、玻璃板、铁栏杆、以及**所有没有碰撞体积的方块**
  （草、花、火把、铁轨、按钮…）；
- 玻璃/树叶后面发现目标后可以直接开火（索敌/交火都走这套判定）。

**寻路**
- 追击/寻路范围：生成时把 FOLLOW_RANGE 从默认 32 提升到
  `max(原值, min(64, 交战范围))`，远程 AI 不会再因为原版寻路范围太短追不到人；
- 路径下一步是水 → 提前重算并绕开；落水往岸边（优先目标方向）游；
- 卡住分级脱困：跳 → 侧向 2.5 格绕行 → 绕行距离递增到 8 格 → 换方向；
- 破门 Goal 优先读取寻路器的 `findPathObstacle()`（路径下一步被什么方块挡住），比按视线扫更准；
  开门/破门成功后清空卡住计数并强制重算路径。

**动作（不使用任何 TACZ 动画/资源，全部原创程序化）**
- 瞄准同步：`DATA_AIMING` 由服务端每 tick 更新，客户端据此播放模型自带的 `gun_hold` 并抬起右臂；
- 开火：播放模型自带 `attack` 片段 + 手臂后坐力上踢（6 帧衰减）+ 原版烟/火粒子模拟枪口；
- 换弹：播放模型自带 `reload`（由弹药状态机同步）；
- 近战：挥砍时同样用 `attack` 片段表现；
- 头部跟随：头部骨骼按实体俯仰角做 0.55 倍/0.35 倍跟随；
- 注：TaCZ 的动画属于其版权资源，**不能也不应**直接调用；这里用我们自己模型自带的 7 段动画
  做同类表现，协议上安全。

**指令（/tacznpc 全量重写 + Tab 补全）**
- `/tacznpc spawn <soldier|bandit|rebel|bounty_hunter|all> [数量]`
- `/tacznpc clear [半径]`、`/tacznpc count [半径]`
- `/tacznpc givenpc <类型> [数量]`
- `/tacznpc difficulty [1-4]`、`/tacznpc generate_prompt <true|false>`
- `/tacznpc npcdebug`（状态/弹匣/备弹/枪/目标距离/卡住/弱运行）
- `/tacznpc debug_ammo <弹匣> <备弹>`（调试：验证打光→近战→补弹→切回）
- `/tacznpc help [chinese|english]`，类型参数带 Tab 补全；
- 生成落点重做：检查地面/水/岩浆/碰撞/间距，10 次尝试，不会再刷在方块里或半空。
## 12. 动作联动到 TACZ 第三人称动画（协议内）

TACZ 的 `HumanoidModelMixin` / `PlayerModelMixin` 会对**任何手持枪械的 LivingEntity**
调用 `InnerThirdPersonManager.setRotationAnglesHead()`，从
`ModSyncedEntityData` 的 `AIMING_PROGRESS_KEY` / `RELOAD_STATE_KEY` / `IS_AIMING_KEY`
等同步数据读取状态，播放它自己的第三人称持枪/瞄准/换弹手臂动作。

这些键玩家由 TACZ 自己维护，NPC 需要手动喂：

- 客户端 tick 里 `WastelandNpcEntity#syncTaczClientAnimationState()` 根据我们已同步的
  `DATA_AIMING` / `DATA_RELOADING` / `DATA_FIRING` 写入：
  - `AIMING_PROGRESS = 瞄准 ? 1.0 : 0.0`
  - `IS_AIMING = 瞄准`
  - `RELOAD_STATE = EMPTY_RELOAD_FEEDING + 倒计时 / NOT_RELOADING`
  - `SHOOT_COOL_DOWN`、`DRAW_COOL_DOWN`、`IS_BOLTING`、`SPRINT_TIME` 清成合理值
- 这样**皮肤模型**（原版人形渲染器）的士兵/劫匪/叛军会自动获得 TACZ 的第三人称
  手臂动作；我们仍然不复制、不打包 TACZ 的任何动画/模型资源，只通过它公开的
  `SyncedDataKey` API 驱动。

**士兵两种模式**：
- `/npcmodel skin`：你的士兵皮肤 + TACZ 第三人称手臂动画；
- `/npcmodel glb`：朋友的 GLB 模型 + 我们自研的程序化抬枪/后坐力/头部跟随。
## 13. 2026-11-20 修复：爆炸配件 / 击中视角 / 同阵营免伤 / 自动换弹

**1) 删除爆炸属性配件 + 爆炸属性枪械**
- 新增 `entity/ai/NpcGunRules`：双保险过滤
  - 资源 id 关键字黑名单（explosive/rpg/grenade/rocket/he/燃烧 等）；
  - TACZ 数据精确判断：`CommonGunIndex.getBulletData().getExplosionData() != null` 的枪一律不用，
    不依赖枪包命名。
- 四个阵营的选枪池、兜底枪池、配件安装候选全部接入；
- `WastelandNpcEntity#sanitizeExplosiveAttachments()` 会在装备/切枪/换弹完成时，
  把已经装在枪上的爆炸配件用 `IGun#unloadAttachment` 卸掉（旧存档/命令塞进去的也会被纠正）。

**2) NPC 命中玩家时视角/准星轻微上跳**
- 新增网络通道 `network/NpcNetwork`（SimpleChannel，只发本模组自己的包）；
- 服务端在 `CombatEvents` 命中玩家时发送 `HitKickMessage`：yaw ±0.6°，pitch 向上 1.3~2.2°；
- 客户端 `client/HitKickHandler` 每 tick 消耗 35% 剩余量平滑加到玩家视角，约 5 tick 回正；
  单次最多累计 6°，连续命中不会把视角甩飞；
- `DistExecutor.unsafeRunWhenOn` 保护，dedicated server 不会去加载客户端类。

**3) 同阵营 NPC 免伤 + 不互打**
- 目标筛选：`NpcTargetGoal`/`NpcCombatHelper.scanForTarget` 只选 `isHostileTo` 的目标（原有）；
- `WastelandNpcEntity#setTarget`：同阵营/友军 NPC 直接拒绝，HurtByTarget 也不会回打友军；
- `CombatEvents`：`LivingAttackEvent` + `LivingHurtEvent` 双重取消，子弹、爆炸、近战全部免伤。

**4) 打完子弹自动换弹**
- `NpcAmmoState` 状态机从 2 态扩展为 3 态：`RANGED / MELEE / RELOADING`
  （保持旧存档 0/1 序号兼容）；
- 弹匣打空且备弹 > 0 → 进入 `RELOADING`：40 tick，期间 `canShoot()` 为 false，
  播放换弹动作（GLB 播自带 reload；皮肤模型经 TACZ 第三人称动画播放换弹）；
- 换弹完成从备弹装满弹匣，然后恢复射击；备弹也为 0 才切近战；
- `npcdebug` 的 `状态=` 会显示 `RELOADING`；读档时不会停在换弹态。

## 14. YSM 联动（软依赖，协议安全）

**指令（YSM 已安装时才注册；没装 YSM 就没有这些指令，也不会崩）**
```
/tacznpc ysm <目标> <模型id> <true|false>
  目标 = nearby 的 NPC 实体 id / soldier|bandit|rebel|bounty_hunter / allnpc
  模型id 从 config/yes_steve_model/custom、cache/<作者>、builtin 扫描，Tab 补全
/tacznpc ysm list            列出扫描到的模型（前 20）
```

- 选择结果写入 NPC 同步数据 `DATA_YSM_MODEL` 并持久化到 NBT（`NpcYsmModel`），
  `/tacznpc npcdebug` 可见；
- 模型 id 只做"引用 + 记录"，不复制、不打包 YSM 的代码或模型资源；
- `compat/YsmCompat` 只用 `ModList.isLoaded` 判断 YSM 是否存在，不加载 YSM 类。

**为什么暂时没有视觉变化（必须说明）**
YSM 2.6.5 的 release jar 除了 mixin 与主类外全部混淆，并且它的模型渲染只挂
玩家（`AbstractClientPlayer` 的 `PlayerCapability`）、投射物、载具，以及硬编码的
Touhou Little Maid 兼容；没有任何公开的“给任意 LivingEntity 设置模型”的接口。
因此在不反编译、不复制 YSM 代码/资源、不违反其 "All rights reserved" 协议的前提下，
本模组能做的是：**指令 + 模型列表 + Tab 补全 + 选择持久化 + 客户端同步**这一层联动；
等 YSM 开放实体模型接口，或换成支持实体的渲染后端，绑定的模型 id 可以直接生效。

## 15. 2026-11-20 反应时间系统

**公式：`f(x) = t + 0.25 * n`**
- `t`：该难度最小反应时间（秒）
- `n = 1 + floor(距离方块 / 16)`：初始 1 个区块，每多 16 格 +1
- 结果夹紧到该难度的 [最小, 最大] 区间

| 难度 | 最小 t | 最大 | 典型值（0~16 格 / 64 格） |
|---|---|---|---|
| 1 | 1.2s | 2.6s | 1.45s / 2.45s |
| 2 | 0.8s | 1.6s | 1.05s / 1.6s（夹紧） |
| 3 | 0.4s | 0.8s | 0.65s / 0.8s（夹紧） |
| 4 | 0.2s | 0.6s | 0.45s / 0.6s（夹紧） |

**难度 4 额外：连杀全局减反应**
- 玩家连续击杀 NPC（死亡前），每 10 杀 → 全体 NPC 反应时间 ×0.8（-20%），
  20 杀 ×0.64、30 杀 ×0.512……最低 0.065s；
- 玩家死亡后连杀清零，反应时间恢复正常，并提示；
- 每 10 杀在聊天栏提示当前减益。

**实现**
- `entity/ai/NpcReactionTime`：公式、难度区间、连杀系数、击杀/死亡钩子；
- 远程：`NpcRangedCombatGoal` 首次看见目标时记录距离 → 计算所需 tick，`seeTime` 达标才开火；
  失去视野会重置（重新计算）；
- 近战：`NpcMeleeCombatGoal` 发现目标后先转身观察反应时间，再冲锋/挥砍；
- 击杀统计挂在 `WastelandNpcEntity#die`，死亡重置挂在 `BountyEvents#onLivingDeath`；
- `/tacznpc npcdebug` 新增 `反应=xx xt` 字段便于验证。

## 16. 2026-11-20 YSM 指令修复 + 永久模型

**为什么之前 /tacznpc ysm 输入不出去**
- 之前整个 `ysm` 子指令包在 `if (YsmCompat.isLoaded())` 里，YSM 检测一时为 false（加载顺序/单机联机差异）
  时整个子命令树消失，客户端就显示未知指令；
- 现在 **`/tacznpc ysm` 无条件注册**：一定能输入、Tab 有补全；YSM 未安装时只提示
  “绑定已保存但不会渲染”，不崩溃、无副作用。

**后两个指令改为永久生效**
- `/tacznpc ysm <阵营> <模型id> <true|false>`
  → 写入世界级 `NpcYsmData` 的阵营默认表，**现有该阵营 NPC + 以后生成/加载的该阵营 NPC** 全部永久使用；
- `/tacznpc ysm allnpc <模型id> <true|false>`
  → 写入 `NpcYsmData` 的全体默认表，**所有现有 + 以后生成的 NPC** 永久使用；
- `/tacznpc ysm <单体实体id> ...` → 只改这一只，写入实体 NBT（`NpcYsmExplicit`），
  优先级最高，直到玩家再次修改。

**优先级**：单体显式指定 > 阵营默认 > 全体默认。
新 NPC 在 `finalizeSpawn`、旧 NPC 在 `readAdditionalSaveData` 时自动套用默认；
`/tacznpc npcdebug` 的 `YSM=xxx(单体/默认)` 可以看当前来源。

配置文件：`data/NpcYsmData`（SavedData，存在世界存档里，重启不丢）。
## 17. 2026-11-20 YSM 模型参数语法（{name="模型id"}）

**问题**：之前模型参数是 Brigadier `word()`，不允许 `{`、`"`、`=` 等字符，中文/SNBT 写法会被判定为指令错误；
`StringArgumentType.string()` 的 unquoted 规则也只允许 `[A-Za-z0-9_- .+]`，同样吃不下 `{name="..."}`。

**解决**：自研 `command/YsmModelArgument`（`ArgumentType<String>`），用
`ArgumentTypeInfos.registerByClass(..., SingletonArgumentInfo.contextFree(...))` 注册（1.20.1 API）：

- 以 `{` 开头：按大括号配对、忽略字符串内括号的方式提取原始 SNBT 文本；
- 其它情况：`StringReader.readString()`（支持普通 id 和带引号字符串）。

因此以下写法都合法：
```
/tacznpc ysm allnpc {name="胡桃-宿雪桃红"} true
/tacznpc ysm allnpc {name:"胡桃-宿雪桃红"} true
/tacznpc ysm allnpc {model:"furina_v2.6"} true
/tacznpc ysm soldier furina_v2.6 true
/tacznpc ysm rebel "显示 名" false
```

`YsmCompat.resolveModelId` 统一解析：`{name="..."}` / 引号外壳 / 纯 id / 元数据里的显示名（中文），
匹配顺序为 **id → 显示名精确 → 显示名包含**。

**Tab 补全**：同时列出英文文件名 id 和 `.ysm` 元数据头里的中文显示名；含空格的名字会自动加引号，
所以不用输入法也能用 Tab 选中中文模型。

**验证**：测试世界的 `load` 函数直接执行上述多种写法，数据包加载无 "Failed to load function"，
说明参数解析通过。

**“全英文模型指令没有效果”说明**：指令本身是成功的（绑定已持久化，`/tacznpc npcdebug` 可见
`YSM=...`）；没有外观变化是因为 YSM 2.6.5 只渲染玩家/投射物/载具，且其 .ysm 模型是加密格式
（实测无明文 JSON/PNG，不能也不应绕过），本模组只能做“选择+持久化+同步”这一层。
## 18. 2026-11-20 自然生成机制重做 + /tacznpc spawn_config

**默认规则（世界级持久数据 NpcSpawnConfigData）**

| 类型 | 间隔 | 每次数量 | 范围（玩家外~内） |
|---|---|---|---|
| 士兵 | 10 分钟 | 3~5 | 48~96 格 |
| 劫匪 | 10 分钟 | 4~6 | 48~96 格 |
| 叛军 | 10 分钟 | 2~3 | 48~96 格 |
| 雇佣兵 | 3 天 | 1 | 48~96 格 |
| 赏金猎人 | 5 天 | 1 | 48~96 格 |

- 一天 = 24000 tick；时间是**服务器 tick 时间**（现实时间流逝），与昼夜循环开关无关。
- **错峰生成**：到时间只开始“排一批”，每个 NPC 之间间隔 50~110 tick，逐个在玩家周围
  内圈~外圈随机位置刷出，不会同一 tick 一起出现；位置校验失败会换点重试。
- 上限：每个维度 `96 × 难度` 个 NPC，防止无限堆积；生成前会做地面/水/碰撞检查。
- 赏金猎人：生成后 **2 天（48000 tick）自然消失**，或追踪玩家死亡 2 次后消失；
  消失走 `discard()`，**不会掉落任何物品**。

**指令**

```
/tacznpc spawn_config                              # 查看全部配置
/tacznpc spawn_config <soldier|bandit|rebel|mercenary|bounty_hunter|all> time <时间>
    时间：10 = 10 分钟；30s / 2h / 3d 也支持
/tacznpc spawn_config <类型|all> amount <min> [max]
/tacznpc spawn_config <类型|all> range <外圈> <内圈>   # 外圈>内圈，16~1024
/tacznpc spawn_config initial_config                # 恢复默认
```

- 类型补全带中文 tooltip（鼠标悬停显示：士兵/劫匪/叛军/雇佣兵/赏金猎人/全部类型）；
- 修改后立即重新起算下一次刷新时间（`NpcSpawnEvents.onConfigChanged()`）；
- 配置保存在世界存档里，重进世界不丢。

**验证**：测试世界 load 函数执行了查看/时间/数量/范围/重置全部写法，数据包加载无解析错误。
## 19. 2026-11-20 强制停留 / 先手反击 / 墙体遮挡视线

**1) 雇佣兵"停留"改为强制停留**
- 右键切换跟随/停留；切换到停留时记录当前站位（`MercStayPos`，存 NBT）。
- 停留模式下 `isMovementLocked()` 为 true：
  - `NpcNavigator.moveTo(...)` 直接拒绝并清路径；
  - 每 tick 清空原版 Navigation；
  - 不再跟随主人、不再追击、不再绕行，只能原地转身/开火/近战。
- 切回跟随清空锚点，恢复正常移动。

**2) 先手反击 100% 优先**
- `WastelandNpcEntity#hurt`：被 LivingEntity 攻击且敌对时，把攻击者设为 `retaliationTarget`，
  锁定 400 tick（20 秒）。
- 锁定期间 `setTarget` 拒绝其它目标（`NpcTargetGoal` 也直接使用 forced target），
  保证 100% 先打"先攻击自己的那个单位"。
- 目标死亡/超时/变敌对失效后自动释放，恢复普通索敌。

**3) 视线不再穿墙**
- `NpcSight.isSeeThrough` 改成明确白名单：
  - 可看穿：空气、水、树叶、玻璃/染色玻璃/遮光玻璃、玻璃板、铁栏杆、地毯、雪层、
    以及草/花/火把/铁轨/按钮等无碰撞小方块；
  - 一律挡视线：石头/泥土等实心方块、**岩浆**、**火/灵魂火**、**细雪**。
- 索敌（`scanForTarget`）：新目标必须当前看得见，墙后不会"刷出新目标"；
  已锁定的目标作为记忆保留，但不会开枪。
- 开火（`maybeShoot`）：每发都重新做一次 LOS 检查，不再因 4 tick 缓存隔墙打枪。
- 失去视野时只朝"最后看见的位置"看，不透视目标真实位置。
## 20. 2026-11-20 /tacznpc ysm 修复与自检

**问题背景**
- 之前有一轮把 ysm 指令注册注释掉了（"intentionally not registered"），但 help 仍在列；
- 已恢复注册，并做了可观测性和易用性修复。

**本次改动**
- `/tacznpc ysm` 恢复注册（始终可用，YSM 没装也能输入）。
- 新增 `/tacznpc ysm status`：显示 `installed=`、扫描到的模型数、全体/阵营默认绑定，
  排查"指令没效果"时先看这个。
- 新增便捷写法：`/tacznpc ysm <模型id> [true|false]` 直接作用于最近的 NPC（不用先找实体 id）。
- `/tacznpc ysm reload` 清模型扫描缓存。
- 每个 ysm 操作都会写 `latest.log`：
  ```
  [npc-tacz] ysm list: N models found, first=[...]
  [npc-tacz] ysm allnpc: model='...' enabled=true affected=N
  [npc-tacz] ysm faction=... model='...' enabled=... affected=N
  [npc-tacz] ysm nearest: entity=... model='...' enabled=...
  ```
- 参数解析：`{name="..."}`、中文显示名、纯 id、带引号名字都走同一个 `YsmModelArgument`。

**验证（实机日志）**
```
[npc-tacz] ysm list: 13 models found, first=[builtin/default, builtin/misc, builtin/wine_fox]
[npc-tacz] ysm allnpc: model='furina_v2.6' enabled=true affected=0
[npc-tacz] ysm allnpc: model='胡桃-宿雪桃红' enabled=true affected=0
[npc-tacz] ysm allnpc: model='furina_v2.6' enabled=false affected=0
```
说明 `/tacznpc ysm` 已被正确注册、执行、能解析中文模型名。

**仍未改变的能力边界**：YSM 2.6.5 只渲染玩家/投射物/载具，模型文件加密且无公开实体接口，
所以 NPC 外观不会变化；绑定会永久保存（npcdebug/status 可见），等 YSM 支持实体后再生效。
## 21. 2026-11-20 整合包压力跑测与性能优化

**压力跑测**：测试世界 + 快速刷怪配置（10 秒一波、每波 5~8 只、范围 64~96、难度 3），
两轮共约 15 分钟，NPC 上限 ~288 只，观察日志/掉帧/异常。

**发现并修复**
1. `config/npc_tacz-common.toml` 里 `traceEnabled=true` 是之前排查遗留：
   - 5 分钟写了 **5.2MB** 日志（每 5 秒每只 NPC 一条 trace），严重影响 I/O；
   - 已改回 false；并在代码里限制 trace 只打印**玩家 48 格内**的 NPC。
2. 客户端 TACZ 第三人称动画同步原来每个 NPC 每 tick 写 8 个同步键：
   - 改为状态变化时立即写 + 每 10 tick 兜底，NPC 多时客户端开销明显下降。
3. 开火的视线检查：把“每发重新做 LOS”放到所有便宜判断之后，冷却/无反应时不跑 DDA。
4. 全局预算，避免几百 NPC 同 tick 尖峰：
   - 索敌扫描：每 tick 最多 24 次；
   - PathFinder 重算：每 tick 最多 16 次；
   超预算的 NPC 顺延到后续 tick（行为不变，只是错峰）。
5. 生成位置增加“与玩家高度差 ≤24 格”限制，避免刷在浮空岛顶/悬崖/洞穴正上方导致成片摔死。
6. 同一 tick 最多打印 8 条 NPC 死亡日志，避免成片死亡刷屏。

**结果**
- 无 `Ticking entity`、无致命异常、无崩溃；NPC 正常交火/换弹/死亡/补枪。
- 世界加载时出现过 1 次 “Can't keep up! 2219ms / 44 ticks behind”（区块+枪包预加载引起），
  之后不再出现；日志量比优化前下降约 10 倍。
- 日志里 `SimpleBedrockModel` 的 NPE 来自 TACZ 枪包 `emx_re:mk951lmg_display` 的 LOD 烘焙
  （TACZ/SimpleBedrockModel 自身问题），与本模组无关。
## 22. 2026-11-20 YSM 指令抽成独立附属 + A 方案假人代理原型

**1) 指令抽取**
- 主模组不再包含 YSM 指令/扫描代码（删除了 `compat/YsmCompat`、`command/YsmModelArgument`，
  NpcCommands 里的 ysm 段与帮助行全部移除）。
- 新增独立附属工程 `mod/addon-ysm`：
  - modId `npc_tacz_ysm`，jar `npc-tacz-ysm-1.0.0.jar`；
  - 内含 `YsmCommands`（/tacznpc ysm ...）、`YsmCompat`、`YsmModelArgument`、
    客户端可见性检查 `YsmProxyClientCheck`、A 方案原型 `YsmFakePlayerTest`；
  - 依赖主模组 `npc_tacz`（必须）+ YSM（可选，`yes_steve_model`）。
- 主模组保留 NPC 的模型绑定数据（`NpcYsmData`、实体上的 `NpcYsmModel` 同步字段/API），
  附属通过公开 API 读写；没有附属时主模组完全不引用 YSM。

**2) A 方案原型（假人渲染代理）**
- `/tacznpc ysm faketest [模型id]`：在最近玩家旁生成一个 Forge FakePlayer（名字 `YSMDummy*`），
  尝试：
  1. `level.addFreshEntity(fake)` 加进世界实体表；
  2. 反射取 FakePlayerNetHandler 的 Connection，`playerList.placeNewPlayer(...)` 注入玩家列表；
  3. 调 `/ysm model set <假人名字> <模型id> -` 让 YSM 给假人绑模型；
  4. 客户端每 40 tick 检查是否收到 `YSMDummy*` 实体并写日志。
- 目的：验证 YSM（官方支持地毯假人）是否会渲染 Forge FakePlayer；成功后把
  “NPC 隐藏 + 假人跟位”扩展到附近少量 NPC，失败则说明 YSM 只认真实连接玩家，A 方案不可行。

**状态**：代码已抽取、构建、部署；附属在游戏日志里确认注册成功
（`[npc-tacz-ysm] /tacznpc ysm 已注册`）。原型尚未完成端到端实机验证
（测试实例在 tick 100 前失焦暂停，而另一版本游戏同时运行）。
**A 方案原型实测结论（2026-11-20 21:54）**
- `/tacznpc ysm faketest` 服务端生成 Forge FakePlayer 成功：
  `fake player YSMDummy1: id=758 added=true tracked=true`；
- `playerList.placeNewPlayer(...)` 失败：`FakePlayerNetHandler` 的 DUMMY_CONNECTION 没有 netty channel
  （`Connection.channel()` 为 null），无法完成玩家加入流程；
- `/ysm model set YSMDummy... <model> -` 返回 0（玩家不在有效玩家列表/连接，YSM 不认）；
- 客户端**始终没有收到** YSMDummy 实体（无 `client sees fake player` 日志）→ Forge FakePlayer 不会被同步到客户端。
- 结论：A 方案用 Forge FakePlayer 走不通；真要实现需要自建 carpet 式“假连接 + netty channel + 玩家列表”完整链路，
  代码量和风险都很大，且还要确认 YSM 会为这种假人同步模型。目前按 B 方案（皮肤/GLB）保留。
## 23. 2026-11-20 AI 弹道解算 + 对玩家连续射击

**1) 弹道解算（NpcBallistics）**
- TACZ 子弹逻辑：每 tick 先按当前速度位移，然后 `velocity *= (1 - friction)`、`velocity.y -= gravity`
  （见 `EntityKineticBullet.tick`），不是射线，所以远距离直瞄会打低。
- 新类 `entity/ai/NpcBallistics`：
  - 从 `BulletData` 读 `speed / 20`（每 tick 格数）、`gravity`、`friction`；
  - `飞行 tick = 距离 / (速度 * (1 - friction/2))`，迭代 3 次：
    目标提前量 `targetEye + targetVelocity * t`，下坠补偿 `0.5 * gravity * t²`；
  - 输出补偿后的瞄准点，再由 `NpcAmmoState` 算 yaw/pitch；拿不到 BulletData 时退回原提前量逻辑。
- 改动点：`NpcAmmoState.shootAt` 使用 `NpcBallistics.aimPoint(...)`；新增 `bulletData()` 从
  `TimelessAPI.getCommonGunIndex(gunId).getBulletData()` 读取。

**2) 对玩家连续射击直到弹匣清空**
- 新配置 `combat.continuousFireOnPlayers`（默认 true）。
- 目标为玩家时：
  - 忽略点射停顿 `burstPause`（此前若正在停顿会先卡住）；
  - 受 TACZ 射击冷却/RPM 限制逐发打，直到弹匣 `< 0` → 自动换弹（沿用状态机）；
  - 对 NPC/怪物仍保留 burstShots + burstPauseTicks 的点射节奏。
- 配置在 `config/npc_tacz-common.toml` 的 `[combat]` 下自动生成。

**说明**：按用户要求本轮**没有启动 Java 游戏**做测试，只做构建与部署；实机表现需下次进游戏验证。
## 24. 2026-11-20 独立 NPC 上限 /tacznpc npcmax

- 上限存放在世界数据 `NpcDifficultyData.maxNpcs`，**独立于原版生物上限**，默认 **32**。
- 指令：
  ```
  /tacznpc npcmax            # 查看当前上限 + 全服当前 NPC 数
  /tacznpc npcmax <n>        # 设置上限，n 范围 1 ~ 2147483647
  ```
- 计数：`NpcSpawnEvents.countAllNpcs(server)` 统计**所有维度**已加载的 WastelandNpcEntity，
  结果缓存 20 tick（避免每次生成都全维度扫实体）。
- 生效范围：
  - 自然生成调度器每次真正生成前检查：达到上限就不再刷，未刷完的批次直接丢弃；
  - `/tacznpc spawn` 指令在达到上限时也只生成剩余额度，并提示
    “NPC 上限 X，当前 Y 个，已跳过剩余生成数”；
  - 旧存档没有 MaxNpcs 字段时按默认 32 处理。
- help 中英文都加了 npcmax 说明。
## 25. 2026-11-20 加强玩家被 NPC 枪械命中的视角抖动

- 服务端（CombatEvents）：
  - 基础 pitch 上跳：1.3~2.2° → **2.8~4.6°**；
  - 基础 yaw 偏移：±0.6° → **±1.6°**；
  - 增加伤害比例：damageScale = clamp(伤害/10, 0.6, 1.6)，重枪/高伤害抖得更明显；
  - 新配置 `combat.hitKickStrength`（默认 1.0，范围 0~5；0 = 完全关闭，2 = 双倍）。
- 客户端（HitKickHandler）：
  - 最大累计偏移 6° → **14°**；
  - 每 tick 回正比例 0.35 → **0.5**（更顿挫、回正更快），并叠加随机微抖动，
    连续命中时准星会抖而不是匀速回正。
- 说明：抖动仍然只在“NPC 的 TACZ 子弹直接命中玩家”时触发。
## 26. 2026-11-21 tacz-npc-modif 全局配置文件 + 诊断/指令优化

**新文件：`config/tacz-npc-modif`（每个版本目录独立，首次启动自动生成）**
格式：`//` 注释；`代码 : 数值/true/false`；中文解释写在代码上一行；支持中文冒号。
可修改项（已接线生效）：
```
target_creative_players        是否攻击创造玩家（默认 false）
target_spectator_players       是否攻击旁观玩家（默认 false）
weak_run_distance              弱运行距离（默认 96）
trace_enabled                  调试 trace（默认 false）
drop_loot                      死亡是否掉落（默认 true）
combat_damage_multiplier       NPC 对玩家伤害倍率（默认 1.0）
damage_cap_difficulty1~4       各难度单发伤害上限（6/9/12/15）
camera_kick_strength           命中视角抖动倍率（0~5）
continuous_fire_players        对玩家连射到弹匣清空（默认 true）
accuracy_multiplier            散布倍率（越小越准）
spread_degrees                 基础散布（度）
reaction_time_multiplier       反应时间倍率
refill_seconds_ranged          远程补弹秒数
refill_seconds_melee           近战补弹秒数
vision_through_glass           能否看穿玻璃
vision_through_leaves          能否看穿树叶
vision_max_distance            视线最大距离
friendly_fire_safety           友军挡线不开火
same_faction_immune            同阵营免伤
npc_max_default                新世界默认 NPC 上限
```
- 修改后 `/tacznpc reloadconfig` 立即重读（`npc_max_default` 与生成类下次生效）。
- 代码位置：`config/NpcGlobalConfig` + 各调用点（WastelandNpcEntity/CombatEvents/NpcSight/NpcReactionTime/NpcAmmoState/NpcRangedCombatGoal/NpcCombatHelper/NpcDifficultyData）。

**指令优化**
- 新别名 `/tnpc`（同 `/tacznpc`）。
- `/tacznpc diagnose`：一键输出 世界难度/玩家模式/上限与全服数量/TACZ/YSM/最近 NPC 状态(枪、弹匣、目标、卡住、弱运行)/全局配置路径；
  创造模式时会直接提示可改 `target_creative_players`。
- `/tacznpc config`：查看已解析的 tacz-npc-modif 项（前 40 项）。
- `/tacznpc reloadconfig`：重读配置文件。
- `/tacznpc npcmax [n]`：独立 NPC 上限（前面已加）。
- help 中英文补充这些指令。
## 27. 2026-11-21 修复“打不到 NPC / NPC 不自然生成”+ 批量 bug 修复

**A. 打不到 NPC（伤害链路硬化 + 自检）**
- `WastelandNpcEntity`：
  - spawn 时强制 `setInvulnerable(false); invulnerableTime = 0`（防止存档/刷怪蛋带无敌标记）；
  - 新增 `isInvulnerableTo`：玩家/玩家子弹来源永远返回 false（近战/枪械都能打）；
  - 前 8 次受伤写日志：`npc hurt: victim/source/direct/amount/result/health`，方便定位。
- 新指令 `/tacznpc selftest`：附近没 NPC 会先生成一只，然后对最近 NPC 施加 5 点玩家伤害，
  输出 `血量前→后 / hurt / 无敌 / 存活`，直接判断“玩家能不能打中 NPC”。

**B. NPC 不自然生成（上限/公告/清理）**
- 根因：原调度器先发“附近出现 x N”公告再逐只生成，但**达到 32 上限时全部丢弃**，
  而且旧存档里远处/空闲 NPC 永久占上限 → 玩家只看到公告、看不到实体；重启后倒计时又要从头等 10 分钟。
- 修复：
  - `scheduleBatch` 先算“上限 - 当前数量”，按真实容量裁剪；容量 0 时先尝试清理一个远处空闲 NPC，
    仍满就整批不排、不发假公告；
  - `processPending` 同样先腾位置；生成成功即作废计数缓存（`invalidateCountCache`）；
  - 每 100 tick `pruneIdleNpcs`：数量接近上限时，清掉“远离所有玩家(>96格)、无目标、非雇佣、
    非赏金猎人、生成>30秒”的 NPC，释放上限并降负载；
  - `/tacznpc clear` 同时清空待生成队列；
  - `spawnForCommand` 生成后作废计数缓存，上限立即反映。

**C. 指令/诊断增强**
- `/tacznpc selftest`：伤害自检（见上）。
- `/tacznpc spawnnow [类型|all]`：立即按自然生成规则刷一波（受上限约束），用来验证“自然生成”。
- `/tacznpc diagnose` 增加自然生成计时：每个阵营下一次刷新的秒数（`nextSpawnSummary`）。
- `/tacznpc help` 同步。
## 28. 2026-11-21 尸体残留修复 + 存档实测伤害正常

**存档日志证据（亡者世界 #1）**
```
00:33:48 [npc-tacz] NPC died: faction=SOLDIER killer=xiaoliuliulllll
00:33:48 [npc-tacz] npc hurt: victim=士兵 source=xiaoliuliulllll amount=343.75 result=true health=0.0
```
→ 玩家伤害链路是通的（一击 343 伤害直接打死），“打不到 NPC”不是伤害被拦截；截图里立着的是**尸体/渲染残留**问题。

**修复**
1. `WastelandNpcEntity.tick()`：死亡后（`isDeadOrDying()` 或血量<=0）立即停止 AI/寻路/弹药 tick，
   记录 `corpseTicks`，达到 `tacz-npc-modif` 的 `corpse_lifetime_ticks`（默认 20 tick=1 秒）后 `discard()`，
   原版删除逻辑之外再加一层兜底。
2. `GlbSoldierRenderer.render()`：死亡实体改用原版人形 fallback 渲染（会正常倒地/消失），
   不再让自定义 GLB 模型站着当“永久尸体”。
3. 新增 `/tacznpc clean [半径]`：手动清理半径内（默认 128）所有死亡/血量<=0 的 NPC，残留尸体可一键清。
4. 空閒 NPC 清理距离从 96 格改为 **160 格**，避免清掉玩家仍在追踪的实体，减少
   `Fetching packet for removed entity ... removed=DISCARDED` 残影警告。
5. `tacz-npc-modif` 新增 `corpse_lifetime_ticks`（默认 20，最小 1），改完 `/tacznpc reloadconfig` 即时生效。

**说明**：日志里 TACZ `hk_mk23`/`suffuse:knife` 解析错误是客户端/服务端枪包版本不一致，不属于本模组；
`ThreadLocalRandom accessed from a different thread` 来自其它模组。
## 29. 2026-11-21 枪械伤害兜底 + NPC 弹道速度修正

**A. TACZ 枪械伤害兜底（GunDamageGuard）**
- 监听 TACZ `EntityHurtByGunEvent.Pre`（服务端），目标为本模组 NPC 时：
  - 前 10 次记录 `[npc-tacz] gun hit pre: npc/gun/amount/headshot/health`；
  - 下一 tick 检查 NPC 血量与 `hurtEventCounter` 是否变化；
  - 若都没变（TACZ 伤害被其它模组/事件吞掉），按 TACZ 的 `baseAmount` 用
    `damageSources().playerAttack/mobAttack` 补一次伤害，日志 `[npc-tacz] gun damage fallback`；
  - 配置 `tacz-npc-modif` 的 `gun_damage_fallback`（默认 true）可关闭。
- `WastelandNpcEntity` 新增 `hurtEventCounter()`，每次 hurt 自增，用于防重复补伤。

**B. NPC 弹道初速修正（NpcBallistics）**
- TACZ 实际子弹初速 = `bullet.speed * [ammo] GlobalBulletSpeedModifier / 20`；
  之前只用了 `speed / 20`，在 `GlobalBulletSpeedModifier=2.0` 的整合包里提前量/下坠算多，
  NPC 会打偏。现在读取 `AmmoConfig.GLOBAL_BULLET_SPEED_MODIFIER` 修正。

**C. 存档检查发现的外部枪包问题（不是本模组）**
- `versions/亡者世界/tacz/tacz_default_gun/data/tacz/data/guns/hk_mk23_data.json`
  首行是 `// gun_buff_v1` 后面直接 `"bolt_feed_time"...`，**缺少开头 `{` 及前面的字段**，
  导致 TACZ `Failed to load data file tacz:hk_mk23_data`；同目录 `.bak` 同样缺头。
- `versions/亡者世界/tacz/ocle/` 是旧版 `pack.json` 结构，TACZ 1.2.0 读不到
  `gunpack.meta.json` → `GunPackFinder` 警告。
- `suffuse:knife` 只有 index，没有对应 data 文件。
- 这些属于枪包/整合包数据本身的问题，需要重新安装对应枪包或删除损坏枪包解决。
## 30. 2026-11-21 爆炸物/满配件修复 + 亡者世界兼容 + DLC 化

**A. 爆炸物配件/枪**
- `NpcGunRules` 改为 token 匹配：`glock` 不再被 `"gl"` 误判成榴弹；`he` 只匹配独立 token；
- 爆炸枪判定加 `ExplosionData.isExplode()`（以前只要存在 explosion 配置就误杀普通枪）；
- `NpcAmmoState.shootAt`：旧存档/manual 改出的爆炸枪在开火前直接换普通枪；
- 枪池过滤 `gunData==null / bulletData==null` 的坏枪包数据，防止 TACZ 数据损坏导致 NPC 空手。

**B. 100% 满配件**
- `installRandomAttachments` 现在用 `IGun.allowAttachment(gun, attachment)` 做真实兼容性校验，
  每个允许槽位随机候选并**逐个重试直到装上**（不是只看类型）；
- 兜底枪 `equipKnownGun` 也补满配件；
- 旧存档 NPC 每 600 tick 检查一次缺失槽位并补齐（只在缺配件时扫描附件表，避免性能问题）；
- 新指令 `/tacznpc attach [半径]` 手动给范围内 NPC 补齐配件（默认 32 格）；
- `/tacznpc npcdebug` 增加“配件=”一行输出，可直接看到每个槽位装了什么。

**C. 亡者世界/TACZ 兼容**
- `GunDamageGuard`（上轮加入）继续工作：TACZ 枪伤被其它模组吞掉时按 baseAmount 兜底补伤，
  并保留 `gun hit pre` / `gun damage fallback` 日志；
- TACZ 枪包/数据解析失败、`ex_cap`/CustomNPCs 等扩展存在时，所有 TACZ API 调用均有 try/catch，
  NPC 装备/配件/开火不会因单个坏枪包抛异常中断；
- 测试世界已验证 TACZ 子弹能命中 NPC：日志出现 `gun hit pre ... health=...` 且血量下降。

**D. DLC 化**
- `deploy.ps1` 改为自动发现 `mod/addon*` 下所有附属工程（DLC 放进去即自动构建/校验/分发）；
- 新增 `docs/DLC.md` 与 `mod/dlc-template` 最小模板（复制为 `mod/addon-xxx` 即可用）；
- 分发保证主模组 + 所有附属一起进两个版本目录与 `_history`。

**E. 测试说明**
- 自动客户端测试在本机容易被 Windows 焦点/暂停打断（日志出现 `Saving and pausing game`），
  已改为日志诊断 + 指令自检；最终实机效果请用 `/tacznpc selftest`、`/tacznpc npcdebug`、`/tacznpc attach` 验证。
## 31. 2026-11-21 满配件真实修复 + 自动测试验证

**根因**：TACZ 的 `IGun#getAttachmentId` 对空槽返回 `tacz:empty` 而不是 `null`，
之前代码用 `!= null` 判断“已装”，导致所有空槽被跳过 → NPC 永远没有配件。

**修复**
- 所有配件空槽判断改用 `DefaultAssets.isEmptyAttachmentId(...)`：
  - `installRandomAttachments` 跳过/安装成功判断；
  - `hasMissingAttachment`；
  - `repairGunAttachments` 前后计数；
  - `/tacznpc npcdebug` 配件显示。
- `/tacznpc attach` 现在能真正补齐；测试中 `repaired=0` 表示已经满配件。
- spawn 指令生成数量修复：尝试次数 10→24、间距判定放宽，仍失败时退化为玩家身边生成；
  测试日志已从 `requested=2 spawned=1` 变为 `requested=2 spawned=2`。
- npcdebug 只统计枪真正允许的槽位，并输出 `(已满)` / `(缺 N 个)`，避免把不允许的 STOCK 槽误报成缺配件。

**自动测试证据（npc测试 测试世界）**
```
[npc-tacz] command spawn: type=soldier requested=2 spawned=2
[npc-tacz] npcdebug attachments:
  SCOPE=tacz:sight_apm4s MUZZLE=tacz:muzzle_silencer_phantom_s1
  GRIP=atea:grip_shift LASER=suffuse:laser_pistol
  EXTENDED_MAG=emx_re:emxre_mag_light
[npc-tacz] gun hit pre: npc=劫匪 gun=tacz:g36k amount=52.0 health=21.13
[npc-tacz] command attach: radius=64 npcs=3 repaired=0
```
- 说明：g36k 本身不允许 STOCK，所以只统计 5 个允许槽且全部有配件；
  若某枪允许的槽位在枪包里没有兼容配件，则无法补满，属于枪包数据限制。

**待办**
- 亡者世界真实整合包内再跑一次：枪伤/配件/爆炸枪过滤/自然生成上限；
- DLC 模板工程可编译性验证（复制为 mod/addon-demo 后跑 deploy）。
## 32. 2026-11-21 亡者世界真实整合包验证 + audit + DLC 模板

**亡者世界（真实 300+ 模组整合包）测试结果**
- 用改版 `launch_test.ps1`（支持 -VersionName / -World）把测试世界复制进亡者世界版本启动；
- 日志证据：
```
[npc-tacz] command spawn: type=soldier requested=2 spawned=2
[npc-tacz] npcdebug attachments:
  SCOPE=emx_re:sight_char2
  MUZZLE=gucci_attachments:muzzle_s_z60
  STOCK=suffuse:stock_bcm_mod2_sopmod
  GRIP=tacz:grip_rk6
  LASER=cib:csol3
  EXTENDED_MAG=tacz:ammo_mod_i
  missing=0
[npc-tacz] command attach: radius=64 npcs=4 repaired=0
```
- 六个允许槽位全部装上真实配件（其中 EXTENDED_MAG 装的是 ammo_mod，非爆炸类），
  说明“100% 满配件”在亡者世界枪包组合下成立；attach 显示已满。
- 枪包数据损坏（hk_mk23 等）存在时 NPC 装备/配件/开火没有异常中断。

**新增 `/tacznpc audit [半径]`**
- 统计范围内 NPC：
  - 爆炸枪数量；
  - 爆炸配件数量（逐个槽位检查）；
  - 缺配件槽总数；
- 结果同时输出到聊天与日志：`command audit: ... explosiveGuns= ... explosiveAttachments= ... missingAttachments=...`
- 用于快速确认“爆炸物/满配件”两个目标是否达成。

**DLC 模板验证**
- `mod/dlc-template` 复制为 `mod/addon-demo` 后 `gradle build` 成功，产出 `npc-tacz-dlc-demo-1.0.0.jar`；
- 修正模板 `settings.gradle`（补 pluginManagement 仓库）和 `local-maven` 相对路径；
- 验证后已删除 demo 工程，`deploy.ps1` 的 addon* 自动发现仍然生效。

**launch_test.ps1**
- 新增 `-VersionName` / `-World` 参数，可直接启动任意版本/存档（如 `-VersionName 亡者世界 -World npc_rewrite_test`），
  便于以后兼容性测试。

**剩余**
- 在亡者世界里跑 `/tacznpc audit 64` 复验爆炸物为 0；
- 补充“优先选择配件槽位齐全枪械”的选枪策略（可选优化）。
## 33. 2026-11-21 亡者世界 audit 验收（爆炸物/满配件双 0）

在亡者世界真实整合包 + 测试副本地图中执行：
```
[npc-tacz] command spawn: type=soldier requested=2 spawned=2
[npc-tacz] npcdebug attachments:
  SCOPE=cib:qmk204 MUZZLE=emx_re:muzzle_sl_bti23
  GRIP=emx_re:grip_threshold LASER=tacz:laser_ngal
  EXTENDED_MAG=tacz:ammo_mod_hp missing=0
[npc-tacz] command audit: radius=64 npcs=4
  explosiveGuns=0 explosiveAttachments=0 missingAttachments=0
[npc-tacz] command count: radius=256 total=14 ranged=14 melee=0 weak=0
[npc-tacz] command attach: radius=64 npcs=10 repaired=0
```
结论：
- 爆炸枪 0、爆炸配件 0（audit 验证）；
- 允许槽位 `missing=0`，attach `repaired=0` → 满配件成立；
- spawnnow 后 NPC 数从 4 增至 14，独立上限/生成链路正常；
- TACZ 枪包损坏（hk_mk23 等）与多个 TACZ 扩展共存时无本模组异常。

测试结束已删除亡者世界测试副本；正式存档未改动。
## 34. 2026-11-21 弱运行距离 96→128 + /tacznpc weakrun

- `NpcGlobalConfig` 默认 `weak_run_distance`：96 → **128**；
- 旧配置文件自动迁移：`config_version < 2` 且值仍为 96 时改为 128，并写入 `config_version : 2`；
  之后玩家用指令改成 96 也不会再被迁移覆盖。
- 新增指令：
  ```
  /tacznpc weakrun              # 查看当前弱运行距离
  /tacznpc weakrun <16~4096>    # 设置并写入 config/tacz-npc-modif，立即生效
  ```
- 已把两个版本的现有 `config/tacz-npc-modif` 中 `weak_run_distance` 改为 128 并补 `config_version : 2`。
## 35. 2026-11-21 YSM 内容全部迁入附属 + 许可证

**YSM 解耦**
- 主模组删除 `com/npctacz/data/NpcYsmData.java`，实体内所有 YSM 命名改为通用模型 API：
  - `getModelId() / setModelId() / setModelExplicit() / hasExplicitModel() / clearExplicitModel()`
  - 同步字段改为 `DATA_MODEL_ID`；主模组只负责存储/同步/NBT
- `npc-tacz-ysm` 附属新增：
  - `YsmData`（沿用旧 SavedData 名 `npc_tacz_ysm`，旧世界的阵营/全体默认模型自动继承）
  - `YsmDefaultEvents`：NPC 加入世界时按“阵营默认 → 全体默认”套用模型
- 旧实体 NBT `NpcYsmModel/NpcYsmExplicit` 在主模组加载时迁移到新键 `NpcModel/NpcModelExplicit`
- 附属删除 `YsmFakePlayerTest`、`YsmProxyClientCheck` 和 `/tacznpc ysm faketest`（降低 YSM ARR 与假人注入风险）
- 主 jar 已确认不包含任何 Ysm/yes_steve 类或资源；`/tacznpc ysm ...` 只在装附属时存在

**许可证**
- 主模组、`npc-tacz-wangwenhao`、`npc-tacz-ysm`、`dlc-template` 统一为 **GPL-3.0-or-later**
- 所有 jar 增加 `LICENSE`（GPL-3.0 全文）
- 新增 `CREDITS.md`：同学原创模型/贴图按 GPL-3.0-or-later 随项目分发；TACZ/YSM 作为运行时依赖，不打包其资源
- 顶层 `.gitignore` 排除 `libs/*.jar`、build、logs，避免误分发 TACZ 本体

## 36. 2026-11-21 亡者世界 移动/攻击/被攻击 排查与修复

**排查结论（亡者世界测试副本）**
- 玩家生存模式时：`/tacznpc selftest` 对 NPC 造成 5 点伤害成功：
  `npc hurt: victim=士兵 source=DSHTest direct=player amount=5.0 result=true`
- 测试世界里 NPC 之间会互相用 TACZ 子弹交火：
  `npc hurt: ... direct=entity.tacz.bullet amount=... result=true`
  且 NPC 位置随时间变化（移动正常）。
- 发现一个真实代码问题：`setTarget` 与 `SoldierNpc/BountyHunterNpc.isHostileTo` 里存在**硬编码的创造/旁观过滤**，
  即使 `tacz-npc-modif` 的 `target_creative_players=true` 也不会攻击创造玩家 → 玩家在创造模式下会感觉“NPC 不动、不攻击”。

**修复**
- `WastelandNpcEntity.setTarget`、`SoldierNpc.isHostileTo`、`BountyHunterNpc.isHostileTo/setTarget`
  全部改为读取 `target_creative_players` / `target_spectator_players` 配置；
- 两个版本现有 `tacz-npc-modif` 已设 `target_creative_players : true`；
- 新增 `/tacznpc targetcreative [true|false]`：直接修改配置并立即生效；
- 新增 `/tacznpc wake [半径]`：清除范围内 NPC 的 NoAI/嘲讽/目标并重新索敌，
  用于整合包其它 AI 模组干扰后的“解冻”；
- `npcdebug` 增加 `NoAI=`、`Pos=` 输出，便于区分“目标/移动/LOS”问题。
## 37. 2026-11-21 亡者世界复测（YSM 解耦后）

- 主模组 npcdebug 已没有 YSM 字样，输出 `Model=furina_v2.6(默认)`，说明：
  - 主模组只保留通用模型 id；
  - `npc-tacz-ysm` 的 `YsmData` + `YsmDefaultEvents` 在亡者世界成功把旧默认模型套到新 NPC 上。
- 亡者世界中 `/tacznpc selftest` 对 NPC 造成 5 点玩家伤害成功（`result=true`），说明“被攻击”链路正常。
- 同版本代码在 npc测试世界实测 NPC 对 NPC 使用 TACZ 子弹交火：
  `npc hurt: ... direct=entity.tacz.bullet amount=... result=true`，位置随时间变化，说明移动/射击正常。
- 亡者世界测试里 NPC 静止是因为没有敌人/目标（target=无），不是 NoAI 或弱运行冻结；`NoAI=false、weak=false、卡住=false`。
- 新增 `/tacznpc wake [半径]` 可清理被其它 AI 模组干扰后的 NoAI/嘲讽/目标；`/tacznpc targetcreative true|false` 可直接改配置。
- 若实机仍无法被 TACZ 枪伤害，优先检查枪包数据损坏（本存档 hk_mk23_data.json 缺 `{`），而不是本模组伤害事件。
## 38. 2026-11-21 targetdebug：士兵中立/创造玩家判定

在亡者世界测试副本里新增 npcdebug 输出：
```
HostilePlayer=false LOS=true ScanR=96 NoAI=false weak=false target=无
```
结论：
- `target_creative_players=true` 配置已生效（日志 `target_creative_players = true`）；
- 士兵（SoldierNpc）本身是**中立的**：只有被玩家打（lastHurtByMob）或该玩家是它的 hunted target 时才会敌对，
  所以 `HostilePlayer=false` 是设计行为，不是 AI 冻结；
- 劫匪/叛军/雇佣兵走基类判定，会按配置攻击创造玩家；
- 想测试“NPC 主动攻击”请用 bandit/rebel，或先打一下士兵触发反击；
- 若在整合包里 NPC 仍然不反击，优先运行 `/tacznpc wake 64`，再检查是否有别的 AI 模组
  （亡者世界里的 dochi_warfare/CustomNPCs 会接管部分 AI）。
## 39. 2026-11-21 创造玩家攻击修复实测（移动/攻击/被攻击）

修复 `SoldierNpc` 在 `target_creative_players=true` 时仍然中立的逻辑后，npc测试世界实测（玩家创造模式）：

```
command spawn: type=soldier requested=1 spawned=1
shot fired gun=tacz:m4a1 mag=29 target=DSHTest
npcdebug: ... target=DSHTest HostilePlayer=true LOS=true
  NoAI=false weak=false Pos=-12, 93, 4 -> -13, 93, 4 -> -15, 94, 5
```

结论：
- 创造模式下士兵现在会主动敌对、锁定玩家并连续开火；
- 位置持续变化 → 移动正常；
- TACZ 枪械对玩家开火正常；
- 此前 `HostilePlayer=false` 是士兵“中立设计”导致，不是 AI 冻结；
- 想保持士兵中立，把 `tacz-npc-modif` 的 `target_creative_players` 设回 false 即可。

至此本轮三项均验证：
1. YSM 全部内容在附属，主 jar 无 Ysm 类/资源；
2. GPL-3.0 许可证 + CREDITS 写入；
3. 创造玩家攻击/移动/TACZ 开火实测通过。

剩余风险：YsmCompat 仍读取 `.ysm` 前 4KB 显示名；公开分发前建议改文件名扫描或取得 YSM 作者许可。
## 40. 2026-11-21 YSM 文件解析移除 / hk_mk23 枪包修复

**1) YSM 协议风险处理**
- `YsmCompat` 不再读取 `.ysm` 文件前 4KB 解析 `<name>`，只按文件名扫描模型 id；
- `displayName()` 恒返回空串；`/tacznpc ysm` 只支持模型 id / `{name="id"}`，不再解析模型内部显示名；
- 这样不再涉及对 YSM ARR 文件的格式逆向，降低协议风险。

**2) hk_mk23 枪包数据修复**
- 损坏文件（缺开头 `{`）已用 TACZ 本体 jar 内的干净数据恢复：
  `assets/tacz/custom/tacz_default_gun/data/tacz/data/guns/hk_mk23_data.json`（3185 字节）；
- 已修复：npc测试、亡者世界、版本1、生存、亡者世界 cf_stage overrides、tacz-removed 副本；
- 原损坏文件备份为 `*.json.dsh-corrupt-bak`；
- 容错解析验证通过（含 TACZ 的 `//` 注释）。
- `suffuse:knife` 缺 data 文件的问题仍属于枪包本身不完整，需要重装对应枪包。

**3) 素材授权**
- 新增 `docs/ASSET_LICENSE_TEMPLATE.md`：给同学的授权话术/许可协议模板；
- CREDITS 增加署名占位；GPL-3.0 许可证保持。
## 41. 2026-11-21 fallback AI 强化 + 全文件检查

- 原 fallback 只有在“战斗/巡逻 goal 都没有运行”时才生效；亡者世界整合包会把 goal 标记为运行但实际不执行，
  导致 fallback 被跳过、NPC 仍然不动。
- 新 fallback（`fallback_ai` 默认 true）不再依赖 goal 状态：
  - 每 10 tick 强制扫描目标（无视 goalSelector）；
  - 目标距离 > 2.5 格：直接 `navigator.moveTo(target)`；
  - 目标距离近且能看见：直接 `shootAt(target)`；
  - 若实体被其它模组置为 NoAI 且不在嘲讽中，自动 `setNoAi(false)`；
  - 日志：`fallback move` / `fallback shoot` / `fallback AI reset NoAI`。
- 已确认文件层面无坏 JSON、主包无 Ysm 资源；`hk_mk23_data.json` 已修复。
- 由于玩家游戏运行中无法通过 deploy.ps1 替换，已手动把新 jar（313,870 字节）覆盖到两个版本；
  正在运行的游戏仍加载旧类，**必须完全退出并重启**后才生效。
## 42. 2026-11-21 根因：NPC 在 EntityJoinLevelEvent 阶段被其它模组移除

**现象**
`npcdebug` 显示：
```
tick=0 HP=40.0/40 alive=false removed=true reason=DISCARDED
```
HP 满、没有 NoAI，但 `alive=false / removed=true`，所以服务端永远不会 tick，客户端看起来就是站桩 NPC。

**证据（亡者世界测试世界，原生 /summon 也一样）**
```
NPC join HIGHEST: canceled=false removed=false
NPC joined level: ... removed=true reason=DISCARDED
protected NPC join: canceled=false removed=true (another mod tried to remove it)
```
说明：在 `EntityJoinLevelEvent` 分发过程中，另一个模组的监听器直接 discard 了 NPC（不是我们的生成代码/死亡逻辑）。

**修复**
`NpcTickWatchdog` 增加对 NPC 加入事件的保护：
- HIGHEST 优先级先 `setCanceled(true)` 占住事件，让其它模组的 join 处理器跳过；
- LOWEST + receiveCanceled 再 `setCanceled(false)` 放行；
- 如果实体已被标记 DISCARDED，反射清空 `Entity.removalReason`；
- 只对 `WastelandNpcEntity` 生效，不影响其它实体。

另外把 `immersive_optimization` 配置加入：
```json
"entities": { "npc_tacz": false }
```
避免 Immersive Optimization 的实体 tick 调度器跳过我们。

**修复后实测（亡者世界，原生 /summon + 命令生成）**
```
npcdebug: tick=30 HP=35.0/35 alive=true removed=false reason=null
          target=DSHTest HostilePlayer=true LOS=true
          Pos=5,93,1 -> 0,91,0 -> 4,92,4
fallback move: faction=BANDIT target=DSHTest dist=5.0
NPC left level: reason=KILLED tick=212
```
→ tick 正常推进、会索敌/移动/开火/正常死亡；此前“不会动”的 NPC 实际是被移除的幽灵实体。

**部署**
- jar `320545` 字节 SHA256 `6EFB5633321E2229678CAADAB25AA62A370B55E33735B4420E5CFBCECBC49369`
- 备份 `post_joinfix_20261121_153143`
- 测试世界已删除；`pauseOnLostFocus` 已恢复 true。
## 43. 2026-11-21 YSM 指令改为显示 / 爆炸属性清理

**YSM**
- `/tacznpc ysm` 根指令改为直接显示：
  - 最近 NPC 的名称、阵营、当前模型
  - 全体默认模型
  - 前 12 个可用模型，每个后面带可点击的 `[设为最近NPC]`
  - 超出部分提示用 `/tacznpc ysm list`
- 不用再手打模型 id 才能看到当前状态；设置仍可用
  `/tacznpc ysm <目标|模型id> <模型id> [true|false]`。

**爆炸属性**
- `NpcGunRules` 新增**数据级**爆炸配件判定：
  - 读取 `CommonAttachmentIndex.getData().getModifier()`，命中 `explosion/explosive/grenade` 即视为爆炸配件；
  - 不再只靠 id 关键字，避免改名的爆炸弹头漏过。
- NPC 装配配件时同时过滤 id + 数据爆炸属性；
- `sanitizeExplosiveAttachments()` 改用数据级判定，旧存档里的爆炸配件会被卸下；
- `tickEquipmentFallback` 会检测 NPC 当前枪械：若枪本身是爆炸枪（id 或 BulletData.explosion.explode），
  直接清空主手并重新挑一把非爆炸枪。
## 44. 2026-11-21 禁用逆天配件（伤害 x>3 / 其它 +60% 以上）

`NpcGunRules.isOverpoweredAttachment(CommonAttachmentIndex)` 新增数据级判定：

- 读取 TACZ 配件的 `AttachmentData.modifier`；
- `damage` / `headshot`：倍率 > 3 视为逆天；
- 其它属性（recoil / inaccuracy / rpm / knockback / effective_range 等）：
  - `multiplier > 1.6` 或 `percent > 0.6`（即 +60% 以上）视为逆天；
- 良性属性（weight / silence / smoke / flash / extended_mag_level / melee / ads / sprint / draw / reload）不参与；
- 支持 recoil 的 fastutil Pair（pitch/yaw）和 inaccuracy 的 Map 结构；
- NPC 装配配件时直接过滤；
- 旧存档 NPC 每 100 tick 的 `sanitizeExplosiveAttachments()` 会同时卸掉爆炸配件和逆天配件。
## 45. 2026-11-21 全局 NPC 管理员界面（Alt+Shift+N）

**入口**
- 游戏内按 `Alt+Shift+N` 打开；仅 OP（permission level >= 2，联机管理员同样）可以编辑，普通玩家只读。
- 客户端只负责显示，所有修改走 C2S 包，服务端二次校验 OP 后才写入世界数据并回发快照。

**持久数据（NpcDifficultyData）**
- GlobalDamageMultiplier（默认 1，0.1~10）
- GlobalFireRateMultiplier（默认 1，0.1~5）
- DropGuns（默认 true）
- DropAmmo（默认 true）
- AmmoDropMultiplier（默认 1，0~5）
- Difficulty（1~4，已有）
- 生成数量/间隔复用 NpcSpawnConfigData：每个 NpcFaction 的 amountMin/amountMax/intervalTicks；GUI 的“全部生成间隔统一”会同时写 5 个阵营。

**实际效果**
- 伤害：CombatEvents 对 NPC 造成的伤害乘以 `GlobalDamageMultiplier × 难度 damageMultiplier`。
- 射速：NpcAmmoState 的 shotCooldown 按 `getNpcRpm × GlobalFireRateMultiplier` 计算。
- 掉枪：基类 dropGunItem + 5 个子类掉落逻辑读取 DropGuns。
- 掉弹：基类 dropAmmo 读取 DropAmmo，数量乘以 AmmoDropMultiplier。
- 生成：GUI 直接修改 NpcSpawnConfigData，修改后调用 NpcSpawnEvents.onConfigChanged()。
- 移动速度：5 个 createAttributes 的 MOVEMENT_SPEED 统一改为 0.09（原版玩家 0.1 的 0.9 倍）；finalizeSpawn/readAdditionalSaveData 再强制一次，旧存档也生效。

**网络**
- NpcNetwork 新增 C2S AdminSyncRequestMessage / AdminUpdateMessage 和 S2C AdminSettingsMessage。
- 消息 id 追加在 HitKick 之后，协议版本仍为 1；客户端/服务端需同时更新。
## 46. 2026-11-21 管理员界面改为 Alt+N / 全部手填数字 / 第三页

- 快捷键从 Alt+Shift+N 改为 **Alt+N**；使用 KeyMapping（默认 N，可在控制里改键），打开时检查 Alt。
- 数值全部改为 **EditBox 手填 + 应用**，不再使用滑块：
  - 全局 NPC 伤害倍数
  - 全局 NPC 射速倍数
  - 世界难度 1-4
  - 掉落子弹数量倍数
  - 全服 NPC 数量上限
  - 每个阵营的生成数量 min/max、间隔秒
  - 全部统一间隔秒
- 界面扩为 3 页：
  1. 战斗与掉落：伤害/射速/难度/枪械掉落/子弹掉落/子弹数量倍率
  2. 每个阵营生成：soldier/bandit/rebel/bounty_hunter/mercenary 分别填 min/max/间隔，逐行应用
  3. 世界：全服上限、当前已生成/上限、自然生成总开关、生成提示、全部间隔统一、恢复默认
- 快照新增 maxNpcs/currentNpcs/spawnEnabled/generatePrompt；重置默认会恢复伤害/射速/掉落/生成/难度。
- NPC 移动速度 0.09（玩家 0.1 的 0.9 倍），旧存档加载时强制。
## 47. 2026-11-21 NPC 拾取背包（9 格 / 每格 64 / 死亡 100% 掉落）

- WastelandNpcEntity 新增 `pickupInventory`：`NonNullList<ItemStack>`，9 格，每格最多 64 个。
- 每 10 tick 扫描周围 8 格内 `ItemEntity`：
  - 跳过还在拾取延迟中的物品；
  - 先叠加到同类物品槽，再放空槽；
  - 每个槽严格限制 64，装不下的部分留在地上；
  - 拾取概率 100%（不随机）。
- 死亡时在 `dropCustomDeathLoot` 里额外调用 `dropPickupInventory()`：
  - 9 格里的所有物品 100% 以掉落物形式生成；
  - 不受管理员界面“枪械/子弹掉落”开关影响（拾取物属于 NPC 自己的背包）。
- NBT：`NpcPickupInventory` 保存/加载，旧存档没有该键则背包为空。
- `/tacznpc npcdebug` 增加 `拾取=N` 显示当前背包物品总数。
## 48. 2026-11-21 帧率优化

- `ai_watchdog` 默认关闭（排查 tick 被整合包跳过时才打开）：不再每 20 tick 对玩家周围实体做一次全量扫描。
- `fallback_ai` 默认关闭，并且运行时先检查战斗/巡逻 goal 是否在跑；goal 正常时不再重复寻路/射击，避免双倍 AI 开销。
- NPC 拾取改为：
  - 每 20 tick（1 秒）一次；
  - 按实体 id 错开，不再同一 tick 几十个 NPC 同时 `getEntitiesOfClass`；
  - 背包满时直接跳过，不扫实体。
- 现有两个版本 `tacz-npc-modif` 已写入：
  ```ini
  ai_watchdog : false
  fallback_ai : false
  ```
- 如果以后某个整合包又把 NPC tick 掐掉，临时把 `ai_watchdog`/`fallback_ai` 改回 true 即可。
## 49. 2026-11-21 NPC 掉落枪械默认全自动

- `NpcGunRules.applyBestFireMode(ItemStack)`：
  - 读取 TACZ `GunData.getFireModeSet()`；
  - 优先 `AUTO`（全自动）；
  - 没有 AUTO 则优先 `SEMI`（半自动）；
  - 都没有则用枪械支持的第一个模式（BURST 等）。
- `NpcAmmoState.prepareDropGun()`：NPC 主手掉落的枪在生成掉落物前设置为最佳 fire mode。
- `WastelandNpcEntity.dropPickupInventory()`：NPC 拾取背包里的枪掉落时也做同样处理。
- 玩家捡到 NPC 掉的枪后不需要先按 G，已经是全自动/半自动；枪械本身支持的模式不变，仍可手动 G 切换。
## 50. 2026-11-21 管理员界面：每个 NPC 是否自然生成

- `NpcSpawnConfigData.Rule` 新增 `enabled`（默认 true），随规则保存/加载。
- 管理员界面第 2 页每个阵营左侧改成可点击开关：
  - `§a●士兵` = 该阵营参与自然生成
  - `§c○士兵` = 不自然生成
- 新增 C2S 动作 `spawn_faction_enabled`，服务端 OP 校验后写入 Rule.enabled。
- 自然生成调度接入：
  - `scheduleBatch`：enabled=false 的阵营不排新批次；
  - `processPending`：已经排队但被关掉的阵营不再生成。
- 快照新增 `spawnFactionEnabled[]`，与 min/max/interval 一起同步。
## 51. 2026-11-21 管理员界面：全局反应时间倍率

- `NpcDifficultyData` 新增 `ReactionTimeMultiplier`（默认 1，0.1~5），世界持久化。
- 管理员界面第 1 页新增“反应时间倍数”（玩家手填数字 + 应用）：
  - >1 = 反应更慢
  - <1 = 反应更快
- NpcReactionTime 接入：
  - 难度缓存每 20 tick 刷新时同步读取该倍率；
  - 最终反应时间 = 原公式 × 全局 reaction_time_multiplier 配置 × 管理员倍率；
  - 管理员倍率不会每个 NPC 每 tick 查 SavedData。
- 恢复默认会把反应倍率重置为 1。
## 52. 2026-11-21 NPC 不再攻击创造/旁观玩家

- 默认模板与两个版本 `tacz-npc-modif` 已改为：
  ```ini
  target_creative_players : false
  target_spectator_players : false
  ```
- 目标判定仍走 `WastelandNpcEntity.isHostileTo` / `setTarget`：
  - 创造模式玩家：不视为敌对目标；不主动索敌、不反击；
  - 旁观模式玩家：不视为目标；
  - 冒险/生存玩家：仍可被索敌（士兵保持原设计：中立，只有被攻击/成为追猎目标才敌对；劫匪/叛军/赏金猎人会主动攻击）。
- 想恢复“攻击创造玩家”：把配置改回 true，或游戏内 `/tacznpc targetcreative true`。
## 53. 2026-11-21 NPC 非步枪射速上限 + 增加散布

**射速**
- `NpcGunRules.cappedNpcRpm(index, rawRpm)` 按 TACZ 枪械类型限制 NPC 射速：
  - sniper：≤45 RPM（狙不再连发）
  - shotgun：≤100 RPM
  - pistol：≤320 RPM
  - smg：≤750 RPM
  - mg/hmg：≤500 RPM
  - rifle：不限制
  - 其它未知类型：≤400 RPM
- `NpcAmmoState` 的 shotCooldown 用 capped RPM 计算，管理员全局射速倍率仍可在此基础上调整。

**散布**
- `tacz-npc-modif` 新增：
  ```ini
  npc_extra_spread_degrees : 1.5
  ```
- NPC 射击散布 = 原枪械/难度散布 + extraSpread + 该配置值 + 距离附加（12 格外每格 +0.04°，最多 +2°），再乘 `accuracy_multiplier`。
- 越大越不准；设为 0 可关闭额外散布。
## 54. 2026-11-21 玩家中弹视角：先上后下的动态后坐力

- `HitKickHandler` 从“直接加偏移 + 线性衰减”改成**弹簧-阻尼模型**：
  - 服务端仍只发一个 yaw/pitch 冲量；
  - 客户端把冲量转成角速度，弹簧先向上推，达到最高点后回摆向下，再逐渐归零；
  - 效果是“先上 → 再下 → 回正”，不是直接抬上去再线性回来。
- 参数：
  - `PITCH_IMPULSE 0.55`、`PITCH_SPRING 0.32`、`PITCH_DAMPING 0.72`
  - 最大偏移 14°、最大角速度 18
- 去掉了原先每次 tick 额外叠加的随机 jitter，避免连续命中后视角永久漂移；回摆本身已经足够有动态感。
## 55. 2026-11-21 Alt+N：全局生命/移速倍率 + 更多管理工具

- `NpcDifficultyData` 新增：
  - `GlobalHealthMultiplier`（默认 1，0.1~10）
  - `GlobalMovementSpeedMultiplier`（默认 1，0.1~5）
  - 世界存档持久化，网络快照同步。
- NPC 实体：
  - 记录原始 `baseMaxHealth`；
  - `refreshAdminAttributes()` 按倍率设置最大生命/移动速度；
  - 新生成、旧存档加载、每 100 tick、管理员修改时都会刷新；
  - 生命倍率变化时按当前血量比例缩放，新生成直接满血。
- Alt+N 第 3 页新增：
  - 全局生命倍数（手填数字）
  - 全局移速倍数（手填数字）
  - “清理尸体”按钮：清理死亡/已移除的幽灵 NPC
  - “唤醒NPC”按钮：附近 64 格 NPC 清 NoAI/嘲讽并重新索敌
  - 恢复默认同时重置生命/移速倍率
- 第 3 页标题改为“世界、生命/移速、上限、工具”。
## 56. 2026-11-21 士兵敌对玩家开关 + 目标优先级

**士兵敌对玩家**
- `NpcDifficultyData` 新增 `SoldierHostilePlayers`（世界持久化，默认 false）。
- Alt+N 第 1 页新增“士兵主动敌对玩家：开/关”。
- 关闭时：士兵保持中立，只有玩家先攻击（lastHurtByMob）或成为追猎目标才反击。
- 开启时：士兵主动把冒险/生存玩家当敌人；创造/旁观仍受 target_creative_players/spectator 限制。

**目标优先级**
`NpcCombatHelper.targetPriority` 新增权重：
1. 攻击自己的生物：由 `lastHurtByMob` / retaliation forced target 最高优先；
2. 敌对生物（Enemy）；
3. 敌对 NPC；
4. 玩家（最低）。
- `scanForTarget` 分数按优先级加 1000 权重；
- 当前目标有效时每 20 tick 检查一次是否有更高优先级目标，可主动切换；
- 群体目标（groupTarget）不会再覆盖更高优先级目标。
- 注意：`lastHurtByMob` / forced target 仍高于普通玩家，所以“玩家先攻击士兵”时玩家会优先反击。
## 57. 2026-11-21 个人可见/受击距离 + 全局设置跨存档 + 拾取 2~3 秒

**个人可见/受击距离**
- 每个玩家可设置 32~10240 格，默认 96（64~96 推荐区间）。
- Alt+N 第 3 页“个人可见距离”：普通玩家也能改自己的值，不需要 OP。
- 客户端：`NpcRenderDistanceEvents` 在实体渲染事件里按个人距离剔除 NPC。
- 服务端：
  - `WastelandNpcEntity.isWithinPlayerViewDistance(player)` 检查距离；
  - 超过距离的玩家不会被选为目标；
  - `setTarget` 拒绝超距玩家；
  - `NpcAmmoState.shootAt` 再兜底拦截，防止已锁定目标追出上限后继续开枪。
- 玩家登录时 `NpcAdminSyncEvents` 自动同步个人距离和设置快照。

**Alt+N 跨存档保留**
- 新增 `NpcAdminDefaults`：Alt+N 每次修改写入 `config/tacz-npc-admin.json`。
- 服务器启动/新建存档时自动 apply，包含：
  - 伤害、射速、反应、生命、移速、难度、掉落、生成、上限、士兵敌对；
  - 每阵营生成 min/max/interval/enabled；
  - 每个玩家的个人可见距离。
- 恢复默认同时清空个人可见距离，并写回全局文件。

**拾取延迟**
- NPC 拾取掉落物前，物品需在地上停留 2~3 秒：
  - `pickup_delay_min=40` tick
  - `pickup_delay_max=60` tick
  - 同一物品用 UUID 哈希固定延迟，避免每 tick 抖动。
- NPC 仍然每 20 tick 扫描、错开、背包满跳过，性能不变。
## 58. 2026-11-21 目标优先级：敌对生物 > 玩家 > 敌对 NPC

- `NpcCombatHelper.targetPriority` 调整为：
  1. 攻击自己的生物（forcedTarget / lastHurtByMob），最高；
  2. 敌对生物 Enemy；
  3. 玩家；
  4. 敌对 NPC。
- 当前目标有效时每 20 tick 重新检查，可主动切换到更高优先级目标。
- 群体目标不会覆盖更高优先级目标。
## 59. 2026-11-21 雇佣兵平衡：血量 /1.5，对玩家伤害 x0.75

- `MercenaryNpc`：
  - `createAttributes` 基础生命 38 -> 38/1.5 = 25.33；
  - `finalizeSpawn` 的难度生命整体 /1.5：
    - 难度1：38/1.5 ≈ 25.33
    - 难度2：46/1.5 ≈ 30.67
    - 难度3：52/1.5 ≈ 34.67
    - 难度4：60/1.5 = 40
  - 通过 `setBaseMaxHealth` 让管理员生命倍率仍能正常叠加。
- `CombatEvents`：
  - 雇佣兵对非主人玩家的伤害先乘 0.75，再走原有全局倍率/封顶；
  - 对主人仍然绝对免伤。
## 60. 2026-11-21 NPC 基础移速：僵尸 ×1.3

- 所有 NPC 基础移速从固定 0.09 改为：
  - 僵尸基准 0.23 × `npc_speed_multiplier`（默认 1.3）= 0.299。
- 配置新增：
  ```ini
  npc_speed_multiplier : 1.3
  ```
- 管理员第 3 页“全局移速倍数”仍然在这个基础上继续乘。
- 雇佣兵跟随/停留、士兵巡逻/交战、逃亡等全部走同一基础速度。
## 61. 2026-11-21 近战受伤倍率 + 阵营单独血量/伤害

- 所有 NPC 受到近战伤害默认 ×1.5：
  - 判定：直接实体 == 来源实体，排除 TACZ 子弹/箭/爆炸等投掷物；
  - 数据字段 `MeleeDamageTakenMultiplier`，默认 1.5，Alt+N 可改。
- 新增每个阵营单独的：
  - 生命倍率（默认 1.0）
  - 伤害倍率（默认 1.0）
- Alt+N 新增第 4 页“阵营单独血量/伤害、近战受伤”：
  - NPC 受近战伤害倍数
  - 士兵/劫匪/叛军/雇佣兵/赏金猎人：左=生命倍率，右=伤害倍率，逐行应用
- 计算顺序：
  - 最大生命 = 基础生命 × 全局生命倍率 × 阵营生命倍率；
  - 伤害 = 基础伤害 × 全局伤害倍率 × 阵营伤害倍率（雇佣兵再 ×0.75 对玩家）；
  - 近战受伤在伤害结算前 ×近战倍率。
- 全局文件 `tacz-npc-admin.json` 同步保存 melee/factionHealth/factionDamage，新存档继承。
- 恢复默认会重置这些字段。
## 62. 2026-11-21 雇佣兵不再自动拾取物品

- `WastelandNpcEntity` 新增 `canPickUpItems()`，默认 true。
- `MercenaryNpc` 覆盖为 false，`tickItemPickup` 直接跳过。
- 其他阵营（士兵/劫匪/叛军/赏金猎人）仍按 2~3 秒延迟拾取。
- 已经存在于旧雇佣兵拾取背包里的物品不会再增加；死亡仍按原逻辑掉落。
## 63. 2026-11-21 Alt+N：NPC 剔除（不显示/不攻击）

- 每个玩家独立设置：
  - `NPC剔除（不显示）：开/关`
  - `NPC剔除距离：32~1024` 格
- Alt+N 新增第 5 页“NPC 剔除（不显示/不攻击）”。
- 开启后：
  - 客户端 `NpcRenderDistanceEvents` 不渲染超过剔除距离的 NPC；
  - 服务端 `isWithinPlayerViewDistance` 取 min(个人可见距离, 剔除距离)，超过的玩家不会被选为目标、已锁定目标会被清除、`shootAt` 再兜底拦截。
- 默认：剔除关闭，剔除距离 96；关闭时仍使用个人可见距离。
- 全局文件 `tacz-npc-admin.json` 同时保存 cullDistances/cullEnabled，新存档继承。
- 恢复默认会清空个人剔除设置。
## 64. 2026-11-21 合并个人可见距离与 NPC 剔除

- 删除第 5 页的独立剔除距离，合并为第 3 页一套机制：
  - `NPC剔除（不显示/不攻击）：开/关`
  - `NPC剔除距离：32~10240`
- 语义：
  - 剔除关闭：**取消 NPC 距离限制**，远处 NPC 正常显示/正常索敌（受服务器实体追踪限制）；
  - 剔除开启：统一用该距离做客户端渲染剔除 + 服务端攻击距离限制；
  - 不再存在“个人可见距离 96 但剔除关闭仍被隐藏”的冲突。
- 原来第 3 页的“个人可见距离”改名为“NPC剔除距离”，Alt+N 总页数恢复为 4 页。
- 全局文件保存 cullEnabled/viewDistance，新存档继续继承。
- 默认：剔除关闭；因此修复“远处 NPC 直接不显示”的问题。
## 65. 2026-11-21 赏金猎人平衡：血量 x0.5，伤害 x0.8

- `BountyHunterNpc`：
  - 基础生命 30 -> 15；
  - 难度生命整体 x0.5：
    - 难度1：26 -> 13
    - 难度2：30 -> 15
    - 难度3：38 -> 19
    - 难度4：46 -> 23
  - 通过 `setBaseMaxHealth` 保证管理员生命倍率/阵营生命倍率继续叠加。
- `CombatEvents`：
  - 赏金猎人造成的所有伤害先 x0.8，再走原有全局/阵营伤害倍率与封顶。
## 66. 2026-11-21 NPC 反应时间 x1.5

- `reaction_time_multiplier` 默认从 1.0 改为 1.5；
- `NpcReactionTime` 运行时默认值也改为 1.5，避免缺键时回落到 1.0；
- 各难度反应时间整体变慢 1.5 倍：
  - 难度1：1.2~2.6s -> 1.8~3.9s
  - 难度2：0.8~1.6s -> 1.2~2.4s
  - 难度3：0.4~0.8s -> 0.6~1.2s
  - 难度4：0.2~0.6s -> 0.3~0.9s
- 管理员界面“反应时间倍数”仍可在此基础上再调。
- 两个整合包 config 已同步改成 1.5。
## 67. 2026-11-21 远处 NPC 不显示：EntityCulling 白名单 + 渲染事件最高优先级

- 现象：npc测试/亡者世界的 `npc_tacz_difficulty` 里 cullEnabled=false，我们的距离剔除并未生效，但远处 NPC 仍不可见。
- 原因：`entityculling` 1.11.2 的实体剔除把自定义 NPC 当成可剔除实体；它的 config 里没有 npc_tacz。
- 修复：
  - 在两个整合包的 `config/entityculling.json` 的 `entityWhitelist` 和 `tickCullingWhitelist` 加入：
    - `npc_tacz:soldier`
    - `npc_tacz:bandit`
    - `npc_tacz:rebel`
    - `npc_tacz:bounty_hunter`
    - `npc_tacz:mercenary`
  - 原配置已备份为 `entityculling.json.bak_npctacz_<时间>`。
  - `NpcRenderDistanceEvents` 的 `RenderLivingEvent.Pre` 提升到 `EventPriority.HIGHEST`。
- 说明：EntityCulling 是 mixin/渲染层剔除，Forge 事件优先级无法强行覆盖它；真正有效的是白名单。
## 68. 2026-11-21 NPC 站定射击 + 移动射击散布大幅增加

- `NpcRangedCombatGoal.think`：
  - 距离合适时优先 `navigator().stop()` 站定射击；
  - 只有敌人进入 `preferred * 0.55` 近身范围才后撤/走位；
  - 配置 `stand_still_when_shooting : true` 可开关。
- `NpcCombatHelper.movingSpread`：
  - 旧：0.8 + speed*1.5，最大 3 度；
  - 新：4.0 + speed*12.0，最大 15 度；
  - 站定（速度 < 0.05）时仍然为 0。
- 最终散布 = 移动散布 + 基础 spread_degrees + npc_extra_spread_degrees + 距离散布。
## 69. 2026-11-21 Alt+N：扩散系数 + 射程系数（all/单阵营）

- 新增数据：
  - `GlobalSpreadMultiplier`（默认 1，0.1~10）：作用于 NPC 最终散布，包含站定/移动/距离散布；
  - `GlobalRangeMultiplier`（默认 1，0.1~5）：全 NPC 射程；
  - `FactionRangeMultipliers`：每个阵营单独射程。
- Alt+N 新增第 5 页：
  - NPC扩散系数
  - NPC射程系数(all)
  - 士兵/劫匪/叛军/雇佣兵/赏金猎人 单独射程系数
- 射程实现：
  - `WastelandNpcEntity.rangeMultiplier()` = 全局射程 × 阵营射程，每 20 tick 缓存；
  - 所有 `getAttackRange()` 覆盖（士兵/劫匪/叛军/赏金猎人）统一乘该系数；
  - 索敌/感知/交战距离基于调整后的射程。
- 扩散实现：`NpcAmmoState` 最终 spread 再乘 `GlobalSpreadMultiplier`。
- 全局文件 `tacz-npc-admin.json` 保存 spread/range/factionRange，新存档继承；恢复默认重置。
- 注意：射程系数放大的是 NPC 的索敌/开火判断距离，不会改 TACZ 子弹自身的弹道极限射程。
## 70. 2026-11-21 Alt+N 第 5 页：单阵营同时有扩散和射程

- 之前单阵营只有射程，界面容易分不清；现在每个阵营一行两个输入框：
  - 左：扩散系数
  - 右：射程系数
- `FactionSpreadMultipliers` 新增并持久化：
  - NBT、网络快照、全局文件 `factionSpread`
  - 默认 1.0，范围 0.1~10
- 射击最终散布 = 全局扩散系数 × 阵营扩散系数 × 原有散布。
- 全局射程/单阵营射程逻辑不变。
- 恢复默认会同时重置单阵营扩散/射程。
## 71. 2026-11-21 NPC 剔除加固：事件最高/最低优先级 + 服务端目标检查

- 渲染：
  - `NpcRenderDistanceEvents` 的 `RenderLivingEvent.Pre` 提升到 `HIGHEST`，并新增 `LOWEST` 兜底；
  - 剔除关闭时，如果其它 Forge 渲染 mod 取消了事件，会在最高/最低优先级重新置为不取消，避免“剔除关着 NPC 还被隐藏”；
  - 剔除开启时仍按统一距离取消渲染。
- 服务端：
  - `NpcRangedCombatGoal.canUse/canContinueToUse` 增加玩家目标距离检查；
  - 已有 `setTarget`、`shootAt`、`isWithinPlayerViewDistance` 三层拦截。
- EntityCulling 1.11.2 是 mixin 层剔除，Forge 事件优先级无法覆盖；继续依赖已写入的 entityWhitelist/tickCullingWhitelist。
- 本机两个整合包的 entityculling 白名单已包含 5 种 NPC。
## 72. 2026-11-21 Alt+N：聊天提示开关 + 清理掉落物 + 更多管理工具

- 每个玩家永久设置（世界 NBT + 全局文件）：
  - 聊天栏提示：开/关（默认开）
  - 控制：自然生成提示、击杀提示、难度4连杀/死亡提示、士兵追杀提示；
  - 不控制：招募雇佣兵、管理员按钮直接反馈等交互消息。
- UI：每一页右下角新增 `聊天：开/关` 按钮，普通玩家可改自己的。
- 第 3 页工具按钮改为 5 个：
  - 清理尸体（死亡/幽灵 NPC）
  - 清理掉落（玩家 64 格内 ItemEntity）
  - 补满弹药（玩家 64 格内 NPC 备弹拉满）
  - 唤醒NPC
  - 恢复默认
- 网络/服务端新增 `clean_items`、`refill_ammo`、`chat_notifications` 动作；
  - 清理/补弹要求 OP 权限；聊天开关任何玩家可改自己。
- 全局文件 `tacz-npc-admin.json` 保存 chatNotificationsDisabled，新存档继承。
## 73. 2026-11-21 中弹视角逐帧平滑 + NPC 直接显示开关

**中弹视角**
- `HitKickHandler` 不再直接改 `player.setXRot/setYRot`；
- 弹簧偏移每 tick 更新，`ViewportEvent.ComputeCameraAngles` 用 `partialTick` 在渲染帧之间插值；
- 效果：从 20Hz 一跳一跳变成逐帧平滑的“上→下→回正”，且不会造成实际准星/鼠标朝向漂移。

**NPC 直接显示**
- 第 3 页原“NPC剔除”按钮改为“NPC直接显示：开/关”：
  - 开（默认）：取消 NPC 距离剔除，远处正常显示；
  - 关：默认 96 格外不显示，且不会攻击玩家；
  - 关闭时会把距离设为 96，下面输入框可再自定义。
- 服务端同规则：直接显示开时不按距离限制目标/攻击；关时用该距离。
- `NpcRenderDistanceEvents` 保留 HIGHEST+LOWEST 双向兜底，剔除关闭时清除其它 Forge 渲染取消。
## 74. 2026-11-21 先转身再攻击 + 正面 150° 视野（背身不被发现）

- 视野：
  - `isTargetInFrontArc(target, 75°)`：水平正前方 ±75°（总共 150°）。
  - 新目标索敌、`hurt` 反击、同阵营增援都要求目标在正面弧内。
  - `setTarget` 拒绝非当前目标的背身目标；`HurtByTargetGoal` 也无法强制背身目标。
  - 玩家从背后偷袭时，NPC 不会立刻转身锁定/叫增援。
- 攻击前转身：
  - 远程：`NpcRangedCombatGoal.maybeShoot` 要求 `isFacingTarget(target, 12°)` 才开火；
  - 近战：重写 `doHurtTarget`，面向目标 12° 内才真正攻击，否则先转头。
- 视觉上会先看到 NPC 转头对准玩家，再开枪/近战。
## 75. 2026-11-21 让 NPC 更像正常人

- 身份：
  - 每个 NPC 生成时随机姓名/称号，按阵营用不同颜色名条：
    - 士兵：姓+名
    - 劫匪：外号
    - 叛军：代号
    - 雇佣兵：外文名
    - 赏金猎人：猎手称号
  - 只在没有自定义名时生成；名字随实体 NBT 保存。
  - `npc_name_tags` 可关闭名条。
- 身体语言：
  - 空闲时只对正面 12 格内玩家瞥一眼（不会凭空发现背后偷袭）；
  - 受击会踉跄：短暂降低精度（`flinchTicks`）；
  - 受击/死亡播放人类痛叫/死亡声（`npc_voice`）。
- 射击：
  - 呼吸/持枪微摆（`npc_aim_sway`），不再像固定炮台；
  - 先转身面向目标再攻击（12° 容差）。
- 交流：
  - 首次发现玩家时，附近 24 格玩家会收到 NPC actionbar 喊话（`npc_callouts`）：
    - 士兵：“发现目标，准备交战。”
    - 劫匪：“有人来了，抄家伙！”
    - 叛军：“目标出现。”
    - 雇佣兵：“接敌。”
    - 赏金猎人：“找到你了。”
  - 每个 NPC 200 tick 冷却，受玩家聊天提示开关控制。
- 新增 config：`npc_name_tags`、`npc_voice`、`npc_idle_look`、`npc_aim_sway`、`npc_callouts`，均默认 true。
## 76. 2026-11-21 人味补充：低血量求援/换弹喊话

- 血量低于 30% 时，每 40 tick 检查一次，200 tick 冷却，向附近 24 格玩家 actionbar 喊“掩护我！”；
- 开始换弹时喊“换弹！”；
- 受玩家 `聊天：开/关` 和个人 `npc_callouts`/`npc_voice` 配置控制。
- 台词不会刷屏：单个 NPC 200 tick 冷却。
## 77. 2026-11-21 按反馈调整：名字恢复、连射扩散、狙空窗、转头时间、删喊话、声音背刺

1. 名字：
   - `npc_random_names` 默认 false，不再随机改名；
   - 上一版生成的随机名会在加载时清掉，用户自定义名不动；
   - 需要随机名可把配置改成 true。
2. 连射：
   - 前 6 发不加额外散布；
   - 第 7 发起每发 +0.45°，最多 +6°；
   - 停火 20 tick 后热量重置；换弹时清零。
3. 狙击：
   - 成功开枪后在原有射速冷却上额外加 12~24 tick（0.6~1.2 秒）空窗。
4. 转头时间：
   - 切换新目标时 `turnReadyTick = now + 8~16 tick`；
   - 未到时间不能开枪/近战；
   - 配置 `npc_turn_delay_min/max`。
5. 删除低血量“掩护我”聊天喊话，只保留士兵/叛军呼叫附近同阵营支援的效果。
6. 删除“换弹！”聊天喊话，换弹动画/状态保留。
7. 背身：
   - 普通潜行/走路绕后仍不会被发现；
   - 冲刺/游泳/高处摔落属于大声，可能被发现；
   - 背后枪击/爆炸命中会被惊动并反击；
   - 背身近战（安静）仍不会触发锁定。
## 78. 2026-11-21 紧急修复：NPC 不攻击玩家

- 根因：正面 150° 视野用了 `getLookAngle()`（身体朝向），但 NPC 的 `LookControl` 主要转头部，身体 yaw 不跟头，导致玩家明明在 NPC 视线里也被判定为"背后"，索敌永久失败。
- 修复：`isTargetInFrontArc` 和 `isFacingTarget` 改用 `getYHeadRot()` 头部朝向计算前向。
- 保留：150° 视野、背身安静不被发现、大声/枪击可惊动、转头时间。
## 79. 2026-11-21 玩家爆头 NPC 额外伤害（可调）

- `NpcDifficultyData` 新增 `PlayerHeadshotBonus`：
  - 默认 **6.5**
  - 范围 **0 ~ 2147483647**
  - 额外固定伤害，在 TACZ 原本爆头伤害之后直接加。
- 判定：
  - 受害者是 WastelandNpcEntity；
  - 来源是玩家；
  - 直接实体是 `tacz:bullet` / `tacz:kinetic_bullet`；
  - 子弹 Y > NPC 脚底 + 0.8×身高。
- Alt+N 第 1 页底部右侧新增“爆头+”输入框 + 应用。
- 世界 NBT、全局文件 `tacz-npc-admin.json` 保存，新存档继承。
- 恢复默认重置为 6.5。
## 80. 2026-11-21 NPC 喊话限制在 32 格内

- `combatCallout` 的接收半径从固定 24 改为 `npc_callout_radius`，默认 **32**。
- 只对 32 格内、且没有关闭聊天提示的玩家显示 actionbar 喊话。
- 新增配置：
  ```ini
  npc_callout_radius : 32
  ```
- 范围外玩家完全收不到 NPC 语句。
## 81. 2026-11-21 远距离可见 + NPC 竖直后坐力

**远距离可见**
- 5 种 NPC 的 `EntityType.Builder.clientTrackingRange` 从 6 提高到 **64 区块（约 1024 格）**。
- 配合“NPC直接显示：开”和 EntityCulling 白名单，玩家可在很远距离收到/渲染 NPC。
- 实际距离还受服务器 entity-broadcast-range-percentage、区块加载、客户端渲染距离限制。

**竖直后坐力**
- 每个 NPC 独立维护 `recoilPhase`：
  - 目标距离 ≥16 格：每成功射击一发 `x += 0.05`；
  - 竖直偏移基于 `f(x)=3sin(x)`，叠加到 `aimPitch`（向上为负、向下为正）；
  - 到 `f(x)≥3`（峰值）后停火 `2~6 tick`（0.1~0.3 秒，纯随机），然后 `x=0` 重新开始。
- NPC 压枪：
  - 92% 情况压回 65%~120%；
  - 8% 完全不压（继续上飘）；
  - 8% 压过头（200%~300%，可能打到脚/地面）。
- 不射击超过 20 tick 后，`x` 每秒减少 0.1。
- 目标 <16 格：继续连射，`x` 不增加，也不会触发停火。
- 换弹：`x`/停火状态清零；`needsCover` 继续让 NPC 换弹时找掩体调整。
- 连射散布：前 6 发正常，第 7 发起每发 +0.45°，最多 +6°；狙击额外 0.6~1.2 秒空窗。
## 82. 2026-11-21 实体剔除离谱：关闭 EntityCulling 的实体剔除

- 数据确认：玩家的 `cullEnabled=false`（NPC直接显示开），我们自己的距离剔除没有生效。
- EntityCulling 1.11.2 即使加入 `entityWhitelist`，仍在部分视角/距离下剔除自定义 NPC。
- 因此直接把两个整合包的 `config/entityculling.json` 设为：
  ```json
  "skipEntityCulling": true
  ```
  - 保留 `skipBlockEntityCulling=false`，方块实体剔除照常；
  - 保留 5 种 NPC 的 entityWhitelist/tickCullingWhitelist，以后改回 false 也能继续用。
- 原配置备份：`entityculling.json.bak_npctacz_20261121_201836`。
- 代价：全局实体剔除关闭，FPS 可能略降；但 NPC 不会再被第三方 mixin 剔除。
- 远距离可见仍受区块加载/客户端渲染距离/服务器广播距离限制；NPC 追踪范围已设 64 区块。
## 83. 2026-11-21 实体剔除继续离谱：关闭 Embeddium 实体剔除

- 复查发现除了 EntityCulling，Embeddium 的：
  ```json
  "performance": { "use_entity_culling": true }
  ```
  也在做实体剔除。
- 已修改两个整合包的 `config/embeddium-options.json`：
  ```json
  "performance": { "use_entity_culling": false }
  ```
- EntityCulling 已设 `"skipEntityCulling": true`。
- MoreCulling 的 `entityModelCulling` 原本就是 false。
- 我们自己的 NPC 距离剔除数据是 `cullEnabled=false`（NPC直接显示开），不是我们的代码在隐藏。
- 原配置备份：
  - `embeddium-options.json.bak_npctacz_20261121_202204`
  - `entityculling.json.bak_npctacz_20261121_201836`
- 必须完全重启才会读配置；如果游戏内改视频设置，可能覆盖 embeddium-options.json。
## 84. 2026-11-21 Alt+N 第 4 页：生命倍率 all 调

- 第 4 页顶部新增：
  - `所有阵营生命倍率(all)`，绑定全局生命倍率，所有阵营生效。
- 下面仍是每个阵营单独生命/伤害倍率；两者相乘。
- 页面布局压缩后仍能容纳 5 个阵营。
- 页面标题更新为“阵营血量/伤害、all生命、近战受伤”。
## 85. 2026-11-21 NPC 首次遇玩家：先控制距离再射击

- `NpcRangedCombatGoal` 新增 `approachMode`：
  - 第一次把玩家设为目标时，NPC 先移动到理想交火距离，未进入距离前不开火；
  - 理想距离：`getPreferredRange() × aggressionMultiplier`；
  - 进入 `preferred × 1.10` 以内后解除 approach，开始正常射击/站定。
- 玩家距离 `≤ max(4, preferred × 0.55)`：
  - 对玩家不再后撤；
  - 停下并直接射击（“离玩家太近就直接射击”）。
- 对非玩家目标（NPC/怪物）保留原来的后撤/走位逻辑。
- 达到理想距离后，后续仍按原本的站定射击、掩体、连射/后坐力逻辑。
## 86. 2026-11-21 把默认管理员设置打进 jar

- 新增资源：
  `assets/npc_tacz/default_admin_settings.json`
- `NpcAdminDefaults.apply`：
  - 如果 `config/tacz-npc-admin.json` 存在，照旧读取玩家/存档配置；
  - 如果不存在（新整合包、亡者世界之前没有全局文件），从 jar 内读取默认设置；
  - 服务器启动后写入 `config/tacz-npc-admin.json`，以后就持久化。
- 默认预设恢复为**项目最初默认值**（不含玩家 UUID 视距/聊天开关）：
  - 难度 2、上限 32、自然生成开、提示开；
  - 士兵主动敌对玩家：**关**；
  - 近战受伤 1.5；
  - 玩家爆头额外伤害 6.5；
  - 全局/阵营伤害、生命、射程、扩散全部 1.0；
  - 生成数量：士兵 3~5/10分钟、劫匪 4~6/10分钟、叛军 2~3/10分钟、赏金 1/5天、雇佣兵 1/3天。
- `npc测试` 已有自己的 `tacz-npc-admin.json`，不会被 jar 默认值覆盖；想恢复默认可在 Alt+N 点“恢复默认”。
- 旧 `亡者世界` 没有全局文件，重启后会套用这套原始默认值并写入文件。
- 之前测试用的 `meleeTaken: 10.0` 和高生成量没有作为默认值。

## 87. 2026-11-21 修复 NPC 穿墙锁定

- 根因：
  - 旧 `NpcTargetGoal.keepCurrentTarget` 只要目标存活/距离没超限就继续保留，不检查视线；
  - 队友共享目标、增援警报、强制反击还可能直接把墙后的玩家塞成目标。
- 修复：
  - `WastelandNpcEntity.setTarget`：任何**新目标**都必须通过 `NpcCombatHelper.canSee`，墙后目标直接拒绝；
  - `NpcTargetGoal.keepCurrentTarget`：每 10 tick 检查一次当前目标视线，连续 10 tick 看不见（约 0.5 秒）就清空目标；
  - `alertAllies`：只有能看见攻击者时才叫增援；
  - 保留 `NpcSight` 的玻璃/树叶/水等可透视规则。
- 已构建并部署到 npc测试/亡者世界。
## 88. 2026-11-21 加强难度 3/4 的 NPC 精准度

- `NpcAmmoState` 最终散布新增难度系数：
  - 难度 3：散布 × `difficulty3_spread_multiplier`，默认 **0.8**
  - 难度 4：散布 × `difficulty4_spread_multiplier`，默认 **0.65**
  - 难度 1/2 不变（1.0）
- 两个整合包 `tacz-npc-modif` 已加入这两项配置。
- 会同时影响站定、移动、连射热量、距离和呼吸摆动后的最终散布，但不影响 TACZ 弹道本身。
- 想更准/更散可以直接调这两个倍率。
## 89. 2026-11-21 隐藏 NPC 盔甲 + 不攻击敌对生物

- 隐藏盔甲：
  - `ClientEvents.NpcRenderer` 不再注册 `LodArmorLayer`，NPC 的盔甲只保留属性/防御，不渲染外观。
  - GLB 士兵模型本来就不渲染盔甲；其 fallback 也走 NpcRenderer。
- 不攻击敌对生物：
  - 新增配置 `attack_hostile_mobs : false`；
  - `WastelandNpcEntity.isHostileTo` 对 `Enemy` 返回该开关；
  - `setTarget` 也要求目标对 NPC 敌对，防止 HurtByTarget 强制锁定僵尸/骷髅；
  - 默认 NPC 不再主动打敌对生物，也不会因为被僵尸打而反击（除非把开关打开）。
- 两个整合包配置已加入该键。
## 90. 2026-11-21 优先反击攻击自己的单位

- `hurt` 不再只对常规敌对目标反击：任何能反击的单位（玩家、敌对 NPC、敌对生物）攻击 NPC 时，都设置 `retaliationTarget`。
- `getForcedTarget` / `NpcTargetGoal` 把攻击者放在最高优先级，先于普通扫描目标。
- 新增 `canRetaliateAgainst`：
  - 同阵营 NPC、创造/旁观玩家、雇佣兵主人不反击；
  - 平时 `attack_hostile_mobs=false` 的敌对生物，只有在先攻击 NPC 时才会被自卫反击。
- 保留隔墙修复：
  - 强制反击目标也必须通过视线校验；
  - 丢失视线 10 tick 后同时清除 `retaliationTarget`，不会被 `getForcedTarget` 立刻拉回来。
## 91. 2026-11-21 管理员界面增加生成距离

- 第 2 页“每阵营生成”底部新增：
  - 外（玩家外距离）
  - 内（玩家内距离）
  - 应用距离
- 范围：16~4096，内不能大于外。
- 发送 `spawn_range_all`，一次性写入全部阵营的生成规则。
- `NpcSpawnConfigData.setRange` 上限从 1024 改为 4096。
- `AdminSettingsMessage` 增加 `innerRadii` / `outerRadii` 快照字段。
- 已构建部署，需要重启生效。
## 92. 2026-11-22 大规模整合包交战性能优化

- `NpcCombatHelper` 全局索敌预算每 tick 从 24 降到 12。
- `NpcTargetGoal`：
  - 首次索敌按实体 id 错峰，避免几十只同时扫描；
  - 当前目标存在时的“更高优先级目标”复扫从每 20 tick 改为 40 tick；
  - 当前目标视线检查从每 10 tick 改为 20 tick。
- `NpcRangedCombatGoal` 视线缓存从 4 tick 改为 8 tick。
- `npc_tacz-common.toml` 的 `targetScanIntervalTicks` 从 20 改为 30（亡者世界和 npc测试）。
- 代价：进入战斗的索敌反应会慢约 0.5~1 秒，但大幅减少大量 NPC 同 tick 索敌/射线检测造成的卡顿。
- 亡者世界的 maxNpcs 已从 640 降到 96，生成量同步降低。
- 已构建；游戏关闭后由后台任务覆盖两个实例。
## 93. 2026-11-22 攻击频率降低 + spawn 指令距离参数

- 交战节奏：
  - `reactionTicks` 默认 4 -> 6
  - `burstPauseTicks` 默认 8 -> 30
  - `continuousFireOnPlayers` 默认 true -> false
  - 亡者世界/npc测试的 `npc_tacz-common.toml` 已同步。
- `/tacznpc spawn <type> <count>` 后面新增两个可选参数：
  - `<outer>` 玩家外距离，0~1024
  - `<inner>` 玩家内距离，0~1024
  - 例：`/tacznpc spawn all 64 128 48`
  - inner 大于 outer 时自动压回 outer
  - 不给距离时保持原来的近身生成
- `spawnForCommand` 新增带半径的重载，指定半径时不会退化为玩家脚下生成。
## 94. 2026-11-22 管理员界面滚动 + 第6页性能/生成范围

- `NpcAdminScreen` 增加 `scrollOffset`：
  - 鼠标滚轮上下滚动；
  - 左下角 ↑/↓ 按钮；
  - 所有页面内容跟随滚动，底部导航固定。
- 页面从 5 页扩到 6 页：
  - 第 6 页：生成范围、全服上限、低配预设、清理尸体/掉落、唤醒 NPC。
- 生成范围字段从第 2 页移到第 6 页，避免第 2 页在小 GUI 下看不到。
- 新增 `low_spec_preset`：
  - 上限 64；
  - 普通阵营 2~4 / 10 分钟；
  - 赏金 1 / 10 分钟，雇佣兵 1 / 6 分钟。
- 适配 GUI Scale 5 / 全屏 1920×1080；窗口较小时可滚动。
## 95. 2026-11-22 第6页攻击节奏按钮

- 第6页新增：
  - 攻击节奏：慢 -> reaction 10t / burstPause 50t / 不连续射击
  - 标准 -> reaction 6t / burstPause 30t / 不连续射击
  - 快 -> reaction 4t / burstPause 8t / 连续射击
- `combat_cadence` 直接写 Forge common config 并保存。
- 配合页面滚动、低配预设，形成完整的“大整合包性能页”。
## 96. 2026-11-22 线性精度与停火

- `NpcAmmoState` 新增 `accuracy` 线性精度：
  - 初始 1.0；
  - 每枪扣 `max(0.01, 0.05 - (难度-1)*0.01)`；
  - `<0.4` 线性增加散布；
  - `<=0.2` 远距离强制停火 10~20 tick，结束后重置 1.0；
  - 目标距离 <=8 格时不停火。
- 没有使用抛物线；只基于射击计数。

## 97. 2026-11-22 阵营目标优先级与墙后行为

- 各阵营目标优先级（低值优先）：
  - 士兵/叛军：敌对生物 > 士兵 NPC > 其他 NPC > 玩家；
  - 劫匪：敌对生物 > NPC > 玩家；
  - 赏金猎人：敌对生物 > 玩家 > NPC；
  - 雇佣兵：敌对生物 > 玩家/NPC。
- 被攻击时 `forcedTarget` 仍是最高优先级；敌对生物可以主动攻击 NPC，也可被反击。
- 目标切换成本提高；目标扫描间隔 30 tick，复扫 40 tick，减少来回换目标。
- 墙后行为：
  - 失去视线后保留 40 tick 最后位置记忆；
  - 士兵/叛军/雇佣兵：压制或前压；
  - 劫匪：远距离慢速压制；
  - 赏金猎人/低血/低弹：架枪等待；
  - 不根据目标真实位置穿墙瞄准，压制子弹会被墙挡住。

## 98. 2026-11-22 帧率与大整合包配置

- 亡者世界管理员配置：maxNpcs 96 → 64；生成数量改为 2~4 / 10 分钟等低配值。
- EntityCulling 重新开启，NPC 白名单保留；Embeddium 实体剔除开启。
- GLB 士兵 24 格外用 Steve fallback；NPC 渲染距离 40 格。
- 新增第 6 页“低配预设：上限64”、清除附近 NPC、攻击节奏慢/标准/快。
- 新增 `/tacznpc precision` 和 `/tacznpc cadence <slow|normal|fast>`。

## 99. 2026-11-22 默认士兵敌对

- `NpcDifficultyData` 的士兵主动敌对默认改为 true。
- 内置默认管理员设置 `soldierHostile` 改为 true。