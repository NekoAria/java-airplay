# Java AirPlay 投屏接收器

[English](README.md)

这是一个基于 Java 25 的局域网 iPhone 屏幕镜像接收器。它接收 H.264 视频、ALAC/AAC-ELD 音频，并通过 GStreamer 提供实验性 HEVC 接收支持。

## 快速开始

需要 Windows、JDK 25 和 GStreamer 1.28.5。启动脚本可以准备项目内的 GStreamer 运行时。

```bat
start.bat
```

打开 iPhone 控制中心的“屏幕镜像”，选择程序中显示的接收器名称。

从源码测试和构建：

```powershell
.\gradlew.bat test
.\gradlew.bat :player:app:bootJar
```

可执行 JAR 位于 `player/app/build/libs/java-airplay-server-1.0.9.jar`。

## 桌面界面

集成 GStreamer 窗口提供接收状态和完整设置。Windows 的 GPU 下拉框会显示真实 DXGI 显卡名称，保存时仍写入对应的 DXGI index。`Save & Restart` 会验证配置并自动重启当前进程，使网络和管线设置立即生效。

- **语言**：英文 / 中文，在「设置 → 语言」或系统托盘菜单中即时切换（按用户持久保存）。
- **独立视频窗口**：集成窗口模式下，Receiver 页面的画面可分离到独立窗口（托盘：显示视频窗口），并支持全屏（ESC 退出）。
- 系统托盘保留「打开 Java AirPlay / 显示视频窗口 / 全屏 / 语言 / 退出」，集成窗口关闭后仍可随时重新打开 GUI。

设置文件：

```text
${user.home}/.java-airplay/application.properties
```

命令行参数优先于桌面保存的设置。完整带注释的配置模板见 `config/application.example.properties`。

## 注意事项

- iPhone 和接收器必须在同一个支持组播的局域网，UDP 5353 用于发现服务。
- 默认开启配对。请妥善保管 identity 文件；删除它会让 iPhone 将接收器识别为新设备。
- HEVC 为实验性功能，默认关闭。仅在 GStreamer 后端中启用 `airplay.hevc=true`。
- 本项目实现的是屏幕镜像兼容传输，不是完整 AirPlay 2；受保护 Apple TV 内容和多房间功能不在范围内。

## 模块

| 模块 | 职责 |
|---|---|
| `lib` | 配对、FairPlay、身份、Bonjour、媒体工具 |
| `server` | AirPlay 控制、视频、音频、时序、重传 |
| `player:gstreamer` | 桌面 UI 与 GStreamer 播放 |
| `player:app` | Spring Boot 应用和设置 |

请仅在可信局域网使用，并在重新分发前审查相关许可证。
