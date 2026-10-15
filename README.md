# EndCraft

**4.2.1 · Windows x64 · 终末地 1.6.3 · 实验性项目**

在终末地场景中使用 Minecraft 的角色、物品栏、建造和战斗，也可选装机械动力、航空学、枪械与女仆。两款游戏同时运行：MC 计算玩法，终末地显示场景。

## 先看启动方式

**终末地必须通过官方鹰角启动器启动。** EFML 用来安装、启用和加载模组；当前使用已验证的 **xinput 代理加载**。安装代理后，游戏经官方启动器启动时会自动载入 EFML 和已启用的 EndCraft 模块。

当前流程为：**配置 EFML → 官方启动器进入终末地可移动场景 → 启动 MC → 等待桥接。** 直接运行 `Endfield.exe` 或沿用旧版第三方直接启动／注入流程，会遇到“请通过官方启动器打开游戏”。

## 安装 4.2.1

### 下载组件

从 [4.2.1 发行页](https://github.com/guchang233/EndCraft/releases/tag/v4.2.1) 下载以下三个文件：

| 文件 | 放在哪里 |
|---|---|
| `EndCraft-gameplay39-4.2.1-win-x64.zip` | 导入 EFML：玩法模块 |
| `EndCraft-probe-4.2.1-win-x64.zip` | 导入 EFML：必需的共享内存模块 |
| `endcraft-neoforge-guest-4.2.1.jar` | 放入 MC NeoForge 实例的 `mods` 文件夹 |

发行页还提供 Fabric 客户端、完整源码归档和校验和。普通 NeoForge 用户不需要下载它们；源码包不能导入 EFML。MC 本体、Java、第三方模组及光影包需要自行准备。

### 配置 EFML

1. 正常退出终末地，安装 [EFML](https://github.com/guchang233/EFML/releases/latest)，指定游戏目录，在加载方式中选择并安装 **xinput 代理**。
2. 在 EFML 的模组页分别导入上面两个 ZIP，启用 `endcraft.probe` 和 `endcraft.gameplay39`。默认配置已包含 `auto_enable: true`。
3. 停用 gameplay38 等旧玩法模块。同一时间只启用一个 EndCraft 玩法模块，probe 需同时启用。

如果 EFML 提示游戏目录已有其他加载器，先用原来的安装工具卸载其代理，再安装 EFML 代理；不要直接覆盖或删除来源不明的 `xinput1_4.dll`。

### 配置 Minecraft

用 PCL2、HMCL 等 MC 启动器创建独立实例：

| 项目 | 配置 |
|---|---|
| Minecraft | 1.21.1 |
| 加载器 | NeoForge；开发环境为 21.1.247，正式 21.1.257 实例已接通桥接 |
| Java | Java 25 |
| MC 内存 | 手动分配 4 GB；与终末地同时运行时避免被自动分配到过小内存 |
| EndCraft | 将上述 NeoForge JAR 放进这个实例的 `mods`，移除旧的 EndCraft JAR |

在实例的 JVM 参数中加入：

```text
--enable-native-access=ALL-UNNAMED -Dendcraft.allowHostConnection=true
```

### 开始游戏

1. 打开**官方鹰角启动器**，点击开始游戏，进入终末地可以移动角色的场景。
2. 确认 EFML 已加载 probe 和 gameplay39，再从 MC 启动器启动上述实例。
3. 等待 MC 资源和桥接存档加载。MC 窗口连上后会自动隐藏，回到终末地即可操作；加载尚未完成时不要再次启动 MC。

看到 MC 物品栏后先测试移动、打开背包和放置方块。没有出现时，按 [故障排查](docs/TROUBLESHOOTING.md) 检查模块状态和 MC 日志。终末地关闭后，已连接的 MC 会尝试正常保存并退出。

## 从旧版升级

正常退出两款游戏，导入并启用 4.2.1 的两个模块 ZIP，停用旧玩法模块。4.2.1 的伤害修正在宿主，已有 4.2.0 MC JAR 可继续使用；需要统一版本号时再换成 4.2.1 JAR，替换后保留一个 EndCraft JAR。

保留原来的 MC 存档与世界锚点，不要用删存档解决连接问题。MC 单独退出或崩溃后，可重新启动同一个实例连接仍在运行的终末地。只退出世界回到 MC 菜单后若未自动重连，可正常退出 MC，再启动该实例。

## 可选模组

以下是本项目使用的版本组合，第三方 JAR 不包含在 EndCraft 附件中；来源、文件名与哈希见 [依赖锁文件](mc-neoforge/modpack.lock.json)。联机双方应使用相同组合。

| 内容 | 版本／状态 |
|---|---|
| Create / Aeronautics / Sable / Flight Control | 6.0.10 / 1.3.2 bundled / 2.0.6 / 0.7.7；航空学尚未完成全部实机验收 |
| TaCZ / Touhou Little Maid | 1.1.8-hotfix-r7 / 1.5.3；Z 配件预览和点击隔离已实测 |
| GeckoLib | 4.9.3，开发组合保留的前置 |
| YSM | 当前停用，本机原生库加载失败 |
| Maid Addition | 可选本地文件，无锁定的公开下载地址；缺少它不阻断构建 |

## 4.2.1 伤害规则

**原生伤害输入 = MC 命中伤害 ÷ 5 × 当前终末地角色攻击力 × 倍率。** 去掉了按目标最大生命扣百分比的旧算法，敌人血量差异不再被换算抵消。

EFML 的 gameplay39 模块配置中，`damage_multiplier` 默认 `1`，允许 `0.01–100`；`2` 为两倍，`0.5` 为一半。修改时保留已有字段。公式和配置检查通过，已实际接通桥接并核对攻击力接口签名；**真实命中扣血与实战平衡尚未验证**。详见 [伤害说明](docs/COMBAT.md) 与 [4.2.1 更新说明](docs/RELEASE-4.2.1.md)。

## 操作与当前限制

- **WASD / 空格 / 左右键**：移动、跳跃、攻击、建造和使用物品。
- **E / F5 / T**：背包、切换视角、聊天；**分号**切换 MC 输入独占，关闭后可能出现双游戏同时响应。
- **F6**：桥接就绪且未打开 MC 菜单时切换终末地原生人物显示；默认隐藏。
- **单引号 `'`**：开关终末地地形三角面；`/endcraft recover` 或 **Ctrl+Alt+R** 恢复位置同步。
- **`/endcraft lan offline`**：开放 MC 局域网，见 [联机教程](docs/MULTIPLAYER.md)。两台电脑各运行终末地的完整联机仍未实测。

复杂／多层地形、女仆长期支撑、航空学起降和 NPC 登船仍有未完成项。光影仅有完整 MC 画面的实验性预览，尚未实现与终末地场景的深度融合；性能优化也没有同场景联动 FPS 对照。Fabric 26.3 客户端保留，不能与 NeoForge 混用存档。

[安装与源码构建](docs/INSTALL.md) · [操作教程](docs/USER-GUIDE.md) · [故障排查](docs/TROUBLESHOOTING.md) · [NeoForge](docs/NEOFORGE.md) · [渲染](docs/RENDERING.md) · [性能](docs/PERFORMANCE.md) · [实现原理](docs/ARCHITECTURE.md)

## 许可

宿主基于 [Better-Endfield](https://github.com/Dr-hydra/Better-Endfield)，使用 AGPL-3.0；MC 端改编自 [SkyCraft](https://github.com/chasmlol/SkyCraft)，使用 MIT。第三方模组遵循各自许可，见 [LICENSE](LICENSE) 和 [第三方声明](THIRD-PARTY-NOTICES.md)。
