# Release Output

Run the following command from the repository root on Windows x64 with JDK 25,
Python 3, and Inno Setup 6.5 or newer:

```bat
gradlew.bat release
```

Generated files:

```text
release/java-airplay-<version>.<yyMMdd>-windows-x64.zip
release/java-airplay-<version>.<yyMMdd>-windows-x64.zip.sha256
release/java-airplay-<version>.<yyMMdd>-windows-x64-setup.exe
release/java-airplay-<version>.<yyMMdd>-windows-x64-setup.exe.sha256
```

The setup EXE installs Java AirPlay under Program Files with Start Menu and
optional desktop shortcuts. The ZIP is the portable distribution and can be
extracted anywhere. Both include:

- `JavaAirPlayReceiver.exe`, a native no-console launcher;
- the executable server JAR under the stable name `java-airplay-server.jar`;
- a compact Java 25 runtime;
- GStreamer and its required plugins;
- fallback startup scripts, editable configuration examples, bilingual
  documentation, and licenses.

Installed and portable users normally run `JavaAirPlayReceiver.exe`.
`start.bat` remains available for diagnostics. Java and GStreamer do not need
to be installed separately. Release binaries are currently unsigned, so
Windows SmartScreen may request confirmation.

Application settings, identity, and logs are stored in
`%USERPROFILE%\.java-airplay`. The installer does not make its Program Files
directory user-writable, and uninstalling the application preserves user data.

Large ZIP, EXE, checksum, staging, runtime, and launcher files are generated
artifacts and are excluded from Git.
