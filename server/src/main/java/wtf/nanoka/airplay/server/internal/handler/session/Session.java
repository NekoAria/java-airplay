package wtf.nanoka.airplay.server.internal.handler.session;

import wtf.nanoka.airplay.lib.AirPlay;
import wtf.nanoka.airplay.lib.AirPlayIdentity;
import wtf.nanoka.airplay.server.internal.AudioControlServer;
import wtf.nanoka.airplay.server.internal.AudioServer;
import wtf.nanoka.airplay.server.internal.VideoServer;
import wtf.nanoka.airplay.server.internal.TimingServer;
import io.netty.channel.ChannelHandlerContext;
import lombok.Getter;

import java.util.Map;
import java.net.InetAddress;
import java.util.concurrent.ConcurrentHashMap;

@Getter
public class Session {

    private final String id;

    private final AirPlay airPlay;
    private final VideoServer videoServer;
    private final AudioServer audioServer;
    private final AudioControlServer audioControlServer;
    private final TimingServer timingServer;
    private final Map<String, ChannelHandlerContext> reverseContexts;
    private final Map<String, ChannelHandlerContext> playlistRequestContexts;
    private InetAddress httpPeerAddress;

    Session(String id, AirPlayIdentity identity, int maxJitterPackets) {
        this.id = id;
        airPlay = new AirPlay(identity);
        videoServer = new VideoServer(airPlay);
        audioControlServer = new AudioControlServer();
        audioServer = new AudioServer(airPlay, audioControlServer::requestResend, maxJitterPackets);
        audioControlServer.setResentPacketConsumer(audioServer::acceptResentPacket);
        audioControlServer.setSyncConsumer(audioServer::updateSync);
        timingServer = new TimingServer();
        reverseContexts = new ConcurrentHashMap<>();
        playlistRequestContexts = new ConcurrentHashMap<>();
    }

    public void removeContext(ChannelHandlerContext context) {
        reverseContexts.values().removeIf(context::equals);
        playlistRequestContexts.values().removeIf(context::equals);
    }

    public synchronized void stopVideo() {
        videoServer.stop();
    }

    public synchronized void stopAudio() {
        audioServer.stop();
        audioControlServer.stop();
    }

    public synchronized void stopTiming() {
        timingServer.stop();
    }

    public synchronized void stop() {
        stopVideo();
        stopAudio();
        stopTiming();
        reverseContexts.clear();
        playlistRequestContexts.clear();
    }

    public synchronized boolean hasActiveStreams() {
        return videoServer.isRunning() || audioServer.isRunning();
    }

    public synchronized boolean authorizeHttp(InetAddress peerAddress) {
        if (peerAddress == null) {
            return false;
        }
        if (httpPeerAddress == null) {
            httpPeerAddress = peerAddress;
        }
        return httpPeerAddress.equals(peerAddress);
    }

    public synchronized boolean isHttpAuthorized(InetAddress peerAddress) {
        return httpPeerAddress != null && httpPeerAddress.equals(peerAddress);
    }

    public synchronized boolean hasHttpAuthorization() {
        return httpPeerAddress != null;
    }
}
