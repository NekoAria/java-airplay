package wtf.nanoka.airplay.player.gstreamer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wtf.nanoka.airplay.player.gstreamer.GstPlayerUtils.DebugConfiguration;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GstPlayerUtilsTest {

    @TempDir
    Path userHome;

    @Test
    void defaultsToDebugLevelThreeWithoutFileLogging() {
        assertEquals(
                new DebugConfiguration("3", null, false),
                resolveConfiguration(Map.of(), Map.of()));
    }

    @Test
    void systemPropertiesOverrideEnvironmentAndResolveRelativeFileUnderUserHome() {
        var properties = Map.of(
                "airplay.gst.debug", "*:4,GST_CAPS:6",
                "airplay.gst.debug.file", "diagnostics/gstreamer-%p.log");
        var environment = Map.of(
                "GST_DEBUG", "1",
                "GST_DEBUG_FILE", "operator.log");
        var expectedFile = userHome.resolve(".java-airplay/diagnostics/gstreamer-%p.log")
                .toAbsolutePath().normalize().toString();

        assertEquals(
                new DebugConfiguration("*:4,GST_CAPS:6", expectedFile, true),
                resolveConfiguration(properties, environment));
    }

    @Test
    void usesExistingGStreamerEnvironmentWhenPropertiesAreAbsent() {
        var environment = Map.of(
                "GST_DEBUG", "2,audio*:6",
                "GST_DEBUG_FILE", "operator.log");

        assertEquals(
                new DebugConfiguration("2,audio*:6", "operator.log", false),
                resolveConfiguration(Map.of(), environment));
    }

    @Test
    void blankFilePropertyDisablesEnvironmentFileLogging() {
        var properties = Map.of("airplay.gst.debug.file", " ");
        var environment = Map.of("GST_DEBUG_FILE", "operator.log");

        assertEquals(
                new DebugConfiguration("3", null, true),
                resolveConfiguration(properties, environment));
    }

    @Test
    void rejectsRelativeFileOutsideUserLogDirectory() {
        var properties = Map.of("airplay.gst.debug.file", "../outside.log");

        assertThrows(
                IllegalArgumentException.class,
                () -> resolveConfiguration(properties, Map.of()));
    }

    private DebugConfiguration resolveConfiguration(
            Map<String, String> properties,
            Map<String, String> environment) {
        return GstPlayerUtils.resolveDebugConfiguration(
                properties::get, environment::get, userHome);
    }
}
