# EndCraft：MC ×《明日方舟：终末地》联动原型

**v1.0.0 是第 15 版原型的历史快照。** 当前开发修复版为 `endcraft.gameplay21`：HUD 按宿主分辨率绘制，已实测鞘翅滑翔与烟花推进，并加入请求宿主普通攻击的适配器；敌人实际扣血尚未验收。发布的 1.0 附件保持原样，当前改动尚未发布新版本。菜单遮罩降至 20%，消除了 HUD 重复绘制。见 [遮罩修复](docs/VALIDATION-gameplay19.md) 和 [飞行验证](docs/VALIDATION-gameplay18.md) 和 [1.0 说明](docs/RELEASE-1.0.0.md)。

当前实际模块为 `endcraft.gameplay21`。真实游戏已显示史蒂夫、装备、MC 方块和物品栏。MC 网格进入原生 HGRP 场景绘制，使用场景深度并在原版 UI 之前绘制。相机支持 ±90° 俯仰；MC 客户端和内置服务器统一地形碰撞。

完整用户目标尚未完成：复杂地形、NPC 碰撞/寻路、战斗、飞行、完整输入隔离与跨场景恢复仍需完善或验收。本次实机证据及限制见 [验证记录](docs/VALIDATION-gameplay18.md)。

MC 端来自 SkyCraft，使用独立的 `mc/run`；共享内存 `Local\EndCraft_v1`，协议版本 12。目标是在实际终末地中运行 MC 玩法，图形直接进入宿主场景。HGRP 材质不可用时退回独立深度合成；该退回方式不提供宿主建筑遮挡。

## 当前安装与使用

本机已注册并启用 `endcraft.gameplay21`，旧玩法模块与临时 Canvas 测试已停用。当前安装已配置下次启动自动开启桥接和项目 MC；本次没有为验收该启动配置重启游戏。`tools/start-guest.ps1` 检查项目与副本的进程，避免两个 MC 同时写映射。自动启动依赖本机的源码开发实例和已安装工具链，尚非独立发行安装包。

桥接俯仰范围为 -90° 至 +90°，第三人称加入正常宿主射线避障。极端仰视时相机会靠近角色，按 F5 使用第一人称可获得清晰视野。

按 `/` 打开 MC 指令输入（也支持 T），输入 `/gamemode creative`、`/gamemode survival`、`/gamemode adventure` 或 `/gamemode spectator` 后回车。权限仅授予项目自身的集成镜像世界，不更改其他 MC 实例或远程服务器。ASCII 指令输入已接入；中文输入法组合尚未接入。

死亡后保持死亡前的视角。只剩 HUD 时先按 F5 切回第三人称；仍异常时关闭终末地原生菜单，再按 **Ctrl+Alt+R** 重新同步角色位置，或输入 **`/endcraft recover`**（同时切回第三人称）。MC 死亡和持续等待超过 5 秒会尝试自动同步。若终末地原角色死亡，需要先通过原游戏复活或换到存活角色。使用及验证见 [指令与恢复记录](docs/VALIDATION-gameplay21.md)。

当前按键：WASD 移动、空格跳跃、1–9 选择快捷栏、右键使用/放置、左键破坏、E 打开 MC 库存、F5 切换视角。鞘翅在加入镜像世界时修复并设为不损耗。穿戴在胸甲栏，空中再次按空格展开，手持烟花右键推进；本次受控测试使用正常 MC 展翼与物品使用方法，确认宿主跟随飞行。玩家双击空格输入与复杂地形连续起降仍需复验。桥接取得全局 PlayerController 的独立动作屏蔽令牌，关闭时移除自己的令牌；菜单快捷键和完整输入隔离仍需验证。

```powershell
python tools/runtime-report.py status --module endcraft.gameplay21 --output reports/gameplay21-status.json
```

诊断含实际 MC 坐标、相机模式、上传的模型顶点数、相机层掩码、可见网格数和渲染错误。`active` 或网格上传计数增加不能单独证明视觉效果正确。

## 构建

当前第 21 版修复保存在本机工作目录，尚未推送。以下命令取得 GitHub 上已发布的 1.0 历史快照及固定版本依赖：

```powershell
git clone --recurse-submodules https://github.com/guchang233/EndCraft.git
cd EndCraft
git checkout v1.0.0
git submodule update --init --recursive
```

需要 Windows x64、Visual Studio C++ 工具链、JDK 25 和 Python 3。构建工具均在本机发现；没有修改系统 PATH，也没有升级现有 MC 实例。当前固定 Minecraft 26.3、Fabric Loader 0.19.5、Fabric API 0.161.0+26.3、Loom 1.17.21、Gradle 9.6.1。

```powershell
# Python 可使用 Codex 捆绑解释器或自己的 Python 3
./tools/build-native.cmd
./tools/build-framework.cmd
./tools/build-mc.ps1 -JavaHome "你的 JDK 25 路径"
python tools/package.py
# 游戏正在运行时，打包至独立暂存目录，保留已加载的包：
python tools/package.py --staging-only
# 在 v1.0.0 标签的干净检出中构建历史 1.0 发行附件；该脚本固定打包 gameplay15：
python tools/package-release.py
```

外部源码固定提交见 `THIRD-PARTY-NOTICES.md`。若从不含 `third_party` 的源码包恢复：

```powershell
git clone https://github.com/chasmlol/SkyCraft.git third_party/SkyCraft
git -C third_party/SkyCraft checkout bfcaf178524b92c2cdeb88e4ce0f13ef9ded6f32
git clone https://github.com/Dr-hydra/Better-Endfield.git third_party/Better-Endfield
git -C third_party/Better-Endfield checkout 35216279f716b9a7b90bf565ec7e25e8999705b9
# mc 与协议源码已经随工程提供，无需覆盖。
```

## 实机验证

安装脚本添加普通 XInput 代理，不执行手工映射，不修改游戏原始档案或反作弊文件。遇到已有同名 DLL、框架设置或索引时会保留它们并停止安装。

```powershell
python tools/install-probe.py install --game-dir "D:\Arknights Endfield"
# 若游戏已经运行，需要正常退出并通过原启动器重启。
# 已安装后更新加载器（游戏必须正常退出）：
python tools/install-probe.py update
# 旧加载器明确报告 Host 未加载时，也可提前部署并保留旧 DLL：
python tools/install-probe.py stage-update
# 下一次正常启动才加载修复版。
# 首次安装的 Host 在实机中未启动接口时，部署 Host 路径诊断：
python tools/install-probe.py stage-host
./tools/build-mc.ps1 -Run -JavaHome "你的 JDK 25 路径"
python tools/runtime-report.py host-status --output reports/host-status.json
python tools/runtime-report.py probe --output reports/method-probe.json
python tools/runtime-report.py observe --output reports/camera-start.json
# 进入可操控场景、转动视角后再读取。
python tools/runtime-report.py status --output reports/runtime-status.json
python tools/diagnose.py
# 可分别添加只读元数据、角色读取和共享内存发布模块（每种仅注册一次）：
python tools/register-inspector.py inspect
python tools/register-inspector.py actor
python tools/register-inspector.py telemetry
python tools/runtime-report.py observe --module endcraft.telemetry --output reports/telemetry-start.json
python tools/runtime-report.py status --module endcraft.telemetry --output reports/telemetry-status.json
```

开发客户端不需要复制账户凭据；Loom 的 Realms 认证提示不代表本地 Fabric 加载失败。正式发行时应通过合法 MC 启动器认证，不捆绑账号或游戏文件。

`probe` 只解析方法。`observe` 在严格匹配 `CameraManager.TailLateTick(System.Single)` 和相机读方法后，通过 Host 的共享 Hook 链在游戏线程读取矩阵和 FOV；它不写相机、角色、生命值或输入。解析成功显示 `resolved_not_verified`，实际连续读到有效相机数据后才确认相机观察能力。已加载的图形 DLL 列表不等于当前图形 API，环境报告中的 API 仅来自标有时间的最近运行日志。

原生 DLL 的 JSON 输入严格解析。诊断模块和玩法模块分别启用；玩法模块的 enable/disable 通过认证 RPC 执行。停用基础探针会撤销观察、停止心跳并关闭映射。宿主保留已经加载的 DLL；已有 DLL 更换需要重启，新身份模块可以通过索引添加。

`endcraft.motion` 和 `endcraft.teleport` 是单次移动试验模块，默认只观察。仅显式 `nudge` 请求会安排游戏线程写入；水平距离限 0.05，8 帧后恢复。它们没有开启持续接管，也没有实现飞行。结果与恢复限制见 `docs/IMPLEMENTATION.md`。MC 将已验证的只读宿主角色采样写入 `mc/run/endcraft-host-actor.json`，其中坐标仍为宿主原始单位，年龄表示采样新鲜度。

加载器通过游戏目录的 `EndCraft-bootstrap.ini` 定位 Host，并将 Host 的配置目录固定到本次安装记录的 Windows 用户目录。加载路径与错误保存在游戏目录 `EndCraft-loader.log`。共享内存仅授予当前 Windows 用户、SYSTEM 和管理员，并使用 medium 完整性级别，以支持启动器提升权限而 MC 保持普通权限的情况。

MC 对映射的读取、写入与释放使用同一锁；宿主停止心跳后会释放旧视图和句柄，允许重新连接。Windows 上的 `NativeLinkTest` 使用独立随机映射与真正的 C++ 子进程测试两轮连接、心跳、退出及重连，不占用游戏映射。构建包含 24 项 Java 测试；`python tools/test_installer.py` 另测 7 项安装文件保护行为。原生测试覆盖模块身份/生命周期，以及移动试验的距离上限、重复请求、不可行走位置、恢复和角色切换；模拟测试不作为实机证据。

Host 优先读取项目 `.tools/framework/third-party/index.json`，保留用户目录索引作为工具与旧安装兼容入口；两份索引使用同一凭据，均不应分享。`tools/prepare-framework.ps1` 从固定提交生成附加路径与进程编号日志的 Host 源码，原始上游文件保持不变。独立进程 `tools/host-smoke.py` 的成功只说明框架可运行，不说明实机接入成功。

## 卸载与产物

```powershell
# 正常关闭终末地之后：
python tools/install-probe.py uninstall
```

卸载只删除安装记录中哈希仍匹配的自有文件；修改过的文件会保留。运行日志不删除。`dist/` 包含模块 ZIP、MC JAR 和 SHA256 校验文件；`reports/` 保存本机与运行结果；`.tools/` 中的认证索引和安装状态不应分享。

阶段与验收表见 `docs/IMPLEMENTATION.md`。本项目包含 AGPL-3.0 与 MIT 源码，分发时遵守 `LICENSE` 和第三方声明。
