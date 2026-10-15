# EndCraft

**4.2.0 · Windows x64 · 实验性项目。** 在终末地的真实场景中使用 Minecraft 的角色、物品栏、方块、战斗、机械动力和航空学，支持 MC 局域网联机。两款游戏同时运行：MC 计算玩法，终末地显示场景。

4.2.0 使用 `endcraft.gameplay38` 适配终末地 **1.6.3**，修正 HUD、物品栏和手持物品颜色、TaCZ 配件预览及菜单点击穿透，并优化网格和纹理传输。安装与验证状态见 [1.6.3 适配记录](docs/ENDFIELD-1.6.3.md) 和 [4.2 更新说明](docs/RELEASE-4.2.0.md)。

**目录**：[安装](#安装-420) · [加载方式](#加载方式) · [常用操作](#常用操作) · [限制与文档](#限制与文档) · [许可](#许可)

## 安装 4.2.0

普通玩家使用发行附件即可，无需源码、Visual Studio 或 Python。

> [!IMPORTANT]
> - **终末地 1.6.3 当前已验证 EFML 的 xinput 代理方式。** 安装代理后从官方鹰角启动器开始游戏。旧版 Better-Endfield 的第三方模块方式见 [加载方式](#加载方式)。
> - **Minecraft 需要自行下载和安装。** EndCraft 不附带 MC 本体、启动器、Java 或任何模组依赖，只提供终末地宿主模块和 MC 端模组 JAR；请自行购买正版 Minecraft，并用 PCL2、HMCL 等启动器安装。

1. 安装终末地并确认能正常进入游戏，再安装 [EFML](https://github.com/guchang233/EFML/releases/latest)，设置游戏路径并安装 xinput 代理。
2. 从 [EndCraft 4.2.0 发行页](https://github.com/guchang233/EndCraft/releases/tag/v4.2.0) 下载 **3 个文件**：
   - `EndCraft-gameplay38-4.2.0-win-x64.zip`：终末地玩法模块。
   - `EndCraft-probe-4.2.0-win-x64.zip`：共享内存模块，负责建立终末地与 MC 的通信通道。**缺少它 MC 连不上**，玩法模块会提示缺少依赖 `endcraft.probe`。
   - `endcraft-neoforge-guest-4.2.0.jar`：MC 模组。
3. 打开 EFML 的 **「模组」→「导入模组压缩包」**，**两个 ZIP 分别导入并都启用**，停用旧版玩法模块。首次导入默认配置已包含 `auto_enable: true`。
4. **自行下载安装 Minecraft**：用 PCL2 或 HMCL 新建独立的 **MC 1.21.1 + NeoForge 21.1.247 或更新** 实例，自行安装 **Java 25**。
   - **游戏内存手动设为 4 GB**（装机械动力时至少 4 GB）。不要用自动分配：终末地运行时空闲内存很少，PCL 自动分配可能只给 512 MB，MC 会在加载中内存溢出、直接消失。
5. 将上述 MC JAR 放入该实例的 `mods` 文件夹，不需要其他模组。在实例 JVM 参数末尾追加：

   ```text
   --enable-native-access=ALL-UNNAMED -Dendcraft.allowHostConnection=true
   ```

6. **先通过官方鹰角启动器启动终末地，进入可移动场景；再启动 MC。** EFML 会随游戏加载模块。连上后 MC 窗口会**自动隐藏**（正常现象，画面改在终末地中显示），继续加载约 1–2 分钟后自动打开桥接存档。切回终末地，看到 MC 物品栏即可。只启动一个 MC。

**可选：机械动力与航空学。** EndCraft 已兼容 [Create 6.0.10](https://modrinth.com/mod/LNytGWDc/version/UjX6dr61)、[Aeronautics 1.3.2 bundled](https://modrinth.com/mod/oWaK0Q19/version/44pLdPGg)、[Sable 2.0.6](https://modrinth.com/mod/T9PomCSv/version/fg9dTRz9) 与 [Create: Flight Control 0.7.7](https://modrinth.com/mod/IGVrnkhH/version/act82pgK)，想玩可自行放入 `mods`，版本需一致；不装不影响基本玩法。联机时所有人的模组组合要相同。

**可选：枪械与女仆。** 开发组合使用 TaCZ 1.1.8-hotfix-r7、Touhou Little Maid 1.5.3 和 GeckoLib 4.9.3；版本与来源见 [锁文件](mc-neoforge/modpack.lock.json)。YSM 当前停用；Maid Addition 是自行提供的可选本地文件。第三方模组、枪包和光影包均不随发行附件分发。

ZIP 导入管理器，JAR 放入 MC 的 `mods`，两者不要混用。只导入上面两个模块 ZIP；源码包 `EndCraft-*-source.tar.gz` 不是模块。

源码安装过旧版的用户：游戏目录里的 `xinput1_4.dll` 是 EndCraft 旧加载器，Better-Endfield 启动时会提示“游戏目录已有未知 xinput1_4.dll”。关闭游戏后执行 `python tools/install-probe.py uninstall` 卸载旧加载器，再从 Better-Endfield 启动；继续使用旧加载器的按 [源码安装与升级说明](docs/INSTALL.md) 维护。两套加载器只保留一套，只启用一个 EndCraft 玩法模块（`endcraft.probe` 不算玩法模块，需同时启用）。

4.1.0 用户：4.1.0 附件缺少共享内存模块，玩法模块还缺 `author` 字段导致无法导入，请改用 4.1.2 附件。终末地关闭或闪退后，MC 会自动保存并退出，不再留在后台。

## 加载方式

EndCraft 的两个 ZIP 是第三方模块，需要由模组加载器装进终末地。下面两种加载器任选其一：

| 加载器 | 状态 | 说明 |
|---|---|---|
| [Better-Endfield 3.5.4](https://github.com/Dr-hydra/Better-Endfield/releases/tag/v3.5.4) | 旧版游戏曾验证 | 需有第三方模块入口；未复测终末地 1.6.3 |
| [EFML](https://github.com/guchang233/EFML) | 1.6.3 实机模块加载已验证 | 基于 Better-Endfield 核心组件的独立模组加载器，与其第三方模块格式兼容 |

**EFML 加载步骤**：

1. 从 [EFML 发行页](https://github.com/guchang233/EFML/releases/latest) 下载 `EFML-<版本>-Setup.exe` 并安装。首次运行时，它会自动查找游戏，并让你选择加载方式：「注入」（推荐）或「xinput 代理」。
2. 在 EFML 的 **「模组」→「导入模组压缩包」** 中分别导入 `EndCraft-probe-…zip` 和对应版本的 `EndCraft-gameplay…zip`，确认两个都已启用，仅保留一个玩法模块。
3. 安装 EFML 的 xinput 代理后，通过官方鹰角启动器点击「开始游戏」。终末地 1.6.3 直接执行 `Endfield.exe` 会提示必须使用官方启动器。进入可移动场景后再启动 MC，其余步骤不变。

**两种加载器不能同时使用。** 如果之前在 Better-Endfield 中选择了「XInput自启动」，请先在 Better-Endfield 中卸载它（或改用其他加载方式），确保游戏目录里没有 Better-Endfield 的 `xinput1_4.dll`。否则 EFML 会提示游戏目录中已有其他加载器，并拒绝启动。换回 Better-Endfield 前，在 EFML 的「设置」中移除代理（如果安装过）。

## 常用操作

- **WASD / 空格**：移动、跳跃；**左键 / 右键**：攻击、破坏、放置、交互。
- **E / F5 / T**：库存、切换视角、聊天与指令。
- **;（分号）**：切换 MC 独占输入；关闭后恢复终末地输入，MC 仍可能响应同一按键。
- **F6**：桥接就绪且没有打开 MC 界面时，切换终末地原生人物模型显示。默认隐藏，按一次显示、再按一次隐藏；状态在当前模块运行期间保持。需加载更新后的玩法 DLL。
- **'（单引号）**：开关终末地地形三角面显示。
- `/gamemode creative`：创造模式；`/endcraft recover` 或 **Ctrl+Alt+R**：恢复角色同步。
- `/endcraft lan offline`：开放局域网；朋友使用相同模组加入。详见 [联机教程](docs/MULTIPLAYER.md)。

## 限制与文档

光影与终末地原生场景的深度融合尚未实现，NeoForge 已增加完整 MC 管线的实验性光影预览入口，见 [渲染覆盖与光影说明](docs/RENDERING.md)。桥接侧已减少重复建模与分配，并增加异步图集读回及队列背压，验证范围见 [性能说明](docs/PERFORMANCE.md)。复杂地形、航空学起降、NPC 登船与 TNT 炸原生岩石仍有未验收项。两台电脑各自运行终末地的联机尚未实机验证。Fabric 26.3 客户端仍保留，存档与 NeoForge 1.21.1 不兼容。

[4.2 更新说明](docs/RELEASE-4.2.0.md) · [操作教程](docs/USER-GUIDE.md) · [故障排查](docs/TROUBLESHOOTING.md) · [NeoForge 与源码构建](docs/NEOFORGE.md) · [实现原理](docs/ARCHITECTURE.md)

## 许可

宿主基于 [Better-Endfield](https://github.com/Dr-hydra/Better-Endfield)，使用 AGPL-3.0；MC 端改编自 [SkyCraft](https://github.com/chasmlol/SkyCraft)，使用 MIT。第三方模组遵循各自许可，见 [许可](LICENSE) 与 [第三方声明](THIRD-PARTY-NOTICES.md)。
