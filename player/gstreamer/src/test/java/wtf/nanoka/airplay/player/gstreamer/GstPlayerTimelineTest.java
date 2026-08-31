package wtf.nanoka.airplay.player.gstreamer;

import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pipeline;
import org.freedesktop.gstreamer.Version;
import org.freedesktop.gstreamer.elements.AppSink;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import wtf.nanoka.airplay.lib.VideoStreamInfo;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GstPlayerTimelineTest {

    @Test
    void preservesRemoteVideoTimelineForAudioCatchUp() {
        try {
            GstPlayerUtils.configurePaths();
            Gst.init(Version.of(1, 10), "PlayerTimelineTest");
        } catch (Throwable error) {
            Assumptions.assumeTrue(false, "Native GStreamer is unavailable: " + error.getMessage());
        }

        try (var player = new TimelinePlayer()) {
            player.onVideoFormatDetected(new VideoStreamInfo(
                    "timeline", 320, 180, 60, VideoStreamInfo.Codec.H264));
            player.onVideo(new byte[]{0, 0, 0, 1, 0x65, 1}, 10L << 32);
            player.onVideo(new byte[]{0, 0, 0, 1, 0x41, 1}, 11L << 32);
            player.onVideo(new byte[]{0, 0, 0, 1, 0x41, 2}, 12L << 32);

            assertEquals(0, player.pullPresentationTimestamp());
            assertEquals(TimeUnit.SECONDS.toNanos(1), player.pullPresentationTimestamp());
            assertEquals(TimeUnit.SECONDS.toNanos(2), player.pullPresentationTimestamp());
        }
    }

    private static final class TimelinePlayer extends GstPlayer {

        private final AppSink sink;

        private TimelinePlayer() {
            super(60, 4, "appsrc name=h264-src ! appsink name=video-sink sync=false", null, false);
            sink = (AppSink) h264Pipeline.getElementByName("video-sink");
        }

        private long pullPresentationTimestamp() {
            try (var sample = sink.pullSample()) {
                return sample.getBuffer().getPresentationTimestamp();
            }
        }

        @Override
        protected Pipeline createH264Pipeline() {
            return (Pipeline) Gst.parseLaunch("appsrc name=h264-src ! fakesink sync=false");
        }

        @Override
        protected Pipeline createHevcPipeline() {
            return null;
        }
    }
}
