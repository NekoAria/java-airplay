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
import io.netty.handler.codec.rtsp.RtspHeaderNames;
import io.netty.handler.codec.rtsp.RtspVersions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private static class NoopConsumer implements AirPlayConsumer {
        @Override public void onVideoFormat(VideoStreamInfo info) { }
        @Override public void onVideo(byte[] bytes) { }
        @Override public void onVideoSrcDisconnect() { }
        @Override public void onAudioFormat(AudioStreamInfo info) { }
        @Override public void onAudio(byte[] bytes) { }
        @Override public void onAudioSrcDisconnect() { }
    }
}
