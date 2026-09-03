package wtf.nanoka.airplay.player.gstreamer;

import org.junit.jupiter.api.Test;
import wtf.nanoka.airplay.lib.AudioStreamInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GstAudioPlayerLifecycleTest {

    @Test
    void boundedQueueDropsOldestPacketsWhileTheFeederIsBackpressured() throws Exception {
        var alac = new RecordingAudioPipeline();
        var aacEld = new RecordingAudioPipeline();
        alac.blockPushes();

        var player = player(alac, aacEld);
        try {
            player.onAudioFormat(alacFormat());
            player.onAudio(packet(0), 0, 0);
            assertTrue(alac.awaitPushStarted(1, TimeUnit.SECONDS));

            int overflow = 8;
            int lastSequence = GstAudioPlayer.AUDIO_QUEUE_CAPACITY + overflow;
            for (int sequence = 1; sequence <= lastSequence; sequence++) {
                player.onAudio(packet(sequence), (long) sequence * 352, sequence);
            }

            alac.releasePushes();
            assertTrue(await(() -> alac.pushCount() == GstAudioPlayer.AUDIO_QUEUE_CAPACITY + 1));

            List<Integer> expectedPackets = new ArrayList<>();
            expectedPackets.add(0);
            for (int sequence = overflow + 1; sequence <= lastSequence; sequence++) {
                expectedPackets.add(sequence);
            }
            assertEquals(expectedPackets, alac.packetIds());
            assertEquals(0, aacEld.pushCount());
        } finally {
            alac.releasePushes();
            player.close();
        }
    }

    @Test
    void feederPushesTimingMetadataAndTerminatesOnClose() throws Exception {
        var alac = new RecordingAudioPipeline();
        var aacEld = new RecordingAudioPipeline();
        var feederThread = new AtomicReference<Thread>();
        var player = new GstAudioPlayer(
                new GstAudioPlayer.AudioPipelines(alac, aacEld),
                action -> {
                    var thread = new Thread(action);
                    feederThread.set(thread);
                    return thread;
                });
        Thread feeder = feederThread.get();

        try (player) {
            assertTrue(feeder.isAlive());
            assertTrue(feeder.isDaemon());
            assertEquals("gstreamer-audio-feeder", feeder.getName());

            player.onAudioFormat(alacFormat());
            player.onAudio(packet(7), 0, 7);
            assertTrue(await(() -> alac.pushCount() == 1));
            assertEquals(new PushedAudio(7, 0, 352L * TimeUnit.SECONDS.toNanos(1) / 44_100),
                    alac.pushes.getFirst());

            player.close();
            feeder.join(TimeUnit.SECONDS.toMillis(1));
            assertFalse(feeder.isAlive());

            player.onAudio(packet(8), 352, 8);
            assertEquals(1, alac.pushCount());
        }
    }

    @Test
    void repeatedFormatDisconnectAndCloseTransitionsAreSafeAndDeterministic() {
        var alac = new RecordingAudioPipeline();
        var aacEld = new RecordingAudioPipeline();
        var player = player(alac, aacEld);

        try (player) {
            player.onAudioFormat(alacFormat());
            player.onAudioFormat(aacEldFormat());
            player.onAudioSrcDisconnect();
            player.onAudioSrcDisconnect();
            player.onAudioFormat(alacFormat());
            player.close();
            player.close();
            player.onAudioSrcDisconnect();
            player.onAudioFormat(aacEldFormat());

            assertEquals(2, alac.configureCount);
            assertEquals(2, alac.playCount);
            assertEquals(1, aacEld.configureCount);
            assertEquals(1, aacEld.playCount);
            assertEquals(6, alac.stopCount);
            assertEquals(6, aacEld.stopCount);
            assertEquals(1, alac.closeCount);
            assertEquals(1, aacEld.closeCount);
        }
    }

    private static GstAudioPlayer player(
            RecordingAudioPipeline alac,
            RecordingAudioPipeline aacEld) {
        return new GstAudioPlayer(
                new GstAudioPlayer.AudioPipelines(alac, aacEld), Thread::new);
    }

    private static AudioStreamInfo alacFormat() {
        return new AudioStreamInfo.AudioStreamInfoBuilder()
                .compressionType(AudioStreamInfo.CompressionType.ALAC)
                .audioFormat(AudioStreamInfo.AudioFormat.ALAC_44100_16_2)
                .samplesPerFrame(352)
                .sampleRate(44_100)
                .build();
    }

    private static AudioStreamInfo aacEldFormat() {
        return new AudioStreamInfo.AudioStreamInfoBuilder()
                .compressionType(AudioStreamInfo.CompressionType.AAC_ELD)
                .audioFormat(AudioStreamInfo.AudioFormat.AAC_ELD_48000_2)
                .samplesPerFrame(480)
                .sampleRate(48_000)
                .build();
    }

    private static byte[] packet(int id) {
        return new byte[]{(byte) id};
    }

    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    private static final class RecordingAudioPipeline implements GstAudioPlayer.AudioPipeline {
        private int configureCount;
        private int playCount;
        private int stopCount;
        private int closeCount;
        private final CopyOnWriteArrayList<PushedAudio> pushes = new CopyOnWriteArrayList<>();
        private CountDownLatch pushStarted = new CountDownLatch(0);
        private CountDownLatch pushRelease = new CountDownLatch(0);

        @Override
        public void configure(String caps) {
            configureCount++;
        }

        @Override
        public void play() {
            playCount++;
        }

        @Override
        public void stop() {
            stopCount++;
        }

        @Override
        public void push(byte[] bytes, long presentationTime, long duration) {
            pushStarted.countDown();
            try {
                pushRelease.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
            pushes.add(new PushedAudio(Byte.toUnsignedInt(bytes[0]), presentationTime, duration));
        }

        @Override
        public void close() {
            closeCount++;
        }

        private void blockPushes() {
            pushStarted = new CountDownLatch(1);
            pushRelease = new CountDownLatch(1);
        }

        private boolean awaitPushStarted(long timeout, TimeUnit unit) throws InterruptedException {
            return pushStarted.await(timeout, unit);
        }

        private void releasePushes() {
            pushRelease.countDown();
        }

        private int pushCount() {
            return pushes.size();
        }

        private List<Integer> packetIds() {
            return pushes.stream().map(PushedAudio::packetId).toList();
        }
    }

    private record PushedAudio(int packetId, long presentationTime, long duration) {
    }
}
