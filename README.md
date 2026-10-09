# EndCraft

**4.1.0 · Windows x64 · 实验性项目。** 在终末地的真实场景中使用 Minecraft 的角色、物品栏、方块、战斗、机械动力和航空学，支持 MC 局域网联机。两款游戏同时运行：MC 计算玩法，终末地显示场景。

## 安装 4.1

普通玩家使用发行附件即可，无需源码、Visual Studio 或 Python。

1. 安装终末地并确认能正常进入游戏，再安装 [Better-Endfield 3.5.4 正式版](https://github.com/Dr-hydra/Better-Endfield/releases/tag/v3.5.4)，在管理器中设置游戏路径。
2. 从 [EndCraft 4.1 发行页](https://github.com/guchang233/EndCraft/releases/tag/v4.1.0) 下载：
   - `EndCraft-gameplay34-4.1.0-win-x64.zip`：终末地宿主模块。
   - `endcraft-neoforge-guest-4.1.0.jar`：MC 模组。
3. 打开 Better-Endfield 的 **「第三方模块」→「导入」**，直接选择上述 ZIP，然后**启用** EndCraft。首次导入默认配置已包含 `auto_enable: true`。
4. 用 PCL2 或 HMCL 新建独立的 **MC 1.21.1 + NeoForge 21.1.247** 实例，使用 **Java 25**，分配约 **4 GB 内存**。
5. 将上述 MC JAR 和以下依赖放入该实例的 `mods` 文件夹：[Create 6.0.10](https://modrinth.com/mod/LNytGWDc/version/UjX6dr61)、[Aeronautics 1.3.2 bundled](https://modrinth.com/mod/oWaK0Q19/version/44pLdPGg)、[Sable 2.0.6](https://modrinth.com/mod/T9PomCSv/version/fg9dTRz9)、[Create: Flight Control 0.7.7](https://modrinth.com/mod/IGVrnkhH/version/act82pgK)。在实例 JVM 参数末尾追加：

   ```text
   --enable-native-access=ALL-UNNAMED -Dendcraft.allowHostConnection=true
   ```

6. **先通过 Better-Endfield 启动终末地，进入可移动场景；再启动 MC，停在标题界面等待自动连接。** 切回终末地，看到 MC 物品栏即可。

ZIP 导入管理器，JAR 放入 MC 的 `mods`，两者不要混用。源码安装的老用户保留原加载器，按 [源码安装与升级说明](docs/INSTALL.md) 维护；只启用一个 EndCraft 玩法模块。

## 常用操作

- **WASD / 空格**：移动、跳跃；**左键 / 右键**：攻击、破坏、放置、交互。
- **E / F5 / T**：库存、切换视角、聊天与指令。
- `/gamemode creative`：创造模式；`/endcraft recover` 或 **Ctrl+Alt+R**：恢复角色同步。
- `/endcraft lan offline`：开放局域网；朋友使用相同模组加入。详见 [联机教程](docs/MULTIPLAYER.md)。

## 限制与文档

光影尚未兼容；复杂地形、航空学起降、NPC 登船与 TNT 炸原生岩石仍有未验收项。两台电脑各自运行终末地的联机尚未实机验证。Fabric 26.3 客户端仍保留，存档与 NeoForge 1.21.1 不兼容。

[4.1 更新说明](docs/RELEASE-4.1.0.md) · [操作教程](docs/USER-GUIDE.md) · [故障排查](docs/TROUBLESHOOTING.md) · [NeoForge 与源码构建](docs/NEOFORGE.md) · [实现原理](docs/ARCHITECTURE.md)

## 许可

宿主基于 [Better-Endfield](https://github.com/Dr-hydra/Better-Endfield)，使用 AGPL-3.0；MC 端改编自 [SkyCraft](https://github.com/chasmlol/SkyCraft)，使用 MIT。第三方模组遵循各自许可，见 [许可](LICENSE) 与 [第三方声明](THIRD-PARTY-NOTICES.md)。
