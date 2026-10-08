# NeoForge 与机械动力航空学实验版

当前成果是可构建的独立移植版，**不是已验收的完整航空学联动**。遵照本次要求，开发期间没有启动《终末地》或 Minecraft；实机测试等用户明确允许后进行。

## 为什么使用 1.21.1

航空学的官方项目使用 NeoForge 1.21.1，无法直接安装到现有 Fabric 26.3 实例。此实验版保留原工程，新增 `mc-neoforge/`，使用全新目录和存档；宿主通信仍使用协议 12。

| 组件 | 固定版本 |
|---|---|
| EndCraft Guest | `3.1.0-neoforge-experimental` |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.247 |
| Create | 6.0.10+mc1.21.1 |
| Create Aeronautics | 1.3.2+mc1.21.1，bundled 文件 |
| Sable | 2.0.6+mc1.21.1 |
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

- `mc-neoforge/build/libs/endcraft-neoforge-guest-3.1.0-neoforge-experimental.jar`
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

## 存档与回退

不要把 `mc/run/saves` 的 26.3 存档复制给 1.21.1 打开。物品、数据组件和存档格式都存在差异，当前没有降级转换器。

回退时正常保存退出 NeoForge，再清除选择并重新打开启动器：

```powershell
[Environment]::SetEnvironmentVariable('ENDCRAFT_GUEST_LOADER', $null, 'User')
```

默认恢复 Fabric。旧 `mc/` 工程、旧存档、已安装模块和宿主锚点没有在本次迁移中被覆盖。两个实例的建筑和库存相互独立。

## 还需要完成的兼容验证

| 项目 | 当前状态与验收要求 |
|---|---|
| 基础桥接 | 需确认模组组合能加载，移动、库存、透明、水、传送及死亡恢复在 1.21.1 下正常 |
| Create | 已接入普通渲染捕获；需检查齿轮、轴、机械动力传输、移动装置及其动态贴图，确定有没有绕过捕获的特效 |
| 航空学显示 | 已写船体姿态与网格适配；需验证组装、平移、旋转、解体和重连，不能只看到静态方块就认定成功 |
| 玩家乘坐／站立 | 依赖 Sable 运行时钩子；需确认移动船体上的玩家、镜头、交互射线和上下船位置 |
| 船体与宿主地形碰撞 | **尚未实现完整物理耦合。** 现有地形桥接供 MC 实体碰撞查询使用，Sable／Rapier 有独立的体素物理缓存；不能据此认为飞机能在终末地地面起降 |
| 宿主 NPC 与移动船体 | 当前动态网格通过场景消息绘制，没有建立对应的动态宿主碰撞体；NPC 登机、阻挡与寻路未完成 |
| 光影与额外渲染器 | 未兼容。Veil／Flywheel 的特殊 GPU 绘制和材质效果需要分别检查；暂不安装额外光影模组 |

首轮限制为附近静态区块、最多 96 个普通实体、256 个方块实体及 8 艘附近船体，船体方块遍历总预算 8192。单条纹理消息受协议大小限制，高分辨率材质可能超出预算。这是便于首轮定位问题的范围，不保证复杂大型飞船或工厂帧率。

下一阶段需要先取得启动授权，验收基础桥接和小型 Create 装置，再测试一艘小型航空学船体。原生地形的 Rapier 静态碰撞输入与移动船体的宿主碰撞体需要继续开发，完成前不标记“航空学完全兼容”。

## 许可与分发

本仓库桥接源码沿用 MIT 和上游声明。下载脚本从发布方获取 Create、Aeronautics 与 Sable，逐文件验证 SHA-512；第三方 JAR、游戏资产和本机存档不进入 Git。各模组按各自许可分发，实验版锁文件不改变这些许可。
