package wtf.nanoka.airplay.app.power;

import com.sun.jna.platform.win32.WinBase;
import org.junit.jupiter.api.Test;
import wtf.nanoka.airplay.lib.AudioStreamInfo;
import wtf.nanoka.airplay.lib.VideoStreamInfo;
import wtf.nanoka.airplay.server.AirPlayConsumer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayAwakeAirPlayConsumerTest {

    private static final int DISPLAY_REQUIRED = WinBase.ES_CONTINUOUS | WinBase.ES_DISPLAY_REQUIRED;
    private static final VideoStreamInfo VIDEO_FORMAT = new VideoStreamInfo(
            "test", 320, 180, 60, VideoStreamInfo.Codec.H264);

    @Test
    void waitsForTheFirstFrameAndReleasesOnDisconnect() {
        List<Integer> states = new ArrayList<>();
        var delegate = new RecordingConsumer();

        try (var preventer = successfulPreventer(states)) {
            var consumer = new DisplayAwakeAirPlayConsumer(delegate, preventer);
            consumer.onVideoFormat(VIDEO_FORMAT);
            consumer.onVideoFormatDetected(VIDEO_FORMAT);
            assertEquals(List.of(), states);

            consumer.onVideo(new byte[]{1});
            consumer.onVideo(new byte[]{2}, 42);
            consumer.onVideoSrcDisconnect();
        }

        assertEquals(List.of(DISPLAY_REQUIRED, WinBase.ES_CONTINUOUS), states);
        assertEquals(1, delegate.videoFormats);
        assertEquals(1, delegate.detectedFormats);
        assertEquals(2, delegate.videoFrames);
        assertEquals(1, delegate.videoDisconnects);
        assertEquals(42, delegate.lastVideoTimestamp);
    }

    @Test
    void retriesARejectedRequestOnTheNextFrame() {
        List<Integer> states = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        try (var preventer = new DisplaySleepPreventer(state -> {
            states.add(state);
            return attempts.getAndIncrement() == 0 ? 0 : WinBase.ES_CONTINUOUS;
        })) {
            var consumer = new DisplayAwakeAirPlayConsumer(new RecordingConsumer(), preventer);
            consumer.onVideoFormat(VIDEO_FORMAT);
            consumer.onVideo(new byte[]{1});
            consumer.onVideo(new byte[]{2});
            consumer.onVideoSrcDisconnect();
        }

        assertEquals(List.of(DISPLAY_REQUIRED, DISPLAY_REQUIRED, WinBase.ES_CONTINUOUS), states);
    }

    @Test
    void anUnknownFormatReleasesTheActiveRequest() {
        List<Integer> states = new ArrayList<>();
        try (var preventer = successfulPreventer(states)) {
            var consumer = new DisplayAwakeAirPlayConsumer(new RecordingConsumer(), preventer);
            consumer.onVideoFormat(VIDEO_FORMAT);
            consumer.onVideo(new byte[]{1});
            consumer.onVideoFormat(new VideoStreamInfo(
                    "test", 0, 0, 0, VideoStreamInfo.Codec.UNKNOWN));
        }

        assertEquals(List.of(DISPLAY_REQUIRED, WinBase.ES_CONTINUOUS), states);
    }

    @Test
    void aLateFrameAfterDisconnectCannotReacquireTheRequest() {
        List<Integer> states = new ArrayList<>();
        try (var preventer = successfulPreventer(states)) {
            var consumer = new DisplayAwakeAirPlayConsumer(new RecordingConsumer(), preventer);
            consumer.onVideoFormat(VIDEO_FORMAT);
            consumer.onVideo(new byte[]{1});
            consumer.onVideoSrcDisconnect();
            consumer.onVideo(new byte[]{2});
        }

        assertEquals(List.of(DISPLAY_REQUIRED, WinBase.ES_CONTINUOUS), states);
    }

    @Test
    void playlistPlaybackUsesTheSameDisplayRequest() {
        List<Integer> states = new ArrayList<>();
        var delegate = new RecordingConsumer();
        try (var preventer = successfulPreventer(states)) {
            var consumer = new DisplayAwakeAirPlayConsumer(delegate, preventer);
            consumer.onMediaPlaylist("https://example.test/stream.m3u8");
            consumer.onMediaPlaylistPause();
            consumer.onMediaPlaylistResume();
            consumer.onMediaPlaylistRemove();
        }

        assertEquals(List.of(
                DISPLAY_REQUIRED,
                WinBase.ES_CONTINUOUS,
                DISPLAY_REQUIRED,
                WinBase.ES_CONTINUOUS), states);
        assertEquals(1, delegate.playlists);
        assertEquals(1, delegate.playlistPauses);
        assertEquals(1, delegate.playlistResumes);
        assertEquals(1, delegate.playlistRemovals);
    }

    @Test
    void serializesConcurrentPlaylistStartAndRemoval() throws Exception {
        List<Integer> states = new ArrayList<>();
        var delegate = new BlockingPlaylistConsumer();
        try (var preventer = successfulPreventer(states)) {
            var consumer = new DisplayAwakeAirPlayConsumer(delegate, preventer);
            Thread start = Thread.ofVirtual().start(
                    () -> consumer.onMediaPlaylist("https://example.test/stream.m3u8"));
            assertTrue(delegate.playlistEntered.await(2, TimeUnit.SECONDS));

            CountDownLatch removalCalling = new CountDownLatch(1);
            Thread remove = Thread.ofVirtual().start(() -> {
                removalCalling.countDown();
                consumer.onMediaPlaylistRemove();
            });
            assertTrue(removalCalling.await(2, TimeUnit.SECONDS));
            assertFalse(delegate.removalEntered.await(250, TimeUnit.MILLISECONDS));
            delegate.allowPlaylistReturn.countDown();

            start.join(TimeUnit.SECONDS.toMillis(2));
            remove.join(TimeUnit.SECONDS.toMillis(2));
            assertFalse(start.isAlive());
            assertFalse(remove.isAlive());
            assertEquals(List.of(DISPLAY_REQUIRED, WinBase.ES_CONTINUOUS), states);
        } finally {
            delegate.allowPlaylistReturn.countDown();
        }
    }

    private DisplaySleepPreventer successfulPreventer(List<Integer> states) {
        return new DisplaySleepPreventer(state -> {
            states.add(state);
            return WinBase.ES_CONTINUOUS;
        });
    }

    private static class RecordingConsumer implements AirPlayConsumer {
        private int videoFormats;
        private int detectedFormats;
        private int videoFrames;
        private int videoDisconnects;
        private long lastVideoTimestamp = -1;
        private int playlists;
        private int playlistRemovals;
        private int playlistPauses;
        private int playlistResumes;

        @Override
        public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
            videoFormats++;
        }

        @Override
        public void onVideoFormatDetected(VideoStreamInfo videoStreamInfo) {
            detectedFormats++;
        }

        @Override
        public void onVideo(byte[] bytes) {
            videoFrames++;
        }

        @Override
        public void onVideo(byte[] bytes, long timestamp) {
            videoFrames++;
            lastVideoTimestamp = timestamp;
        }

        @Override
        public void onVideoSrcDisconnect() {
            videoDisconnects++;
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
        public void onMediaPlaylist(String playlistUri) {
            playlists++;
        }

        @Override
        public void onMediaPlaylistRemove() {
            playlistRemovals++;
        }

        @Override
        public void onMediaPlaylistPause() {
            playlistPauses++;
        }

        @Override
        public void onMediaPlaylistResume() {
            playlistResumes++;
        }
    }

    private static final class BlockingPlaylistConsumer extends RecordingConsumer {
        private final CountDownLatch playlistEntered = new CountDownLatch(1);
        private final CountDownLatch allowPlaylistReturn = new CountDownLatch(1);
        private final CountDownLatch removalEntered = new CountDownLatch(1);

        @Override
        public void onMediaPlaylist(String playlistUri) {
            playlistEntered.countDown();
            await(allowPlaylistReturn);
            super.onMediaPlaylist(playlistUri);
        }

        @Override
        public void onMediaPlaylistRemove() {
            removalEntered.countDown();
            super.onMediaPlaylistRemove();
        }

        private void await(CountDownLatch latch) {
            try {
                latch.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Playlist test callback was interrupted", error);
            }
        }
    }
}
