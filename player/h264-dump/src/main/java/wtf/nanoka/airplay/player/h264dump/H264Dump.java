package wtf.nanoka.airplay.player.h264dump;

import wtf.nanoka.airplay.lib.AudioStreamInfo;
import wtf.nanoka.airplay.lib.VideoStreamInfo;
import wtf.nanoka.airplay.server.AirPlayConsumer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

@Slf4j
public class H264Dump implements AirPlayConsumer, AutoCloseable {

    private final FileChannel videoFileChannel;

    public H264Dump() throws IOException {
        this(Path.of("dump.h264"));
    }

    public H264Dump(Path outputFile) throws IOException {
        videoFileChannel = FileChannel.open(Objects.requireNonNull(outputFile, "outputFile"),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    @Override
    public void onVideo(byte[] bytes) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                videoFileChannel.write(buffer);
            }
        } catch (IOException e) {
            log.warn("Unable to write H.264 dump: {}", e.getMessage());
        }
    }

    @Override
    public void onVideoSrcDisconnect() {
    }

    @Override
    public void onAudio(byte[] bytes) {
    }

    @Override
    public void onAudioSrcDisconnect() {
    }

    @Override
    public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
    }

    @Override
    public void onAudioFormat(AudioStreamInfo audioStreamInfo) {
    }

    @PreDestroy
    @Override
    public void close() throws IOException {
        videoFileChannel.close();
    }
}
