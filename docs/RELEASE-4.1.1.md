# EndCraft v4.1.1

4.1.1 修正 4.1.0 发行附件的打包问题，功能与 4.1.0 相同。**只用 4.1.0 附件无法连接，请改用本版本附件。**

## 修复

| 现象 | 原因与修复 |
|---|---|
| 导入模块提示“模块声明缺少有效的 author” | 玩法模块的 `module.json` 缺少 Better-Endfield 3.5.4 要求的 `author`；已补上，默认配置含 `auto_enable: true` |
| MC 一直连不上终末地，日志显示 “Endfield has not created it yet” | 共享内存由 `endcraft.probe` 模块创建，玩法模块只负责打开；4.1.0 只发布了玩法模块。现新增附件 `EndCraft-probe-4.1.1-win-x64.zip` |
| 终末地闪退或被强制结束后，隐藏的 MC 一直留在后台 | “随终末地退出”默认关闭，且连接一断就丢了终末地进程号，退出检查永远不触发。现默认开启：记住所连终末地，进程消失 5 秒后（或连接中断超过 2 分钟）MC 正常保存并退出；`-Dskycraft.quitWithSkyrim=false` 可保留旧行为 |
| 模块包内含大量文档 | 模块 ZIP 只保留 DLL、`module.json`、README 与许可文件 |

## 安装

1. Better-Endfield 必须为 **3.5.4**；更新的版本关闭了「第三方模块」入口。
2. 在「第三方模块」中分别导入并启用 `EndCraft-gameplay34-4.1.1-win-x64.zip` 与 `EndCraft-probe-4.1.1-win-x64.zip`，重启终末地。
3. Minecraft 需自行安装：MC 1.21.1 + NeoForge 21.1.247 或更新、Java 25，**游戏内存手动设为 4 GB**（启动器自动分配可能只给 512 MB，MC 会内存溢出后消失）。`endcraft-neoforge-guest-4.1.1.jar` 放入 `mods`；机械动力与航空学为可选。
4. MC 连上终末地后窗口会自动隐藏，约 1–2 分钟后终末地中出现 MC 物品栏。

完整说明见 [README](../README.md)，4.1 功能见 [4.1 更新说明](RELEASE-4.1.0.md)。
