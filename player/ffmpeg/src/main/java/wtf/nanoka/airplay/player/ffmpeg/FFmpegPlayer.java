package wtf.nanoka.airplay.player.ffmpeg;

import lombok.extern.slf4j.Slf4j;
import wtf.nanoka.airplay.lib.AudioStreamInfo;
import wtf.nanoka.airplay.lib.VideoStreamInfo;
import wtf.nanoka.airplay.player.gstreamer.GstAudioPlayer;
import wtf.nanoka.airplay.server.AirPlayAudioConsumer;
import wtf.nanoka.airplay.server.AirPlayConsumer;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Slf4j
public class FFmpegPlayer implements AirPlayConsumer, AutoCloseable {

    private static final long PROCESS_STOP_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(1);

    private final AirPlayAudioConsumer audioConsumer;
    private final FFplayProcessFactory processFactory;
    private final Object videoLock = new Object();
    private final Object audioLock = new Object();

    private volatile boolean closed;
    private Process videoProcess;
    private VideoStreamInfo.Codec activeVideoCodec = VideoStreamInfo.Codec.UNKNOWN;

    public FFmpegPlayer() {
        this(new GstAudioPlayer());
    }

    public FFmpegPlayer(AirPlayAudioConsumer audioConsumer) {
        this(audioConsumer, FFmpegPlayer::startFFplay);
    }

    FFmpegPlayer(AirPlayAudioConsumer audioConsumer, FFplayProcessFactory processFactory) {
        this.audioConsumer = Objects.requireNonNull(audioConsumer, "audioConsumer");
        this.processFactory = Objects.requireNonNull(processFactory, "processFactory");
    }

    @Override
    public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        selectVideoFormat(videoStreamInfo);
    }

    @Override
    public void onVideoFormatDetected(VideoStreamInfo videoStreamInfo) {
        selectVideoFormat(videoStreamInfo);
    }

    private void selectVideoFormat(VideoStreamInfo videoStreamInfo) {
        Objects.requireNonNull(videoStreamInfo, "videoStreamInfo");
        synchronized (videoLock) {
            if (closed) {
                return;
            }
            VideoStreamInfo.Codec codec = videoStreamInfo.getCodec();
            if (codec != VideoStreamInfo.Codec.UNKNOWN
                    && codec == activeVideoCodec
                    && videoProcess != null
                    && videoProcess.isAlive()) {
                return;
            }
            stopVideoProcessLocked();
            if (codec == VideoStreamInfo.Codec.UNKNOWN) {
                return;
            }

            List<String> command = ffplayCommand(codec);
            try {
                videoProcess = Objects.requireNonNull(
                        processFactory.start(command), "processFactory.start(command)");
                activeVideoCodec = codec;
            } catch (IOException | RuntimeException failure) {
                throw new IllegalStateException(
                        "Unable to start FFplay command: " + String.join(" ", command), failure);
            }
            log.info("Started FFplay {} video process", codec);
        }
    }

    @Override
    public void onVideo(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        Process process;
        synchronized (videoLock) {
            if (closed || videoProcess == null) {
                return;
            }
            process = videoProcess;
            if (!process.isAlive()) {
                videoProcess = null;
                activeVideoCodec = VideoStreamInfo.Codec.UNKNOWN;
                return;
            }
        }

        try {
            var input = process.getOutputStream();
            input.write(bytes);
            input.flush();
        } catch (IOException failure) {
            log.warn("Unable to write video to FFplay: {}", failure.getMessage());
            synchronized (videoLock) {
                if (videoProcess == process) {
                    stopVideoProcessLocked();
                }
            }
        }
    }

    @Override
    public void onVideoSrcDisconnect() {
        synchronized (videoLock) {
            stopVideoProcessLocked();
        }
    }

    @Override
    public void onAudioFormat(AudioStreamInfo audioStreamInfo) {
        synchronized (audioLock) {
            if (closed) {
                return;
            }
            audioConsumer.onAudioFormat(audioStreamInfo);
        }
    }

    @Override
    public void onAudio(byte[] bytes) {
        synchronized (audioLock) {
            if (closed) {
                return;
            }
            audioConsumer.onAudio(bytes);
        }
    }

    @Override
    public void onAudio(byte[] bytes, long timestamp, int sequenceNumber) {
        synchronized (audioLock) {
            if (closed) {
                return;
            }
            audioConsumer.onAudio(bytes, timestamp, sequenceNumber);
        }
    }

    @Override
    public void onAudioSrcDisconnect() {
        synchronized (audioLock) {
            if (closed) {
                return;
            }
            audioConsumer.onAudioSrcDisconnect();
        }
    }

    @Override
    public void close() {
        synchronized (videoLock) {
            if (closed) {
                return;
            }
            closed = true;
            stopVideoProcessLocked();
        }
        synchronized (audioLock) {
            closeAudioConsumer();
        }
    }

    private void closeAudioConsumer() {
        if (!(audioConsumer instanceof AutoCloseable closeable)) {
            audioConsumer.onAudioSrcDisconnect();
            return;
        }
        try {
            closeable.close();
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to close the FFmpeg audio backend", failure);
        }
    }

    private void stopVideoProcessLocked() {
        Process process = videoProcess;
        videoProcess = null;
        activeVideoCodec = VideoStreamInfo.Codec.UNKNOWN;
        if (process == null) {
            return;
        }

        try {
            process.getOutputStream().close();
        } catch (IOException failure) {
            log.debug("Unable to close FFplay input: {}", failure.getMessage());
        }
        try {
            if (!process.isAlive()) {
                return;
            }
            process.destroy();
            if (!waitForProcessExit(process)) {
                log.warn("FFplay did not stop within {} ms; forcing termination", PROCESS_STOP_TIMEOUT_MILLIS);
                Process forcedProcess = process.destroyForcibly();
                if (!waitForProcessExit(forcedProcess)) {
                    log.error("FFplay is still alive {} ms after forced termination",
                            PROCESS_STOP_TIMEOUT_MILLIS);
                }
            }
        } catch (RuntimeException failure) {
            log.warn("Unable to stop FFplay: {}", failure.getMessage());
        }
    }

    private static boolean waitForProcessExit(Process process) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROCESS_STOP_TIMEOUT_MILLIS);
        try {
            while (process.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    return process.waitFor(remaining, TimeUnit.NANOSECONDS) || !process.isAlive();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            return true;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static Process startFFplay(List<String> command) throws IOException {
        return new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
    }

    private static List<String> ffplayCommand(VideoStreamInfo.Codec codec) {
        String codecName;
        if (codec == VideoStreamInfo.Codec.H264) {
            codecName = "h264";
        } else if (codec == VideoStreamInfo.Codec.HEVC) {
            codecName = "hevc";
        } else {
            throw new IllegalArgumentException("Unsupported FFplay video codec: " + codec);
        }
        return List.of(
                "ffplay", "-fs",
                "-f", codecName,
                "-codec:v", codecName,
                "-probesize", "32",
                "-analyzeduration", "0",
                "-vf", "setpts=0",
                "-flags", "low_delay",
                "-");
    }

    @FunctionalInterface
    interface FFplayProcessFactory {
        Process start(List<String> command) throws IOException;
    }
}
