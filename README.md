# Java AirPlay Receiver

[简体中文](README.zh-CN.md)

Java 25 desktop receiver for iPhone Screen Mirroring on a local network. It receives H.264 video, ALAC/AAC-ELD audio, and supports opt-in experimental HEVC through GStreamer.

## Quick Start

Requirements: Windows, JDK 25, and GStreamer 1.28.5 (the startup script can prepare a project-local runtime).

```bat
start.bat
```

Open iPhone Control Center, choose Screen Mirroring, then select the receiver name shown by the application.

Build and test from source:

```powershell
.\gradlew.bat test
.\gradlew.bat :player:app:bootJar
```

The executable JAR is `player/app/build/libs/java-airplay-server-1.0.9.jar`.

## Desktop UI

The integrated GStreamer window provides receiver status and settings. It can select a detected Windows DXGI GPU by its real name, while storing the corresponding DXGI index in configuration. `Save & Restart` validates and relaunches the current process so network and pipeline changes take effect.

- **Language**: English / 中文, switched live from Settings → Language or the tray menu (persisted per user).
- **Detached video window**: in integrated-window mode the Receiver page video can be detached into its own window (tray: Show video window) and toggled full screen (ESC exits).
- The system tray also keeps **Open Java AirPlay**, **Show video window**, **Full screen**, **Language**, and **Quit**, so the GUI stays reachable when the integrated window is closed.

Settings are stored in:

```text
${user.home}/.java-airplay/application.properties
```

Command-line properties override saved desktop settings. Copy `config/application.example.properties` for a documented configuration template.

## Notes

- Keep the iPhone and receiver on the same multicast-capable LAN. UDP 5353 must be available for discovery.
- Pairing is enabled by default. Keep the identity file private and do not delete it unless the receiver should pair as a new device.
- HEVC is experimental and disabled by default. Enable `airplay.hevc=true` only with the GStreamer player.
- This project implements the legacy screen-mirroring transport, not full AirPlay 2. Protected Apple TV content and multi-room features are out of scope.

## Modules

| Module | Purpose |
|---|---|
| `lib` | Pairing, FairPlay, identity, Bonjour, media helpers |
| `server` | AirPlay control, video, audio, timing, retransmission |
| `player:gstreamer` | Desktop UI and GStreamer playback |
| `player:app` | Spring Boot application and settings |

Use this receiver only on trusted networks and review applicable licenses before redistributing it.
