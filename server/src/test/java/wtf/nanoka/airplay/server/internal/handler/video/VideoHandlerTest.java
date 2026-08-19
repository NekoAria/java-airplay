package wtf.nanoka.airplay.server.internal.handler.video;

import wtf.nanoka.airplay.lib.AirPlay;
import wtf.nanoka.airplay.lib.AudioStreamInfo;
import wtf.nanoka.airplay.lib.VideoStreamInfo;
import wtf.nanoka.airplay.server.AirPlayConsumer;
import wtf.nanoka.airplay.server.internal.packet.VideoPacket;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void prependsCodecParameterSetsToTheFirstAccessUnit() {
        byte[] sps = {
                0x27, 0x64, 0x00, 0x1f, (byte) 0xac, 0x13, 0x14, 0x50,
                0x54, 0x16, (byte) 0xfa, (byte) 0xe6, (byte) 0xe0, 0x20,
                0x20, 0x20, 0x40
        };
        byte[] configuration = avcConfiguration(sps, new byte[]{0x28, 1, 2});
        byte[] accessUnit = {0, 0, 0, 2, 0x65, 1};
        var consumer = new RecordingConsumer();
        var handler = new VideoHandler(new NoopAirPlay(), consumer);

        handler.channelRead(null, new VideoPacket(1, configuration.length, 100, configuration));
        handler.channelRead(null, new VideoPacket(0, accessUnit.length, 100, accessUnit));

        assertEquals(VideoStreamInfo.Codec.H264, consumer.detectedFormat.getCodec());
        byte[] expected = new byte[4 + sps.length + 4 + 3 + accessUnit.length];
        int offset = 0;
        offset = append(expected, offset, new byte[]{0, 0, 0, 1});
        offset = append(expected, offset, sps);
        offset = append(expected, offset, new byte[]{0, 0, 0, 1});
        offset = append(expected, offset, new byte[]{0x28, 1, 2});
        offset = append(expected, offset, new byte[]{0, 0, 0, 1, 0x65, 1});
        assertEquals(expected.length, offset);
        assertArrayEquals(expected, consumer.video);
    }

    @Test
    void asynchronousConsumerDoesNotBlockTheNettyEventLoop() throws Exception {
        var consumer = new BlockingConsumer(false);
        var channel = new EmbeddedChannel(new VideoHandler(new NoopAirPlay(), consumer, true));
        byte[] accessUnit = {0, 0, 0, 1, 0x65, 1};

        assertTimeout(Duration.ofSeconds(1), () -> channel.writeInbound(
                new VideoPacket(0, accessUnit.length, 123, accessUnit)));
        assertTrue(consumer.entered.await(1, TimeUnit.SECONDS));

        consumer.release.countDown();
        channel.runPendingTasks();
        channel.finishAndReleaseAll();
    }

    @Test
    void consumerFailureClosesTheVideoChannel() throws Exception {
        var consumer = new BlockingConsumer(true);
        var channel = new EmbeddedChannel(new VideoHandler(new NoopAirPlay(), consumer, true));
        byte[] accessUnit = {0, 0, 0, 1, 0x65, 1};

        channel.writeInbound(new VideoPacket(0, accessUnit.length, 123, accessUnit));
        assertTrue(consumer.entered.await(1, TimeUnit.SECONDS));
        consumer.release.countDown();
        for (int attempt = 0; attempt < 20 && channel.isActive(); attempt++) {
            Thread.sleep(10);
            channel.runPendingTasks();
        }

        assertFalse(channel.isActive());
        channel.finishAndReleaseAll();
    }

    private static byte[] avcConfiguration(byte[] sps, byte[] pps) {
        byte[] payload = new byte[6 + 2 + sps.length + 1 + 2 + pps.length];
        payload[0] = 1;
        payload[4] = (byte) 0xff;
        payload[5] = (byte) 0xe1;
        payload[6] = (byte) (sps.length >>> 8);
        payload[7] = (byte) sps.length;
        System.arraycopy(sps, 0, payload, 8, sps.length);
        int offset = 8 + sps.length;
        payload[offset++] = 1;
        payload[offset++] = (byte) (pps.length >>> 8);
        payload[offset++] = (byte) pps.length;
        System.arraycopy(pps, 0, payload, offset, pps.length);
        return payload;
    }

    private static int append(byte[] destination, int offset, byte[] source) {
        System.arraycopy(source, 0, destination, offset, source.length);
        return offset + source.length;
    }

    private static class NoopAirPlay extends AirPlay {
        @Override
        public void decryptVideo(byte[] video) {
        }
    }

    private static class RecordingConsumer implements AirPlayConsumer {
        private byte[] video;
        private VideoStreamInfo detectedFormat;

        @Override public void onVideoFormat(VideoStreamInfo info) { }
        @Override public void onVideoFormatDetected(VideoStreamInfo info) { detectedFormat = info; }
        @Override public void onVideo(byte[] bytes) { video = bytes; }
        @Override public void onVideoSrcDisconnect() { }
        @Override public void onAudioFormat(AudioStreamInfo info) { }
        @Override public void onAudio(byte[] bytes) { }
        @Override public void onAudioSrcDisconnect() { }
    }

    private static final class BlockingConsumer extends RecordingConsumer {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final boolean fail;

        private BlockingConsumer(boolean fail) {
            this.fail = fail;
        }

        @Override
        public void onVideo(byte[] bytes) {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (fail) {
                throw new IllegalStateException("test failure");
            }
        }
    }
}
