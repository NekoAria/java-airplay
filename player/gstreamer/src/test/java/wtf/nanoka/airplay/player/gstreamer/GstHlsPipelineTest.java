package wtf.nanoka.airplay.player.gstreamer;

import org.freedesktop.gstreamer.ElementFactory;
import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pipeline;
import org.freedesktop.gstreamer.Version;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GstHlsPipelineTest {

    @Test
    void configuresTheTopLevelPlaybin3Element() {
        try {
            GstPlayerUtils.configurePaths();
            Gst.init(Version.of(1, 10), "HlsPipelineTest");
        } catch (Throwable error) {
            Assumptions.assumeTrue(false, "Native GStreamer is unavailable: " + error.getMessage());
        }
        Assumptions.assumeTrue(ElementFactory.find("playbin3") != null, "playbin3 is unavailable");

        try (Pipeline pipeline = (Pipeline) Gst.parseLaunch("playbin3")) {
            pipeline.set("uri", "http://localhost:7000/playlist/master.m3u8");
            assertEquals("http://localhost:7000/playlist/master.m3u8", pipeline.get("uri"));
        }
    }
}
