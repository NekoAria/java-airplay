package wtf.nanoka.airplay.player.gstreamer;

import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pipeline;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import wtf.nanoka.airplay.lib.VideoStreamInfo;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag(GstTestSupport.NATIVE_GSTREAMER_TAG)
class GstHevcPipelineTest {

    @Test
    void buildsAndRunsTheSoftwareHevcPath() {
        GstTestSupport.initialize("HevcPipelineTest");
        GstTestSupport.assumeElementFactories(
                "appsrc", "h265parse", "avdec_h265", "queue", "clocksync", "autovideosink",
                "videotestsrc", "x265enc", "fakesink");

        String configuredPipeline = GstPlayerDefault.createPipelineDescription(
                VideoStreamInfo.Codec.HEVC, "avdec_h264", "auto", "balanced");
        assertTrue(configuredPipeline.contains("appsrc name=hevc-src ! h265parse"));
        assertTrue(configuredPipeline.contains("! avdec_h265 ! queue"));
        assertTrue(configuredPipeline.contains("sync=true"));
        try (Pipeline configured = (Pipeline) Gst.parseLaunch(configuredPipeline)) {
            assertTrue(configured.getElementByName("hevc-src") != null);
        }

        String testPipeline = "videotestsrc num-buffers=5 "
                + "! video/x-raw,width=320,height=180,framerate=30/1 "
                + "! x265enc tune=zerolatency ! h265parse ! avdec_h265 ! fakesink sync=false";
        try (Pipeline pipeline = (Pipeline) Gst.parseLaunch(testPipeline)) {
            try {
                pipeline.play();
                pipeline.getState(10, TimeUnit.SECONDS);
            } finally {
                pipeline.stop();
            }
        }
    }
}
