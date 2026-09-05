package wtf.nanoka.airplay.player.ffmpeg;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import wtf.nanoka.airplay.lib.AudioStreamInfo;
import wtf.nanoka.airplay.player.test.PlaybackFixture;
import wtf.nanoka.airplay.server.AirPlayAudioConsumer;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("ffmpeg-playback")
class FFmpegPlaybackSmokeTest {

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void playsSyntheticAirPlayAccessUnitsWithFFplayOnPath() throws InterruptedException {
        AtomicReference<Process> startedProcess = new AtomicReference<>();
        FFmpegPlayer.FFplayProcessFactory processFactory = command -> {
            Process process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
            startedProcess.set(process);
            return process;
        };

        Process process;
        try (FFmpegPlayer player = new FFmpegPlayer(new SilentAudioConsumer(), processFactory)) {
            PlaybackFixture.playH264(player);

            process = startedProcess.get();
            assertNotNull(process, "FFplay was not started");
            assertTrue(process.isAlive(), "FFplay exited while receiving the H.264 fixture");
        }

        assertFalse(process.isAlive(), "FFplay did not stop when the player closed");
    }

    private static final class SilentAudioConsumer implements AirPlayAudioConsumer {

        @Override
        public void onAudioFormat(AudioStreamInfo audioStreamInfo) {
        }

        @Override
        public void onAudio(byte[] bytes) {
        }

        @Override
        public void onAudioSrcDisconnect() {
        }
    }
}
