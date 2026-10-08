# EndCraft NeoForge 实验版

独立的 Minecraft 1.21.1 / NeoForge 客户端桥接工程，版本 `3.1.0-neoforge-experimental`。现有 Fabric 26.3 工程仍保留在 `../mc/`。

已完成源码移植、Create 普通渲染器捕获及 Sable 移动船体网格导出，已通过构建与非游戏测试。**尚未启动游戏验收，航空学完整联动尚未确认。**

构建、版本组合、切换方法和待验收项目见 [NeoForge 说明](../docs/NEOFORGE.md)。

在项目根目录执行以下命令只下载依赖、构建和运行非游戏测试：

```powershell
python tools/fetch-neoforge-mods.py
# 新环境先构建测试用的原生共享内存宿主：
.\tools\build-native.cmd
.\tools\build-neoforge.ps1
```

输出：`build/libs/endcraft-neoforge-guest-3.1.0-neoforge-experimental.jar`。

源码沿用 EndCraft / SkyCraft 的 MIT 许可。Create、Aeronautics、Sable 各有自己的许可；通过发布方下载并校验，第三方 JAR 不提交到本仓库。
