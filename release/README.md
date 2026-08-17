# Release Output

Run the following command from the repository root on Windows x64 with JDK 25:

```bat
gradlew.bat release
```

Generated files:

```text
release/java-airplay-<version>-windows-x64.zip
release/java-airplay-<version>-windows-x64.zip.sha256
```

The ZIP contains the executable JAR, a compact Java 25 runtime, GStreamer, startup scripts, editable configuration, bilingual documentation, and licenses. End users extract the ZIP and run `start.bat`; they do not need to install Java or GStreamer.

The large ZIP and checksum are build artifacts and are excluded from Git.
