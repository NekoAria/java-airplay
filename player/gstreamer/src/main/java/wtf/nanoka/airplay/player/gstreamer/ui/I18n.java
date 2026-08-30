package wtf.nanoka.airplay.player.gstreamer.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;
import java.util.prefs.Preferences;

/**
 * UI localization. Supports English and Simplified Chinese, persisted per user
 * in {@code Preferences}. The default is {@link Language#ENGLISH} so existing
 * users keep the UI they had before this feature existed.
 */
public final class I18n {

    private static final String LANGUAGE_PREFERENCE = "ui-language";
    private static final String BUNDLE_NAME = "wtf.nanoka.airplay.player.gstreamer.ui.messages";

    private final Preferences preferences;
    private final List<Runnable> listeners = new ArrayList<>();

    private Language language;

    public I18n() {
        this(Preferences.userNodeForPackage(I18n.class));
    }

    I18n(Preferences preferences) {
        this.preferences = preferences;
        language = Language.fromPreference(preferences.get(LANGUAGE_PREFERENCE, Language.ENGLISH.name()));
    }

    public Language language() {
        return language;
    }

    public String tr(String key) {
        return bundle(language.locale).getString(key);
    }

    /** Adds a callback invoked on the EDT when the language changes. */
    public void addLanguageChangeListener(Runnable listener) {
        listeners.add(listener);
    }

    /** Selects a language, persists it, and notifies listeners on the EDT. */
    public void setLanguage(Language language) {
        if (this.language == language) {
            return;
        }
        this.language = language;
        preferences.put(LANGUAGE_PREFERENCE, language.name());
        List.copyOf(listeners).forEach(listener -> {
            if (javax.swing.SwingUtilities.isEventDispatchThread()) {
                listener.run();
            } else {
                javax.swing.SwingUtilities.invokeLater(listener);
            }
        });
    }

    public void close() {
        listeners.clear();
    }

    private static ResourceBundle bundle(Locale locale) {
        try {
            return ResourceBundle.getBundle(BUNDLE_NAME, locale, new Utf8Control());
        } catch (MissingResourceException missing) {
            // Fall back to the English copy shipped with the application.
            return ResourceBundle.getBundle(BUNDLE_NAME, Locale.ROOT, new Utf8Control());
        }
    }

    /**
     * Loads {@code .properties} bundles as UTF-8. ResourceBundle's default
     * ISO-8859-1 reading would corrupt the Chinese resource files.
     */
    private static final class Utf8Control extends ResourceBundle.Control {

        @Override
        public ResourceBundle newBundle(String baseName, Locale locale, String format,
                                        ClassLoader loader, boolean reload) throws IOException {
            String resourceName = toResourceName(toBundleName(baseName, locale), "properties");
            try (InputStream stream = loader.getResourceAsStream(resourceName)) {
                if (stream == null) {
                    return null;
                }
                return new PropertyResourceBundle(new InputStreamReader(stream, StandardCharsets.UTF_8));
            }
        }
    }

    public enum Language {
        ENGLISH("English", Locale.ROOT),
        CHINESE("中文", Locale.SIMPLIFIED_CHINESE);

        private final String label;
        private final Locale locale;

        Language(String label, Locale locale) {
            this.label = label;
            this.locale = locale;
        }

        public String label() {
            return label;
        }

        public static Language fromPreference(String value) {
            try {
                return Language.valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException | NullPointerException ignored) {
                return ENGLISH;
            }
        }
    }
}