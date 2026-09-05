package wtf.nanoka.airplay.player.gstreamer;

import org.freedesktop.gstreamer.Bus;
import org.freedesktop.gstreamer.State;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import wtf.nanoka.airplay.player.test.PlaybackFixture;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@Tag("gstreamer-playback")
class GStreamerPlaybackSmokeTest {

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void decodesSyntheticAirPlayAccessUnitsWithTheSelectedPipeline() throws InterruptedException {
        AtomicReference<String> firstPipelineError = new AtomicReference<>();

        try (GstPlayerDefault player = new GstPlayerDefault(30, 8, "auto")) {
            player.h264Pipeline.getBus().connect((Bus.ERROR) (source, code, message) ->
                    firstPipelineError.compareAndSet(null, code + ": " + message));

            PlaybackFixture.playH264(player);
            State playbackState = player.h264Pipeline.getState(5, TimeUnit.SECONDS);
            String error = firstPipelineError.get();

            assertNull(error, "GStreamer playback failed: " + error);
            assertEquals(State.PLAYING, playbackState, "GStreamer pipeline did not reach PLAYING");

            player.onVideoSrcDisconnect();
            assertEquals(State.NULL, player.h264Pipeline.getState(5, TimeUnit.SECONDS),
                    "GStreamer pipeline did not stop after disconnect");
        }
    }
}
