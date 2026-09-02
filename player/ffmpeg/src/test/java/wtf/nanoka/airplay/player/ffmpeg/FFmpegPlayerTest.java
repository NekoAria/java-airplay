package wtf.nanoka.airplay.player.ffmpeg;

import org.junit.jupiter.api.Test;
import wtf.nanoka.airplay.lib.AudioStreamInfo;
import wtf.nanoka.airplay.lib.VideoStreamInfo;
import wtf.nanoka.airplay.server.AirPlayAudioConsumer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FFmpegPlayerTest {

    @Test
    void forwardsEveryAudioCallbackWithoutLosingTimingMetadata() {
        var audio = new RecordingAudioConsumer();
        var player = new FFmpegPlayer(audio, command -> new RecordingProcess());
        AudioStreamInfo streamInfo = audioStreamInfo();
        byte[] frame = {(byte) 0xf8, (byte) 0xe8, 0x50, 0x00};

        player.onAudioFormat(streamInfo);
        player.onAudio(frame, 0xffff_ff00L, 65_535);
        player.onAudioSrcDisconnect();
        player.close();

        assertSame(streamInfo, audio.streamInfo);
        assertSame(frame, audio.frame);
        assertEquals(0xffff_ff00L, audio.timestamp);
        assertEquals(65_535, audio.sequenceNumber);
        assertEquals(1, audio.disconnects);
        assertEquals(1, audio.closes);
    }

    @Test
    void unknownCodecDoesNotStartAProcessAndDisconnectIsIdempotent() {
        var audio = new RecordingAudioConsumer();
        var processes = new RecordingProcessFactory();
        var player = new FFmpegPlayer(audio, processes);

        player.onVideoFormat(new VideoStreamInfo("unknown"));

        assertEquals(0, processes.commands.size());
        assertDoesNotThrow(player::onVideoSrcDisconnect);
        assertDoesNotThrow(player::onVideoSrcDisconnect);
        player.close();
    }

    @Test
    void selectsCodecSpecificCommandsWithoutRestartingDuplicateFormatUpdates() {
        var audio = new RecordingAudioConsumer();
        var processes = new RecordingProcessFactory();
        var player = new FFmpegPlayer(audio, processes);
        byte[] accessUnit = {0, 0, 0, 1, 0x65, 1};

        player.onVideoFormatDetected(videoInfo(VideoStreamInfo.Codec.H264));
        RecordingProcess h264 = processes.processes.get(0);
        player.onVideo(accessUnit);
        player.onVideoFormatDetected(videoInfo(VideoStreamInfo.Codec.HEVC));
        RecordingProcess hevc = processes.processes.get(1);

        assertArrayEquals(accessUnit, h264.input.toByteArray());
        assertEquals("h264", optionValue(processes.commands.get(0), "-f"));
        assertEquals("h264", optionValue(processes.commands.get(0), "-codec:v"));
        assertEquals("hevc", optionValue(processes.commands.get(1), "-f"));
        assertEquals("hevc", optionValue(processes.commands.get(1), "-codec:v"));
        assertEquals(1, h264.destroyCalls);
        assertFalse(h264.isAlive());
        assertTrue(hevc.isAlive());

        player.onVideoFormatDetected(videoInfo(VideoStreamInfo.Codec.HEVC));
        assertEquals(2, processes.commands.size());
        assertEquals(0, hevc.destroyCalls);
        assertTrue(hevc.isAlive());

        player.onVideoFormat(new VideoStreamInfo("next-stream"));
        assertEquals(2, processes.commands.size());
        assertEquals(1, hevc.destroyCalls);
        assertFalse(hevc.isAlive());
        player.close();
    }

    @Test
    void closeReleasesVideoAndAudioExactlyOnce() {
        var audio = new RecordingAudioConsumer();
        var processes = new RecordingProcessFactory();
        var player = new FFmpegPlayer(audio, processes);
        player.onVideoFormatDetected(videoInfo(VideoStreamInfo.Codec.H264));
        RecordingProcess process = processes.processes.get(0);

        player.close();
        player.close();
        player.onAudio(new byte[]{1}, 10, 1);
        player.onAudioSrcDisconnect();

        assertEquals(1, process.destroyCalls);
        assertFalse(process.isAlive());
        assertEquals(1, audio.closes);
        assertEquals(0, audio.disconnects);
        assertEquals(0, audio.frames);
    }

    @Test
    void forceTerminationWaitsForTheProcessToActuallyExit() {
        var process = new DelayedForceProcess();
        var player = new FFmpegPlayer(new RecordingAudioConsumer(), command -> process);
        player.onVideoFormatDetected(videoInfo(VideoStreamInfo.Codec.H264));

        player.close();

        assertEquals(1, process.destroyCalls);
        assertEquals(1, process.forcedDestroyCalls);
        assertEquals(2, process.waitCalls);
        assertFalse(process.isAlive());
    }

    @Test
    void processStartFailureReportsTheCompleteCommand() {
        var player = new FFmpegPlayer(
                new RecordingAudioConsumer(),
                command -> {
                    throw new IOException("ffplay was not found");
                });

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> player.onVideoFormatDetected(videoInfo(VideoStreamInfo.Codec.HEVC)));

        assertTrue(failure.getMessage().contains("ffplay -fs -f hevc"));
        assertTrue(failure.getMessage().contains("-codec:v hevc"));
        assertInstanceOf(IOException.class, failure.getCause());
        player.close();
    }

    private static AudioStreamInfo audioStreamInfo() {
        return new AudioStreamInfo.AudioStreamInfoBuilder()
                .compressionType(AudioStreamInfo.CompressionType.AAC_ELD)
                .audioFormat(AudioStreamInfo.AudioFormat.AAC_ELD_44100_2)
                .sampleRate(44_100)
                .samplesPerFrame(480)
                .build();
    }

    private static VideoStreamInfo videoInfo(VideoStreamInfo.Codec codec) {
        return new VideoStreamInfo("video", 1920, 1080, 60, codec);
    }

    private static String optionValue(List<String> command, String option) {
        int optionIndex = command.indexOf(option);
        assertTrue(optionIndex >= 0 && optionIndex + 1 < command.size(), "Missing command option " + option);
        return command.get(optionIndex + 1);
    }

    private static final class RecordingProcessFactory implements FFmpegPlayer.FFplayProcessFactory {
        private final List<List<String>> commands = new ArrayList<>();
        private final List<RecordingProcess> processes = new ArrayList<>();

        @Override
        public Process start(List<String> command) {
            if (!processes.isEmpty()) {
                assertFalse(processes.getLast().isAlive(), "Previous FFplay process must stop before replacement");
            }
            commands.add(List.copyOf(command));
            RecordingProcess process = new RecordingProcess();
            processes.add(process);
            return process;
        }
    }

    private static final class RecordingProcess extends Process {
        private final ByteArrayOutputStream input = new ByteArrayOutputStream();
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private int destroyCalls;
        private int forcedDestroyCalls;

        @Override
        public OutputStream getOutputStream() {
            return input;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() throws InterruptedException {
            while (alive.get()) {
                Thread.sleep(10);
            }
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return !alive.get();
        }

        @Override
        public int exitValue() {
            if (alive.get()) {
                throw new IllegalThreadStateException("Process is still running");
            }
            return 0;
        }

        @Override
        public void destroy() {
            destroyCalls++;
            alive.set(false);
        }

        @Override
        public Process destroyForcibly() {
            forcedDestroyCalls++;
            alive.set(false);
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }
    }

    private static final class DelayedForceProcess extends Process {
        private final ByteArrayOutputStream input = new ByteArrayOutputStream();
        private boolean alive = true;
        private boolean forceRequested;
        private int destroyCalls;
        private int forcedDestroyCalls;
        private int waitCalls;

        @Override
        public OutputStream getOutputStream() {
            return input;
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            alive = false;
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            waitCalls++;
            if (forceRequested) {
                alive = false;
                return true;
            }
            return false;
        }

        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException("Process is still running");
            }
            return 0;
        }

        @Override
        public void destroy() {
            destroyCalls++;
        }

        @Override
        public Process destroyForcibly() {
            forcedDestroyCalls++;
            forceRequested = true;
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }
    }

    private static final class RecordingAudioConsumer implements AirPlayAudioConsumer, AutoCloseable {
        private AudioStreamInfo streamInfo;
        private byte[] frame;
        private long timestamp;
        private int sequenceNumber;
        private int frames;
        private int disconnects;
        private int closes;

        @Override
        public void onAudioFormat(AudioStreamInfo audioStreamInfo) {
            streamInfo = audioStreamInfo;
        }

        @Override
        public void onAudio(byte[] bytes) {
            frame = bytes;
            frames++;
        }

        @Override
        public void onAudio(byte[] bytes, long timestamp, int sequenceNumber) {
            frame = bytes;
            this.timestamp = timestamp;
            this.sequenceNumber = sequenceNumber;
            frames++;
        }

        @Override
        public void onAudioSrcDisconnect() {
            disconnects++;
        }

        @Override
        public void close() {
            closes++;
        }
    }
}
