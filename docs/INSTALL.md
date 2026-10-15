# 安装、构建与升级（4.2.1）

普通玩家按 [README](../README.md#安装-421) 使用发行附件安装即可。**终末地从官方鹰角启动器开始游戏，EFML 使用 xinput 代理加载模组。** 本页不再提供旧版第三方直接启动、直接注入或 3.0 自带加载器的安装流程。

## 发行附件安装

1. 正常退出终末地，安装 EFML 并配置游戏目录，选择 xinput 代理加载。
2. 导入并启用 `EndCraft-gameplay39-4.2.1-win-x64.zip` 与 `EndCraft-probe-4.2.1-win-x64.zip`，停用旧玩法模块。
3. 自备 MC 1.21.1、NeoForge 和 Java 25，将 `endcraft-neoforge-guest-4.2.1.jar` 放入独立实例的 `mods`；设置 4 GB 内存和 README 中的 JVM 参数。
4. 从官方启动器进入终末地可移动场景，再启动 MC，等待自动连接及桥接存档加载。

已有其他加载器时，先通过其原安装工具卸载代理。EFML 提示未知同名 DLL 时，不要直接覆盖。ZIP 导入 EFML，JAR 放进 MC 实例，源码归档用于开发；三种文件用途不同。

## 开发环境

需要 Git、Python 3、Visual Studio 的 C++ 桌面开发工具与 Windows SDK、JDK 25。NeoForge 开发构建还使用 Java 21 工具链，请安装 JDK 21 并确保 Gradle 能发现它。

```powershell
git clone --recurse-submodules https://github.com/guchang233/EndCraft.git
cd EndCraft
git checkout v4.2.1
git submodule update --init --recursive
```

最新安装文档以 main 上的 README 为准，标签保留其发布时的快照。也可使用发行页的 `EndCraft-4.2.1-source.tar.gz`，其中包含固定版本的上游源码；GitHub 自动生成的 Source code 包不包含子模块内容。

## 构建

在仓库根目录运行，示例 JDK 路径需换成实际安装位置：

```powershell
$taskJavaHome = 'C:\你的路径\jdk-25'
.\tools\build-native.cmd
python tools/fetch-neoforge-mods.py
.\tools\build-neoforge.ps1 -JavaHome $taskJavaHome
```

原生产物位于 `build/native/`，包括 `endcraft.probe.dll` 和 `endcraft.gameplay39.dll`；NeoForge JAR 位于 `mc-neoforge/build/libs/`。构建脚本会运行相应检查，不会启动游戏。首次构建需要下载依赖，缓存完整后才可加 `-Offline`。

Fabric 客户端单独构建，先将 `JAVA_HOME` 设为 JDK 25，再在 `mc/` 下执行 `gradlew.bat build --console=plain --no-configuration-cache`。要生成全部发行附件，需先构建两款客户端及原生模块，提交源码，再运行 `python tools/package-release.py`。

## 用源码连接游戏

先用 EFML 安装或注册所构建的 probe 和 gameplay39。已注册的模块包可能仍被游戏使用，不要直接覆盖其中的 DLL；正常关闭游戏后更新，或使用项目的模块身份切换工具。

游戏仍由**官方鹰角启动器**启动。进入可移动场景后，运行开发客户端：

```powershell
.\tools\start-neoforge.ps1 -BridgeHost -JavaHome $taskJavaHome
```

省略 `-BridgeHost` 是独立 MC 开发模式。项目开发存档位于 `mc-neoforge/run/saves/`；PCL2、HMCL 等正式实例使用各自的游戏目录，两者不要混淆。

## 升级与回退

更新前备份 MC 存档和世界锚点。保留一个 probe、一个启用的玩法模块和一个 MC 桥接客户端。4.2.1 伤害修正在宿主，4.2.0 MC JAR 可继续使用；替换 JAR 时正常退出 MC 并移除旧 JAR。

回退也应使用配套的模块与客户端。模块版本切换不会自动删除建筑，删除存档不是回退步骤。旧脚本的安装记录与 EFML 的当前配置分开维护，不要手工删除安装清单来绕过文件归属检查。
