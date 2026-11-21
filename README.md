# npc-tacz

独立编写的 Minecraft 1.20.1 Forge NPC 模组，使用 TACZ 枪械作为运行时依赖。

**这不是 TACZ 官方项目。** 主模组不包含 TACZ 的代码、模型、贴图、音效或枪包数据。

## 功能

- 5 种 NPC：士兵、劫匪、叛军、雇佣兵、赏金猎人
- TACZ 枪械、配件、换弹、弹道、曳光
- 阵营敌对、目标优先级、掩体、侧翼、增援
- 玩家 360° 可见范围、NPC 剔除、个人距离设置
- Alt+N 管理员界面：伤害、射速、生命、移速、反应、掉落、生成、阵营倍率、扩散、射程
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
   mod/build/libs/npc-tacz-1.0.0.jar
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
