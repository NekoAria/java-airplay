# Java AirPlay 投屏接收器

[English](README.md)

这是一个基于 Java 25 的桌面 AirPlay 投屏接收器，目标是让 iPhone 在同一局域网中进行低延迟屏幕镜像。默认配置向手机声明 1920x1080、60 fps，接收 H.264 视频以及 ALAC/AAC-ELD 音频，完成配对、FairPlay 解密、音频丢包重传，然后交给 GStreamer 硬件或软件解码。

本项目实现的是现代 iOS 仍用于“屏幕镜像”的兼容传输路径，不等于完整 AirPlay 2。完整 AirPlay 2 还包括 HomeKit/TLV8 配对、加密控制连接、PTP、多房间音频、设备分组以及受保护 Apple TV 内容，这些不在当前实现范围内。

## 当前技术基线

| 项目 | 版本或要求 |
|---|---|
| Java | JDK 25 |
| Gradle Wrapper | 9.7.0 |
| Spring Boot | 4.0.7 |
| Netty | 4.2.15.Final |
| 推荐原生播放器 | 64 位 GStreamer 1.28.5 |
| 默认视频能力 | 1920x1080、60 fps |
| Windows 默认解码 | 可用时优先选择 D3D12，然后 NVIDIA/D3D11 硬件解码 |
| 接收音频格式 | ALAC、AAC-ELD |

## JAR 中已经包含什么

构建生成的 Spring Boot JAR 已经包含 Java 协议实现、Netty、JmDNS、配对与 FairPlay、GStreamer Java 绑定、JNA、日志和所有播放器模块。

默认的 GStreamer 播放器还需要原生 GStreamer DLL 和插件。Windows 启动脚本会把它们放在项目目录下的 `.runtime/gstreamer`，以后每次启动都显式使用这一份运行时。`.runtime` 不提交到 Git，因为它包含体积较大的平台二进制文件。

当 `player.implementation=gstreamer` 时，不需要 FFmpeg 或 VLC。只有主动选择 `ffmpeg` 才需要 `ffplay`，主动选择 `vlc` 才需要系统安装 VLC。

## 普通用户使用完整 Windows 发布包

开发者在 Windows x64、JDK 25 环境执行：

```bat
gradlew.bat release
```

`release/` 会生成完整 ZIP 和 SHA-256 文件。ZIP 已包含可执行 JAR、精简 Java 25 runtime、GStreamer、启动脚本、可编辑配置、中英文手册和许可证。

普通用户只需要解压 ZIP，然后双击：

```text
start.bat
```

完整 ZIP 不要求用户另外安装 Java 或 GStreamer。下面的源码开发方式才要求预装 JDK 25。

## Windows 源码开箱启动

### 第一步：确认 Java 25

```powershell
java -version
```

第一行必须显示 Java 25。自动脚本不会下载安装 JDK，JDK 25 是唯一需要事先准备的基础环境。

### 第二步：直接启动

在项目根目录运行：

```bat
start.bat
```

第一次启动会自动完成以下工作：

1. 检查 Java 是否为 25。
2. 检查项目内是否已经存在完整 GStreamer。
3. 如果电脑已经安装 GStreamer，将它复制到 `.runtime/gstreamer`。
4. 如果电脑没有 GStreamer，先访问 FlClash 控制器 `127.0.0.1:9090`，读取当前 `mixed-port`，验证代理流量，然后下载官方 GStreamer 1.28.5。
5. 对下载的安装器执行 SHA-256 校验，校验不一致会立即停止，不会继续安装。
6. 将 GStreamer 静默安装到项目 `.runtime/gstreamer`。
7. 检查 H.264、AAC、ALAC、`appsrc`、解析器、视频输出和音频输出插件。
8. 如果可执行 JAR 不存在，自动调用 Gradle Wrapper 构建。
9. 使用项目内 GStreamer 启动接收器。

iPhone 控制中心的“屏幕镜像”列表中应该出现 `Java AirPlay`。

### 第三步：只检查环境，不下载安装

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\bootstrap-runtime.ps1 -CheckOnly
```

检查成功会显示 Java 25、GStreamer 路径、必要插件和 D3D12/D3D11 硬件解码状态。

### 第四步：后台启动

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\start.ps1 -Background
```

命令会返回一段 JSON，其中包含进程 PID、JAR 路径和实际 GStreamer 路径。结束后台进程：

```powershell
Stop-Process -Id <上一步返回的PID>
```

当 `player.tray.enabled=true` 时，也可以通过系统托盘的 `Quit` 退出。

## 从源码完整构建

```powershell
.\gradlew.bat clean build --warning-mode all
```

真正可独立运行的工件是：

```text
player/app/build/libs/java-airplay-server-1.0.7.jar
```

`app-1.0.7-plain.jar` 不是独立工件，不要用它启动。正确文件名以 `java-airplay-server-` 开头。

只构建可执行 JAR：

```powershell
.\gradlew.bat :player:app:bootJar
```

构建普通用户开箱即用的完整 Windows 包：

```powershell
.\gradlew.bat release
```

生成结果：

```text
release/java-airplay-1.0.7-windows-x64.zip
release/java-airplay-1.0.7-windows-x64.zip.sha256
```

只运行测试：

```powershell
.\gradlew.bat test
```

清理后重新验收：

```powershell
.\gradlew.bat clean test :player:app:bootJar --warning-mode all
```

## 已经拿到构建好的 JAR 怎么用

推荐仍然通过项目启动脚本运行，因为脚本会自动准备和固定原生环境：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\start.ps1 `
  -JarPath D:\apps\java-airplay-server-1.0.7.jar
```

如果直接运行 Java，需要自己告诉 JVM GStreamer DLL 在哪里：

```powershell
java --enable-native-access=ALL-UNNAMED `
  "-Dgstreamer.path=$PWD\.runtime\gstreamer\bin" `
  -jar .\player\app\build\libs\java-airplay-server-1.0.7.jar
```

不写 `--enable-native-access=ALL-UNNAMED` 时，JDK 25 会对 JNA/GStreamer 原生调用发出警告；未来 JDK 可能直接阻止，因此生产启动必须保留这个参数。可执行 Spring Boot JAR 的 Manifest 也已经声明该权限。

## 配置文件怎么放

Spring Boot 会先读取 JAR 内默认配置，再读取外部配置。常用位置：

```text
项目根目录/application.properties
项目根目录/config/application.properties
通过 -ConfigPath 明确指定的任意文件
```

生成一份可修改配置：

```powershell
Copy-Item .\config\application.example.properties .\config\application.properties
```

明确指定配置文件启动：

```powershell
.\start.bat -ConfigPath .\config\application.properties
```

命令行参数优先级最高，适合临时测试：

```powershell
.\start.bat --airplay.serverName=Gaming-PC --player.gstreamer.videoQueueDepth=2
```

配置值中包含空格时，建议写入 properties 文件，避免命令行转义问题。

## application.properties 每一行说明

下面覆盖打包默认配置中的全部键。AirPlay 与 Player 配置已通过自动绑定测试，非法范围会在启动阶段直接报错，不会静默使用错误值。

| 配置键 | 默认值 | 详细说明 |
|---|---:|---|
| `logging.file.name` | `${user.home}/.java-airplay/java-airplay.log` | 主日志文件。`${user.home}` 是当前 Windows 用户目录，例如 `C:\Users\用户名`。日志不再写进仓库。 |
| `logging.level.org.springframework.web` | `INFO` | Spring Web 基础日志。本程序不是网页服务器，正常保持 `INFO`。 |
| `logging.level.javax.jmdns` | `WARN` | Bonjour/mDNS 日志。手机找不到设备时可临时改为 `DEBUG`。 |
| `logging.level.wtf.nanoka` | `INFO` | 项目自身日志。`DEBUG` 会增加握手、流格式、NTP 和重传信息，但不会输出媒体密钥。 |
| `logging.level.io.netty` | `INFO` | Netty 网络框架日志。生产环境保持 `INFO`。 |
| `logging.level.io.netty.handler.logging.LoggingHandler` | `INFO` | AirPlay 控制连接的 Netty 日志。仅协议排错时提高。 |
| `logging.level.org.freedesktop.gstreamer` | `INFO` | GStreamer Java 绑定日志。原生 GStreamer 还可使用环境变量 `GST_DEBUG`。 |
| `spring.output.ansi.enabled` | `ALWAYS` | 控制终端彩色日志。作为服务写纯文本时可改为 `NEVER`。 |
| `logging.logback.rollingpolicy.max-file-size` | `10MB` | 单个日志达到该大小后滚动。可写 `20MB`、`100MB`。 |
| `logging.logback.rollingpolicy.max-history` | `7` | 保留多少份历史滚动日志。 |
| `airplay.serverName` | `Java AirPlay` | iPhone“屏幕镜像”列表看到的名称，不能为空。多台接收器必须使用不同名称。 |
| `airplay.width` | `1920` | 最大声明宽度。可填 320-7680 或 `auto`；auto 协商时使用 1920 兜底，连接后从 H.264 SPS 读取手机实际宽度。 |
| `airplay.height` | `1080` | 最大声明高度。可填 240-4320 或 `auto`；auto 协商时使用 1080 兜底，连接后从 H.264 SPS 读取手机实际高度。 |
| `airplay.fps` | `60` | 最大声明帧率。可填 1-120 或 `auto`；auto 协商时使用 60 兜底，连接后从视频时间戳测量手机实际帧率。 |
| `airplay.identityFile` | `${user.home}/.java-airplay/identity.key` | 接收器持久身份种子，固定 32 字节。重启时必须保留，否则 iPhone 会认为是另一台设备。不要公开或提交到 Git。 |
| `airplay.audioJitterPackets` | `3` | 音频乱序缓冲，允许 1 到 64。值越小延迟越低，值越大越能抵抗 Wi-Fi 抖动。游戏建议 2-3；不稳定网络建议 4-8。 |
| `airplay.requirePairing` | `true` | 必须完成 Pair-Verify 才允许 FairPlay 和媒体 SETUP。关闭会降低局域网访问控制，不建议。 |
| `player.implementation` | `gstreamer` | 播放后端。可选 `gstreamer`、`ffmpeg`、`vlc`、`h264-dump`。只有 GStreamer 是当前推荐的实时视频加音频路径。 |
| `player.tray.enabled` | `true` | 是否显示系统托盘和 `Quit`。无人值守或没有桌面会话时设为 `false`。 |
| `player.gstreamer.swing` | `false` | 是否通过 Swing appsink 显示。`false` 使用原生 sink，减少 CPU 拷贝和 UI 线程延迟，游戏必须优先用 `false`。 |
| `player.gstreamer.videoDecoder` | `auto` | H.264 解码器。`auto` 优先 D3D12、NVIDIA NVDEC、D3D11；Windows 可固定 `d3d12h264dec`、`d3d11h264dec` 或 `vulkanh264dec`；软件回退为 `avdec_h264`。插件不存在时会启动失败并明确报错。 |
| `player.gstreamer.gpuAdapter` | `auto` | GPU 适配器选择。Windows 下 `auto` 会扫描 DXGI 适配器，并优先选择支持 H.264 硬解且专用显存最大的 D3D12 适配器；也可以填写扫描结果中的任意非负 DXGI 索引（`0`、`1`、`2`、`3`……）固定给 D3D12/D3D11。该索引是 DXGI 原生顺序，不等同于任务管理器中的 GPU 编号。NVDEC 的 CUDA 序号与 DXGI 序号没有可靠映射，因此显式数字索引会被拒绝。 |
| `player.gstreamer.videoQueueDepth` | `2` | 已解密视频访问单元队列，允许 1 到 16。满时丢最旧帧，防止延迟越积越高。游戏建议 2；显示不稳定时可用 3-4。 |

完整示例文件位于 `config/application.example.properties`，文件中每一项都带中文注释。

### auto 的真实含义和协议限制

AirPlay 的顺序是手机先请求接收器 `/info`，接收器必须先返回显示能力，手机之后才发送设备信息、H.264 SPS 和媒体时间戳。因此不能在同一次 `/info` 协商前读取手机原生分辨率/FPS。这里的 `auto` 在协商阶段使用 1920x1080@60 兜底，连接后从 H.264 SPS 读取手机实际宽高，并从视频时间戳测量实际帧率。GStreamer 会按手机真实 SPS 和时间戳自动跟随，包括横竖屏变化。没有桌面会话时，协商兜底仍为 1920x1080、60 fps。

## 游戏直播推荐配置

```properties
airplay.serverName=Gaming PC
airplay.width=1920
airplay.height=1080
airplay.fps=60
airplay.audioJitterPackets=3
airplay.requirePairing=true
player.implementation=gstreamer
player.tray.enabled=true
player.gstreamer.swing=false
player.gstreamer.videoDecoder=d3d12h264dec
player.gstreamer.gpuAdapter=auto
player.gstreamer.videoQueueDepth=2
```

OBS 中可捕获 GStreamer 视频窗口；音频可捕获 Windows 默认播放设备，或者使用专用虚拟声卡。AirPlay 屏幕镜像一定会调用 iPhone 的硬件 H.264 编码器。接收端可以减少缩放、重传和排队，但无法完全消除手机编码器自身的耗电与性能占用。

Windows 启动日志会按原生顺序打印所有 DXGI 适配器，例如 `[0]`、`[1]`、`[2]`。确认名称后再把对应数字写入配置；适配器数量不限制为两张。GStreamer 会为每个支持硬解的 GPU 注册独立 decoder factory，例如 index 0 使用 `d3d12h264dec`，index 1 使用 `d3d12h264device1dec`，程序会用同样规则动态匹配 `device2`、`device3` 以及更后的索引。`auto` 会跳过软件适配器和没有 H.264 硬解 factory 的适配器；显式索引没有对应硬解器时会在启动阶段明确报错。

## 网络和防火墙要求

手机与电脑必须位于支持组播的同一局域网，不能只满足“能互相访问互联网”。

- UDP 5353 必须能在手机和电脑之间传递，这是 mDNS 发现。
- Java/OpenJDK 必须允许 Public 或 Private 网络的入站 TCP、UDP。
- 控制、视频、音频、重传和 NTP timing 使用动态端口，不能只固定开放一个端口。
- 路由器的访客 Wi-Fi、AP 隔离、客户端隔离必须关闭。
- 跨 VLAN 必须配置 mDNS reflector 和相应防火墙规则。
- 普通 USB 数据线不是 AirPlay 网络。
- iPhone 使用 USB-C 网卡接入与电脑相同交换网络时，可以进行有线 AirPlay。

Windows 检查命令：

```powershell
Get-NetConnectionProfile
Get-NetFirewallRule -Enabled True -Direction Inbound -Action Allow |
  Where-Object DisplayName -Match 'Java|OpenJDK|AirPlay'
```

为了降低手机无线电开销，推荐电脑接网线，手机使用干净的 5 GHz 或 6 GHz Wi-Fi。iPhone 通过 USB-C 网卡接网线可以去掉 Wi-Fi 传输开销，但 H.264 硬件编码仍然存在。

## FlClash 代理行为

Windows 自动下载脚本会优先访问：

```text
http://127.0.0.1:9090/version
http://127.0.0.1:9090/configs
```

脚本从 `/configs` 读取 `mixed-port`，然后通过该 HTTP 代理访问一个小型 Gradle HTTPS 地址验证真实流量。控制接口或代理流量异常时会警告，并尝试直接下载。已经存在项目内 GStreamer 时不会联网。

## Linux 部署

先安装 Java 25，再安装 GStreamer。Debian/Ubuntu 常见命令：

```shell
sudo apt install gstreamer1.0-tools gstreamer1.0-plugins-base \
  gstreamer1.0-plugins-good gstreamer1.0-plugins-bad \
  gstreamer1.0-plugins-ugly gstreamer1.0-libav
```

检查并启动：

```shell
gst-inspect-1.0 avdec_h264
gst-inspect-1.0 avdec_aac
gst-inspect-1.0 avdec_alac
sh scripts/start.sh --player.tray.enabled=false
```

常见 Linux 硬件解码器是 `vah264dec` 或 `v4l2h264dec`。如果作为 systemd 服务启动，需要给服务正确的 `DISPLAY`、Wayland、PipeWire 或 PulseAudio 会话权限；没有桌面和音频会话的系统服务无法正常显示画面或播放声音。

## macOS 部署

通过 Homebrew 或 GStreamer 官方 Framework 安装 GStreamer，确保 H.264、AAC、ALAC 插件存在。Apple 硬件解码常用元素是 `vtdec_hw`：

```shell
sh scripts/start.sh --player.gstreamer.videoDecoder=vtdec_hw
```

macOS 第一次启动时需要允许终端或 Java 访问本地网络。

## 常见问题排查

### iPhone 看不到 Java AirPlay

确认手机不是访客 Wi-Fi，电脑和手机处于同一网段。临时关闭 VPN/TUN 虚拟网卡，检查 UDP 5353，并将 `logging.level.javax.jmdns` 临时改为 `DEBUG`。

### 能看到设备，但点击后连接失败

查看 `${user.home}/.java-airplay/java-airplay.log`。保持 `airplay.requirePairing=true`。不要一开始就删除 `identity.key`，先保存日志；身份不一致、旧配对或握手顺序问题都会在日志中体现。

### 已连接但没有画面

执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\bootstrap-runtime.ps1 -CheckOnly
.\.runtime\gstreamer\bin\gst-inspect-1.0.exe d3d12h264dec
```

将 `player.gstreamer.videoDecoder` 临时改为 `d3d12h264dec` 或 `avdec_h264`。如果 D3D12 能工作而 D3D11 报 `E_NOINTERFACE`，说明问题集中在 D3D11 插件/驱动接口；如果只有软件解码能工作，则优先更新显卡驱动。

上游 GStreamer 1.28 文档确实包含 `vulkanh264dec` 和 `vulkansink`，但当前 Windows 发布包实际没有注册这些元素。代码已经允许在未来运行时同时提供两个元素时选择 `vulkanh264dec`；当前环境缺少插件时会明确报错。Vulkan 上传/渲染能力不能单独替代 H.264 解码。

### 音频断断续续

把 `airplay.audioJitterPackets` 从 3 调到 4 或 6。这样会增加几个音频包的延迟，但更能抵抗乱序。电脑尽量使用网线，不要使用拥堵的 2.4 GHz Wi-Fi。

### 延迟会不断增加

确认 `player.gstreamer.swing=false`、`player.gstreamer.videoQueueDepth=2`，并确认硬件解码生效。当前实现会丢弃已经解密的最旧画面，不会允许播放器队列无限增长。

### 如何重置接收器身份

先停止程序，删除 `airplay.identityFile` 指向的文件，再启动。iPhone 会将它识别为一台全新的接收器，需要重新配对。

### 日志在哪里

默认：

```text
C:\Users\当前用户名\.java-airplay\java-airplay.log
```

日志自动滚动，默认每份 10MB，保留 7 份。

## 项目模块

| 模块 | 职责 |
|---|---|
| `lib` | 配对、FairPlay、RTSP plist、媒体解密、持久身份、Bonjour |
| `server` | Netty 控制服务、会话、NTP、视频 TCP、音频 RTP/UDP、重传 |
| `player:gstreamer` | 低延迟 H.264、ALAC、AAC-ELD、HLS 播放 |
| `player:ffmpeg` | 可选的纯视频 `ffplay` 后端 |
| `player:vlc` | 实验性 VLC 后端 |
| `player:h264-dump` | 将 Annex-B H.264 写入 `dump.h264` |
| `player:app` | Spring Boot 可执行程序、类型化配置、系统托盘 |
| `client` | 实验性发送端与协议验证代码 |

## 安全与法律边界

本程序只适合可信局域网。默认强制配对，对媒体长度做上限检查，不在日志中输出媒体密钥，身份种子存放在仓库外。

Apple 没有公开 FairPlay 接收端规范。受 DRM 保护的视频应用可能主动禁止镜像。重新分发 FairPlay 相关代码或打包第三方原生运行时时，应自行审查许可证和所在地法律要求。
