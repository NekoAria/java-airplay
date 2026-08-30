package wtf.nanoka.airplay.server.internal.handler.control;

import wtf.nanoka.airplay.lib.AirPlayIdentity;
import wtf.nanoka.airplay.lib.AudioStreamInfo;
import wtf.nanoka.airplay.lib.VideoStreamInfo;
import wtf.nanoka.airplay.server.AirPlayConfig;
import wtf.nanoka.airplay.server.AirPlayConsumer;
import wtf.nanoka.airplay.server.internal.handler.session.SessionManager;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.rtsp.RtspHeaderNames;
import io.netty.handler.codec.rtsp.RtspVersions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        var channel = new EmbeddedChannel(new ControlHandler(
                new SessionManager(identity, 4), config, new NoopConsumer(), identity));
        var request = new DefaultFullHttpRequest(RtspVersions.RTSP_1_0, HttpMethod.GET, "/info");
        request.headers().set(RtspHeaderNames.CSEQ, "1");

        channel.writeInbound(request);

        assertEquals(0, request.refCnt());
        FullHttpResponse response = channel.readOutbound();
        response.release();
        channel.finishAndReleaseAll();
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
        var channel = new EmbeddedChannel(new ControlHandler(sessions, config, consumer, identity));
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
    void acceptsHttpControlWhenPairingIsDisabled() {
        var identity = AirPlayIdentity.random();
        var config = config();
        config.setRequirePairing(false);
        var consumer = new NoopConsumer();
        var channel = new EmbeddedChannel(new ControlHandler(
                new SessionManager(identity, 4), config, consumer, identity));
        var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/stop");
        request.headers().set("X-Apple-Session-ID", "open-session");

        channel.writeInbound(request);

        FullHttpResponse response = channel.readOutbound();
        assertEquals(HttpResponseStatus.OK, response.status());
        assertTrue(consumer.playlistRemoved);
        response.release();
        channel.finishAndReleaseAll();
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
}
