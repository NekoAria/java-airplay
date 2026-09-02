package wtf.nanoka.airplay.player.gstreamer;

import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pipeline;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag(GstTestSupport.NATIVE_GSTREAMER_TAG)
class GstHlsPipelineTest {

    @Test
    void configuresTheTopLevelPlaybin3Element() {
        GstTestSupport.initialize("HlsPipelineTest");
        GstTestSupport.assumeElementFactories("playbin3");

        try (Pipeline pipeline = (Pipeline) Gst.parseLaunch("playbin3")) {
            pipeline.set("uri", "http://localhost:7000/playlist/master.m3u8");
            assertEquals("http://localhost:7000/playlist/master.m3u8", pipeline.get("uri"));
        }
    }
}
