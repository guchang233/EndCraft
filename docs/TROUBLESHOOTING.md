# 故障排查与恢复

先确认仅有一个 EndCraft 玩法模块和一个项目 MC 进程。不要用删存档、覆盖加载中的 DLL 或重复启动多个客户端来尝试恢复。

## 常见现象

| 现象 | 先检查 | 处理 |
|---|---|---|
| 无 MC 物品栏 | 是否已进入可移动场景；MC 启动日志是否仍在下载／加载 | 等待首次加载，检查 Host 和玩法状态 |
| 只有 UI，没有史蒂夫 | 是否第一人称、原生角色是否死亡 | F5；关闭原生菜单；`/endcraft recover`；原角色死亡时先正常复活 |
| 原生传送后异常 | 是否仍在加载或菜单、MC 是否重新确认位置 | 关闭菜单后等待，再执行恢复；不要反复远距离传送 |
| 空旷地面／坡面卡住 | 新地形采样是否就绪；是否在多层结构 | 松开移动、恢复位置；换单层开阔区域测试，记录场景与诊断 |
| 玻璃不透明、水静止 | 是否仍加载旧玩法模块或旧 MC 源码 | 确认 `endcraft.gameplay33`；正常重启项目 MC，再检查渲染错误与动画计数 |
| 两款游戏同时响应按键 | 分号当前状态、MC 库存是否打开 | 这是 3.0 已知输入限制；先收起库存、松开按键，避免同时操作 |
| 创造模式仍显示旧损伤 | 模式是否切成功、当前原生角色是否更换 | 免伤不补满旧血量；看后续受击是否继续掉血及 protection_error |
| 鞘翅不展开 | 胸甲栏是否穿戴、是否已经离地 | 空中再按空格；先在高处开阔区域测试 |
| NPC 不上船 | 类型／体型、距离、座位是否空闲 | 换普通体型、空座位及空旷位置；不是所有 NPC 都支持 |
| TNT 没炸开原生岩石 | 是否只有 MC 方块／敌人效果、是否已取得原生炸弹样本 | 原生岩石接口未完成验收，不属于 3.0 保证功能 |

## 读取诊断

在安装工作目录运行。RPC 凭据由工具自动读取，**不要打印或分享完整索引文件**。

```powershell
python tools/runtime-report.py host-status --output reports/host-status.json
python tools/runtime-report.py status --module endcraft.gameplay33 --output reports/gameplay33-status.json
```

| 字段 | 含义 |
|---|---|
| `gameplay.active` | 桥接正在运行；本身不能证明画面正确 |
| `guest_ready` | MC 已进入世界并确认本轮位置同步 |
| `host_menu`、`host_teleporting` | 原生菜单或传送正在暂停桥接 |
| `error`、`render_error`、`pipeline_error` | 控制、消息渲染、绘制阶段错误 |
| `avatar_vertices`、`visible_meshes` | 模型是否上传及被宿主判为可见 |
| `origin_raw_units` | 这个 MC 世界对应的宿主坐标锚点 |
| `transparency.layer_meshes` | 分离的透明／流体渲染网格数 |
| `transparency.atlas_regions`、`atlas_uploads` | 纹理动画接收及应用计数 |
| `combat.hurt_reactions` | 原生受击请求、调用和状态变化 |
| `combat.native_explosions` | 原生炸弹样本与可命中物转交状态 |

累计计数非零不代表当前帧正常；比较两次报告时看是否增长，同时检查错误和实际画面。

常用日志：

- `reports/guest-startup.log`：项目 MC 的启动、依赖与资源加载。
- `mc/run/logs/latest.log`：MC 当前会话。
- 游戏目录 `EndCraft-loader.log`：加载器定位 Host 的结果。
- Host 的用户配置／日志目录：`%LOCALAPPDATA%/BetterEndfield`。

提交故障时说明终末地版本／场景、MC 模式、发生前的动作及相关错误字段。只分享必要的诊断片段；先去掉凭据、UID 和个人路径。

## 脚本报“现有文件已保留”

安装工具记录自有文件的路径和 SHA256。文件已被用户或其他程序修改时，工具停止覆盖或删除。先核对文件是谁创建的，再处理冲突；不要删除安装清单来让脚本强行接管现有文件。

模块包存在通常表示已经注册；Windows 会保持已加载 DLL 的映像。正常关闭游戏后再处理更新，或者使用新的模块身份执行受控升级。

## 自动 MC 启动失败

确认项目目录未移动、JDK 25 路径存在，并含 `javac.exe`。可手动启动并看完整输出：

```powershell
.\tools\start-guest.ps1 -JavaHome 'C:\你的路径\jdk-25'
```

默认 JDK 路径不适用时，按 [安装教程](INSTALL.md) 设置 `ENDCRAFT_JAVA_HOME` 并重新打开原启动器。脚本只复用该项目自己的 MC；发现另一个带 EndCraft/SkyCraft 隐藏启动标志的开发客户端时会停止，以免重复写共享内存。

## 停用与回退

准备下一次启动不自动开启桥接：

```powershell
python tools/configure-gameplay-startup.py --manual --module gameplay33
```

该命令不立即停用当前桥接。当前操作异常时正常退出游戏，再按手动模式启动。

回退到 2.0 需要保持宿主模块与 MC 版本配套，并只启用一个玩法模块。先备份存档和锚点，不覆盖加载中的模块包。新版本产生的建筑仍是存档数据；回退不会自动删除它们。

## 什么不能靠恢复键解决

恢复键主要解决位置／角色同步；它不会实现多层地形网格、修复所有 NPC 寻路、添加光影兼容或完成原生岩石破坏。若在同一地形反复卡住，应记录复现场景并停止在该处继续建造测试。
