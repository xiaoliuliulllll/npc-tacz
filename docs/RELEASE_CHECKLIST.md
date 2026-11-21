# 开源 / 发布检查清单（npc-tacz）

适用：MC百科、CurseForge、Modrinth 发布主模组 `npc-tacz`。  
**主模组不包含 YSM 内容**；YSM 附属不要跟主模组一起公开。

## 一、发布前必须解决的阻塞项

### 1. 同学素材授权（最重要）
`assets/npc_tacz/models/entity/modern_soldier.json` 和 6 张实体贴图由同学原创。

公开前必须做到：

- [ ] 同学明确回复/签署：同意这些素材以 **GPL-3.0-or-later** 随本项目及附属/DLC 使用、修改、分发
- [ ] 把同学的名字/网名填进 `CREDITS.md`（当前是空占位）
- [ ] 保存聊天记录/授权截图；最好让对方在仓库或文件中补一句授权声明
- [ ] 如果同学不同意 GPL 分发，则**不要开源/不要发布含这些素材的 jar**，先换素材

模板：`docs/ASSET_LICENSE_TEMPLATE.md`

### 2. 不要把 TACZ 本体带进仓库/发布包
以下文件**绝对不能**提交或放进源码 zip：

- `mod/libs/tacz.jar`（含 TACZ 的 CC BY-NC-ND 资源）
- `mod/libs/fastutil-8.5.9.jar`

`.gitignore` 已排除 `libs/*.jar`，但如果你压缩整个工程文件夹，它们仍会进去。  
发布源码前检查：仓库文件列表里不应出现任何 `*.jar` 从 `libs/` 来的文件。

### 3. YSM 附属不要公开
YSM 2.6.5 发布 jar 是 **All Rights Reserved**。

- [ ] 公开仓库前把 `mod/addon-ysm` 从公开仓库移除（保留本地/私有分支）
- [ ] 不要把 `npc-tacz-ysm-1.0.0.jar` 上传到 CurseForge/Modrinth/MC百科
- [ ] 不要公开 YSM 联动源码，除非先取得 YSM 作者授权

### 4. `npc-tacz-wangwenhao` 附属单独确认
如果也要发布这个附属：

- [ ] 附属素材来源/授权明确
- [ ] 许可证与主模组一致（GPL-3.0-or-later）
- [ ] 不要把它和主模组混成一个 jar

## 二、GitHub/Gitee 开源仓库

```text
LICENSE                 GPL-3.0 全文
README.md               项目说明、构建方法、依赖
CREDITS.md              素材与第三方声明
.gitignore              排除 build、libs/*.jar、日志、备份
mod/src/                主模组源码
mod/build.gradle
mod/settings.gradle
mod/gradle.properties
```

建议：

- [ ] `git init` 后先 `git status` 检查：不应有 `libs/tacz.jar`、`build/`、`run/`、`*.jar` 构建产物、`备份/`
- [ ] `mod/*.jar` 构建产物加进 `.gitignore`（本清单配套已加）
- [ ] README 里的 `E:\...` 绝对路径改成相对路径/通用说明
- [ ] 给出从源码构建的步骤；TACZ jar 由使用者自己放入 `mod/libs/`（只作编译依赖）
- [ ] 发布 tag（如 `v1.0.0`）和源码 zip，满足 GPL 的 Corresponding Source
- [ ] README/项目描述写明：**GPL-3.0-or-later**、源码地址、TACZ 是必需前置

## 三、CurseForge

- 项目类型：Minecraft Mods
- 游戏版本：1.20.1；加载器：Forge
- 上传文件：**只传 `npc-tacz-1.0.0.jar`**
- 许可证：GPL-3.0-or-later
- 依赖关系：添加 **TACZ（Timeless and Classics Guns: Zero）为 Required Dependency**
- Source/Issue 链接填 GitHub/Gitee
- 项目描述写清楚：
  - 需要 TACZ
  - 不是 TACZ 官方附属
  - 不含 TACZ 资源
- [ ] 不要上传 `libs/tacz.jar`、YSM 附属、测试世界、源码 zip 里带 TACZ jar

## 四、Modrinth

- Project type：Mod
- Loader：Forge
- Game version：1.20.1
- License：GPL-3.0-or-later
- Dependencies：TACZ 设为 Required
- Source link：仓库地址
- 上传文件：主模组 jar
- 版本号/Changelog 与 jar 的 `mods.toml` 一致

## 五、MC百科

- 建立模组词条：`npc-tacz`
- 前置模组：TACZ（Timeless and Classics Guns: Zero）
- 许可证：GPL-3.0-or-later
- 下载/源码链接指向 CurseForge/Modrinth/仓库
- 简介避免写“TACZ 官方”“YSM 官方”等误导性表述
- 截图/图标使用自己的游戏截图或自己的素材，不要使用 TACZ 图标/资源当封面

## 六、许可证义务提醒

- 主模组是 GPL-3.0-or-later：发布 jar 时必须能提供完整源码/构建脚本，或公开源码链接
- TACZ 代码 GPL-3.0：与本项目 GPL-3.0-or-later 兼容，但不要打包 TACZ 代码
- TACZ 资源 CC BY-NC-ND 4.0：
  - 不打包、不修改、不单独分发
  - **NC** 表示非商业；不要做付费墙/付费整合包
- 同学素材按 GPL 分发后，别人也可以再分发/修改；这是 GPL 的正常效果，先确认同学接受
- 不要给 GPL 代码加额外限制（例如“禁止二次分发”“禁止用于商业整合包”这类条款）

## 七、发布前最终测试

- [ ] 全新 Forge 1.20.1 + 只装 TACZ + 本模组，能进游戏
- [ ] 不装 TACZ 时能明确提示缺少前置，而不是崩溃
- [ ] 单人 OP 按 Alt+N 能打开管理员界面
- [ ] 多人服务器普通玩家打开只读、管理员可改
- [ ] NPC 生成/攻击/拾取/掉落/重生正常
- [ ] `/tacznpc npcdebug`、`/tacznpc wake` 等指令正常
- [ ] 发布 jar 解压检查：无 `com/tacz/`、无 `assets/tacz/`、无 YSM 条目

完成以上再点发布。
