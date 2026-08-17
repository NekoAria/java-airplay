# Java AirPlay Receiver

[简体中文](README.zh-CN.md)

Java AirPlay Receiver is a Java 25 desktop receiver for low-latency iPhone screen mirroring. The default profile advertises 1920x1080 at 60 fps, receives H.264 video plus ALAC or AAC-ELD audio, performs FairPlay decryption, repairs short audio loss through retransmission, and sends the media to GStreamer.

The receiver implements the legacy mirroring transport still used by modern iOS devices. It is not a complete AirPlay 2 implementation: HomeKit/TLV8 pairing, encrypted AirPlay 2 control, PTP, multi-room audio, grouping, and protected Apple TV content are outside the current scope.

## Current Baseline

| Component | Version or requirement |
|---|---|
| Java | JDK 25 |
| Gradle Wrapper | 9.7.0 |
| Spring Boot | 4.0.7 |
| Netty | 4.2.15.Final |
| Recommended native player | GStreamer 1.28.5, 64-bit |
| Default video profile | 1920x1080, 60 fps |
| Default Windows decoder | D3D12 hardware decoder when available, then NVIDIA/D3D11 |
| Supported receiver audio | ALAC and AAC-ELD |

## What Is Included

The executable Spring Boot JAR includes the Java protocol implementation, Netty, JmDNS, pairing and FairPlay code, GStreamer Java bindings, JNA, logging, and all player modules.

The default GStreamer backend additionally needs native GStreamer binaries and plugins. On Windows, the provided scripts keep those binaries inside `.runtime/gstreamer` below the project directory. They are deliberately excluded from Git because they are large platform binaries.

The `ffmpeg` backend needs `ffplay` on `PATH`. The `vlc` backend needs a compatible VLC installation. Neither is required when `player.implementation=gstreamer`.

## Complete Windows Release for End Users

Developers build the self-contained Windows x64 package with:

```bat
gradlew.bat release
```

The generated ZIP is written to `release/` with a matching SHA-256 file. It contains the executable JAR, a compact Java 25 runtime, GStreamer, startup scripts, editable configuration, bilingual manuals, and licenses.

An end user only needs to extract the ZIP and run:

```bat
start.bat
```

No separate Java or GStreamer installation is required for the complete ZIP. The following source-development instructions require JDK 25.

## Windows Source Quick Start

### 1. Check Java

```powershell
java -version
```

The first line must report Java 25. The runtime bootstrap intentionally does not install a JDK.

### 2. Start from the repository

```bat
start.bat
```

The first start performs these operations automatically:

1. Verifies Java 25.
2. Looks for a complete project-local GStreamer runtime.
3. Copies an existing 64-bit GStreamer installation into `.runtime/gstreamer` when one is available.
4. Otherwise checks the FlClash controller at `127.0.0.1:9090`, reads its `mixed-port`, verifies proxy traffic, downloads the official GStreamer 1.28.5 installer, and verifies SHA-256.
5. Installs GStreamer below `.runtime/gstreamer`.
6. Verifies `appsrc`, `h264parse`, H.264, AAC, ALAC, video sink, and audio sink plugins.
7. Builds the executable JAR when it does not exist.
8. Starts the receiver with the project-local GStreamer path.

The expected iPhone Screen Mirroring entry is `Java AirPlay`.

### 3. Check without changing anything

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\bootstrap-runtime.ps1 -CheckOnly
```

### 4. Run in the background

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\start.ps1 -Background
```

The command returns JSON containing the process ID, JAR path, and GStreamer path. Stop that process with:

```powershell
Stop-Process -Id <process-id>
```

The tray icon also provides a `Quit` action when `player.tray.enabled=true`.

## Building from Source

```powershell
.\gradlew.bat clean build --warning-mode all
```

The executable artifact is:

```text
player/app/build/libs/java-airplay-server-1.0.7.jar
```

The `app-1.0.7-plain.jar` file is not standalone. Use the `java-airplay-server-*.jar` Spring Boot artifact.

Build only the executable JAR:

```powershell
.\gradlew.bat :player:app:bootJar
```

Build the complete end-user Windows package:

```powershell
.\gradlew.bat release
```

Generated artifacts:

```text
release/java-airplay-1.0.7-windows-x64.zip
release/java-airplay-1.0.7-windows-x64.zip.sha256
```

Run tests:

```powershell
.\gradlew.bat test
```

## Using a Prebuilt JAR

Place the JAR anywhere and provide its path to the startup script:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\start.ps1 `
  -JarPath D:\apps\java-airplay-server-1.0.7.jar
```

The script still checks and uses `.runtime/gstreamer` from this repository.

Direct Java startup is possible, but then the native path is your responsibility:

```powershell
java --enable-native-access=ALL-UNNAMED `
  "-Dgstreamer.path=$PWD\.runtime\gstreamer\bin" `
  -jar .\player\app\build\libs\java-airplay-server-1.0.7.jar
```

## Configuration Files

Spring Boot reads the packaged defaults and then external configuration. Supported external locations include `./application.properties`, `./config/application.properties`, and an explicit file passed to the startup script.

Prepare an editable configuration:

```powershell
Copy-Item .\config\application.example.properties .\config\application.properties
```

Start with an explicit file:

```powershell
.\start.bat -ConfigPath .\config\application.properties
```

Command-line properties have the highest priority:

```powershell
.\start.bat --airplay.serverName=Gaming-PC --player.gstreamer.videoQueueDepth=2
```

## Complete Property Reference

Every property shipped in `application.properties` is bound or consumed as described below.

| Property | Default | Meaning and operational guidance |
|---|---:|---|
| `logging.file.name` | `${user.home}/.java-airplay/java-airplay.log` | Main log file. The parent directory also stores receiver identity. |
| `logging.level.org.springframework.web` | `INFO` | Spring web infrastructure logging. The application is not an HTTP web server, so `INFO` is sufficient. |
| `logging.level.javax.jmdns` | `WARN` | Bonjour/mDNS library logging. Use `DEBUG` only when the receiver is not discoverable. |
| `logging.level.wtf.nanoka` | `INFO` | Application and protocol logging. `DEBUG` adds handshake, stream, timing, and packet diagnostics without printing media keys. |
| `logging.level.io.netty` | `INFO` | Netty framework logging. Keep at `INFO` for normal use. |
| `logging.level.io.netty.handler.logging.LoggingHandler` | `INFO` | Control pipeline logger. Increase only for protocol troubleshooting. |
| `logging.level.org.freedesktop.gstreamer` | `INFO` | GStreamer Java binding logging. Native GStreamer also honors `GST_DEBUG`. |
| `spring.output.ansi.enabled` | `ALWAYS` | Keeps colored console logs. Use `NEVER` for plain service logs. |
| `logging.logback.rollingpolicy.max-file-size` | `10MB` | Rotates the active log after this size. |
| `logging.logback.rollingpolicy.max-history` | `7` | Number of old rotated log files retained. |
| `airplay.serverName` | `Java AirPlay` | Name shown in the iPhone Screen Mirroring list. It must not be blank. |
| `airplay.width` | `1920` | Maximum advertised display width. Use 320-7680 or `auto`. Auto uses a 1920 fallback for negotiation, then detects the phone stream from H.264 SPS. |
| `airplay.height` | `1080` | Maximum advertised display height. Use 240-4320 or `auto`. Auto uses a 1080 fallback for negotiation, then detects the phone stream from H.264 SPS. |
| `airplay.fps` | `60` | Maximum advertised frame rate. Use 1-120 or `auto`. Auto uses 60 for negotiation, then measures the phone stream from packet timestamps. |
| `airplay.identityFile` | `${user.home}/.java-airplay/identity.key` | Persistent 32-byte receiver identity seed. Do not delete it unless the iPhone should see a new receiver. Do not publish it. |
| `airplay.audioJitterPackets` | `3` | Audio reorder window, range 1 to 64. `2-3` gives lower latency; `4-8` tolerates unstable Wi-Fi better. |
| `airplay.requirePairing` | `true` | Rejects FairPlay/media setup until Pair-Verify succeeds. Disabling it weakens access control and is not recommended. |
| `player.implementation` | `gstreamer` | Selects `gstreamer`, `ffmpeg`, `vlc`, or `h264-dump`. Only GStreamer supports the intended video plus live audio path. |
| `player.tray.enabled` | `true` | Enables the desktop tray icon. Set `false` for headless or service operation. |
| `player.gstreamer.swing` | `false` | Uses a Swing appsink when true. False uses the native video sink and avoids CPU copies, so it is preferred for gaming. |
| `player.gstreamer.videoDecoder` | `auto` | `auto` prefers D3D12, NVIDIA NVDEC, then D3D11. Windows can force `d3d12h264dec`, `d3d11h264dec`, or `vulkanh264dec`; software fallback is `avdec_h264`. Invalid or missing elements fail fast. |
| `player.gstreamer.gpuAdapter` | `auto` | GPU adapter selection. On Windows, `auto` enumerates DXGI adapters and selects the capable D3D12 adapter with the most dedicated VRAM; any available non-negative DXGI index (`0`, `1`, `2`, `3`, ...) can be selected explicitly for D3D12/D3D11. The index is the native DXGI order, not the Task Manager number. Numeric selection is intentionally rejected for NVDEC because CUDA and DXGI device ordinals are not guaranteed to match. |
| `player.gstreamer.videoQueueDepth` | `2` | Number of decrypted video access units retained, range 1 to 16. A full queue drops the oldest frame. Use 2 for gaming and 3-4 for unstable rendering. |

The same documented configuration is available in `config/application.example.properties`.

### What `auto` Can and Cannot Do

AirPlay asks the receiver for display capabilities before the iPhone sends model, H.264 SPS, or media timing information. Therefore the phone's native resolution/FPS cannot be read before the same `/info` response. With `auto`, this receiver uses 1920x1080@60 only as the negotiation fallback, then reads the actual phone resolution from H.264 SPS and measures actual FPS from video packet timestamps. GStreamer follows the actual SPS and timestamps, including orientation changes. In a headless session, the negotiation fallback remains 1920x1080@60.

## Recommended Gaming Configuration

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

For OBS, capture the GStreamer video window and capture the Windows default playback device or a dedicated virtual audio device. AirPlay mirroring always uses the iPhone hardware video encoder. The receiver can reduce retries, scaling, and queueing but cannot remove that phone-side encoder cost.

On Windows, the startup log prints every DXGI adapter in native order, for example `[0]`, `[1]`, and `[2]`. Use one of those indices only after checking its name. GStreamer registers one decoder factory per capable adapter, such as `d3d12h264dec` for index 0 and `d3d12h264device1dec` for index 1; the application derives the same pattern for `device2`, `device3`, and later indices. `auto` skips software or non-decoding adapters and chooses the capable hardware adapter with the largest dedicated video-memory budget. An explicit adapter without a registered D3D H.264 decoder fails at startup with a clear error.

## Network and Firewall

The receiver and iPhone must share a multicast-capable LAN. Required behavior:

- UDP 5353 multicast must pass between devices for mDNS discovery.
- Java must accept inbound TCP and UDP traffic.
- Dynamic TCP/UDP ports are used for control, video, audio, retransmission, and NTP timing.
- Guest Wi-Fi client isolation must be disabled.
- Routed VLANs require an mDNS reflector and suitable firewall rules.
- A direct USB cable is not an AirPlay transport.
- A USB-C Ethernet adapter works when the iPhone and receiver join the same LAN.

Windows inspection commands:

```powershell
Get-NetConnectionProfile
Get-NetFirewallRule -Enabled True -Direction Inbound -Action Allow |
  Where-Object DisplayName -Match 'Java|OpenJDK|AirPlay'
```

For the lowest phone radio load, connect the receiver by Ethernet and keep the iPhone on clean 5 GHz or 6 GHz Wi-Fi. USB-C Ethernet can remove Wi-Fi radio traffic from the phone, but hardware H.264 encoding still occurs.

## Linux Deployment

Install Java 25 and GStreamer packages. Debian/Ubuntu package names are typically:

```shell
sudo apt install gstreamer1.0-tools gstreamer1.0-plugins-base \
  gstreamer1.0-plugins-good gstreamer1.0-plugins-bad \
  gstreamer1.0-plugins-ugly gstreamer1.0-libav
```

Verify plugins and start:

```shell
gst-inspect-1.0 avdec_h264
gst-inspect-1.0 avdec_aac
gst-inspect-1.0 avdec_alac
sh scripts/start.sh --player.tray.enabled=false
```

Hardware decoder names vary. Common values are `vah264dec` and `v4l2h264dec`. The service needs access to the active display and audio session; a system service without `DISPLAY`, Wayland, PipeWire, or PulseAudio access cannot show or play media.

## macOS Deployment

Install Java 25 and GStreamer through Homebrew or the official framework. Verify `vtdec_hw`, AAC, and ALAC plugins, then run:

```shell
sh scripts/start.sh --player.gstreamer.videoDecoder=vtdec_hw
```

macOS must allow the terminal or Java process to receive local-network traffic.

## Troubleshooting

### The receiver is not visible

Confirm both devices are on the same non-guest LAN. Temporarily disable VPN/TUN adapters, confirm UDP 5353 is not filtered, and set `logging.level.javax.jmdns=DEBUG`.

### The receiver is visible but connection fails

Inspect `${user.home}/.java-airplay/java-airplay.log`. Keep `airplay.requirePairing=true`; repeated authentication failures often indicate a stale or mismatched receiver identity. Do not delete `identity.key` until logs have been collected.

### Video connects but no window appears

Run the runtime check and inspect the selected decoder:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\bootstrap-runtime.ps1 -CheckOnly
.\.runtime\gstreamer\bin\gst-inspect-1.0.exe d3d12h264dec
```

Set `player.gstreamer.videoDecoder=avdec_h264` to isolate a hardware decoder or driver problem. On Windows, try `d3d12h264dec` before `d3d11h264dec` when the D3D11 plugin reports `E_NOINTERFACE`.

The current Windows GStreamer runtime exposes D3D12 H.264 decoding and `d3d12videosink`. Upstream GStreamer 1.28 documents `vulkanh264dec` and `vulkansink`, but the Windows runtime shipped with this release does not register them. The code accepts `vulkanh264dec` when a future runtime provides both elements; otherwise it fails fast with a clear missing-plugin message. Vulkan upload/rendering alone does not decode H.264.

### Audio breaks up

Increase `airplay.audioJitterPackets` from 3 to 4 or 6. This increases latency by a few packets but tolerates more reordering. Prefer Ethernet for the receiver and avoid congested 2.4 GHz Wi-Fi.

### Latency keeps growing

Keep `player.gstreamer.swing=false` and `player.gstreamer.videoQueueDepth=2`. Confirm the hardware decoder is active. The receiver drops already decrypted old frames instead of allowing an unbounded queue.

### Resetting receiver identity

Stop the receiver, remove the file configured by `airplay.identityFile`, and restart. The iPhone will see a new receiver identity and must pair again.

## Module Layout

| Module | Responsibility |
|---|---|
| `lib` | Pairing, FairPlay, RTSP plist parsing, media decryption, receiver identity, Bonjour |
| `server` | Netty control server, sessions, NTP timing, video TCP, audio RTP/UDP, retransmission |
| `player:gstreamer` | Low-latency H.264, ALAC, AAC-ELD, HLS playback |
| `player:ffmpeg` | Optional video-only `ffplay` backend |
| `player:vlc` | Experimental VLC backend |
| `player:h264-dump` | Writes decrypted Annex-B H.264 to `dump.h264` |
| `player:app` | Spring Boot executable application and typed configuration |
| `client` | Experimental sender and protocol test client |

## Security and Legal Notes

The receiver is intended for trusted local networks. Pairing is required by default, malformed frame sizes are bounded, media keys are not logged, and the identity seed is stored outside the repository.

FairPlay receiver behavior is not publicly specified by Apple. Protected streaming applications may intentionally block mirroring. Review applicable licenses and local law before redistributing FairPlay-related code or packaging third-party native runtimes.
