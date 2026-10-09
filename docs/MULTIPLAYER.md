# 局域网联机（NeoForge 客户端）

EndCraft 4.1 起，NeoForge 1.21.1 客户端可以复用 Minecraft 自带的局域网联机。一台电脑运行终末地和桥接客户端作为**主机**，其他人作为**访客**加入同一个 MC 世界。访客有两种：

| 访客类型 | 需要运行 | 看到的内容 |
|---|---|---|
| 普通访客 | 只运行 NeoForge MC 客户端 | 主机附近的终末地地形（按材质着色的低多边形地面）、主机和其他玩家的 MC 角色与建筑 |
| 自带终末地的访客 | 自己的终末地 + 桥接客户端 | 自己终末地中的真实画面，叠加所有玩家的 MC 角色和建筑 |

主机在终末地中会看到所有访客的 MC 角色、放置的方块和机械动力装置。

## 准备

所有人使用**同一版本**的 EndCraft NeoForge 客户端 jar，以及相同的模组组合：NeoForge 21.1.247、Create 6.0.10、Create Aeronautics 1.3.2、Sable 2.0.6、Create: Flight Control 0.7.7（版本固定在 `mc-neoforge/modpack.lock.json`）。版本不一致时，加入会被拒绝，提示“客户端缺少此服务端需要的网络通道”。

## 主机：开放局域网

进入终末地游戏世界、桥接存档加载完成后，在终末地中按 T 打开 MC 聊天：

```text
/endcraft lan                        # 开放局域网，验证正版账号
/endcraft lan offline                # 不验证正版账号（连不上 Mojang 验证服务器时使用）
/endcraft lan offline cheats 25565   # 参数顺序任意：cheats 允许访客使用指令，数字为端口
```

聊天框显示端口和本机局域网地址。也可以在 `mc-neoforge/run/config/skycraft.properties` 中设置进入存档后自动开放：

```properties
lan=true
lan_port=25565
lan_online_mode=false
lan_cheats=false
```

## 普通访客：加入

在多人游戏中选择列表下方出现的局域网世界，或直接连接主机显示的地址。访客加入和重生时都会出现在主机玩家身边（桥接存档是虚空世界，地形跟随主机的终末地角色）。主机服务器把访客水平 96 格内的终末地地形碰撞和三角面分批发给访客，访客可以站在终末地地形上。

## 自带终末地的访客

两边的终末地坐标必须换算到同一套 MC 坐标，访客要使用主机的世界锚点：

1. 主机进入终末地游戏世界后执行：
   ```bash
   python tools/show-anchor.py
   ```
   输出主机锚点和访客要执行的命令。
2. 访客执行（替换为主机的锚点），然后重启终末地：
   ```bash
   python tools/configure-gameplay-startup.py --auto-start --module gameplay34 --anchor X Y Z
   ```
   改锚点会让访客自己存档中已有建筑相对终末地的位置整体平移。
3. 访客进入终末地游戏世界后，在 MC 聊天中执行：
   ```text
   /endcraft join             # 搜索并加入第一个局域网世界（5 秒内）
   /endcraft join <地址>      # 直接连接
   /endcraft leave            # 回到自己的桥接存档
   ```
   也可以在 `skycraft.properties` 写 `join=<地址>`，启动后自动加入。

自带终末地的访客使用自己终末地的地形，服务器不再向其发送主机地形；终末地内的快速传送会通知服务器，不会被判定为移动过快而拉回。

## 掉线与恢复

终末地仍在运行而 MC 崩溃或被关闭时，双击仓库根目录的 `restart-neoforge-mc.cmd`，MC 会以桥接模式重新启动，连回终末地并打开桥接存档。主机需重新执行 `/endcraft lan`（或已设置 `lan=true`）。

## 限制

- 地形只来自主机终末地角色周围已加载的区域；普通访客走远后没有地形。
- 终末地 NPC 在普通访客端不可见；自带终末地的访客各自面对自己终末地的 NPC，宿主战斗只作用于各自的终末地。
- 自带终末地的玩家需处于终末地的同一地区。
- 普通访客单机测试已实机验证（加入、出生在主机身边、在终末地中显示）；**两台电脑各自运行终末地的联机尚未实机验证**。
