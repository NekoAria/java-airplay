# Windows packaging

This directory contains only reproducible packaging sources. Java, GStreamer,
the compiled launcher, portable archives, and installers are generated at build
time and are not committed.

## Outputs

On Windows x64 with JDK 25, Python 3, and Inno Setup 6.5 or newer installed:

```powershell
.\gradlew.bat release -Pversion=1.0.10
```

The version supplied to Gradle is combined with the current UTC build date.
The `release/` directory receives:

```text
java-airplay-<version>.<yyMMdd>-windows-x64.zip
java-airplay-<version>.<yyMMdd>-windows-x64.zip.sha256
java-airplay-<version>.<yyMMdd>-windows-x64-setup.exe
java-airplay-<version>.<yyMMdd>-windows-x64-setup.exe.sha256
```

The ZIP is the portable distribution. Both distributions contain a compact
Java 25 runtime, the required GStreamer runtime, a stable
`java-airplay-server.jar`, and `JavaAirPlayReceiver.exe`.

## Build components

- `AirPlayReceiver.cs` compiles with the Windows .NET Framework C# compiler as
  an x64 `winexe`, so launching the application does not open a console window.
  It uses `runtime/bin/javaw.exe`, configures the bundled GStreamer paths, and
  forwards command-line arguments to the server JAR.
- `make_icon.py` generates the multi-resolution launcher and installer icon
  using only the Python standard library.
- `installer.iss` installs the staged payload under Program Files and creates
  Start Menu and optional desktop shortcuts. Gradle supplies its version,
  staging path, output path, and icon path through Inno Setup defines.
- `ChineseSimplified.isl` is a vendored Simplified Chinese translation from
  [`jrsoftware/issrc@is-6_7_3`](https://github.com/jrsoftware/issrc/tree/is-6_7_3/Files/Languages/Unofficial).
  Inno Setup 6 omits unofficial translations; the upstream license is in
  `ChineseSimplified.LICENSE.txt`.
- `validate_distribution.ps1` checks the stable payload layout, bundled Java,
  native launcher, plugin scanner, and all GStreamer codecs required by the
  application.

Set `CSC` or `INNO_SETUP_COMPILER` when the tools are installed in nonstandard
locations. Standard .NET Framework and Inno Setup 6 locations are detected
automatically.

Useful individual tasks:

```powershell
.\gradlew.bat validateWindowsPackagingSources
.\gradlew.bat stageWindowsRelease validateStagedWindowsDistribution
.\gradlew.bat buildPortableRelease checksumPortableRelease
.\gradlew.bat buildWindowsInstaller checksumWindowsInstaller
```

`JavaAirPlayReceiver.exe --validate-installation` checks the staged or installed
paths and launch arguments without starting Java. The distribution validator
also loads every required bundled GStreamer plugin. The release workflow runs
both checks for the portable archive and a silent installation before publishing
assets. It also runs the full GStreamer test suite on Windows after the runtime
has been prepared; the packaging task itself excludes that crash-prone native
suite. For a non-CI release build, validate it in a separate invocation:

```powershell
$env:GSTREAMER_1_0_ROOT_MSVC_X86_64 = (Resolve-Path .runtime\gstreamer).Path
.\gradlew.bat :player:gstreamer:test --rerun-tasks
```

## Security and user data

The installer does not grant normal users write access to the Program Files
application directory. Mutable settings, receiver identity, and logs remain in
`%USERPROFILE%\.java-airplay`; uninstalling or upgrading the application does
not remove that directory.
