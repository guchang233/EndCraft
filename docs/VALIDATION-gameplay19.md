# 第 19 版菜单遮罩修复

已在真实终末地热加载，并重启项目 MC 实例以应用 Screen Mixin；终末地未重启。下次自动启动已切换至第 19 版，发布的 GitHub 1.0 附件保持原样。

- MC 透明菜单背景由原版 75%–82% 渐变改为均匀 20%（alpha=51/255），只在桥接连接时应用。
- 消除宿主 Graphics.DrawTexture 与 Canvas RawImage 同时绘制 HUD 的重复混合。正常使用即时绘制，Canvas 保留为回调超过 250 ms 未绘制时的备用。
- 实际库存两次采样：背景像素 alpha 从 192/200 降到 51/51；面板像素 alpha 仍为 255。1920×1080 实机前后截图确认场景更清楚，面板和文字正常。测试后关闭了测试打开的库存。
- MC 构建与 24 项 Java 测试通过；原生 alpha、相机、模块 ABI/生命周期检查通过。

本地证据：reports/ui-alpha-before.json、reports/ui-alpha-after.json、reports/ui-before.png、reports/gameplay19-frame.png。截图含账号画面，未提交。

当前能力和剩余限制沿用第 18 版：移动、方块建造/破坏、HUD、场景网格和受控飞行已运行；实际攻击扣血尚未验收，弓/TNT独立伤害与敌人伤害回传未完成，复杂地形与 NPC 寻路仍有限制。
