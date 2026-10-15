# 终末地 1.6.3 适配记录

2026-10-15，4.2.0 使用宿主模块 `endcraft.gameplay38`，共享内存协议保持 12。最初的 35 版适配结果保留在下方；最新渲染与输入修正见 [渲染说明](RENDERING.md)。旧 4.1.2 附件仍使用 `gameplay34`。

## 启动与安装

通过 EFML 的 xinput 代理加载。先从官方鹰角启动器进入游戏，EFML 再自动加载共享内存模块 `endcraft.probe` 和玩法模块 `endcraft.gameplay38`。终末地 1.6.3 直接运行 `Endfield.exe` 会提示必须使用官方启动器。下方保留最初 35 版适配的历史验证；后续渲染及输入修正见 [渲染说明](RENDERING.md)。

开发工具现在优先使用 `%EFML_HOME%/modules/index.json` 或 `%LOCALAPPDATA%/EFML/modules/index.json`，通过 EFML CLI 注册、启用和配置模块。没有 EFML 时保留旧 Better-Endfield 流程。旧项目目录和 Better-Endfield 用户目录的索引不代表当前 EFML 实际加载的模块。

```powershell
python tools/audit-endfield-compat.py
python tools/runtime-report.py host-status
# 新身份首次注册；已注册时拒绝覆盖可能仍在加载的 DLL
python tools/register-inspector.py gameplay38
python tools/configure-gameplay-startup.py --auto-start --module gameplay38
```

只启用一个玩法模块，`endcraft.probe` 必须同时启用。开发配置保留当前实际世界锚点；新版本不强制切换到 Fabric 客户端。MC 客户端仍需单独启动。

## 已验证

- 已安装 1.6.3 的 `GameAssembly.dll` SHA-256 为 `ab00f1c41e13edf9816f142b166e3c2536d5bc7707992c39e5ed52d1cc888e2c`；元数据为 `461321d9dca6a832bce3bd2390402efc7c83c145554e97a8058bb68fbec1c8ca`。
- 必需程序集与关键元数据名称检查通过。名称存在仅用于预检查，不能单独证明运行兼容。
- EFML 实机加载 `gameplay35` 成功，原 `gameplay34` 已停用；角色、输入、受击、传送和渲染请求方法签名解析及钩子注册成功。
- 实机元数据显示 `RenderRequest.hgCamera` 偏移仍为 16；模块启动检查确认 `ScriptableRenderContext` 值大小仍为 8。
- 进入可操控场景后，NeoForge 1.21.1 自动进入桥接存档，角色同步、HUD、地形碰撞数据、原生人物隐藏、MC 人物网格和实际绘制均运行；用户确认成功。截帧已看到 MC 人物及快捷栏。
- 原生完整构建、输入与模型 F6 检查、网格分层、透明裁剪及 NeoForge 构建通过；EFML 路径选择与安装共存的 12 项 Python 检查通过。

本次未完整复测多人、航空学物理船、战斗、所有快捷键及光影包，不能将基础桥接通过等同于这些功能均已验收。

本机诊断证据保存在忽略目录 `reports/`：`endfield-compat.json`、`host-efml-163.json`、`gameplay35-163.json` 和各类的 `*-163-metadata.json`。
