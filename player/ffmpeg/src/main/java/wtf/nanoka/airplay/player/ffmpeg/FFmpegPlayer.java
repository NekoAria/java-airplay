package wtf.nanoka.airplay.player.ffmpeg;

import wtf.nanoka.airplay.lib.AudioStreamInfo;
import wtf.nanoka.airplay.lib.VideoStreamInfo;
import wtf.nanoka.airplay.server.AirPlayConsumer;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;

@Slf4j
public class FFmpegPlayer implements AirPlayConsumer {

    private Process h264Process;

    @Override
    public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        try {
            ProcessBuilder pb = new ProcessBuilder("ffplay", "-fs", "-f", "h264", "-codec:v", "h264", "-probesize", "32",
                    "-analyzeduration", "0", "-vf", "setpts=0", "-flags", "low_delay", "-");
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            h264Process = pb.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onVideo(byte[] bytes) {
        if (h264Process == null || !h264Process.isAlive()) {
            return;
        }
        try {
            h264Process.getOutputStream().write(bytes);
            h264Process.getOutputStream().flush();
        } catch (IOException e) {
            log.error(e.getMessage());
        }
    }

    @Override
    public void onVideoSrcDisconnect() {
        if (h264Process != null) {
            h264Process.destroy();
            h264Process = null;
        }
    }

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
