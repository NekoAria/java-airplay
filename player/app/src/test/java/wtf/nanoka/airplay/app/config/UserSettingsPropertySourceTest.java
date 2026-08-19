package wtf.nanoka.airplay.app.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserSettingsPropertySourceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void desktopSettingsOverrideExternalConfigButNotRuntimeOverrides() throws Exception {
        Path settingsFile = temporaryDirectory.resolve("application.properties");
        Files.writeString(settingsFile, "airplay.serverName=Desktop\n", StandardCharsets.UTF_8);
        String previous = System.getProperty("java-airplay.settings-file");
        String previousServerName = System.getProperty("airplay.serverName");
        System.setProperty("java-airplay.settings-file", settingsFile.toString());
        try {
            var context = new GenericApplicationContext();
            context.getEnvironment().getPropertySources().addLast(new MapPropertySource(
                    "externalConfig", Map.of("airplay.serverName", "External")));

            new UserSettingsPropertySource().initialize(context);

            assertEquals("Desktop", context.getEnvironment().getProperty("airplay.serverName"));

            System.setProperty("airplay.serverName", "SystemProperty");
            assertEquals("SystemProperty", context.getEnvironment().getProperty("airplay.serverName"));

            context.getEnvironment().getPropertySources().addFirst(new SimpleCommandLinePropertySource(
                    "--airplay.serverName=CommandLine"));
            new UserSettingsPropertySource().initialize(context);

            assertEquals("CommandLine", context.getEnvironment().getProperty("airplay.serverName"));
        } finally {
            if (previous == null) {
                System.clearProperty("java-airplay.settings-file");
            } else {
                System.setProperty("java-airplay.settings-file", previous);
            }
            if (previousServerName == null) {
                System.clearProperty("airplay.serverName");
            } else {
                System.setProperty("airplay.serverName", previousServerName);
            }
        }
    }

    @Test
    void commandLineCanSelectTheSettingsFile() throws Exception {
        Path settingsFile = temporaryDirectory.resolve("custom.properties");
        Files.writeString(settingsFile, "airplay.serverName=Custom\n", StandardCharsets.UTF_8);
        var context = new GenericApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new SimpleCommandLinePropertySource(
                "--java-airplay.settings-file=" + settingsFile));

        new UserSettingsPropertySource().initialize(context);

        assertEquals("Custom", context.getEnvironment().getProperty("airplay.serverName"));
    }

    @Test
    void rejectsASettingsPathWithoutAParentDirectory() {
        var context = new GenericApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new SimpleCommandLinePropertySource(
                "--java-airplay.settings-file=" + temporaryDirectory.getRoot()));

        assertThrows(IllegalArgumentException.class,
                () -> new UserSettingsPropertySource().initialize(context));
    }
}
