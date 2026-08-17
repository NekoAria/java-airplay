package wtf.nanoka.airplay.server.internal.handler.session;

import wtf.nanoka.airplay.lib.AirPlayIdentity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SessionManager {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final AirPlayIdentity identity;
    private final int maxJitterPackets;

    public SessionManager(AirPlayIdentity identity, int maxJitterPackets) {
        this.identity = identity;
        this.maxJitterPackets = maxJitterPackets;
    }

    public Session getSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("AirPlay session id is required");
        }
        return sessions.computeIfAbsent(sessionId, id -> new Session(id, identity, maxJitterPackets));
    }

    public void removeSession(String sessionId) {
        var session = sessions.remove(sessionId);
        if (session != null) {
            session.stop();
        }
    }

    public void removeContexts(io.netty.channel.ChannelHandlerContext context) {
        sessions.values().forEach(session -> session.removeContext(context));
    }

    public void stopAll() {
        sessions.values().forEach(Session::stop);
        sessions.clear();
    }
}
