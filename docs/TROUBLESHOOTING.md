# 故障排查与恢复（4.2.1）

当前启动流程见 [README](../README.md)：**官方鹰角启动器启动终末地，EFML xinput 代理加载 probe 与 gameplay39，再启动 MC。** 排错时保留现有存档，只运行一个 MC 桥接客户端和一个玩法模块。

## 启动与连接

| 现象 | 检查与处理 |
|---|---|
| “请通过官方启动器打开游戏” | 关闭提示，回到官方鹰角启动器开始游戏；不要直接运行 Endfield.exe，也不要沿用第三方直接启动／直接注入流程 |
| EFML 的旧启动按钮仍报上述提示 | 先确认 xinput 代理已安装，再直接从官方鹰角启动器开始游戏；该按钮不是必需步骤 |
| EFML 提示已有未知 xinput1_4.dll | 用原安装工具卸载旧代理，再安装 EFML 代理；不要覆盖来源不明的文件 |
| MC 一直等待 Endfield 创建共享内存 | 检查 EFML 中 probe 与 gameplay39 是否都启用、宿主是否加载成功；终末地应处于可移动场景 |
| MC 在终末地进菜单后暂时停住 | 收起原生菜单、等待加载完成，再检查桥接状态；不要重复启动 MC |
| MC 窗口隐藏 | 连接后自动隐藏是正常现象；切回终末地查看 MC HUD |
| MC 窗口消失，终末地也无 HUD | 查看 MC 日志是否崩溃或内存不足；将 MC 内存手动设为 4 GB，检查 Java 25 和 JVM 参数 |
| 提示已有另一个 MC 连接 | 正常退出多余的桥接客户端，再启动一个实例 |
| MC 回到标题或模组列表后不自动进世界 | 正常退出并重新启动同一个 MC 实例；保留存档 |
| 模块导入失败 | 使用 4.2.1 的两个模块 ZIP，源码 tar.gz 和 MC JAR 不导入 EFML |

## 画面、控制与战斗

| 现象 | 检查与处理 |
|---|---|
| 有 HUD 没有人物 | 检查是否第一人称、角色是否死亡；按 F5，收起原生菜单，必要时执行 `/endcraft recover` |
| 方块／颜色仍表现为旧版本 | 核对实际加载的 gameplay39 与 MC JAR，正常重启后检查 `render_error`、`pipeline_error` |
| MC 与终末地同时响应 | 分号关闭独占时可能同时响应；收起 MC 菜单、松开按键后切换独占 |
| Z 配件界面偏暗或按钮穿透 | 确认加载的是当前宿主和包含 4.2 渲染修正的 MC JAR；提供复现时的界面状态和错误字段 |
| 换算伤害异常 | 检查 `combat.damage_model` 是否为 `player_attack`、倍率是否为 1，再记录 `last_mc_damage`、`last_player_attack`、`last_host_damage` 与 `combat.error` |
| 创造模式仍有旧损伤 | 免伤不会补满此前损失的原生血量；看后续受击是否继续扣血 |
| 新地形或多层结构卡住 | 松开移动并执行恢复，先换开阔区域验证；复杂地形不能仅靠恢复键解决 |
| 女仆穿地、NPC 不上船、船体异常 | 这些场景尚未完整验收；保存并记录场景、位置与动作，不将短程通过等同于全面兼容 |
| YSM 阻断 MC 启动 | 当前开发组合因 err:54 停用 YSM；不要按旧教程直接恢复它来解决模型问题 |
| 枪包不显示 | 正式 MC 实例使用其游戏目录下的 `tacz/`；项目开发实例为 `mc-neoforge/run/tacz/`。按枪械模组规则重载 |

## 日志与状态

正式实例查看**该实例游戏目录**下的 `logs/latest.log`。开发 NeoForge 查看 `mc-neoforge/run/logs/latest.log`；Fabric 为 `mc/run/logs/latest.log`。EFML 日志和设置默认在 `%LOCALAPPDATA%/EFML`，设置过 `EFML_HOME` 时使用该目录。

有源码工具时，可在仓库根目录读取状态：

```powershell
python tools/runtime-report.py host-status --output reports/host-status.json
python tools/runtime-report.py status --module endcraft.gameplay39 --output reports/gameplay39-status.json
```

`guest_ready` 表示 MC 已确认本轮同步；`host_menu` 表示原生菜单状态；`error`、`render_error`、`pipeline_error` 分别记录控制、渲染消息和绘制错误。计数曾经增加不代表当前帧正常，应比较前后报告并核对实际画面。

不要分享完整模块索引或 RPC 凭据。报告问题时提供版本、MC 实例、复现动作和必要的日志片段，去掉 UID 与个人路径。

## 开发实例恢复

已进入终末地可移动场景、且没有另一个桥接 MC 时：

```powershell
.\tools\start-neoforge.ps1 -BridgeHost -JavaHome 'C:\你的路径\jdk-25'
```

这是启动项目的 NeoForge 开发客户端。PCL2、HMCL 实例应从自己的 MC 启动器启动；不要误用默认选择 Fabric 的通用旧脚本来替代它。位置恢复指令不会升级模组、补齐光影功能或修复全部地形与 NPC 行为。
