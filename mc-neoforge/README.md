# EndCraft NeoForge 客户端（4.0）

Minecraft 1.21.1 / NeoForge 客户端桥接工程，版本 `4.0.0`。Fabric 26.3 工程仍保留在 `../mc/`。

包含 Create、机械动力航空学（Sable）与 Create: Flight Control 的适配，已通过构建、非游戏测试和首轮实机测试。航空学船体与终末地地形的物理联动仍在验收中。

构建、版本组合、切换方法和待验收项目见 [NeoForge 说明](../docs/NEOFORGE.md)。

在项目根目录执行以下命令只下载依赖、构建和运行非游戏测试：

```powershell
python tools/fetch-neoforge-mods.py
# 新环境先构建测试用的原生共享内存宿主：
.\tools\build-native.cmd
.\tools\build-neoforge.ps1
```

输出：`build/libs/endcraft-neoforge-guest-4.0.0.jar`。

源码沿用 EndCraft / SkyCraft 的 MIT 许可。Create、Aeronautics、Sable、Create: Flight Control 各有自己的许可；通过发布方下载并校验，第三方 JAR 不提交到本仓库。
