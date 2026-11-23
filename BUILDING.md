# 构建说明

本项目使用 ForgeGradle，目标环境：

- Minecraft 1.20.1
- Forge 47.4.10
- Java 17
- Gradle 8.8

## 1. 准备 TACZ 编译依赖

从 TACZ 的发布页下载 **1.2.0 Forge 1.20.1** 的 jar，然后：

```text
mod/libs/tacz.jar
```

这个 jar 只用于编译，**不要提交到仓库，也不要随发布包分发**。

## 2. 构建

```bash
cd mod
gradle build
```

如果使用 Gradle Wrapper，也可以：

```bash
./gradlew build
```

输出：

```text
mod/build/libs/npc-tacz-1.1.1.jar
```

## 3. 本地运行（可选）

```bash
cd mod
gradle runServer
```

## 4. 常见问题

- **找不到 TACZ 类**：确认 `mod/libs/tacz.jar` 存在且是 Forge 1.20.1 版本。
- **fastutil 缺失**：构建脚本会从 Maven Central 拉取 `it.unimi.dsi:fastutil:8.5.9`。
- **Forge 依赖下载慢**：使用代理或在 `settings.gradle` 中配置可用镜像。
- **不要提交**：`libs/*.jar`、`build/`、`.gradle/`、`run/`、任何构建 jar。
