# EndCraft v4.0.0

4.0 新增 **NeoForge 1.21.1 客户端**，在终末地场景中使用机械动力（Create）、机械动力航空学（Create Aeronautics／Sable）与 Create: Flight Control。原 Fabric 26.3 客户端保留，两者共用宿主模块 `endcraft.gameplay33` 与共享内存协议 12，按需选择其一启动。

## 新增

| 内容 | 说明 |
|---|---|
| NeoForge 客户端 | `mc-neoforge/`：MC 1.21.1、NeoForge 21.1.247，独立目录与存档 `EndCraft-Neo-Bridge` |
| 固定模组组合 | Create 6.0.10、Create Aeronautics 1.3.2（bundled）、Sable 2.0.6、Create: Flight Control 0.7.7；文件名、地址与 SHA-512 固定在 `mc-neoforge/modpack.lock.json` |
| Create 渲染 | 方块实体与移动装置走普通渲染器并被捕获；桥接期间让 Flywheel 回退到可捕获的绘制路径 |
| 航空学船体 | 读取 Sable 船体的插值姿态，把 plot 方块转换到世界坐标后绘制到终末地 |
| 船体与终末地地形 | 把终末地地形体素注入 Sable 的 Rapier 静态地形，船体可与原生地面碰撞（待实机验收） |
| 启动与诊断 | `tools/start-neoforge.ps1`（独立／`-BridgeHost`）、`tools/test-neoforge-runtime.ps1` 运行时诊断（透明度、渲染阶段、射线命中、渲染类型） |

## 首轮实机测试中修复的问题

| 现象 | 原因与修复 |
|---|---|
| 进游戏黑屏，只剩 MC HUD | Sable 内置的 Veil 后处理和 1.21.1 屏幕暗角都会把整帧 alpha 写成 1；联机时跳过两者。导出帧约 98% 像素恢复透明 |
| 不自动进入桥接存档 | Create／Sable 世界数据触发 1.21.1 的实验性设置备份确认，而桥接时 MC 窗口隐藏；打开自己的桥接存档时自动确认 |
| 物理杖有时选不中船体、左键打不掉 | Sable 以 plot 坐标（约 2000 万格外）报告船体命中，比较时被终末地地形抢走；先投影回世界坐标再比较 |
| 船体／Create 部件互相穿插 | 动态网格的半透明判断误把所有渲染类型当成半透明而不写深度；改为读取渲染类型的透明度设置 |
| 区段边界方块出现破洞或残留面 | 恢复与 Fabric 版一致的 `setSectionDirty` 挂钩，相邻区段、区块加载和光照更新都会重发 |
| 放置的方块隔一段时间消失 | 终末地在场景切换／菜单期间会丢弃网格消息；世界编号变化时清空并全量重发，恢复后补发，并持续轮换重发已导出区段 |

## 已知限制

- 航空学船体在终末地地面的起降、滑行，以及移动船体的宿主碰撞体（NPC 阻挡、登船）尚未完成验收。
- Create: Flight Control 已加载，飞控方块未实机验收。
- 不要把 Fabric 26.3 存档复制给 1.21.1 打开；两边建筑与库存相互独立，没有转换器。
- 光影与 Veil／Flywheel 的特殊 GPU 效果未兼容。
- 实机结论来自开发机，不保证所有游戏构建、显卡或其他模组组合可用。

Fabric 客户端的功能、限制与安装流程同 [3.0 更新说明](RELEASE-3.0.0.md) 和 [安装教程](INSTALL.md)。NeoForge 客户端的构建、启动、切换与回退见 [NeoForge 说明](NEOFORGE.md)。
