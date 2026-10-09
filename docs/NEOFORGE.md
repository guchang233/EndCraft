# NeoForge 1.21.1 与机械动力航空学（EndCraft 4.0）

EndCraft 4.0 新增的 NeoForge 1.21.1 客户端，可在终末地场景中使用 Create、机械动力航空学与 Create: Flight Control。已在开发机上完成首轮实机测试（见下方“实机测试记录”）；仍属实验性联动，**尚未标记为航空学完全兼容**，未完成项见文末。

## 为什么使用 1.21.1

航空学的官方项目使用 NeoForge 1.21.1，无法直接安装到现有 Fabric 26.3 实例。4.0 保留原 Fabric 工程，新增 `mc-neoforge/`，使用全新目录和存档；宿主通信仍使用协议 12，两个客户端共用同一个宿主模块。

| 组件 | 固定版本 |
|---|---|
| EndCraft Guest | `4.0.0` |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.247 |
| Create | 6.0.10+mc1.21.1 |
| Create Aeronautics | 1.3.2+mc1.21.1，bundled 文件 |
| Sable | 2.0.6+mc1.21.1 |
| Create: Flight Control | 0.7.7（代码 GPL-3.0，资源保留所有权利） |
| Java | JDK 25；桥接类使用 Java 22 字节码和 Foreign Function & Memory API |
| 已有宿主模块 | `endcraft.gameplay33`，协议 12 |

参考：[航空学官方源码](https://github.com/Creators-of-Aeronautics/Simulated-Project)、[Create 官方依赖指南](https://wiki.createmod.net/developers/depend-on-create/neoforge-1.21.1)、[Sable 官方源码](https://github.com/ryanhcode/sable)。依赖文件名、下载地址和 SHA-512 固定在 `mc-neoforge/modpack.lock.json`，不使用“自动下载最新版”。

## 已移植的部分

- 用 NeoForge 注册、事件和网络 Payload 替换 Fabric 入口；保留角色、伤害代理、游戏模式、重生和共享内存通信代码。
- 将 26.3 的输入接口改为 1.21.1 的 GLFW 回调，转换宿主 SDL 按键码，保留 WASD、鼠标、聊天和库存操作。
- 重写 1.21.1 方块网格、实体、方块实体和纹理导出；透明面、流体与纹理动画使用现有协议传给宿主。
- 捕获 Create 的方块实体及移动装置渲染。桥接期间请求 Flywheel 使用普通渲染路径，因为实例化绘制不会经过网格捕获接口。
- 增加 Sable 适配：读取船体的插值姿态，将远端 plot 方块坐标转换到世界坐标，再相对场景原点打包，避免大坐标直接转成浮点数导致抖动。
- 默认独立启动不连接宿主；只有指定桥接选项才连接。两种桥接客户端启动脚本都有防重复写入检查。

这些是实现和静态验证结果。**不把“代码已写”“模组依赖已下载”当成“游戏内兼容已确认”。**

## 构建，不启动游戏

在项目根目录执行：

```powershell
python tools/fetch-neoforge-mods.py
.\tools\build-native.cmd
.\tools\build-neoforge.ps1
```

已有 `build/native/endcraft-standin.exe` 和 `endcraft.probe.dll` 时，可以跳过原生构建。它们供隔离的共享内存测试使用，不接入游戏进程。

指定 JDK 或离线重建：

```powershell
.\tools\build-neoforge.ps1 -JavaHome 'C:\你的路径\jdk-25'
.\tools\build-neoforge.ps1 -Offline
```

离线选项要求 Gradle、NeoForge 和测试依赖已经缓存。此脚本只执行 `build`，不调用 `runClient`。

产物：

- `mc-neoforge/build/libs/endcraft-neoforge-guest-4.0.0.jar`
- 同目录的 `-sources.jar`
- `mc-neoforge/build/reports/tests/test/index.html`：测试报告

本机 37 项非游戏测试通过，覆盖射线／碰撞、下船落点、输入映射、网格格式、大坐标精度、共享内存心跳与重连，以及配置的 Mixin 目标与 Sable 反射接口。字节码检查不加载游戏，也不能证明多个模组的运行时注入顺序和画面表现正确。

## 允许实测后如何启动

**以下命令会启动 MC，仅在用户明确允许实测后执行。当前没有执行，也没有修改本机的加载器选择。**

先独立检查模组是否能加载：

```powershell
.\tools\start-neoforge.ps1
```

独立模式显示 MC 窗口，默认不连接终末地，不会自动打开桥接存档。可创建一个新的创造存档检查 Create 和航空学。

接入已经运行的 EndCraft 宿主：

```powershell
.\tools\start-neoforge.ps1 -BridgeHost
```

此模式连接后隐藏 MC 窗口，并创建／打开 `mc-neoforge/run/saves/EndCraft-Neo-Bridge`。启动前必须正常保存退出旧 Fabric 桥接客户端；脚本发现旧写入进程会拒绝启动。

如果现有宿主会自动拉起 Fabric，可在**之后获准的测试会话**将启动器继承的加载器选择改为 NeoForge：

```powershell
[Environment]::SetEnvironmentVariable('ENDCRAFT_GUEST_LOADER', 'neoforge', 'User')
```

正常退出游戏和原启动器，重新打开启动器，使其继承新环境变量。宿主仍调用原来的 `tools/start-guest.ps1`，该脚本据变量选择客户端。此选择不会自动安装或替换 Host。

日志在 `reports/neoforge-startup.log` 和 `mc-neoforge/run/logs/latest.log`。首次启动还会下载 1.21.1 的资产；构建成功不代表资产已全部缓存。

## 局域网联机（实验）

一台电脑运行终末地和桥接客户端作为主机，其他人用普通的 NeoForge 1.21.1 客户端加入同一个世界。复用 MC 自带的局域网联机，不需要其他人运行终末地。

**主机**：进入桥接存档后，按 T 打开 MC 聊天并输入：

```text
/endcraft lan                 # 开放局域网，验证正版账号
/endcraft lan offline         # 不验证正版账号（本机连不上 Mojang 验证服务器时使用）
/endcraft lan offline 25565   # 指定端口
```

聊天框会显示端口和本机局域网地址。也可以在 `mc-neoforge/run/config/skycraft.properties` 写入 `lan=true`（以及可选的 `lan_port`、`lan_online_mode=false`），进入存档后自动开放。

**访客**：安装与主机相同的 NeoForge 21.1.247、Create、Aeronautics、Sable、Create: Flight Control 和 EndCraft NeoForge 客户端 jar，在多人游戏中选择局域网世界，或直接连接主机显示的地址。

| 效果 | 说明 |
|---|---|
| 出生位置 | 访客加入或重生时出现在主机玩家身边，不会掉进虚空世界 |
| 地形碰撞 | 主机服务器把终末地地形的碰撞体素和三角面，按访客位置（水平 96 格内）分批同步给访客，访客可以站在终末地地形上 |
| 地形显示 | 访客的 MC 把收到的三角面按材质着色，显示为低多边形地面 |
| 主机看到访客 | 访客的 MC 角色、放置的方块和机械动力装置照常绘制到终末地场景 |

限制：地形只来自主机终末地角色周围已加载的区域，访客走远后没有地形；终末地 NPC 在访客端不可见；访客不能看到终末地原生画面。**该功能已通过构建和单元测试，尚未实机联机验收。**

## 存档与回退

不要把 `mc/run/saves` 的 26.3 存档复制给 1.21.1 打开。物品、数据组件和存档格式都存在差异，当前没有降级转换器。

回退时正常保存退出 NeoForge，再清除选择并重新打开启动器：

```powershell
[Environment]::SetEnvironmentVariable('ENDCRAFT_GUEST_LOADER', $null, 'User')
```

默认恢复 Fabric。旧 `mc/` 工程、旧存档、已安装模块和宿主锚点没有在本次迁移中被覆盖。两个实例的建筑和库存相互独立。

## 实机测试记录（2026-10-08）

- 模组组合可以加载；桥接模式连接终末地，传送、地面抬升、宿主 NPC 替身正常。
- 首轮测试修正：实体类型改为 `DeferredRegister` 注册；Flywheel `VisualizationManager` 是接口，Mixin 改为接口并使用 `LevelAccessor` 参数。`MixinTargetTest` 增加了接口目标、`@Inject` 参数和 `@WrapOperation` 签名检查。
- 在 `EndCraft-Neo-Bridge` 中放置了 Create 夹具（动力源、传动轴、齿轮、玻璃、水），并生成了 Sable 测试船体；`render_audit` 捕获到 1 个批次、180 个顶点。
- **黑屏问题**：联机后终末地只显示 MC HUD，背景全黑。`overlay_audit` 测得导出帧约 99% 像素为 alpha=255 的黑色。有两个来源：Sable 内置的 Veil 在世界渲染后运行 `composite` 后处理，经无 alpha 的 RGB16F 缓冲写回主帧缓冲；1.21.1 的屏幕暗角（vignette）混合模式也会把整屏 alpha 写成 1。已增加 `VeilPostMixin` 和 `GuiVignetteMixin`，联机期间跳过这两步。重启后实测：导出帧约 98% 像素完全透明，不透明像素只剩 HUD。
- **物理体有时无法被物理杖选中、无法左键破坏**：Sable 把射线命中船体的位置报告为 plot 坐标（约 2000 万格外），并改写了 MC 自己的距离判断；本项目的 `SkyClip` 却用原始坐标比较 MC 命中与终末地地形命中，船体总被判定为更远，准星沿线只要有终末地地形就被地形抢走。现改为先用 `SableCompanion.projectOutOfSubLevel` 投影回世界坐标再比较。`pick_probe` 实测：原始距离 28,968,096 格，投影后 11.08 格，命中保留为船体方块。
- **方块遮挡错误**：移植时 `ClientLevelDigMixin` 只重发方块所在的 16³ 区段，不像原 Fabric 版那样挂 `setSectionDirty`；区段边界上放置／破坏方块后，相邻区段被剔除的面不会更新，出现透视破洞或残留面。现在 `LevelRendererMixin` 挂 1.21.1 的 `setSectionDirty(IIIZ)`，方块改动、相邻区段、区块加载、光照更新都会重发；同时恢复被遗漏的 `SkyDigClient.blockChanged`，周期扫描只补发尚未导出的区段。
- **船体／Create 部件之间层级错乱**：动态网格（Sable 船体、方块实体、实体）的“是否半透明”原来靠 RenderType 描述文本里有没有 `translucent`／`alpha`／`text` 来判断，而每个描述都含 `texture[...]`，于是全部被标成半透明，宿主不写深度、按提交顺序叠画。现改为读取 RenderType 的透明度设置（`no_transparency` 即不透明）。`rendertype_probe` 实测：solid、cutout、entitySolid、entityCutout 为不透明，translucent、entityTranslucent 为半透明。
- **放置的方块隔一段时间消失，在旁边再放一个又出现**：终末地在渲染端未就绪（场景切换、菜单、重新初始化）时会丢弃收到的网格消息，处理时出现托管异常也会丢弃该区段；场景重载还会销毁已有的区段对象。原 Fabric 版在终末地世界编号（epoch）变化时会清空并全量重发，移植版缺了这一步，之前每 20 帧的全量重扫恰好掩盖了问题。现在：epoch 变化时先发 `ClearAll` 再全量重发；终末地加载／菜单期间暂停发送，恢复后重发已导出区段；没有新改动时每 4 帧轮换重发一个已导出区段，丢失的区段会自动恢复。实测重启后终末地持有区段从 16（含失效残留）变为 2、可见网格从 0 变为 3，未再出现新的丢弃。
- **不自动进图**：Create／Sable 的世界数据使 1.21.1 把桥接存档标为实验性设置，打开时弹出备份确认页，而桥接模式下 MC 窗口隐藏，无法点击。`MirrorWorld` 现在在打开自己的桥接存档时自动选择“不备份直接继续”。重启后实测可以自动进入存档。

运行时诊断（只对本项目 NeoForge 客户端生效）：

```powershell
.\tools\test-neoforge-runtime.ps1 -Operation status         # 玩家、界面、Sable 船体
.\tools\test-neoforge-runtime.ps1 -Operation overlay_audit  # 导出帧透明度
.\tools\test-neoforge-runtime.ps1 -Operation render_audit   # Sable 网格捕获与批次透明标记
.\tools\test-neoforge-runtime.ps1 -Operation stage_probe    # 各渲染阶段与 HUD 图层的像素
.\tools\test-neoforge-runtime.ps1 -Operation pick_probe     # 朝首个船体的射线命中与距离
.\tools\test-neoforge-runtime.ps1 -Operation rendertype_probe # 渲染类型的半透明判定
```

## 还需要完成的兼容验证

| 项目 | 当前状态与验收要求 |
|---|---|
| 基础桥接 | 模组组合加载、自动进入桥接存档、HUD 透明叠加、放置方块显示已实机确认；库存、水、死亡恢复仍需逐项验收 |
| Create | 已接入普通渲染捕获；需检查齿轮、轴、机械动力传输、移动装置及其动态贴图，确定有没有绕过捕获的特效 |
| 航空学显示 | 已写船体姿态与网格适配；需验证组装、平移、旋转、解体和重连，不能只看到静态方块就认定成功 |
| 玩家乘坐／站立 | 依赖 Sable 运行时钩子；需确认移动船体上的玩家、镜头、交互射线和上下船位置 |
| 船体与宿主地形碰撞 | 已写 `SableTerrainMixin`／`SableHostTerrain`：在 Sable 把区块段上传给 Rapier 前，用宿主地形体素填充空气格；地形流更新、方块改动后逐格同步，玩家放置的 MC 方块优先。宿主地形按石头的摩擦与弹性计算。适配器已加载无报错，**船体落地、滑行和地形重载尚未实机验收** |
| Create: Flight Control | 0.7.7 已加载，未装的可选联动（ComputerCraft 等）自动跳过；飞控方块尚未实机验收 |
| 宿主 NPC 与移动船体 | 当前动态网格通过场景消息绘制，没有建立对应的动态宿主碰撞体；NPC 登机、阻挡与寻路未完成 |
| 光影与额外渲染器 | 未兼容。Veil／Flywheel 的特殊 GPU 绘制和材质效果需要分别检查；暂不安装额外光影模组 |

首轮限制为附近静态区块、最多 96 个普通实体、256 个方块实体及 8 艘附近船体，船体方块遍历总预算 8192。单条纹理消息受协议大小限制，高分辨率材质可能超出预算。这是便于首轮定位问题的范围，不保证复杂大型飞船或工厂帧率。

下一阶段验收小型航空学船体在终末地地面的起降与滑行，并为移动船体建立宿主碰撞体；完成前不标记“航空学完全兼容”。

## 许可与分发

本仓库桥接源码沿用 MIT 和上游声明。下载脚本从发布方获取 Create、Aeronautics、Sable 与 Create: Flight Control，逐文件验证 SHA-512；第三方 JAR、游戏资产和本机存档不进入 Git。各模组按各自许可分发，锁文件不改变这些许可。Create: Flight Control 未公开源码仓库，锁文件的来源链接指向其 Modrinth 页面。
