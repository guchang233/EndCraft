# 第 21 版：指令与死亡恢复

本机真实终末地运行中热加载 `endcraft.gameplay21`，仅重启项目 Minecraft 客户端。24 项 Java 测试及原生模块生命周期、相机、alpha 网格检查通过。

- 补齐 `/`、T 聊天入口、ASCII 文本、编辑键与修饰键；项目自身集成镜像世界授予命令权限。游戏日志确认 `/gamemode survival`、adventure、creative 执行。
- `/kill @s` 复现旧问题：MC 重生切回第一人称，模型顶点数为 0；第三人称恢复后顶点数为 1128。
- 修复后再次通过相同聊天输入通路执行 `/kill @s`，重生生命值 20，第三人称模式为 1、模型顶点数为 864，桥接 ready 为 true，渲染错误为空。
- `/endcraft recover` 返回游戏内反馈，重新同步到宿主当前角色脚部。原生模块提供 Ctrl+Alt+R 快捷键；该快捷键实现已加载，物理键盘组合尚未单独验收。
- 宿主角色对象变化保持原世界锚点，并将 MC 同步至新角色的实际位置。此修复不代表跨地图原生传送已验收。

本地证据保存在 `reports/respawn-camera-fixed21.json`、`reports/camera-restored21.json` 与 `mc/run/endcraft-command-check.txt`、`mc/run/logs/latest.log`；含账号场景数据，不提交。

恢复方法：只剩 UI 时按 F5 切回第三人称；仍异常则关闭终末地原生菜单，按 Ctrl+Alt+R 或输入 `/endcraft recover`。终末地原角色死亡需要先正常复活或切换存活角色。中文输入法组合、原生菜单按键隔离与跨场景恢复仍待完善。
