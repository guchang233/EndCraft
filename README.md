# EndCraft

在《明日方舟：终末地》的真实场景中，使用 Minecraft 的角色、物品栏、建造与战斗机制。

**当前版本：4.1.0 · 实验性联动原型 · Windows x64。** Minecraft 与终末地同时运行；MC 模拟玩法，宿主插件把角色、方块和实体绘制到终末地场景中。

[4.1 更新说明](docs/RELEASE-4.1.0.md) · [局域网联机](docs/MULTIPLAYER.md) · [发行版本](https://github.com/guchang233/EndCraft/releases) · [安装与升级](docs/INSTALL.md) · [操作教程](docs/USER-GUIDE.md) · [故障排查](docs/TROUBLESHOOTING.md) · [实现原理](docs/ARCHITECTURE.md)

## 4.1 新增：局域网联机

NeoForge 客户端可以复用 MC 的局域网联机：一台电脑运行终末地作为主机，在 MC 聊天输入 `/endcraft lan offline` 开放世界，其他人用相同模组的 NeoForge 客户端加入。

- **普通访客**只运行 MC，出生在主机身边，可以站在主机同步过来的终末地地形上；主机在终末地中看到访客的 MC 角色和建筑。
- **自带终末地的访客**用 `/endcraft join` 加入，并通过 `tools/show-anchor.py` 与主机统一世界锚点，各自在自己的终末地中看到所有玩家。
- 引号键 `'` 在终末地中显示地形碰撞三角面，F3+B 显示碰撞箱；MC 崩溃时双击 `restart-neoforge-mc.cmd` 重新连回终末地。

步骤与限制见 [局域网联机说明](docs/MULTIPLAYER.md)，更新内容见 [4.1 更新说明](docs/RELEASE-4.1.0.md)。

## 4.0 新增：机械动力与航空学

4.0 提供两个可选的 MC 客户端，共用同一个宿主模块：

| 客户端 | 版本 | 适用 |
|---|---|---|
| Fabric（`mc/`） | MC 26.3 | 原有玩法，功能与 3.0 一致 |
| NeoForge（`mc-neoforge/`） | MC 1.21.1 | 机械动力 6.0.10、机械动力航空学 1.3.2（Sable 2.0.6）、Create: Flight Control 0.7.7 |

NeoForge 客户端能在终末地场景里搭建机械动力装置和航空学船体：船体随 Sable 物理移动并绘制到原生场景，物理杖和左键可正常选中船体，船体可与终末地地形碰撞（落地、滑行待验收）。首轮实机测试修复了黑屏、不自动进图、船体选不中、方块互相穿插和放置方块消失等问题，详见 [4.0 更新说明](docs/RELEASE-4.0.0.md) 与 [NeoForge 说明](docs/NEOFORGE.md)。

## 能做什么

| 功能 | 当前效果 |
|---|---|
| 角色与界面 | 第一／第三人称 MC 角色、装备、九格快捷栏和库存；HUD 随宿主分辨率调整 |
| 建造 | 在原生场景放置、破坏 MC 方块；玻璃透明、染色玻璃及水的颜色与动画已实机确认 |
| 移动 | MC 行走、跳跃、创造飞行、鞘翅和烟花推进；视角支持上下看至 ±90° |
| 战斗 | 保留 MC 武器冷却、暴击、弓和 TNT 的伤害计算，转交宿主伤害与受击组件 |
| 游戏模式 | `/gamemode` 切换；创造／旁观模式同步宿主免伤 |
| 实体 | 显示船、矿车、箭、掉落物等；普通体型宿主生物可进入附近载具，覆盖范围有限 |
| 恢复 | 原生传送后的角色同步、死亡恢复、存档锚点保留及重新连接；终末地切换场景后自动重发方块网格；MC 崩溃后可一键重启连回 |
| 机械动力／航空学 | 仅 NeoForge 客户端：Create 装置、Sable 船体显示与选取、船体对终末地地形的碰撞（实验） |
| 联机 | 仅 NeoForge 客户端：局域网联机，访客同步终末地地形；可选离线模式与允许作弊 |

Fabric 客户端的玻璃和流体渲染、高速上坡跟随、保存退出与坐标锚点修复来自 3.0，见 [3.0 更新说明](docs/RELEASE-3.0.0.md) 与 [验证记录](docs/VALIDATION-gameplay33.md)。

## 开始使用

本版本面向愿意使用源码与工具链的玩家和开发者，**尚无一键安装器**。只把 JAR 放进终末地目录不会生效；需要配套 Host、宿主模块和独立 MC 实例。

1. 按 [安装教程](docs/INSTALL.md) 准备 Windows、Visual Studio C++、JDK 25、Python 和固定版本源码。
2. 构建并安装加载器／Host，注册 `endcraft.gameplay33`，配置启动。
3. 通过原启动器进入终末地可移动场景，等待项目 MC 连接和物品栏出现。
4. 先用玻璃、水和少量方块测试，再按 [操作教程](docs/USER-GUIDE.md) 体验飞行、战斗和载具。

使用 NeoForge 客户端（机械动力／航空学）时，第 2 步之后改为：

```powershell
python tools/fetch-neoforge-mods.py      # 下载并校验固定版本的模组
.\tools\build-neoforge.ps1               # 构建与非游戏测试
.\tools\start-neoforge.ps1 -BridgeHost   # 终末地运行后启动并连接
```

两个客户端不能同时连接；切换前先正常保存退出另一个。完整步骤与回退见 [NeoForge 说明](docs/NEOFORGE.md)。

已有本项目 2.0 安装的用户，按教程中的 [升级步骤](docs/INSTALL.md#从本项目-20-升级) 操作；保留存档及旧坐标锚点，只开启一个玩法模块。

## 常用操作

| 按键／指令 | 操作 |
|---|---|
| WASD、空格 | 移动、跳跃；创造模式双击空格飞行 |
| 1–9、滚轮 | 选择快捷栏物品 |
| 左键、右键 | 攻击／破坏、使用／放置／交互 |
| E、F5 | MC 库存、切换视角 |
| T 或 `/` | 打开 MC 聊天／指令 |
| `/gamemode creative` | 创造模式；生存、冒险、旁观也可切换 |
| `/endcraft recover` | 重新同步位置并切回第三人称 |
| Ctrl+Alt+R | 重新同步角色位置 |
| `;` | 实验性 MC 独占开关；当前并非两款游戏的按键使用对象互斥切换 |
| `/endcraft lan [offline] [cheats] [端口]` | NeoForge：开放局域网 |
| `/endcraft join [地址]`、`/endcraft leave` | NeoForge：加入局域网世界、回到自己的世界 |
| `'` | NeoForge：在终末地中显示地形碰撞三角面 |
| F1、F3+B | NeoForge 需宿主模块 `gameplay34`：隐藏 MC HUD、显示碰撞箱 |

## 已知限制

- 地形碰撞采用高度场，洞穴、桥下、多层建筑及复杂墙体尚未完整适配；NPC 寻路不等同于碰撞阻挡。
- MC 放置的水已正常显示；终末地原生水域尚未映射为 MC 流体，完整水上浮力未验证。
- 分号关闭独占后，MC 仍可能同时响应输入；按用户指示，3.0 暂未修改为双向互斥切换。
- TNT 的 MC 方块破坏、敌人伤害和任务击杀已有实测；**炸原生岩石仍待真实炸弹样本校准与验收**。
- 战斗奖励、重新登录后的状态持久性、所有 NPC 类型及跨全新地图建筑锚定未全面验证。
- MC 光影包尚未兼容。透明面复杂相交、水下效果及水面反射／折射仍需后续验证。
- NeoForge 客户端：航空学船体在终末地地面的起降与滑行、移动船体的宿主碰撞体（NPC 阻挡、登船）、Create: Flight Control 飞控方块尚未完成验收。
- 1.21.1 与 26.3 的存档互不兼容，两个客户端的建筑和库存相互独立。
- 联机：两台电脑各自运行终末地的联机尚未实机验证；普通访客只能看到主机终末地角色附近的地形。
- 当前实机结论来自开发机；不能据此保证所有游戏构建、显卡或其他模组组合可用。

## 文档与开发

| 文档 | 内容 |
|---|---|
| [安装与升级](docs/INSTALL.md) | 下载、源码构建、首次安装、2.0 升级、启动与卸载 |
| [操作教程](docs/USER-GUIDE.md) | 建造、玻璃／水、鞘翅、游戏模式、战斗、载具和传送 |
| [故障排查](docs/TROUBLESHOOTING.md) | 无 HUD、角色消失、卡地形、输入冲突、诊断与回退 |
| [实现原理](docs/ARCHITECTURE.md) | 双进程通信、坐标、绘制、碰撞、战斗及光影路线 |
| [4.1 更新说明](docs/RELEASE-4.1.0.md) | 局域网联机、地形线框、碰撞箱、F1/F3 与一键重启 |
| [局域网联机](docs/MULTIPLAYER.md) | 开放局域网、加入、锚点对齐、掉线恢复 |
| [4.0 更新说明](docs/RELEASE-4.0.0.md) | NeoForge 客户端、机械动力／航空学适配及修复 |
| [NeoForge 说明](docs/NEOFORGE.md) | 1.21.1 客户端构建、启动、切换、诊断与待验收项 |
| [3.0 更新说明](docs/RELEASE-3.0.0.md) | Fabric 客户端发行内容、验证结果和未完成项 |

Fabric 客户端使用 Minecraft **26.3**、Fabric Loader **0.19.5**、Fabric API **0.161.0+26.3**；NeoForge 客户端使用 Minecraft **1.21.1**、NeoForge **21.1.247**。两者均用 JDK **25**，宿主模块为 `endcraft.gameplay34`（`gameplay33` 仍可用，只是不转发 F1/F3），共享内存协议为 **12**。

## 许可与来源

MC 端改编自 [SkyCraft](https://github.com/chasmlol/SkyCraft)，使用 MIT 许可；NeoForge 客户端依赖的 Create、Aeronautics、Sable 与 Create: Flight Control 由脚本从发布方下载，不随仓库分发，各自遵循原许可；宿主接入基于 [Better-Endfield](https://github.com/Dr-hydra/Better-Endfield)，使用 AGPL-3.0。原许可声明保留，详见 [LICENSE](LICENSE)、[mc/LICENSE](mc/LICENSE) 与 [第三方声明](THIRD-PARTY-NOTICES.md)。

发行附件不含游戏文件、账号凭据、存档、运行日志或含 UID 的截图。上游源码固定提交随完整源码附件提供。
