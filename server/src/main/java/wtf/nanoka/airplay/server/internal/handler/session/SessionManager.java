package wtf.nanoka.airplay.server.internal.handler.session;

import wtf.nanoka.airplay.lib.AirPlayIdentity;

import java.net.InetAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SessionManager {

    private static final int MAX_HTTP_SESSIONS_PER_PEER = 32;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final AirPlayIdentity identity;
    private final int maxJitterPackets;
    private final Map<InetAddress, Integer> pairedPeers = new ConcurrentHashMap<>();
    private final Map<InetAddress, Set<String>> httpSessionsByPeer = new ConcurrentHashMap<>();

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

    public Session findSession(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? null : sessions.get(sessionId);
    }

    public void markPeerPaired(InetAddress address) {
        if (address != null) {
            pairedPeers.merge(address, 1, Integer::sum);
        }
    }

    public void unmarkPeerPaired(InetAddress address) {
        if (address != null) {
            pairedPeers.computeIfPresent(address, (ignored, count) -> count > 1 ? count - 1 : null);
        }
    }

    public boolean isPeerPaired(InetAddress address) {
        return address != null && pairedPeers.containsKey(address);
    }

    public boolean authorizeHttpSession(Session session, InetAddress address) {
        if (session == null || address == null) {
            return false;
        }
        var sessionIds = httpSessionsByPeer.computeIfAbsent(address, ignored -> ConcurrentHashMap.newKeySet());
        synchronized (sessionIds) {
            if (!sessionIds.contains(session.getId()) && sessionIds.size() >= MAX_HTTP_SESSIONS_PER_PEER) {
                return false;
            }
            if (!session.authorizeHttp(address)) {
                return false;
            }
            sessionIds.add(session.getId());
            return true;
        }
    }

    public void removeSession(String sessionId) {
        var session = sessions.remove(sessionId);
        if (session != null) {
            session.stop();
            httpSessionsByPeer.values().forEach(sessionIds -> sessionIds.remove(sessionId));
            httpSessionsByPeer.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        }
    }

    public void removeContexts(io.netty.channel.ChannelHandlerContext context) {
        sessions.values().forEach(session -> session.removeContext(context));
    }

    public void stopAll() {
        sessions.values().forEach(Session::stop);
        sessions.clear();
        pairedPeers.clear();
        httpSessionsByPeer.clear();
    }
}
