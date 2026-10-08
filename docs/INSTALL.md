# 安装、构建与升级

适用版本：EndCraft 3.0.0，宿主模块 `endcraft.gameplay33`。以下流程针对本项目自己的加载器与 Host；已有其他加载器或 Better-Endfield 安装时，先确认归属和配置，不直接覆盖同名文件。

## 准备环境

| 项目 | 要求 |
|---|---|
| 系统 | Windows x64 |
| 游戏 | 已安装的终末地客户端，能通过原启动器进入可移动场景 |
| C++ | Visual Studio，安装“使用 C++ 的桌面开发”和 Windows SDK；脚本通过 vswhere 定位工具链 |
| Java | JDK 25，目录内须有 `bin/javac.exe`，只有 Java 运行环境不够 |
| Python | Python 3；命令示例使用 `python`，可替换为解释器绝对路径 |
| Git | Git for Windows；使用仓库下载方式时需要 |
| 网络 | 首次构建 MC 需要下载 Gradle、Fabric 和 Minecraft 依赖与资源 |

两款游戏同时运行，显存、内存和帧时间需以自己的机器实测。不要将此项目覆盖到已有 MC 实例；项目世界位于 `mc/run`。

## 取得 3.0 源码

选择一种方式。

**使用 Git：**

```powershell
git clone --recurse-submodules https://github.com/guchang233/EndCraft.git
cd EndCraft
git checkout v3.0.0
git submodule update --init --recursive
```

**使用发行附件：** 从 [3.0 发行页](https://github.com/guchang233/EndCraft/releases/tag/v3.0.0) 下载 `EndCraft-3.0.0-source.zip`，解压后进入包含 `VERSION`、`tools`、`mc` 的目录。此附件含固定提交的上游源码。GitHub 自动生成的 Source code 压缩包不含子模块内容，应优先使用项目上传的完整源码附件。

核对附件时，可用：

```powershell
Get-FileHash .\EndCraft-3.0.0-source.zip -Algorithm SHA256
```

将结果与同一发行页的 `SHA256SUMS.txt` 对照。

后续命令均在项目根目录运行。安装后保留该目录的位置：Host 索引和启动配置使用它的绝对路径；直接搬走目录会使启动失效。

## 构建

在 PowerShell 中，将 JDK 路径替换为自己的安装位置：

```powershell
$taskJavaHome = 'C:\你的路径\jdk-25'
& "$taskJavaHome\bin\javac.exe" -version

.\tools\build-native.cmd
# 上一条成功后再继续，失败则先看编译输出。
.\tools\build-framework.cmd
.\tools\build-mc.ps1 -JavaHome $taskJavaHome
python tools/package.py --staging-only
```

各步成功后应看到：

| 位置 | 内容 |
|---|---|
| `build/native/endcraft.gameplay33.dll` | 宿主玩法模块 |
| `build/framework/BetterEndfield.Host.dll` | 配套 Host |
| `build/framework/xinput1_4.dll` | 加载器 |
| `build/distribution-staging/` | 初始诊断模块包 |
| `mc/build/libs/endcraft-guest-3.0.0.jar` | MC 模组 JAR |

首轮完整下载成功后，可用 `-Offline` 重建 MC。不要在资源缓存不完整时强行离线启动。

发行页中的模块 ZIP 和 JAR 是预构建组件，不能替代配套 Host 和开发启动环境；当前教程使用源码实例完成端到端启动。

## 首次安装

1. 正常关闭终末地。确认目录包含 `Endfield.exe`。
2. 由于本教程使用暂存包，将初始诊断包复制到安装脚本预期位置。首次安装时，`build/package` 应尚不存在：

```powershell
Copy-Item -LiteralPath .\build\distribution-staging -Destination .\build\package -Recurse
python tools/install-probe.py install --game-dir 'D:\Arknights Endfield'
```

3. 注册玩法模块，配置下次启动自动运行：

```powershell
python tools/register-inspector.py gameplay33
python tools/configure-gameplay-startup.py --auto-start --module gameplay33
```

每个模块身份只注册一次。如果脚本报告“已安装”“包已存在”或“现有文件已保留”，不要反复覆盖，按升级或排错章节处理。

4. 自动启动默认使用 Minecraft 启动器的 `.minecraft/runtime/java-runtime-epsilon` JDK。若你的 JDK 位于其他目录，将以下用户环境变量设为实际 JDK 25 路径，然后重新打开游戏启动器，使其继承新值：

```powershell
[Environment]::SetEnvironmentVariable('ENDCRAFT_JAVA_HOME', $taskJavaHome, 'User')
```

也可先手动启动 MC，明确传入 JDK：

```powershell
.\tools\start-guest.ps1 -JavaHome $taskJavaHome
```

此命令会持续运行；保持 PowerShell 窗口开启。自动启动同样需要项目源码目录和工具链。

5. 通过原启动器打开终末地，登录并进入可以移动的场景，等待 MC 九格物品栏出现。首次 MC 资源加载可能较慢，查看 `reports/guest-startup.log` 的进度。

6. 按 F5 检查角色，放少量方块，再测试玻璃、水与移动。异常时先停止扩大测试范围，参考 [故障排查](TROUBLESHOOTING.md)。

## 从本项目 2.0 升级

适用于保存着 `.tools/install-manifest.json` 的原安装工作目录。保留该目录、`.tools` 和 `mc/run/saves`；重新克隆到另一目录不会自动接管旧安装。

### 1. 保存旧锚点并备份

游戏仍在场景时，在 2.0 的源码目录读取旧模块状态：

```powershell
python tools/runtime-report.py status --module endcraft.gameplay27 --output reports/before-upgrade.json
```

如果实际上已经加载其他开发模块，将 `endcraft.gameplay27` 替换为真实模块 ID。正常关闭终末地，并正常退出项目 MC；两个进程都退出后，复制 `mc/run/saves` 到单独的备份目录。不要让两个 MC 同时打开同一存档。

保留 `reports/before-upgrade.json`，其中 `body.gameplay.origin_raw_units` 是已有建筑对应的坐标锚点。

### 2. 更新并构建

在原工作目录取得 `v3.0.0` 源码；有自己的修改时先保存它们，不使用强制重置或清空工作目录。Git 检出标签不会替你合并本地修改。

```powershell
git fetch origin --tags
git checkout v3.0.0
git submodule update --init --recursive

$taskJavaHome = 'C:\你的路径\jdk-25'
.\tools\build-native.cmd
.\tools\build-framework.cmd
.\tools\build-mc.ps1 -JavaHome $taskJavaHome
python tools/package.py --staging-only
python tools/install-probe.py update
```

使用暂存打包，不改已经注册的旧模块包。加载器／Host 更新需要游戏已正常关闭。

### 3. 切换玩法模块

先启动终末地进入场景，让原安装 Host 的 RPC 可用，然后运行：

```powershell
python tools/activate-gameplay2.py --old gameplay27 --new gameplay33
python tools/configure-gameplay-startup.py --auto-start --module gameplay33 --anchor-report reports/before-upgrade.json
```

`--old` 也须匹配实际旧模块。切换脚本先停用旧桥接并确认恢复原生角色，再注册新包；它保留旧 DLL。新模块不要先发无锚点的 enable：首次初始化后锚点不可修改。

完成配置后，正常退出并重新启动终末地，使新模块从保存的锚点自动初始化。确认史蒂夫、原建筑和物品栏位置正确。

如果 `gameplay33` 已注册或已加载，这不是首次 2.0 升级流程；不要重复注册或覆盖已加载 DLL。保留旧包，参考排错或使用下次独立模块身份的升级流程。

## 启动控制

```powershell
# 下次启动自动运行：
python tools/configure-gameplay-startup.py --auto-start --module gameplay33
# 下次启动仅加载模块，不自动开启玩法：
python tools/configure-gameplay-startup.py --manual --module gameplay33
```

配置影响下次启动，不会立即改变当前玩法。参数未指定新锚点时，已有配置的锚点保持不变。

## 卸载

正常关闭两款游戏后：

```powershell
python tools/install-probe.py uninstall
```

卸载删除安装记录内仍匹配哈希的自有加载器、Host 和配置。用户改过的文件会被保留，脚本会说明原因。源码、模块构建包、MC 存档和日志不会随之清空；需要保留存档时不要删除 `mc/run/saves`。

## 构建发行附件

在干净、已提交的源码工作目录中：

```powershell
python tools/package-release.py --module endcraft.gameplay33
```

输出 `dist/release-3.0.0`：宿主模块 ZIP、版本化 MC JAR、完整源码 ZIP 及 SHA256 校验文件。此命令不推送 GitHub，也不修改本机已安装模块。
