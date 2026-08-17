package wtf.nanoka.airplay.player.vlc;

import com.formdev.flatlaf.FlatDarkLaf;
import wtf.nanoka.airplay.lib.AudioStreamInfo;
import wtf.nanoka.airplay.lib.VideoStreamInfo;
import wtf.nanoka.airplay.server.AirPlayConsumer;
import lombok.extern.slf4j.Slf4j;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.log.LogLevel;
import uk.co.caprica.vlcj.log.NativeLog;
import uk.co.caprica.vlcj.media.callback.nonseekable.NonSeekableInputStreamMedia;
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent;

import javax.swing.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

@Slf4j
public class VlcPlayer implements AirPlayConsumer, AutoCloseable {

    static {
        FlatDarkLaf.setup();
    }

    private final EmbeddedMediaPlayerComponent mediaPlayerComponent;
    private final MediaPlayerFactory mediaPlayerFactory;
    private final NativeLog nativeLog;

    private final JFrame window;

    private final PipedOutputStream output;
    private final InputStream input;

    public VlcPlayer() {
        mediaPlayerFactory = new MediaPlayerFactory("-vv", "--demux=h264");

        nativeLog = mediaPlayerFactory.application().newLog();
        nativeLog.setLevel(LogLevel.DEBUG);
        nativeLog.addLogListener((level, module, file, line, name, header, id, message) ->
                log.debug("[VLCJ] [{}] [{}] {} {}", level, module, name, message));

        mediaPlayerComponent = new EmbeddedMediaPlayerComponent(mediaPlayerFactory, null, null, null, null);

        window = new JFrame("AirPlay player");
        window.setSize(800, 600);
        window.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        window.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                mediaPlayerComponent.release();
            }
        });
        window.setContentPane(mediaPlayerComponent);
        window.setVisible(true);

        input = new PipedInputStream(1024 * 1024);
        try {
            output = new PipedOutputStream((PipedInputStream) input);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create VLC input pipe", e);
        }

        NonSeekableInputStreamMedia media = new NonSeekableInputStreamMedia() {

            @Override
            protected long onGetSize() {
                return 0;
            }

            @Override
            protected InputStream onOpenStream() {
                return input;
            }

            @Override
            protected void onCloseStream(InputStream inputStream) throws IOException {
                inputStream.close();
            }
        };

        mediaPlayerComponent.mediaPlayer().media().play(media);
        mediaPlayerComponent.mediaPlayer().controls().play();
    }

    @Override
    public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
    }

    @Override
    public void onVideo(byte[] bytes) {
        try {
            output.write(bytes);
        } catch (IOException e) {
            log.warn("Unable to write video data to VLC: {}", e.getMessage());
        }
    }

    @Override
    public void onVideoSrcDisconnect() {
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

    @Override
    public void close() throws IOException {
        output.close();
        input.close();
        mediaPlayerComponent.release();
        nativeLog.release();
        mediaPlayerFactory.release();
        window.dispose();
    }
}
