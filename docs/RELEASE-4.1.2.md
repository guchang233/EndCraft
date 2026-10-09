# EndCraft v4.1.2

4.1.2 修正 4.1.0 发行附件的打包问题，并改进安装与退出体验；玩法功能与 4.1.0 相同。**只用 4.1.0 附件无法连接，请改用本版本附件。**（4.1.1 未发布，内容并入本版本。）

## 修复

| 现象 | 原因与修复 |
|---|---|
| 导入模块提示“模块声明缺少有效的 author” | 玩法模块的 `module.json` 缺少 Better-Endfield 3.5.4 要求的 `author`；已补上，默认配置含 `auto_enable: true` |
| MC 一直连不上终末地，日志显示 “Endfield has not created it yet” | 共享内存由 `endcraft.probe` 模块创建，玩法模块只负责打开；4.1.0 只发布了玩法模块。现新增附件 `EndCraft-probe-4.1.2-win-x64.zip`，玩法模块声明依赖它，漏装时 Better-Endfield 会直接提示 |
| 终末地闪退或被强制结束后，隐藏的 MC 一直留在后台 | “随终末地退出”默认关闭，且连接一断就丢了终末地进程号，退出检查永远不触发。现默认开启：记住所连终末地，进程消失 5 秒后（或连接中断超过 2 分钟）MC 正常保存并退出；`-Dskycraft.quitWithSkyrim=false` 可保留旧行为 |
| 同时开两个 MC 时两者抢同一个角色 | 第二个连接桥接的 MC 不再连接，并在 MC 中提示关闭其中一个 |

## 改进

| 内容 | 说明 |
|---|---|
| 内存不足提示 | MC 可用内存低于约 3 GB 时，连接后在聊天栏提示把游戏内存设为 4096 MB（启动器自动分配可能只给 512 MB，MC 会在加载中内存溢出） |
| 模块包精简 | 模块 ZIP 只保留 DLL、`module.json`、README 与许可文件 |
| 源码包 | 改为 `EndCraft-4.1.2-source.tar.gz`，不会再被误选为模块 ZIP |
| 构建 | CI 升级到 Node 24 版本的 GitHub Actions |

## 安装

1. Better-Endfield 必须为 **3.5.4**；更新的版本关闭了「第三方模块」入口。
2. 在「第三方模块」中分别导入并启用 `EndCraft-gameplay34-4.1.2-win-x64.zip` 与 `EndCraft-probe-4.1.2-win-x64.zip`，重启终末地。
3. Minecraft 需自行安装：MC 1.21.1 + NeoForge 21.1.247 或更新、Java 25，**游戏内存手动设为 4 GB**。`endcraft-neoforge-guest-4.1.2.jar` 放入 `mods`；机械动力与航空学为可选。
4. MC 连上终末地后窗口会自动隐藏，约 1–2 分钟后终末地中出现 MC 物品栏；关闭终末地后 MC 自动保存退出。

完整说明见 [README](../README.md)，4.1 功能见 [4.1 更新说明](RELEASE-4.1.0.md)。
