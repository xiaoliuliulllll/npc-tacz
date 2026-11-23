# npc-tacz

独立编写的 Minecraft 1.20.1 Forge NPC 模组，使用 TACZ 枪械作为运行时依赖。

**这不是 TACZ 官方项目。** 主模组不包含 TACZ 的代码、模型、贴图、音效或枪包数据。

[English](README_EN.md) | [CurseForge](https://www.curseforge.com/minecraft/mc-mods/npc-tacz)

## 功能

- 5 种 NPC：士兵、劫匪、叛军、雇佣兵、赏金猎人
- TACZ 枪械、配件、换弹、弹道、曳光
- 阵营敌对、最近目标索敌、武装玩家优先、被攻击立刻转火
- 被围攻时找掩体、撤退、向队友靠拢并呼叫支援
- 目标躲在方块后：火力压制 / 等待 / 前压三种随机战术
- 苦力怕规避、近战终结、撤退射击精度惩罚
- 玩家 360° 可见范围、NPC 剔除、个人距离设置
- Alt+N 管理员界面：8 页、第 1 页常用开关、固定页码、自动保存
- 四类伤害倍率、NPC 发光开关、玻璃/树叶透视开关
- 战利品桶（可调消失时间/开关）、按枪械类型的额外子弹、狙击枪 48 发上限
- 钻石弹药盒掉落，左键单击/背包拖动快速存入同款子弹
- Xaero 小地图生物雷达蓝色 NPC 点
- 9 格拾取背包、死亡掉落、清理掉落物、补满弹药
- 玩家爆头额外伤害、狙击空窗、连射扩散、竖直后坐力
- 正面 150° 视野、背身安静不被发现、大声行为可惊动

## 前置

- Minecraft 1.20.1
- Forge 47.x
- **TACZ（Timeless and Classics Guns: Zero）1.2.x**，必需
- Java 17

## 构建

1. 下载 TACZ 1.2.0 Forge 1.20.1 的 jar。
2. 重命名为 `tacz.jar`，放到：

   ```text
   mod/libs/tacz.jar
   ```

3. 安装 JDK 17 和 Gradle 8.8。
4. 构建：

   ```bash
   cd mod
   gradle build
   ```

5. 输出：

   ```text
   mod/build/libs/npc-tacz-1.1.4.jar
   ```

更详细说明见 [BUILDING.md](BUILDING.md)。

> `mod/libs/tacz.jar` 只是本地编译依赖。TACZ 代码为 GPL-3.0，资源为 CC BY-NC-ND 4.0；不要把 TACZ jar 提交到仓库或放进发布包。

## 许可证

**GPL-3.0-or-later**，见 [LICENSE](LICENSE)。

第三方声明和素材来源见 [CREDITS.md](CREDITS.md)。

## 素材

现代士兵模型与 6 张 NPC 贴图由 **尺子老梅** 原创制作，按 GPL-3.0-or-later 随本项目分发。

## 不包含

- TACZ 本体 jar / 资源
- YSM（Yes Steve Model）代码或资源
- 任何未经授权的第三方模型、贴图、音效

## 发布

只发布主模组 jar。TACZ 以 Required Dependency 形式声明。

发布前检查清单见 [docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md)。
