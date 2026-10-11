# EndCraft v4.2.0

4.2.0 为 NeoForge 客户端引入四个内容模组：**永恒枪械工坊（TaCZ）**、**Yes Steve Model（YSM）**、**车万女仆（Touhou Little Maid）** 与 Maid Addition，并完成静态兼容审计。玩法桥接功能与 4.1.2 相同。

## 内容模组

| 模组 | 版本 | 来源 |
|---|---|---|
| TaCZ（1.21.1 NeoForge 移植） | 1.1.8-hotfix-r7 | Modrinth `OypNE65K`，哈希核对一致 |
| Yes Steve Model | 2.6.3-neoforge+mc1.21.1 | Modrinth `86xjpqqS`，哈希核对一致 |
| Touhou Little Maid | 1.5.3-neoforge+mc1.21.1 | Modrinth `R0bDWFAW`，哈希核对一致 |
| Maid Addition | 1.0.1-beta | 无发布主页，仅本地 SHA-512 锁定 |
| GeckoLib（新增前置） | 4.9.3 | Modrinth `8BmcQJ2H`，YSM 外置依赖 |

全部九件模组（含原有四件）记录在 `mc-neoforge/modpack.lock.json`，`python tools/fetch-neoforge-mods.py` 逐件校验 SHA-512。

## 兼容处理

| 问题 | 处理 |
|---|---|
| YSM 缺少 GeckoLib 前置（jar 内无内嵌、无依赖声明），运行时会类加载失败 | 锁定 GeckoLib 4.9.3 作为外置前置；测试守卫验证 YSM 仍未内嵌 GeckoLib、GeckoLib 仍在锁文件 |
| 本项目 Mixin 与四个模组在 7 个类上共享注入目标 | 逐一比对发布字节码注入点，确认无方法级交集；允许清单写入 `MixinTargetTest`，新的类级重叠会直接使测试失败 |
| TaCZ 官方标注与 Veil 旧版不兼容 | Sable 内嵌 Veil 4.3.2（≥ 修复版 3.1.2），且 `VeilPostMixin` 目标有效性已有字节码审计覆盖 |
| 女仆、扫帚、枪械投射物占满旧实体导出预算（96） | 实体导出上限提高到 192；方块实体预算不变 |

新增静态测试（不加载游戏）：四个模组的 Mixin 配置每个声明类可解析、锁文件与 `libs/` 目录双向一致（哈希逐件核对）、YSM GeckoLib 前置守卫。

## 待实机验收

静态审计**不等于**游戏内兼容确认。以下各项需实测后再更新 [NEOFORGE](NEOFORGE.md)：

- 四个模组随桥接客户端加载、进入桥接存档；
- 枪械射击/换弹的渲染捕获，以及 TaCZ 特效是否绕过捕获；
- 女仆实体（含 GeckoLib 动画）经实体导出通道的显示；
- YSM 玩家模型在第三人称 avatar 通道的表现；
- Maid Addition 区块加载对导出预算与帧率的影响；
- TLM 扫帚物理检查期间与终末地地形的穿模表现（模组主动跳过 collide 调用）。

## 使用提示

- 枪包（`.zip`）放入 `mc-neoforge/run/tacz/`；游戏内 `/tacz reload` 重载。
- YSM 模型包放入 `mc-neoforge/run/ysm/`。
- 以上目录以 MC 游戏目录（`mc-neoforge/run`）为根；独立与桥接模式共用。

安装与启动见 [README](../README.md)；桥接细节见 [NEOFORGE](NEOFORGE.md)。
