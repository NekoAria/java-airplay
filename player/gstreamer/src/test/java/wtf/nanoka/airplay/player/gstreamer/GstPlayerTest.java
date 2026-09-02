package wtf.nanoka.airplay.player.gstreamer;

import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pipeline;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

@Tag(GstTestSupport.NATIVE_GSTREAMER_TAG)
@Disabled("Requires a native GStreamer installation and display")
class GstPlayerTest {

    @Test
    void playsNativeTestPattern() {
        GstTestSupport.initialize("BasicPipeline");
        GstTestSupport.assumeElementFactories("videotestsrc", "autovideosink");

        try (Pipeline pipeline = (Pipeline) Gst.parseLaunch("videotestsrc ! autovideosink")) {
            pipeline.play();
            TimeUnit.SECONDS.sleep(3);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
