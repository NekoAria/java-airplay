package wtf.nanoka.airplay.server.internal.handler.audio;

import wtf.nanoka.airplay.lib.AirPlay;
import wtf.nanoka.airplay.lib.AudioStreamInfo;
import wtf.nanoka.airplay.lib.VideoStreamInfo;
import wtf.nanoka.airplay.server.AirPlayConsumer;
import wtf.nanoka.airplay.server.internal.packet.AudioPacket;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AudioHandlerTest {

    @Test
    void handlesSequenceNumberWrap() throws Exception {
        var consumer = new RecordingConsumer();
        var handler = new AudioHandler(new NoopAirPlay(), consumer, (sequence, count) -> { }, 3, null);

        handler.accept(packet(65534));
        handler.accept(packet(65535));
        handler.accept(packet(0));
        handler.accept(packet(1));

        assertEquals(List.of(65534, 65535, 0, 1), consumer.sequences);
    }

    @Test
    void requestsResendThenSkipsAStaleGap() throws Exception {
        var consumer = new RecordingConsumer();
        List<String> resendRequests = new ArrayList<>();
        var handler = new AudioHandler(new NoopAirPlay(), consumer,
                (sequence, count) -> resendRequests.add(sequence + ":" + count), 3, null);

        handler.accept(packet(10));
        handler.accept(packet(12));
        handler.accept(packet(13));
        handler.accept(packet(14));

        assertEquals(List.of(10, 12, 13, 14), consumer.sequences);
        assertEquals("11:1", resendRequests.getFirst());
    }

    @Test
    void ignoresAacEldNoDataMarkers() throws Exception {
        var consumer = new RecordingConsumer();
        var handler = new AudioHandler(new NoopAirPlay(), consumer, (sequence, count) -> { }, 3,
                AudioStreamInfo.CompressionType.AAC_ELD);

        handler.accept(packet(1, new byte[]{0, 0x68, 0x34, 0}));
        handler.accept(packet(2));

        assertEquals(List.of(2), consumer.sequences);
    }

    private AudioPacket packet(int sequenceNumber) {
        return packet(sequenceNumber, new byte[16]);
    }

    private AudioPacket packet(int sequenceNumber, byte[] payload) {
        return AudioPacket.builder()
                .sequenceNumber(sequenceNumber)
                .timestamp(Integer.toUnsignedLong(sequenceNumber * 480))
                .encodedAudio(payload)
                .encodedAudioSize(payload.length)
                .available(true)
                .build();
    }

    private static class NoopAirPlay extends AirPlay {
        @Override
        public void decryptAudio(byte[] audio, int audioLength) {
        }
    }

    private static class RecordingConsumer implements AirPlayConsumer {
        private final List<Integer> sequences = new ArrayList<>();

        @Override
        public void onAudio(byte[] bytes, long timestamp, int sequenceNumber) {
            sequences.add(sequenceNumber);
        }

        @Override public void onVideoFormat(VideoStreamInfo info) { }
        @Override public void onVideo(byte[] bytes) { }
        @Override public void onVideoSrcDisconnect() { }
        @Override public void onAudioFormat(AudioStreamInfo info) { }
        @Override public void onAudio(byte[] bytes) { }
        @Override public void onAudioSrcDisconnect() { }
    }
}
