# Java AirPlay 投屏接收器

如果这个项目对你有帮助，欢迎在 GitHub 上给仓库点一个 Star。你的支持可以帮助更多开发者发现项目，也会鼓励持续改进。

[![GitHub Stars](https://img.shields.io/github/stars/Arc-Lira/java-airplay?style=flat-square)](https://github.com/Arc-Lira/java-airplay/stargazers)
[![Java 25](https://img.shields.io/badge/Java-25-blue?style=flat-square)](https://jdk.java.net/25/)
[![Platform](https://img.shields.io/badge/platform-Windows%20x64-0078D6?style=flat-square)](https://github.com/Arc-Lira/java-airplay)
[![License](https://img.shields.io/badge/license-MIT-green?style=flat-square)](LICENSE)

[English](README.md) · [Issues](https://github.com/Arc-Lira/java-airplay/issues) · [Releases](https://github.com/Arc-Lira/java-airplay/releases)

这是一个基于 Java 25 的 Windows 桌面 AirPlay 接收器，可通过局域网接收 iPhone/iPad 屏幕镜像。项目支持 H.264 视频和 ALAC/AAC-ELD 音频，并通过 GStreamer 或 FFmpeg 视频后端提供可选的实验性 HEVC 支持。

## 核心能力

| 能力 | 说明 |
|---|---|
| 屏幕镜像 | 通过传统 AirPlay 传输接收 iPhone/iPad 屏幕镜像 |
| 音视频接收 | H.264 视频与 ALAC/AAC-ELD 音频 |
| 实验性 HEVC | 通过 GStreamer 或 FFmpeg 视频后端提供可选 H.265 支持 |
| 硬件解码 | 自动选择或手动选择 Windows DXGI GPU 适配器 |
| 稳定播放 | 默认保留编码参考帧，并在拥塞时使用 TCP 背压 |
| 自适应显示 | 自动检测实际分辨率、帧率和编码格式，支持竖屏视频 |
| 桌面体验 | 中英文界面、独立视频窗口、全屏、系统托盘、主题，以及投屏期间保持显示器常亮 |

## 快速开始

### 使用发行包

Windows x64 发行版同时提供标准安装程序和便携 ZIP。两者均内置精简版 Java 25 runtime、GStreamer、配置文件和文档，无需另外安装 Java 或 GStreamer。

1. 运行安装 EXE，或解压便携 ZIP。
2. 从开始菜单启动“Java AirPlay Receiver”，或在便携目录运行 `JavaAirPlayReceiver.exe`。
3. 在 iPhone/iPad 上打开控制中心。
4. 选择“屏幕镜像”，然后选择程序中显示的接收器名称。

发行版二进制文件尚未签名，因此 Windows SmartScreen 可能会要求确认。两种发行形式均包含 `start.bat`，可作为诊断备用启动方式。

### 从源码运行

从源码构建需要 Windows、JDK 25，以及用于下载 Gradle 依赖的网络连接。启动脚本可以准备项目内的 GStreamer runtime。

```powershell
./gradlew.bat test
./gradlew.bat :player:app:bootJar
start.bat
```

可执行 JAR 生成位置：

```text
player/app/build/libs/java-airplay-server-1.0.10.jar
```

## 桌面界面

集成式 GStreamer 窗口提供接收器状态、视频信息和完整设置。

- 在“设置 → 语言”或系统托盘中切换中英文界面。
- 将视频 Canvas 分离到独立窗口，并使用 `ESC` 切换或退出全屏。
- 通过系统托盘中的“打开 Java AirPlay”“显示视频窗口”“全屏”“语言”和“退出”操作访问程序。
- 按真实名称选择已检测到的 Windows DXGI GPU，同时保存对应的原生适配器索引。
- 使用“保存并重启”验证并应用网络和播放设置。

## 配置

设置文件位于：

```text
${user.home}/.java-airplay/application.properties
```

命令行参数优先于桌面界面保存的设置。完整配置模板请参考 `config/application.example.properties`。

常用配置：

| 配置项 | 默认值 | 说明 |
|---|---:|---|
| `airplay.serverName` | `Java AirPlay` | 在 iPhone/iPad 的“屏幕镜像”列表中显示的名称 |
| `airplay.width` / `airplay.height` | `1920` / `1080` | 向发送端声明的显示能力，填写 `auto` 可检测实际视频流 |
| `airplay.fps` | `60` | 向发送端声明的最大帧率，实际发送帧率由时间戳测量 |
| `airplay.requirePairing` | `true` | 接收媒体前是否要求完成 AirPlay 配对 |
| `airplay.hevc` | `false` | 启用实验性 HEVC 协商 |
| `player.implementation` | `gstreamer` | 选择播放后端；`ffmpeg` 要求 `PATH` 中存在 `ffplay`，音频仍使用 GStreamer |
| `player.gstreamer.renderMode` | `balanced` | 选择 balanced、quality 或 low-latency 呈现模式 |
| `player.gstreamer.videoQueueDepth` | `2` | Java 侧缓存的编码视频访问单元数量 |
| `player.gstreamer.aggressiveFrameDropping` | `false` | 实验性模式，拥塞时丢弃编码帧 |

默认启用安全视频路径。除非你更重视最低延迟而不是画面完整性，否则请保持 `player.gstreamer.aggressiveFrameDropping=false`。丢弃 H.264/HEVC 参考帧可能导致花屏，并持续到发送端重新连接。

### GStreamer 原生日志诊断

`logging.level.org.freedesktop.gstreamer` 控制 Java 绑定层日志。GStreamer 原生跟踪必须在 `Gst.init` 前配置，因此应使用 JVM 系统属性，而非 `application.properties`：

| 系统属性 | 默认值 | 说明 |
|---|---:|---|
| `airplay.gst.debug` | `3` | 原生 `GST_DEBUG` 规则，支持 `*:4,GST_CAPS:6` 等分类规则 |
| `airplay.gst.debug.file` | 未设置 | 可选的原生日志文件；相对路径解析到 `${user.home}/.java-airplay` 下 |

未设置对应系统属性时，会沿用已有的 `GST_DEBUG` 或 `GST_DEBUG_FILE` 环境变量。文件日志默认关闭；启用后会通过 `GST_DEBUG_NO_COLOR` 关闭颜色。通过 `airplay.gst.debug.file` 指定路径时，会自动创建父目录。

PowerShell 示例：

```powershell
$env:JAVA_TOOL_OPTIONS = '-Dairplay.gst.debug=*:4 -Dairplay.gst.debug.file=diagnostics/gstreamer-%p.log'
.\start.bat
Remove-Item Env:JAVA_TOOL_OPTIONS
```

日志会写入 `${user.home}/.java-airplay/diagnostics`，其中 `%p` 会替换为进程 ID。原生日志文件不会自动轮转，请仅在排查问题时临时启用文件日志，并手动清理旧文件。

## 播放后端能力矩阵

`player.implementation` 仅选择媒体渲染器；配对、会话校验和镜像独占权均由服务端统一执行，与后端无关。

| 后端 | H.264 镜像 | 实验性 HEVC | ALAC / AAC-ELD 音频 | HTTP/HLS 播放 | 桌面体验 | GPU 选择 |
|---|---|---|---|---|---|---|
| `gstreamer`（默认） | 支持 | 支持，需要 `airplay.hevc=true` 及相应插件 | 支持 | 支持，通过 `playbin3` | `player.gstreamer.swing=true` 时提供集成式接收器与设置界面 | 自动选择或指定 Windows DXGI 适配器 |
| `ffmpeg` | 支持，通过 `ffplay` | 支持，需要 `airplay.hevc=true` | 支持，通过 GStreamer | 不支持 | 独立的 FFplay 全屏窗口 | 应用不提供选择能力 |
| `vlc` | 支持 | 不支持 | 不支持 | 不支持 | 基础内嵌 VLC 窗口 | 应用不提供选择能力 |
| `h264-dump` | 仅写入 `dump.h264` | 不支持 | 不支持 | 不支持 | 无 | 不适用 |

Windows 安装包和便携版默认使用 GStreamer。源码使用 `ffmpeg` 时，`PATH` 中需存在 `ffplay`，并且仍需安装 GStreamer 音频插件。`vlc` 需要兼容的本地 libVLC。仅用于诊断的 `h264-dump` 会将 `dump.h264` 写入进程工作目录。

## 镜像会话接管

接收器同一时间仅允许一个有效的 RTSP 屏幕镜像所有者。接管不会弹出确认提示：另一条有效控制连接开始建立时序、视频或音频流时，会自动取得所有权。

设备 A 向设备 B 交接时，固定顺序为 `撤销 → 停止源 → 排空旧回调 → 通知断开`：

1. 撤销 A 的控制连接和媒体租约，拒绝其后续镜像操作与媒体数据包。
2. 在等待播放器回调前，先停止 A 的视频、音频和时序源。
3. 只等待已经在 A 的旧租约下执行的回调结束。
4. 对此前每个已连接的媒体流，仅向播放后端发送一次断开通知；随后关闭 A 的旧控制通道，再继续 B 的建立流程。

因此，B 开始镜像时，A 可能立即显示镜像已断开。接收器绝不会组合 A 的视频与 B 的音频。即使快速重连，或新控制连接复用同一个 AirPlay session ID，A 的迟到帧、延迟拆流请求和断开回调也无法停止 B。

此策略仅适用于 RTSP 镜像的时序、视频和音频流。HTTP/HLS 播放路径保持独立生命周期，不参与镜像接管。

## 网络与安全说明

- iPhone/iPad 和接收器应连接到同一个支持组播的局域网。
- mDNS 服务发现需要 UDP 端口 `5353` 可用。
- 默认开启配对。请妥善保管 identity 文件，除非需要让接收器作为新设备重新配对，否则不要删除它。
- 请仅在可信网络中使用接收器。
- 本项目实现的是传统屏幕镜像传输，不是完整 AirPlay 2。受保护的 Apple TV 内容和多房间功能不在支持范围内。

## 构建与测试

```powershell
./gradlew.bat test
./gradlew.bat :player:app:bootJar
```

在 Windows x64 环境中，从项目根目录构建完整发行版。打包需要 Python 3 和 Inno Setup 6.5 或更高版本：

```powershell
./gradlew.bat release
```

生成文件：

```text
release/java-airplay-<version>.<yyMMdd>-windows-x64.zip
release/java-airplay-<version>.<yyMMdd>-windows-x64.zip.sha256
release/java-airplay-<version>.<yyMMdd>-windows-x64-setup.exe
release/java-airplay-<version>.<yyMMdd>-windows-x64-setup.exe.sha256
```

仅限 Windows 的 `release` 任务会构建无控制台原生启动器、便携 ZIP、标准安装程序及校验文件。用户设置保存在 `%USERPROFILE%\.java-airplay`，升级或卸载不会删除。

## 模块

| 模块 | 职责 |
|---|---|
| `lib` | 配对、FairPlay、身份、Bonjour 和媒体工具 |
| `server` | AirPlay 控制、视频、音频、时序和重传 |
| `player:gstreamer` | 桌面界面和 GStreamer 播放后端 |
| `player:app` | Spring Boot 应用、设置和系统托盘 |
| `player:ffmpeg` | FFplay H.264/HEVC 视频与共享的 GStreamer 音频后端 |
| `player:vlc` | 备用 H.264 播放后端 |
| `player:h264-dump` | H.264 调试输出后端 |

## 上游沿革与致谢

本项目衍生自 Sergei Fedorov 创建、采用 MIT 许可证的 [serezhka/java-airplay](https://github.com/serezhka/java-airplay)。面向 Windows 的开发还借鉴了 [Druadach/java-airplay](https://github.com/Druadach/java-airplay)；可查看该分支[相对于原项目的提交对比](https://github.com/Druadach/java-airplay/compare/serezhka%3Ajava-airplay%3Amain...main)。

以下方面参考、改编或重新实现了该分支的工作：

- RTP 音频序列号回绕、有界抖动与乱序处理，以及 GStreamer 和 Netty 缓冲区生命周期修复（[5cf34f3](https://github.com/Druadach/java-airplay/commit/5cf34f3)）。
- FFmpeg 视频播放，以及通过 GStreamer 输出 ALAC/AAC-ELD 音频（[61ce455](https://github.com/Druadach/java-airplay/commit/61ce455)）。
- AirPlay 会话独占接管，以及过期媒体流和迟到数据包隔离（[be16964](https://github.com/Druadach/java-airplay/commit/be16964)）。
- 通过系统托盘控制运行时全屏模式，以及可靠的应用退出流程（[87cd8bd](https://github.com/Druadach/java-airplay/commit/87cd8bd)、[373951f](https://github.com/Druadach/java-airplay/commit/373951f)）。
- 中英文 Windows 配置与控制（[2c2ede0](https://github.com/Druadach/java-airplay/commit/2c2ede0)）。
- Windows CI 与自包含发行方案，包括捆绑运行时、安装程序和便携 ZIP（[dae1376](https://github.com/Druadach/java-airplay/commit/dae1376)、[0b1b610](https://github.com/Druadach/java-airplay/commit/0b1b610)）。

这些实现随后已集成、重构、测试并扩展，可能与上述补丁不同。以上链接仅记录技术沿革，不表示原作者为本项目背书。著作权归相应作者和贡献者所有。

## 许可证

本项目采用 [MIT License](LICENSE)。在打包或重新分发 GStreamer 前，请审查适用的第三方许可证和重新分发条款。
