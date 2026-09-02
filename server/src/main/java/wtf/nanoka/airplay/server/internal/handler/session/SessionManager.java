package wtf.nanoka.airplay.server.internal.handler.session;

import wtf.nanoka.airplay.lib.AirPlayIdentity;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class SessionManager {

    private static final int MAX_HTTP_SESSIONS_PER_PEER = 32;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final AirPlayIdentity identity;
    private final int maxJitterPackets;
    private final Map<InetAddress, Integer> pairedPeers = new ConcurrentHashMap<>();
    private final Map<InetAddress, Set<String>> httpSessionsByPeer = new ConcurrentHashMap<>();
    private final Map<String, Integer> httpSessionReferences = new HashMap<>();
    private final Map<String, Set<ControlSession>> controlSessionsById = new HashMap<>();
    private final Map<String, ControlSession> lastClaimedControlBySessionId = new HashMap<>();

    private ControlSession activeControlSession;
    private MediaLease videoLease;
    private MediaLease audioLease;
    private TimingLease timingLease;

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

    public synchronized boolean authorizeHttpSession(Session session, InetAddress address) {
        if (session == null || address == null || sessions.get(session.getId()) != session) {
            return false;
        }
        var sessionIds = httpSessionsByPeer.computeIfAbsent(address, ignored -> ConcurrentHashMap.newKeySet());
        if (!sessionIds.contains(session.getId()) && sessionIds.size() >= MAX_HTTP_SESSIONS_PER_PEER) {
            return false;
        }
        if (!session.authorizeHttp(address)) {
            return false;
        }
        sessionIds.add(session.getId());
        return true;
    }

    public synchronized boolean retainHttpSession(Session session) {
        if (session == null || sessions.get(session.getId()) != session) {
            return false;
        }
        httpSessionReferences.merge(session.getId(), 1, Integer::sum);
        return true;
    }

    public void releaseHttpSession(String sessionId) {
        Session removedSession = null;
        synchronized (this) {
            Integer references = httpSessionReferences.get(sessionId);
            if (references == null) {
                return;
            }
            if (references > 1) {
                httpSessionReferences.put(sessionId, references - 1);
            } else {
                httpSessionReferences.remove(sessionId);
                removedSession = removeManagedSessionIfUnused(sessionId, null);
            }
        }
        if (removedSession != null) {
            removedSession.stop();
        }
    }

    public synchronized ControlSession openControlSession(String sessionId, Runnable closeAction) {
        return registerControlSession(getSession(sessionId), closeAction);
    }

    public synchronized ControlSession openControlSession(Session session, Runnable closeAction) {
        Objects.requireNonNull(session, "session");
        if (sessions.get(session.getId()) != session) {
            throw new IllegalStateException("Cannot bind a control channel to an inactive AirPlay session");
        }
        return registerControlSession(session, closeAction);
    }

    public synchronized boolean isControlSessionUsable(ControlSession controlSession, Session session) {
        return controlSession != null
                && controlSession.session == session
                && sessions.get(session.getId()) == session
                && controlSessionsById.getOrDefault(session.getId(), Set.of()).contains(controlSession)
                && !controlSession.closed
                && !controlSession.revoked;
    }

    synchronized Activation claimActiveControl(ControlSession controlSession) {
        if (controlSession.closed
                || controlSession.revoked
                || sessions.get(controlSession.session.getId()) != controlSession.session
                || !controlSessionsById.getOrDefault(
                        controlSession.session.getId(), Set.of()).contains(controlSession)) {
            controlSession.revoked = true;
            return Activation.rejected();
        }
        if (activeControlSession == controlSession) {
            return Activation.unchanged();
        }

        ControlSession previousActive = activeControlSession;
        MediaLease previousVideoLease = videoLease;
        MediaLease previousAudioLease = audioLease;
        TimingLease previousTimingLease = timingLease;
        var controlsToClose = new LinkedHashSet<ControlSession>();

        ControlSession previousClaimedControl = lastClaimedControlBySessionId.put(
                controlSession.session.getId(), controlSession);
        if (previousClaimedControl != null && previousClaimedControl != controlSession) {
            previousClaimedControl.revoked = true;
            controlsToClose.add(previousClaimedControl);
        }
        if (previousActive != null && previousActive != controlSession) {
            previousActive.revoked = true;
            controlsToClose.add(previousActive);
        }

        activeControlSession = controlSession;
        videoLease = null;
        audioLease = null;
        timingLease = null;
        return new Activation(
                true,
                true,
                previousActive,
                previousVideoLease,
                previousAudioLease,
                previousTimingLease,
                List.copyOf(controlsToClose));
    }

    synchronized Deactivation deactivateControlSession(ControlSession controlSession) {
        if (activeControlSession != controlSession) {
            return Deactivation.empty();
        }
        var deactivation = new Deactivation(
                controlSession, videoLease, audioLease, timingLease);
        activeControlSession = null;
        videoLease = null;
        audioLease = null;
        timingLease = null;
        return deactivation;
    }

    synchronized Deactivation clearMediaOwnership() {
        if (activeControlSession == null) {
            return Deactivation.empty();
        }
        ControlSession previousActive = activeControlSession;
        previousActive.revoked = true;
        var deactivation = new Deactivation(
                previousActive, videoLease, audioLease, timingLease);
        activeControlSession = null;
        videoLease = null;
        audioLease = null;
        timingLease = null;
        return deactivation;
    }

    synchronized MediaLease openVideoLease(ControlSession controlSession) {
        requireActiveControl(controlSession);
        videoLease = new MediaLease();
        return videoLease;
    }

    synchronized MediaLease openAudioLease(ControlSession controlSession) {
        requireActiveControl(controlSession);
        audioLease = new MediaLease();
        return audioLease;
    }

    synchronized boolean ownsVideoLease(ControlSession controlSession, MediaLease lease) {
        return activeControlSession == controlSession && videoLease == lease;
    }

    synchronized boolean ownsAudioLease(ControlSession controlSession, MediaLease lease) {
        return activeControlSession == controlSession && audioLease == lease;
    }

    synchronized TimingLease openTimingLease(ControlSession controlSession) {
        requireActiveControl(controlSession);
        timingLease = new TimingLease();
        return timingLease;
    }

    synchronized boolean ownsTimingLease(ControlSession controlSession, TimingLease lease) {
        return activeControlSession == controlSession && timingLease == lease;
    }

    synchronized TimingLease closeTimingLease(ControlSession controlSession) {
        if (activeControlSession != controlSession) {
            return null;
        }
        TimingLease previous = timingLease;
        timingLease = null;
        return previous;
    }

    synchronized TimingLease closeTimingLease(
            ControlSession controlSession,
            TimingLease expectedLease) {
        if (!ownsTimingLease(controlSession, expectedLease)) {
            return null;
        }
        timingLease = null;
        return expectedLease;
    }

    synchronized boolean hasTimingLease(Session session) {
        return activeControlSession != null
                && activeControlSession.session == session
                && timingLease != null;
    }

    synchronized boolean hasVideoLease(Session session) {
        return activeControlSession != null
                && activeControlSession.session == session
                && videoLease != null;
    }

    synchronized boolean hasAudioLease(Session session) {
        return activeControlSession != null
                && activeControlSession.session == session
                && audioLease != null;
    }

    synchronized MediaLease closeVideoLease(ControlSession controlSession) {
        if (activeControlSession != controlSession) {
            return null;
        }
        MediaLease previous = videoLease;
        videoLease = null;
        return previous;
    }

    synchronized MediaLease closeVideoLease(ControlSession controlSession, MediaLease expectedLease) {
        if (!ownsVideoLease(controlSession, expectedLease)) {
            return null;
        }
        videoLease = null;
        return expectedLease;
    }

    synchronized MediaLease closeAudioLease(ControlSession controlSession) {
        if (activeControlSession != controlSession) {
            return null;
        }
        MediaLease previous = audioLease;
        audioLease = null;
        return previous;
    }

    synchronized MediaLease closeAudioLease(ControlSession controlSession, MediaLease expectedLease) {
        if (!ownsAudioLease(controlSession, expectedLease)) {
            return null;
        }
        audioLease = null;
        return expectedLease;
    }

    synchronized void releaseActiveControlIfIdle(ControlSession controlSession) {
        if (activeControlSession == controlSession
                && videoLease == null
                && audioLease == null
                && timingLease == null) {
            activeControlSession = null;
        }
    }

    synchronized boolean isActiveSession(Session session) {
        return activeControlSession != null && activeControlSession.session == session;
    }

    Session closeControlSession(ControlSession controlSession) {
        Session removedSession;
        synchronized (this) {
            if (controlSession.closed) {
                return null;
            }
            controlSession.closed = true;
            controlSession.revoked = true;
            if (activeControlSession == controlSession) {
                activeControlSession = null;
                videoLease = null;
                audioLease = null;
                timingLease = null;
            }
            lastClaimedControlBySessionId.remove(controlSession.session.getId(), controlSession);
            Set<ControlSession> controls = controlSessionsById.get(controlSession.session.getId());
            if (controls != null) {
                controls.remove(controlSession);
                if (controls.isEmpty()) {
                    controlSessionsById.remove(controlSession.session.getId());
                }
            }
            removedSession = removeManagedSessionIfUnused(
                    controlSession.session.getId(), controlSession.session);
        }
        return removedSession;
    }

    public void removeSession(String sessionId) {
        Session removedSession;
        synchronized (this) {
            removedSession = removeManagedSessionIfUnused(sessionId, null);
        }
        if (removedSession != null) {
            removedSession.stop();
        }
    }

    public void removeContexts(io.netty.channel.ChannelHandlerContext context) {
        sessions.values().forEach(session -> session.removeContext(context));
    }

    public void stopAll() {
        List<Session> sessionsToStop;
        synchronized (this) {
            for (Set<ControlSession> controls : controlSessionsById.values()) {
                for (ControlSession controlSession : controls) {
                    controlSession.closed = true;
                    controlSession.revoked = true;
                }
            }
            controlSessionsById.clear();
            lastClaimedControlBySessionId.clear();
            activeControlSession = null;
            videoLease = null;
            audioLease = null;
            timingLease = null;
            sessionsToStop = new ArrayList<>(sessions.values());
            sessions.clear();
            pairedPeers.clear();
            httpSessionsByPeer.clear();
            httpSessionReferences.clear();
        }
        sessionsToStop.forEach(Session::stop);
    }

    private Session removeManagedSessionIfUnused(String sessionId, Session expectedSession) {
        if (controlSessionsById.containsKey(sessionId)
                || httpSessionReferences.containsKey(sessionId)) {
            return null;
        }
        Session managedSession = sessions.get(sessionId);
        if (managedSession == null || (expectedSession != null && managedSession != expectedSession)
                || !sessions.remove(sessionId, managedSession)) {
            return null;
        }
        removeHttpAuthorization(sessionId);
        return managedSession;
    }

    private ControlSession registerControlSession(Session session, Runnable closeAction) {
        var controlSession = new ControlSession(
                session, Objects.requireNonNull(closeAction, "closeAction"));
        controlSessionsById.computeIfAbsent(session.getId(), ignored -> new HashSet<>()).add(controlSession);
        return controlSession;
    }

    private void requireActiveControl(ControlSession controlSession) {
        if (activeControlSession != controlSession || controlSession.closed || controlSession.revoked) {
            throw new IllegalStateException("Cannot open media for an inactive AirPlay control session");
        }
    }

    private void removeHttpAuthorization(String sessionId) {
        httpSessionsByPeer.values().forEach(sessionIds -> sessionIds.remove(sessionId));
        httpSessionsByPeer.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    static final class Activation {
        private final boolean accepted;
        private final boolean activeChanged;
        private final ControlSession previousActive;
        private final MediaLease previousVideoLease;
        private final MediaLease previousAudioLease;
        private final TimingLease previousTimingLease;
        private final List<ControlSession> controlsToClose;

        private Activation(
                boolean accepted,
                boolean activeChanged,
                ControlSession previousActive,
                MediaLease previousVideoLease,
                MediaLease previousAudioLease,
                TimingLease previousTimingLease,
                List<ControlSession> controlsToClose) {
            this.accepted = accepted;
            this.activeChanged = activeChanged;
            this.previousActive = previousActive;
            this.previousVideoLease = previousVideoLease;
            this.previousAudioLease = previousAudioLease;
            this.previousTimingLease = previousTimingLease;
            this.controlsToClose = controlsToClose;
        }

        private static Activation rejected() {
            return new Activation(false, false, null, null, null, null, List.of());
        }

        private static Activation unchanged() {
            return new Activation(true, false, null, null, null, null, List.of());
        }

        boolean accepted() {
            return accepted;
        }

        boolean activeChanged() {
            return activeChanged;
        }

        ControlSession previousActive() {
            return previousActive;
        }

        MediaLease previousVideoLease() {
            return previousVideoLease;
        }

        MediaLease previousAudioLease() {
            return previousAudioLease;
        }

        TimingLease previousTimingLease() {
            return previousTimingLease;
        }

        List<ControlSession> controlsToClose() {
            return controlsToClose;
        }
    }

    record Deactivation(
            ControlSession controlSession,
            MediaLease videoLease,
            MediaLease audioLease,
            TimingLease timingLease) {

        private static Deactivation empty() {
            return new Deactivation(null, null, null, null);
        }
    }

    static final class TimingLease {
    }

    static final class MediaLease {
        private final AtomicBoolean connected = new AtomicBoolean();

        void markConnected() {
            connected.set(true);
        }

        boolean markDisconnected() {
            return connected.getAndSet(false);
        }
    }

    public static final class ControlSession {
        private final Session session;
        private final Runnable closeAction;
        private final AtomicBoolean closeRequested = new AtomicBoolean();
        private boolean revoked;
        private boolean closed;

        private ControlSession(Session session, Runnable closeAction) {
            this.session = session;
            this.closeAction = closeAction;
        }

        public Session getSession() {
            return session;
        }

        void closeChannel() {
            if (closeRequested.compareAndSet(false, true)) {
                closeAction.run();
            }
        }
    }
}
