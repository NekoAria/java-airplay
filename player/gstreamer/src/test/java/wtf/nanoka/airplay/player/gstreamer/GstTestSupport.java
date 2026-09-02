package wtf.nanoka.airplay.player.gstreamer;

import org.freedesktop.gstreamer.ElementFactory;
import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Version;
import org.junit.jupiter.api.Assumptions;

import java.util.ArrayList;

final class GstTestSupport {

    static final String NATIVE_GSTREAMER_TAG = "native-gstreamer";
    private static final String REQUIRE_NATIVE_GSTREAMER_TESTS_ENV =
            "JAVA_AIRPLAY_REQUIRE_NATIVE_GSTREAMER_TESTS";

    private GstTestSupport() {
    }

    static void initialize(String applicationName) {
        try {
            GstPlayerUtils.configurePaths();
            Gst.init(Version.of(1, 10), applicationName);
        } catch (RuntimeException | LinkageError error) {
            failOrAbort(
                    isNativeGStreamerRequired(),
                    "Native GStreamer initialization failed: " + error,
                    error);
        }
    }

    static boolean isElementFactoryAvailable(String factoryName) {
        try {
            return ElementFactory.find(factoryName) != null;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    static void assumeElementFactories(String... factoryNames) {
        var unavailableFactories = new ArrayList<String>();
        for (String factoryName : factoryNames) {
            if (!isElementFactoryAvailable(factoryName)) {
                unavailableFactories.add(factoryName);
            }
        }
        if (!unavailableFactories.isEmpty()) {
            failOrAbort(
                    isNativeGStreamerRequired(),
                    "Required GStreamer element factories are unavailable: "
                            + String.join(", ", unavailableFactories),
                    null);
        }
    }

    static void failOrAbort(boolean required, String message, Throwable cause) {
        if (required) {
            throw new AssertionError(message, cause);
        }
        Assumptions.abort(message);
    }

    private static boolean isNativeGStreamerRequired() {
        return Boolean.parseBoolean(System.getenv(REQUIRE_NATIVE_GSTREAMER_TESTS_ENV));
    }
}
