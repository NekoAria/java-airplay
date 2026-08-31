package wtf.nanoka.airplay.player.gstreamer;

import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pipeline;
import org.freedesktop.gstreamer.Version;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import wtf.nanoka.airplay.lib.VideoStreamInfo;

import java.time.Duration;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeout;

class GstPlayerBackpressureTest {

    @Test
    void aggressiveModeKeepsTheAirPlayConsumerNonBlocking() {
        initializeGStreamer();

        try (var player = new SlowSinkPlayer(true)) {
            startVideo(player);
            byte[] idr = {0, 0, 0, 1, 0x65, 1};

            assertTimeout(Duration.ofSeconds(2), () -> {
                for (int frame = 0; frame < 200; frame++) {
                    player.onVideo(idr, frame);
                }
            });
        }
    }

    @Test
    void safeModeBackpressuresWithoutFailingTheVideoConsumer() throws Exception {
        initializeGStreamer();

        var player = new SlowSinkPlayer(false);
        boolean closed = false;
        try {
            startVideo(player);
            byte[] idr = {0, 0, 0, 1, 0x65, 1};
            var submission = new FutureTask<Void>(() -> {
                for (int frame = 0; frame < 200; frame++) {
                    player.onVideo(idr, frame);
                }
                return null;
            });
            Thread.ofVirtual().start(submission);

            Thread.sleep(750);
            assertFalse(submission.isDone(), "Safe mode should apply TCP backpressure instead of dropping frames");
            assertTimeout(Duration.ofMillis(500), () -> {
                for (int packet = 0; packet < 100; packet++) {
                    player.onAudio(new byte[]{1, 2, 3}, packet * 480L, packet);
                }
            });
            player.close();
            closed = true;
            assertDoesNotThrow(() -> submission.get(2, TimeUnit.SECONDS));
        } finally {
            if (!closed) {
                player.close();
            }
        }
    }

    private void initializeGStreamer() {
        try {
            GstPlayerUtils.configurePaths();
            Gst.init(Version.of(1, 10), "PlayerBackpressureTest");
        } catch (Throwable error) {
            Assumptions.assumeTrue(false, "Native GStreamer is unavailable: " + error.getMessage());
        }
    }

    private void startVideo(GstPlayer player) {
        player.onVideoFormatDetected(new VideoStreamInfo(
                "backpressure", 320, 180, 60, VideoStreamInfo.Codec.H264));
    }

    private static final class SlowSinkPlayer extends GstPlayer {

        private SlowSinkPlayer(boolean aggressiveFrameDropping) {
            super(60, 2, "appsrc name=h264-src ! identity sleep-time=1000000 ! fakesink sync=false", null,
                    aggressiveFrameDropping);
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
