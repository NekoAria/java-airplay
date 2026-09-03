/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2021 Neil C Smith - Codelerity Ltd.
 *
 * Copying and distribution of this file, with or without modification,
 * are permitted in any medium without royalty provided the copyright
 * notice and this notice are preserved. This file is offered as-is,
 * without any warranty.
 *
 */
package wtf.nanoka.airplay.player.gstreamer;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Kernel32;
import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Version;
import org.freedesktop.gstreamer.glib.GLib;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Utility methods for use in examples.
 */
class GstPlayerUtils {

    private static final String DEBUG_SPEC_PROPERTY = "airplay.gst.debug";
    private static final String DEBUG_FILE_PROPERTY = "airplay.gst.debug.file";
    private static final String GST_DEBUG = "GST_DEBUG";
    private static final String GST_DEBUG_FILE = "GST_DEBUG_FILE";
    private static final String GST_DEBUG_NO_COLOR = "GST_DEBUG_NO_COLOR";
    private static final String DEFAULT_DEBUG_SPEC = "3";

    private static boolean initialized;

    private GstPlayerUtils() {
    }

    static void initialize() {
        initialize("BasicPipeline");
    }

    static synchronized void initialize(String applicationName) {
        if (initialized) {
            return;
        }
        configurePaths();
        configureDebugging();
        Gst.init(Version.of(1, 10), applicationName);
        initialized = true;
    }

    /**
     * Configures paths to the GStreamer libraries. On Windows queries various
     * GStreamer environment variables, and then sets up the PATH environment
     * variable. On macOS, adds the location to jna.library.path (macOS binaries
     * link to each other). On both, the gstreamer.path system property can be
     * used to override. On Linux, assumes GStreamer is in the path already.
     */
    static void configurePaths() {
        if (Platform.isWindows()) {
            String gstPath = System.getProperty("gstreamer.path", findWindowsLocation());
            if (!gstPath.isEmpty()) {
                String systemPath = System.getenv("PATH");
                if (systemPath == null || systemPath.trim().isEmpty()) {
                    Kernel32.INSTANCE.SetEnvironmentVariable("PATH", gstPath);
                } else {
                    Kernel32.INSTANCE.SetEnvironmentVariable("PATH", gstPath
                            + File.pathSeparator + systemPath);
                }
            }
        } else if (Platform.isMac()) {
            String gstPath = System.getProperty("gstreamer.path",
                    "/Library/Frameworks/GStreamer.framework/Libraries/");
            if (!gstPath.isEmpty()) {
                String jnaPath = System.getProperty("jna.library.path", "").trim();
                if (jnaPath.isEmpty()) {
                    System.setProperty("jna.library.path", gstPath);
                } else {
                    System.setProperty("jna.library.path", jnaPath + File.pathSeparator + gstPath);
                }
            }

        }
    }

    private static void configureDebugging() {
        var configuration = resolveDebugConfiguration(
                System::getProperty, GLib::getEnv, Path.of(System.getProperty("user.home")));
        GLib.setEnv(GST_DEBUG, configuration.debugSpec(), true);

        var debugFile = configuration.debugFile();
        if (configuration.hasDebugFileOverride()) {
            applyDebugFileOverride(debugFile);
        }
        if (debugFile != null) {
            GLib.setEnv(GST_DEBUG_NO_COLOR, "1", false);
        }
    }

    private static void applyDebugFileOverride(String debugFile) {
        if (debugFile == null) {
            GLib.unsetEnv(GST_DEBUG_FILE);
            return;
        }

        var parent = Path.of(debugFile).getParent();
        if (parent != null) {
            createDebugLogDirectory(parent);
        }
        GLib.setEnv(GST_DEBUG_FILE, debugFile, true);
    }

    private static void createDebugLogDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Unable to create GStreamer debug log directory: " + directory, e);
        }
    }

    static DebugConfiguration resolveDebugConfiguration(
            Function<String, String> propertyLookup,
            Function<String, String> environmentLookup,
            Path userHome) {
        var propertyDebug = trimToNull(propertyLookup.apply(DEBUG_SPEC_PROPERTY));
        var environmentDebug = trimToNull(environmentLookup.apply(GST_DEBUG));
        String debugSpec;
        if (propertyDebug != null) {
            debugSpec = propertyDebug;
        } else if (environmentDebug != null) {
            debugSpec = environmentDebug;
        } else {
            debugSpec = DEFAULT_DEBUG_SPEC;
        }

        var configuredDebugFile = propertyLookup.apply(DEBUG_FILE_PROPERTY);
        var hasDebugFileOverride = configuredDebugFile != null;
        var debugFile = hasDebugFileOverride
                ? resolveDebugFilePath(trimToNull(configuredDebugFile), userHome)
                : trimToNull(environmentLookup.apply(GST_DEBUG_FILE));
        return new DebugConfiguration(debugSpec, debugFile, hasDebugFileOverride);
    }

    private static String resolveDebugFilePath(String configuredPath, Path userHome) {
        if (configuredPath == null) {
            return null;
        }
        var debugFile = Path.of(configuredPath);
        if (debugFile.isAbsolute()) {
            return debugFile.normalize().toString();
        }
        var logDirectory = userHome.resolve(".java-airplay").toAbsolutePath().normalize();
        var resolvedFile = logDirectory.resolve(debugFile).normalize();
        if (!resolvedFile.startsWith(logDirectory)) {
            throw new IllegalArgumentException(
                    "Relative GStreamer debug log path must remain inside " + logDirectory);
        }
        return resolvedFile.toString();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Query over a stream of possible environment variables for GStreamer
     * location, filtering on the first non-null result, and adding \bin\ to the
     * value.
     *
     * @return location or empty string
     */
    static String findWindowsLocation() {
        if (!Platform.is64Bit()) {
            return "";
        }

        Stream<String> configuredLocations = Stream.of("GSTREAMER_1_0_ROOT_MSVC_X86_64",
                        "GSTREAMER_1_0_ROOT_MINGW_X86_64",
                        "GSTREAMER_1_0_ROOT_X86_64")
                .map(System::getenv)
                .filter(p -> p != null && !p.isBlank());
        Stream<String> standardLocations = Stream.of(
                        System.getenv("LOCALAPPDATA") == null ? null
                                : Path.of(System.getenv("LOCALAPPDATA"), "Programs", "gstreamer", "1.0", "msvc_x86_64").toString(),
                        System.getenv("ProgramFiles") == null ? null
                                : Path.of(System.getenv("ProgramFiles"), "gstreamer", "1.0", "msvc_x86_64").toString())
                .filter(p -> p != null && !p.isBlank());
        return Stream.concat(configuredLocations, standardLocations)
                .map(Path::of)
                .filter(Files::isDirectory)
                .map(path -> path.resolve("bin").toString() + File.separator)
                .findFirst().orElse("");
    }

    record DebugConfiguration(String debugSpec, String debugFile, boolean hasDebugFileOverride) {
    }
}
