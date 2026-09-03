package wtf.nanoka.airplay.server.internal.handler.control;

import com.dd.plist.BinaryPropertyListWriter;
import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;
import wtf.nanoka.airplay.lib.AirPlayIdentity;
import wtf.nanoka.airplay.lib.AudioStreamInfo;
import wtf.nanoka.airplay.lib.VideoStreamInfo;
import wtf.nanoka.airplay.server.AirPlayConfig;
import wtf.nanoka.airplay.server.AirPlayConsumer;
import wtf.nanoka.airplay.server.internal.handler.session.SessionManager;
import wtf.nanoka.airplay.server.internal.handler.session.SessionMediaCoordinator;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.rtsp.RtspHeaderNames;
import io.netty.handler.codec.rtsp.RtspMethods;
import io.netty.handler.codec.rtsp.RtspVersions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlHandlerTest {

    @Test
    void releasesConsumedControlRequests() {
        var identity = AirPlayIdentity.random();
        var config = new AirPlayConfig();
        config.setServerName("test");
        config.setWidth("1920");
        config.setHeight("1080");
        config.setFps("60");
        var sessions = new SessionManager(identity, 4);
        var channel = new EmbeddedChannel(handler(sessions, config, new NoopConsumer(), identity));
        var request = new DefaultFullHttpRequest(RtspVersions.RTSP_1_0, HttpMethod.GET, "/info");
        request.headers().set(RtspHeaderNames.CSEQ, "1");

        channel.writeInbound(request);

        assertEquals(0, request.refCnt());
        FullHttpResponse response = channel.readOutbound();
        response.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void rejectsRtspSessionRebindOnSameConnection() {
        var identity = AirPlayIdentity.random();
        var config = config();
        config.setRequirePairing(false);
        var sessions = new SessionManager(identity, 4);
        var channel = new EmbeddedChannel(handler(sessions, config, new NoopConsumer(), identity));

        channel.writeInbound(feedbackRequest("original-session", 1));
        FullHttpResponse acceptedResponse = channel.readOutbound();
        assertEquals(HttpResponseStatus.OK, acceptedResponse.status());
        assertTrue(channel.isActive());
        assertNotNull(sessions.findSession("original-session"));
        acceptedResponse.release();

        channel.writeInbound(feedbackRequest("replacement-session", 2));
        FullHttpResponse rejectedResponse = channel.readOutbound();
        assertEquals(HttpResponseStatus.BAD_REQUEST, rejectedResponse.status());
        rejectedResponse.release();
        channel.runPendingTasks();

        assertFalse(channel.isActive());
        assertNull(sessions.findSession("replacement-session"));
        channel.finishAndReleaseAll();
        assertNull(sessions.findSession("original-session"));
    }

    @Test
    void proxiesOnlyLocalMlhlsPlaylistUris() {
        assertEquals("http://localhost:7000/playlist/master.m3u8?session=session-1",
                ControlHandler.playlistUriToLocal("mlhls://localhost/master.m3u8",
                        "http://localhost:7000/playlist", "session-1"));
        assertThrows(IllegalArgumentException.class, () -> ControlHandler.playlistUriToLocal(
                "https://example.com/master.m3u8", "http://localhost:7000/playlist", "session-1"));
        assertThrows(IllegalArgumentException.class, () -> ControlHandler.playlistUriToLocal(
                "mlhls://attacker/master.m3u8", "http://localhost:7000/playlist", "session-1"));
    }

    @Test
    void rejectsUnpairedHttpControlWithoutCreatingASession() {
        var identity = AirPlayIdentity.random();
        var config = config();
        var sessions = new SessionManager(identity, 4);
        var consumer = new NoopConsumer();
        var channel = new EmbeddedChannel(handler(sessions, config, consumer, identity));
        var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/stop");
        request.headers().set("X-Apple-Session-ID", "untrusted-session");

        channel.writeInbound(request);

        FullHttpResponse response = channel.readOutbound();
        assertEquals(470, response.status().code());
        assertNull(sessions.findSession("untrusted-session"));
        assertFalse(consumer.playlistRemoved);
        response.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void retainsPlaylistSessionUntilTheHttpChannelCloses() {
        var identity = AirPlayIdentity.random();
        var config = config();
        config.setRequirePairing(false);
        var sessions = new SessionManager(identity, 4);
        var session = sessions.getSession("playlist-session");
        var channel = new EmbeddedChannel(handler(sessions, config, new NoopConsumer(), identity));
        var request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.GET,
                "/playlist/master.m3u8?session=playlist-session");

        channel.writeInbound(request);

        FullHttpResponse response = channel.readOutbound();
        assertEquals(HttpResponseStatus.SERVICE_UNAVAILABLE, response.status());
        sessions.removeSession(session.getId());
        assertEquals(session, sessions.findSession(session.getId()));
        response.release();
        channel.finishAndReleaseAll();
        assertNull(sessions.findSession(session.getId()));
    }

    @Test
    void acceptsHttpControlWhenPairingIsDisabled() {
        var identity = AirPlayIdentity.random();
        var config = config();
        config.setRequirePairing(false);
        var consumer = new NoopConsumer();
        var sessions = new SessionManager(identity, 4);
        var channel = new EmbeddedChannel(handler(sessions, config, consumer, identity));
        var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/stop");
        request.headers().set("X-Apple-Session-ID", "open-session");

        channel.writeInbound(request);

        FullHttpResponse response = channel.readOutbound();
        assertEquals(HttpResponseStatus.OK, response.status());
        assertTrue(consumer.playlistRemoved);
        response.release();
        channel.finishAndReleaseAll();
        assertNull(sessions.findSession("open-session"));
    }

    @Test
    void lateRtspTeardownFromRevokedControlCannotDisconnectReplacement() throws Exception {
        var identity = AirPlayIdentity.random();
        var config = config();
        config.setRequirePairing(false);
        var sessions = new DeferredCloseSessionManager(identity, 4);
        var consumer = new RecordingVideoConsumer();
        var coordinator = new SessionMediaCoordinator(sessions, consumer);
        var originalChannel = new EmbeddedChannel(handler(sessions, coordinator, config, consumer, identity));
        var replacementChannel = new EmbeddedChannel(handler(sessions, coordinator, config, consumer, identity));

        try {
            originalChannel.writeInbound(feedbackRequest("sender-a", 1));
            assertOkAndRelease(originalChannel);
            replacementChannel.writeInbound(feedbackRequest("sender-b", 1));
            assertOkAndRelease(replacementChannel);

            originalChannel.writeInbound(videoSetupRequest("sender-a", 2, 100));
            assertOkAndRelease(originalChannel);
            var originalSession = sessions.findSession("sender-a");
            assertNotNull(originalSession);
            assertTrue(originalSession.getVideoServer().isRunning());

            replacementChannel.writeInbound(videoSetupRequest("sender-b", 2, 200));
            assertOkAndRelease(replacementChannel);
            var replacementSession = sessions.findSession("sender-b");
            assertNotNull(replacementSession);
            assertFalse(originalSession.getVideoServer().isRunning());
            assertTrue(replacementSession.getVideoServer().isRunning());
            assertTrue(originalChannel.isActive(),
                    "The test keeps the revoked channel alive to deliver an in-flight request");

            originalChannel.writeInbound(videoTeardownRequest("sender-a", 3, 100));
            assertOkAndRelease(originalChannel);

            assertFalse(originalSession.getVideoServer().isRunning());
            assertTrue(replacementSession.getVideoServer().isRunning());
            assertEquals(0, consumer.videoDisconnectCount);
        } finally {
            originalChannel.finishAndReleaseAll();
            replacementChannel.finishAndReleaseAll();
        }
    }

    private static DefaultFullHttpRequest feedbackRequest(String sessionId, int sequenceNumber) {
        var request = new DefaultFullHttpRequest(
                RtspVersions.RTSP_1_0, HttpMethod.POST, "/feedback");
        request.headers().set(RtspHeaderNames.CSEQ, sequenceNumber);
        request.headers().set("Active-Remote", sessionId);
        HttpUtil.setKeepAlive(request, true);
        return request;
    }

    private static DefaultFullHttpRequest videoSetupRequest(
            String sessionId,
            int sequenceNumber,
            long streamConnectionId) throws Exception {
        return videoStreamRequest(
                RtspMethods.SETUP, sessionId, sequenceNumber, streamConnectionId);
    }

    private static DefaultFullHttpRequest videoTeardownRequest(
            String sessionId,
            int sequenceNumber,
            long streamConnectionId) throws Exception {
        return videoStreamRequest(
                RtspMethods.TEARDOWN, sessionId, sequenceNumber, streamConnectionId);
    }

    private static DefaultFullHttpRequest videoStreamRequest(
            HttpMethod method,
            String sessionId,
            int sequenceNumber,
            long streamConnectionId) throws Exception {
        var stream = new NSDictionary();
        stream.put("type", 110);
        stream.put("streamConnectionID", streamConnectionId);
        var body = new NSDictionary();
        body.put("streams", new NSArray(stream));
        byte[] payload = BinaryPropertyListWriter.writeToArray(body);
        var request = new DefaultFullHttpRequest(
                RtspVersions.RTSP_1_0, method, "/stream", Unpooled.wrappedBuffer(payload));
        request.headers().set(RtspHeaderNames.CSEQ, sequenceNumber);
        request.headers().set("Active-Remote", sessionId);
        request.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/x-apple-binary-plist");
        HttpUtil.setContentLength(request, payload.length);
        HttpUtil.setKeepAlive(request, true);
        return request;
    }

    private static void assertOkAndRelease(EmbeddedChannel channel) {
        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(HttpResponseStatus.OK, response.status());
        response.release();
    }

    private static ControlHandler handler(
            SessionManager sessions,
            AirPlayConfig config,
            AirPlayConsumer consumer,
            AirPlayIdentity identity) {
        return handler(
                sessions, new SessionMediaCoordinator(sessions, consumer), config, consumer, identity);
    }

    private static ControlHandler handler(
            SessionManager sessions,
            SessionMediaCoordinator mediaCoordinator,
            AirPlayConfig config,
            AirPlayConsumer consumer,
            AirPlayIdentity identity) {
        return new ControlHandler(sessions, mediaCoordinator, config, consumer, identity);
    }

    private AirPlayConfig config() {
        var config = new AirPlayConfig();
        config.setServerName("test");
        config.setWidth("1920");
        config.setHeight("1080");
        config.setFps("60");
        return config;
    }

    private static class NoopConsumer implements AirPlayConsumer {
        private boolean playlistRemoved;

        @Override public void onVideoFormat(VideoStreamInfo info) { }
        @Override public void onVideo(byte[] bytes) { }
        @Override public void onVideoSrcDisconnect() { }
        @Override public void onAudioFormat(AudioStreamInfo info) { }
        @Override public void onAudio(byte[] bytes) { }
        @Override public void onAudioSrcDisconnect() { }
        @Override public void onMediaPlaylistRemove() { playlistRemoved = true; }
    }

    private static final class RecordingVideoConsumer extends NoopConsumer {
        private int videoDisconnectCount;

        @Override
        public void onVideoSrcDisconnect() {
            videoDisconnectCount++;
        }
    }

    private static final class DeferredCloseSessionManager extends SessionManager {

        private DeferredCloseSessionManager(AirPlayIdentity identity, int maxJitterPackets) {
            super(identity, maxJitterPackets);
        }

        @Override
        public synchronized ControlSession openControlSession(String sessionId, Runnable ignoredCloseAction) {
            return super.openControlSession(sessionId, () -> { });
        }
    }
}
