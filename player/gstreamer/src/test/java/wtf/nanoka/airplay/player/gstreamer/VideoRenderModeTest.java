package wtf.nanoka.airplay.player.gstreamer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoRenderModeTest {

    @Test
    void synchronizedModesUseTheClockAndDecodedFrameQueues() {
        assertTrue(VideoRenderMode.BALANCED.clockSyncProperties().contains("sync=true"));
        assertTrue(VideoRenderMode.BALANCED.clockSyncProperties().contains("sync-to-first=true"));
        assertTrue(VideoRenderMode.BALANCED.queueProperties().contains("leaky=downstream"));
        assertTrue(VideoRenderMode.QUALITY.clockSyncProperties().contains("sync=true"));
        assertTrue(VideoRenderMode.QUALITY.queueProperties().contains("leaky=no"));
    }

    @Test
    void lowLatencyModeExplicitlyDisablesClockSynchronization() {
        assertTrue(VideoRenderMode.LOW_LATENCY.clockSyncProperties().contains("sync=false"));
        assertThrows(IllegalArgumentException.class, () -> VideoRenderMode.fromProperty("invalid"));
    }
}
