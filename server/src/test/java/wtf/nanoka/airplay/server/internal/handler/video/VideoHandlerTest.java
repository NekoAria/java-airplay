package wtf.nanoka.airplay.server.internal.handler.video;

import wtf.nanoka.airplay.lib.AirPlay;
import wtf.nanoka.airplay.lib.AudioStreamInfo;
import wtf.nanoka.airplay.lib.VideoStreamInfo;
import wtf.nanoka.airplay.server.AirPlayConsumer;
import wtf.nanoka.airplay.server.internal.packet.VideoPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class VideoHandlerTest {

    @Test
    void convertsEveryNalLengthPrefix() {
        byte[] payload = {
                0, 0, 0, 2, 0x65, 1,
                0, 0, 0, 3, 0x41, 2, 3
        };
        var consumer = new RecordingConsumer();
        var handler = new VideoHandler(new NoopAirPlay(), consumer);

        handler.channelRead(null, new VideoPacket(0, payload.length, 123, payload));

        assertArrayEquals(new byte[]{
                0, 0, 0, 1, 0x65, 1,
                0, 0, 0, 1, 0x41, 2, 3
        }, consumer.video);
    }

    private static class NoopAirPlay extends AirPlay {
        @Override
        public void decryptVideo(byte[] video) {
        }
    }

    private static class RecordingConsumer implements AirPlayConsumer {
        private byte[] video;

        @Override public void onVideoFormat(VideoStreamInfo info) { }
        @Override public void onVideo(byte[] bytes) { video = bytes; }
        @Override public void onVideoSrcDisconnect() { }
        @Override public void onAudioFormat(AudioStreamInfo info) { }
        @Override public void onAudio(byte[] bytes) { }
        @Override public void onAudioSrcDisconnect() { }
    }
}
