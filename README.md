# Java AirPlay Receiver

If this project is useful to you, please consider giving the repository a Star on GitHub. It helps more developers discover the project and supports continued development.

[![GitHub Stars](https://img.shields.io/github/stars/Arc-Lira/java-airplay?style=flat-square)](https://github.com/Arc-Lira/java-airplay/stargazers)
[![Java 25](https://img.shields.io/badge/Java-25-blue?style=flat-square)](https://jdk.java.net/25/)
[![Platform](https://img.shields.io/badge/platform-Windows%20x64-0078D6?style=flat-square)](https://github.com/Arc-Lira/java-airplay)
[![License](https://img.shields.io/badge/license-MIT-green?style=flat-square)](LICENSE)

[简体中文](README.zh-CN.md) · [Issues](https://github.com/Arc-Lira/java-airplay/issues) · [Releases](https://github.com/Arc-Lira/java-airplay/releases)

Java 25 desktop receiver for iPhone Screen Mirroring on a local network. It receives H.264 video and ALAC/AAC-ELD audio, with opt-in experimental HEVC through the GStreamer or FFmpeg video backend.

## Highlights

| Capability | Details |
|---|---|
| Screen mirroring | Receive iPhone screen mirroring over the legacy AirPlay transport |
| Video and audio | H.264 video with ALAC/AAC-ELD audio |
| Experimental HEVC | Optional H.265 support through the GStreamer or FFmpeg video backend |
| Hardware decoding | Automatic or selected Windows DXGI GPU adapter |
| Reliable playback | Preserves encoded reference frames and applies TCP backpressure by default |
| Adaptive display | Detects the actual stream size, frame rate, and codec, including portrait video |
| Desktop workflow | Bilingual UI, detachable video window, full screen, system tray, themes, and display-awake playback |

## Quick Start

### Packaged Release

The Windows x64 release is available as a standard installer and a portable ZIP. Both include a compact Java 25 runtime, GStreamer, configuration, and documentation; Java and GStreamer do not need to be installed separately.

1. Run the setup EXE, or extract the portable ZIP.
2. Start **Java AirPlay Receiver** from the Start Menu or run `JavaAirPlayReceiver.exe` from the portable directory.
3. Open Control Center on the iPhone.
4. Choose **Screen Mirroring**, then select the receiver name shown by the application.

Release binaries are unsigned, so Windows SmartScreen may request confirmation. Both distributions include `start.bat` as a diagnostic fallback.

### From Source

Source builds require Windows, JDK 25, and network access for Gradle dependencies. The startup script can prepare a project-local GStreamer runtime.

```powershell
./gradlew.bat test
./gradlew.bat :player:app:bootJar
start.bat
```

The executable JAR is generated at:

```text
player/app/build/libs/java-airplay-server-1.0.9.jar
```

## Desktop UI

The integrated GStreamer window provides receiver status, video details, and settings.

- Switch between English and Chinese from **Settings → Language** or the system tray.
- Detach the video Canvas into a separate window and toggle full screen with `ESC`.
- Keep the receiver reachable through **Open Java AirPlay**, **Show video window**, **Full screen**, **Language**, and **Quit** tray actions.
- Select a detected Windows DXGI GPU by its real name while storing its native adapter index.
- Use **Save & Restart** to validate and apply network and playback settings.

## Configuration

Settings are stored in:

```text
${user.home}/.java-airplay/application.properties
```

Command-line properties take precedence over saved desktop settings. Use `config/application.example.properties` as the complete configuration template.

Common settings:

| Property | Default | Description |
|---|---:|---|
| `airplay.serverName` | `Java AirPlay` | Name shown in the iPhone Screen Mirroring list |
| `airplay.width` / `airplay.height` | `1920` / `1080` | Declared display capability; use `auto` to detect the stream |
| `airplay.fps` | `60` | Declared maximum frame rate; the sender rate is measured from timestamps |
| `airplay.requirePairing` | `true` | Require AirPlay pairing before accepting media |
| `airplay.hevc` | `false` | Enable experimental HEVC negotiation |
| `player.implementation` | `gstreamer` | Select the playback backend; `ffmpeg` requires `ffplay` on `PATH` and uses GStreamer for audio |
| `player.gstreamer.renderMode` | `balanced` | Choose balanced, quality, or low-latency presentation |
| `player.gstreamer.videoQueueDepth` | `2` | Number of encoded video access units buffered in Java |
| `player.gstreamer.aggressiveFrameDropping` | `false` | Experimental mode that drops encoded frames under pressure |

The safe video path is enabled by default. Keep `player.gstreamer.aggressiveFrameDropping=false` unless the lowest possible latency is more important than picture integrity. Dropping H.264/HEVC reference frames can cause block corruption until the sender reconnects.

## Playback Backend Matrix

`player.implementation` selects only the media renderer; the server enforces pairing, session validation, and exclusive mirroring ownership for every backend.

| Backend | H.264 mirroring | Experimental HEVC | ALAC / AAC-ELD audio | HTTP/HLS playback | Desktop experience | GPU selection |
|---|---|---|---|---|---|---|
| `gstreamer` (default) | Yes | Yes, with `airplay.hevc=true` and the required plugins | Yes | Yes, through `playbin3` | Integrated receiver and settings UI when `player.gstreamer.swing=true` | Automatic or explicit Windows DXGI adapter |
| `ffmpeg` | Yes, through `ffplay` | Yes, with `airplay.hevc=true` | Yes, through GStreamer | No | Separate full-screen FFplay window | Not exposed by the application |
| `vlc` | Yes | No | No | No | Basic embedded VLC window | Not exposed by the application |
| `h264-dump` | Writes only to `dump.h264` | No | No | No | None | Not applicable |

Packaged Windows distributions use GStreamer by default. Source builds using `ffmpeg` require `ffplay` on `PATH`; the GStreamer audio plugins are still required. The `vlc` backend requires a compatible local libVLC installation. The diagnostic-only `h264-dump` backend writes `dump.h264` to the process working directory.

## Mirroring Session Takeover

The receiver allows only one active RTSP screen-mirroring owner. There is no confirmation prompt: another valid control connection automatically takes ownership when it starts setting up timing, video, or audio.

A handoff from device A to device B intentionally follows `revoke → stop → drain → disconnect`:

1. Revoke A's control and media leases, rejecting subsequent mirroring operations and media packets.
2. Stop A's video, audio, and timing sources before waiting for player callbacks.
3. Wait only for callbacks already running under A's revoked leases.
4. Notify the playback backend once per previously connected media stream, close A's stale control channel, then continue B's setup.

Device A may therefore show a disconnection as soon as B starts mirroring. Video from A is never combined with audio from B. Late frames, delayed teardown, and disconnect callbacks from A cannot stop B, even during a rapid reconnect or when a new control connection reuses the same AirPlay session ID.

This policy applies only to RTSP mirroring timing, video, and audio streams. HTTP/HLS playback retains its separate lifecycle and does not participate in mirroring takeover.

## Network and Security Notes

- Keep the iPhone and receiver on the same multicast-capable LAN.
- UDP port `5353` must be available for mDNS discovery.
- Pairing is enabled by default. Keep the identity file private and do not delete it unless the receiver should pair as a new device.
- Use the receiver only on trusted networks.
- This project implements legacy screen mirroring, not full AirPlay 2. Protected Apple TV content and multi-room features are out of scope.

## Build and Test

```powershell
./gradlew.bat test
./gradlew.bat :player:app:bootJar
```

From the repository root, build the complete Windows x64 distributions. Packaging requires Python 3 and Inno Setup 6.5 or newer.

```powershell
./gradlew.bat release
```

Generated files:

```text
release/java-airplay-<version>.<yyMMdd>-windows-x64.zip
release/java-airplay-<version>.<yyMMdd>-windows-x64.zip.sha256
release/java-airplay-<version>.<yyMMdd>-windows-x64-setup.exe
release/java-airplay-<version>.<yyMMdd>-windows-x64-setup.exe.sha256
```

The Windows-only release task builds a native no-console launcher, portable ZIP, standard installer, and checksums. User settings remain under `%USERPROFILE%\.java-airplay` and survive upgrades or uninstall.

## Modules

| Module | Purpose |
|---|---|
| `lib` | Pairing, FairPlay, identity, Bonjour, and media helpers |
| `server` | AirPlay control, video, audio, timing, and retransmission |
| `player:gstreamer` | Desktop UI and GStreamer playback backend |
| `player:app` | Spring Boot application, settings, and system tray |
| `player:ffmpeg` | FFplay H.264/HEVC video with the shared GStreamer audio backend |
| `player:vlc` | Alternate H.264 playback backend |
| `player:h264-dump` | H.264 debugging output backend |

## Upstream Lineage and Acknowledgements

This project derives from Sergei Fedorov's MIT-licensed [serezhka/java-airplay](https://github.com/serezhka/java-airplay). Its Windows development also draws on [Druadach/java-airplay](https://github.com/Druadach/java-airplay); see the fork's [changes from the original project](https://github.com/Druadach/java-airplay/compare/serezhka%3Ajava-airplay%3Amain...main).

The following areas were informed by, adapted from, or reimplemented from that fork:

- RTP audio sequence rollover, bounded jitter/reordering handling, and GStreamer and Netty buffer-lifecycle fixes ([5cf34f3](https://github.com/Druadach/java-airplay/commit/5cf34f3)).
- FFmpeg video playback with ALAC/AAC-ELD audio routed through GStreamer ([61ce455](https://github.com/Druadach/java-airplay/commit/61ce455)).
- Exclusive AirPlay session takeover with stale-stream and late-packet isolation ([be16964](https://github.com/Druadach/java-airplay/commit/be16964)).
- System tray controls for full-screen mode at runtime and reliable application shutdown ([87cd8bd](https://github.com/Druadach/java-airplay/commit/87cd8bd), [373951f](https://github.com/Druadach/java-airplay/commit/373951f)).
- Bilingual Windows configuration and controls ([2c2ede0](https://github.com/Druadach/java-airplay/commit/2c2ede0)).
- Windows CI and self-contained distribution concepts, including a bundled runtime, installer, and portable ZIP ([dae1376](https://github.com/Druadach/java-airplay/commit/dae1376), [0b1b610](https://github.com/Druadach/java-airplay/commit/0b1b610)).

These implementations have since been integrated, refactored, tested, and extended, so they may differ from the referenced patches. The links document technical lineage, not endorsement. Copyright remains with the respective authors and contributors.

## License

Released under the [MIT License](LICENSE). Review applicable third-party and redistribution licenses before bundling or redistributing GStreamer.
