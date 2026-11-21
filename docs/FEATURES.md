# npc-tacz 功能与配置总览

适用版本：Minecraft 1.20.1 / Forge 47.x / TACZ 1.2.x  
主模组许可证：GPL-3.0-or-later

## 一、核心玩法

| 系统 | 内容 |
|---|---|
| 阵营 NPC | 士兵、劫匪、叛军、雇佣兵、赏金猎人 |
| 武器 | 使用 TACZ 枪械与配件；自动避免爆炸枪/爆炸配件 |
| 战斗 AI | 索敌、反应时间、点射/连射、掩体、侧翼、友军火力规避 |
| 生成 | 按阵营的自然生成数量、间隔、开关、总上限 |
| 拾取 | 9 格背包，每格 64，死亡 100% 掉落 |
| 增援（新） | NPC 受击/死亡会呼叫附近同阵营支援 |
| 管理员界面 | Alt+N，三页，OP/联机管理员可改 |
| 玩家反馈 | 弹簧式中弹视角（先上后下）、击杀提示 |

## 二、常用指令

| 指令 | 作用 |
|---|---|
| `/tacznpc npcdebug` | 查看最近 NPC 的阵营/目标/枪/拾取数/NoAI/位置 |
| `/tacznpc wake [半径]` | 清除 NPC 的 NoAI/嘲讽/目标，重新索敌 |
| `/tacznpc targetcreative [true\|false]` | 是否攻击创造玩家（默认 false） |
| `/tacznpc spawn <类型> [数量]` | 在玩家附近生成 |
| `/tacznpc clean [半径]` | 清理尸体 |
| `/tacznpc spawn_config` | 查看/修改自然生成规则 |
| `/tacznpc audit [半径]` | 检查爆炸枪/配件/缺配件 |
| `/tacznpc ysm` | 附属：显示 YSM 模型列表与当前模型 |
| `/tacznpc weakrun [16~4096]` | 弱运行距离 |

## 三、管理员界面（Alt+N）

| 页 | 项目 |
|---|---|
| 第 1 页 | 全局伤害倍数、全局射速倍数、反应时间倍数、难度、枪械掉落、子弹掉落、子弹数量倍率 |
| 第 2 页 | 每个阵营：是否自然生成、最小数量、最大数量、生成间隔 |
| 第 3 页 | 全服 NPC 上限、当前数量、自然生成总开关、生成提示、全部统一间隔、恢复默认 |

只读权限：普通玩家打开界面只能看，不能改。服务端会再次校验 permission level >= 2。

## 四、配置（`config/tacz-npc-modif`）

| 键 | 默认 | 说明 |
|---|---:|---|
| `target_creative_players` | false | 是否攻击创造玩家 |
| `target_spectator_players` | false | 是否攻击旁观玩家 |
| `weak_run_distance` | 128 | 弱运行距离 |
| `npc_extra_spread_degrees` | 1.5 | NPC 额外散布 |
| `accuracy_multiplier` | 1.0 | 全局散布倍率 |
| `ally_alert_radius` | 24 | 同阵营增援半径 |
| `ally_alert_max` | 4 | 一次最多增援人数 |
| `ally_alert_cooldown` | 100 | 同一 NPC 增援冷却 tick |
| `kill_feed` | true | 击杀提示 |
| `fallback_ai` | false | 整合包抑制 goal 时的兜底 AI |
| `ai_watchdog` | false | tick 看门狗（排查用） |
| `npc_bullet_tracer` | true | NPC 子弹曳光 |
| `drop_loot` | true | 总掉落开关 |

## 五、性能相关

| 优化 | 说明 |
|---|---|
| SavedData 缓存 | 伤害/射速/难度/反应时间每 20 tick 缓存，不每次受击查世界数据 |
| 枪型缓存 | 按枪 id 缓存 rifle/sniper/... 类型，避免每发子弹查 TimelessAPI |
| 数据重载清理 | 枪包/Tag 重载时清空枪型缓存 |
| 拾取节流 | 每 20 tick、按实体错开、背包满跳过 |
| 调试扫描关闭 | `ai_watchdog=false`、`fallback_ai=false` |
| 渲染 LOD | 士兵 GLB 模型超过 24 格回退原版人形渲染 |

## 六、兼容与协议

| 项 | 结论 |
|---|---|
| TACZ 代码 | GPL-3.0；本模组 GPL-3.0-or-later，兼容；不打包其代码 |
| TACZ 资源 | CC BY-NC-ND 4.0；不打包、不修改、不单独分发 |
| 主 jar | 无 `com/tacz/`、无 `assets/tacz/` |
| YSM | 2.6.5 All Rights Reserved；YSM 附属不要公开分发 |
| 模型/贴图 | 同学 尺子老梅 原创；CREDITS 已署名，仍需保留授权记录 |
| 发布 | 只能上传主 jar；不要上传 `libs/tacz.jar`、YSM 附属或含 TACZ 的源码包 |
