package wtf.nanoka.airplay.player.gstreamer.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ThemeManagerTest {

    @Test
    void parsesPersistedThemeModesAndFallsBackToSystem() {
        assertEquals(ThemeManager.ThemeMode.SYSTEM, ThemeManager.ThemeMode.fromPreference("system"));
        assertEquals(ThemeManager.ThemeMode.LIGHT, ThemeManager.ThemeMode.fromPreference("LIGHT"));
        assertEquals(ThemeManager.ThemeMode.DARK, ThemeManager.ThemeMode.fromPreference("Dark"));
        assertEquals(ThemeManager.ThemeMode.SYSTEM, ThemeManager.ThemeMode.fromPreference("unknown"));
        assertEquals(ThemeManager.ThemeMode.SYSTEM, ThemeManager.ThemeMode.fromPreference(null));
    }
}
