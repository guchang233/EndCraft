# NeoForge 1.21.1 开发与兼容状态（4.2.1）

面向使用源码的开发者。普通安装与启动以 [README](../README.md) 为准：终末地从官方鹰角启动器启动，EFML 通过 xinput 代理加载模组，再运行 MC。

## 固定组合

| 组件 | 版本／状态 |
|---|---|
| EndCraft / 宿主 | 4.2.1 / endcraft.gameplay39；协议 12，需同时启用 probe |
| Minecraft / NeoForge | 1.21.1 / 开发构建 21.1.247；正式 21.1.257 实例已接通 |
| Java | JDK 25；NeoForge 构建还使用 Java 21 工具链 |
| Create / Aeronautics | 6.0.10 / 1.3.2 bundled |
| Sable / Flight Control | 2.0.6 / 0.7.7 |
| TaCZ / Touhou Little Maid | 1.1.8-hotfix-r7 / 1.5.3 |
| GeckoLib | 4.9.3 |
| YSM | 2.6.3，因本机原生库 err:54 停用，保存在 libs-disabled 供审计 |
| Maid Addition | 1.0.1-beta，可选本地文件；缺失不阻断构建 |

来源、文件名、下载地址与 SHA-512 以 [modpack.lock.json](../mc-neoforge/modpack.lock.json) 为准。第三方 JAR、用户枪包、模型包与光影包不附在 EndCraft 发行附件中。

## 构建与启动

按 [安装与源码构建](INSTALL.md) 准备工具链，在仓库根目录运行：

```powershell
.\tools\build-native.cmd
python tools/fetch-neoforge-mods.py
.\tools\build-neoforge.ps1 -JavaHome 'C:\你的路径\jdk-25'
```

产物为 `mc-neoforge/build/libs/endcraft-neoforge-guest-4.2.1.jar`。缓存完整后可用 `-Offline` 构建；构建本身不启动游戏。

终末地需先经官方启动器进入可移动场景，EFML 已加载 probe 与 gameplay39，然后运行：

```powershell
.\tools\start-neoforge.ps1 -BridgeHost -JavaHome 'C:\你的路径\jdk-25'
```

省略 `-BridgeHost` 时是独立 MC 开发模式。开发世界保存在 `mc-neoforge/run/saves/`，不要打开 Fabric 26.3 存档。正式 MC 启动器实例使用自己的游戏目录；其 JVM 参数见 README。

## 已有实现与验证范围

- NeoForge 注册、输入、共享内存、角色与方块桥接已有实机记录。宿主与两款 MC 客户端使用协议 12。
- 方块、实体、方块实体、标准图集粒子和字体有网格导出路径；HUD、第一人称和菜单使用 MC 像素读回。渲染覆盖范围见 [渲染说明](RENDERING.md)。
- TaCZ 的配件界面临时切换第一人称、去除暗色背景并隔离原生 UI 点击；用户已确认亮度与点击正常。
- gameplay39 改用角色攻击力换算伤害；公式与配置检查通过，真实命中扣血尚未验证，见 [伤害说明](COMBAT.md)。
- Create/Flywheel 请求普通渲染路径；Sable 船体姿态、坐标变换和地形体素已接入。完整飞船起降、站立、碰撞、NPC 登船和飞控操作仍需实机验收。
- Mob 接入三角面碰撞，女仆短程行走曾通过，但仍有掉出世界记录；长期支撑不能作为已完成功能。
- 两台电脑各运行终末地的完整联机尚未验证，现有 MC LAN 流程见 [联机说明](MULTIPLAYER.md)。

`MixinTargetTest` 检查注入目标、锁定内容模组的共享目标以及依赖哈希；Maid Addition 未提供时不审计该可选文件。静态审计通过不等于第三方模组全部实机兼容。

## 光影与性能

Iris/Sodium 与用户提供的光影包使用独立的可选锁文件。当前提供完整 MC 画面的实验性预览，尚未将光影与终末地原生场景深度融合。操作与限制见 [渲染说明](RENDERING.md)。

已有缓冲区复用、区块快照、异步图集读回和队列背压优化；复杂船体仍有捕获成本，没有同场景联动 FPS 对照，见 [性能说明](PERFORMANCE.md)。
