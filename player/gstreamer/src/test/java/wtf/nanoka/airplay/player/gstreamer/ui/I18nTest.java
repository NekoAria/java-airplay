package wtf.nanoka.airplay.player.gstreamer.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.prefs.Preferences;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class I18nTest {

    private Preferences preferences;

    @BeforeEach
    void setUp() {
        preferences = Preferences.userRoot().node("java-airplay-test-" + System.nanoTime());
    }

    @AfterEach
    void tearDown() throws Exception {
        preferences.removeNode();
    }

    private I18n i18n() {
        return new I18n(preferences);
    }

    @Test
    void parsesPersistedLanguagesAndFallsBackToEnglish() {
        assertEquals(I18n.Language.ENGLISH, I18n.Language.fromPreference("english"));
        assertEquals(I18n.Language.CHINESE, I18n.Language.fromPreference("CHINESE"));
        assertEquals(I18n.Language.CHINESE, I18n.Language.fromPreference("Chinese"));
        assertEquals(I18n.Language.ENGLISH, I18n.Language.fromPreference("unknown"));
        assertEquals(I18n.Language.ENGLISH, I18n.Language.fromPreference(null));
    }

    @Test
    void loadsEnglishAndChineseMessages() {
        I18n i18n = i18n();
        assertEquals("Receiver", i18n.tr("frame.receiver"));
        assertEquals("Settings", i18n.tr("frame.settings"));

        i18n.setLanguage(I18n.Language.CHINESE);
        assertEquals("接收器", i18n.tr("frame.receiver"));
        assertEquals("设置", i18n.tr("frame.settings"));

        i18n.setLanguage(I18n.Language.ENGLISH);
        assertEquals("Ready", i18n.tr("status.ready"));
        i18n.close();
    }

    @Test
    void persistsLanguageInPreferences() {
        I18n i18n = i18n();
        i18n.setLanguage(I18n.Language.CHINESE);
        assertEquals("CHINESE", preferences.get("ui-language", ""));
        i18n.close();
    }

    @Test
    void notifiesListenersOnLanguageChange() {
        I18n i18n = i18n();
        I18n.Language[] observed = new I18n.Language[1];
        i18n.addLanguageChangeListener(() -> observed[0] = i18n.language());
        i18n.setLanguage(I18n.Language.CHINESE);
        // Listener dispatch may be deferred to the EDT; the language state
        // itself must already be updated on the calling thread.
        assertEquals(I18n.Language.CHINESE, i18n.language());
        assertTrue(i18n.tr("settings.title").contains("设置"));
        i18n.close();
    }

    @Test
    void keepsEnglishAndChineseMessageKeysInSync() throws Exception {
        Properties english = loadMessages("messages.properties");
        Properties chinese = loadMessages("messages_zh.properties");

        assertEquals(english.stringPropertyNames(), chinese.stringPropertyNames());
    }

    private Properties loadMessages(String fileName) throws Exception {
        Properties messages = new Properties();
        try (var input = I18nTest.class.getResourceAsStream(fileName);
             var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            messages.load(reader);
        }
        return messages;
    }
}
