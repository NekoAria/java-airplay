package wtf.nanoka.airplay.server.internal.handler.session;

import lombok.extern.slf4j.Slf4j;
import wtf.nanoka.airplay.server.AirPlayConsumer;
import wtf.nanoka.airplay.server.internal.handler.session.SessionAirPlayConsumer.StreamKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
public final class SessionMediaCoordinator {

    private final SessionManager sessionManager;
    private final AirPlayConsumer delegate;
    private final ReentrantLock transitionLock = new ReentrantLock(true);
    private final SessionMediaCallbackGate callbackGate = new SessionMediaCallbackGate();
    private final SourceStopper sourceStopper;
    private final List<SourceStop> pendingSourceStops = new ArrayList<>();

    private volatile boolean acceptingControlOperations = true;

    public SessionMediaCoordinator(SessionManager sessionManager, AirPlayConsumer delegate) {
        this(sessionManager, delegate, SessionMediaCoordinator::stopSessionSource);
    }

    SessionMediaCoordinator(
            SessionManager sessionManager,
            AirPlayConsumer delegate,
            SourceStopper sourceStopper) {
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.sourceStopper = Objects.requireNonNull(sourceStopper, "sourceStopper");
    }

    public void resume() {
        acceptingControlOperations = true;
    }

    public void pause() {
        acceptingControlOperations = false;
    }

    public boolean isInMediaCallback() {
        return callbackGate.isInCallback();
    }

    public <T> Optional<T> setupTiming(
            SessionManager.ControlSession controlSession,
            ControlOperation<T> operation) throws Exception {
        Objects.requireNonNull(controlSession, "controlSession");
        Objects.requireNonNull(operation, "operation");
        transitionLock.lock();
        try {
            if (!acceptingControlOperations || !retryPendingSourceStops()) {
                return Optional.empty();
            }
            SessionManager.Activation activation = sessionManager.claimActiveControl(controlSession);
            if (!activation.accepted()) {
                return Optional.empty();
            }

            SessionManager.TimingLease replacedLease = null;
            if (!activation.activeChanged()) {
                replacedLease = sessionManager.closeTimingLease(controlSession);
            }
            var sourcesToStop = staleSourceStops(activation);
            if (replacedLease != null) {
                sourcesToStop.add(new SourceStop(
                        controlSession.getSession(), SourceKind.TIMING, null));
            }
            boolean sourcesStopped = stopSourcesOrDefer(sourcesToStop);
            activation.controlsToClose().forEach(this::closeChannel);
            if (!sourcesStopped) {
                sessionManager.releaseActiveControlIfIdle(controlSession);
                return Optional.empty();
            }

            SessionManager.TimingLease newLease = sessionManager.openTimingLease(controlSession);
            try {
                T result = Objects.requireNonNull(
                        operation.run(), "timing setup result");
                if (!acceptingControlOperations
                        || !sessionManager.ownsTimingLease(controlSession, newLease)) {
                    rollbackTimingSetup(controlSession, newLease);
                    return Optional.empty();
                }
                return Optional.of(result);
            } catch (Exception | Error failure) {
                rollbackTimingSetup(controlSession, newLease);
                throw failure;
            }
        } finally {
            transitionLock.unlock();
        }
    }

    public <T> Optional<T> runControlOperation(
            SessionManager.ControlSession controlSession,
            ControlOperation<T> operation) throws Exception {
        Objects.requireNonNull(controlSession, "controlSession");
        Objects.requireNonNull(operation, "operation");
        transitionLock.lock();
        try {
            if (!acceptingControlOperations || !sessionManager.isControlSessionUsable(
                    controlSession, controlSession.getSession())) {
                return Optional.empty();
            }
            return Optional.of(Objects.requireNonNull(
                    operation.run(), "control operation result"));
        } finally {
            transitionLock.unlock();
        }
    }

    public <T> Optional<T> setupVideo(
            SessionManager.ControlSession controlSession,
            MediaSetup<T> setup) throws Exception {
        return setupVideo(controlSession, setup, () -> { });
    }

    public <T> Optional<T> setupVideo(
            SessionManager.ControlSession controlSession,
            MediaSetup<T> setup,
            Runnable commit) throws Exception {
        return setup(controlSession, StreamKind.VIDEO, setup, commit);
    }

    public <T> Optional<T> setupAudio(
            SessionManager.ControlSession controlSession,
            MediaSetup<T> setup) throws Exception {
        return setupAudio(controlSession, setup, () -> { });
    }

    public <T> Optional<T> setupAudio(
            SessionManager.ControlSession controlSession,
            MediaSetup<T> setup,
            Runnable commit) throws Exception {
        return setup(controlSession, StreamKind.AUDIO, setup, commit);
    }

    public void disconnectVideo(SessionManager.ControlSession controlSession) {
        disconnect(controlSession, StreamKind.VIDEO);
    }

    public void disconnectAudio(SessionManager.ControlSession controlSession) {
        disconnect(controlSession, StreamKind.AUDIO);
    }

    public void disconnectAll(SessionManager.ControlSession controlSession) {
        Objects.requireNonNull(controlSession, "controlSession");
        transitionLock.lock();
        try {
            SessionManager.MediaLease videoLease = sessionManager.closeVideoLease(controlSession);
            SessionManager.MediaLease audioLease = sessionManager.closeAudioLease(controlSession);
            stopSourcesOrDefer(sourceStops(
                    controlSession.getSession(), videoLease, audioLease, null));
            sessionManager.releaseActiveControlIfIdle(controlSession);
        } finally {
            transitionLock.unlock();
        }
    }

    public void controlDisconnected(SessionManager.ControlSession controlSession) {
        if (controlSession == null) {
            return;
        }
        transitionLock.lock();
        try {
            SessionManager.Deactivation deactivation = sessionManager.deactivateControlSession(controlSession);
            stopSourcesOrDefer(sourceStops(
                    controlSession.getSession(),
                    deactivation.videoLease(),
                    deactivation.audioLease(),
                    deactivation.timingLease()));

            Session removedSession = sessionManager.closeControlSession(controlSession);
            if (removedSession != null && tryStopSession(removedSession)) {
                completePendingSourceStops(removedSession);
            }
        } finally {
            transitionLock.unlock();
        }
    }

    public void stopAll() {
        transitionLock.lock();
        try {
            acceptingControlOperations = false;
            retryPendingSourceStops();
            SessionManager.Deactivation deactivation = sessionManager.clearMediaOwnership();
            SessionManager.ControlSession activeControl = deactivation.controlSession();
            if (activeControl != null) {
                stopSourcesOrDefer(sourceStops(
                        activeControl.getSession(),
                        deactivation.videoLease(),
                        deactivation.audioLease(),
                        deactivation.timingLease()));
            }
            closeChannel(activeControl);
        } finally {
            transitionLock.unlock();
        }
    }

    private <T> Optional<T> setup(
            SessionManager.ControlSession controlSession,
            StreamKind streamKind,
            MediaSetup<T> setup,
            Runnable commit) throws Exception {
        Objects.requireNonNull(controlSession, "controlSession");
        Objects.requireNonNull(setup, "setup");
        Objects.requireNonNull(commit, "commit");

        transitionLock.lock();
        try {
            if (!acceptingControlOperations || !retryPendingSourceStops()) {
                return Optional.empty();
            }

            SessionManager.Activation activation = sessionManager.claimActiveControl(controlSession);
            if (!activation.accepted()) {
                return Optional.empty();
            }

            SessionManager.MediaLease replacedLease = null;
            if (!activation.activeChanged()) {
                replacedLease = closeLease(controlSession, streamKind);
            }

            var sourcesToStop = staleSourceStops(activation);
            if (replacedLease != null) {
                sourcesToStop.add(new SourceStop(
                        controlSession.getSession(), sourceKind(streamKind), replacedLease));
            }
            boolean sourcesStopped = stopSourcesOrDefer(sourcesToStop);
            activation.controlsToClose().forEach(this::closeChannel);
            if (!sourcesStopped) {
                sessionManager.releaseActiveControlIfIdle(controlSession);
                return Optional.empty();
            }

            SessionManager.MediaLease newLease = openLease(controlSession, streamKind);
            var sessionConsumer = new SessionAirPlayConsumer(
                    sessionManager,
                    controlSession,
                    newLease,
                    streamKind,
                    delegate,
                    callbackGate);
            try {
                T result = Objects.requireNonNull(setup.start(sessionConsumer), "media setup result");
                if (!acceptingControlOperations
                        || !ownsLease(controlSession, streamKind, newLease)) {
                    rollbackSetup(controlSession, streamKind, newLease);
                    return Optional.empty();
                }
                commit.run();
                if (!acceptingControlOperations
                        || !ownsLease(controlSession, streamKind, newLease)) {
                    rollbackSetup(controlSession, streamKind, newLease);
                    return Optional.empty();
                }
                return Optional.of(result);
            } catch (Exception | Error failure) {
                rollbackSetup(controlSession, streamKind, newLease);
                throw failure;
            }
        } finally {
            transitionLock.unlock();
        }
    }

    private void disconnect(
            SessionManager.ControlSession controlSession,
            StreamKind streamKind) {
        Objects.requireNonNull(controlSession, "controlSession");
        transitionLock.lock();
        try {
            SessionManager.MediaLease lease = closeLease(controlSession, streamKind);
            if (lease != null) {
                stopSourceOrDefer(
                        controlSession.getSession(), sourceKind(streamKind), lease);
            }
            sessionManager.releaseActiveControlIfIdle(controlSession);
        } finally {
            transitionLock.unlock();
        }
    }

    private void rollbackTimingSetup(
            SessionManager.ControlSession controlSession,
            SessionManager.TimingLease expectedLease) {
        sessionManager.closeTimingLease(controlSession, expectedLease);
        if (!sessionManager.hasTimingLease(controlSession.getSession())) {
            stopSourceOrDefer(
                    controlSession.getSession(), SourceKind.TIMING, null);
        }
        sessionManager.releaseActiveControlIfIdle(controlSession);
    }

    private void rollbackSetup(
            SessionManager.ControlSession controlSession,
            StreamKind streamKind,
            SessionManager.MediaLease expectedLease) {
        SessionManager.MediaLease removedLease = closeLease(controlSession, streamKind, expectedLease);
        if (!hasLease(controlSession.getSession(), streamKind)) {
            stopSourceOrDefer(
                    controlSession.getSession(),
                    sourceKind(streamKind),
                    removedLease != null ? removedLease : expectedLease);
        } else {
            awaitCallbacks(expectedLease);
            notifyDisconnect(expectedLease, streamKind);
        }
        sessionManager.releaseActiveControlIfIdle(controlSession);
    }

    private void awaitCallbacks(SessionManager.MediaLease... leases) {
        for (SessionManager.MediaLease lease : leases) {
            callbackGate.awaitDrained(lease);
        }
    }

    private boolean retryPendingSourceStops() {
        if (pendingSourceStops.isEmpty()) {
            return true;
        }
        var stopsToRetry = List.copyOf(pendingSourceStops);
        pendingSourceStops.clear();
        return stopSourcesOrDefer(stopsToRetry);
    }

    private List<SourceStop> staleSourceStops(SessionManager.Activation activation) {
        SessionManager.ControlSession previousControl = activation.previousActive();
        if (previousControl == null) {
            return new ArrayList<>();
        }
        return sourceStops(
                previousControl.getSession(),
                activation.previousVideoLease(),
                activation.previousAudioLease(),
                activation.previousTimingLease());
    }

    private List<SourceStop> sourceStops(
            Session session,
            SessionManager.MediaLease videoLease,
            SessionManager.MediaLease audioLease,
            SessionManager.TimingLease timingLease) {
        var sourceStops = new ArrayList<SourceStop>();
        if (videoLease != null) {
            sourceStops.add(new SourceStop(session, SourceKind.VIDEO, videoLease));
        }
        if (audioLease != null) {
            sourceStops.add(new SourceStop(session, SourceKind.AUDIO, audioLease));
        }
        if (timingLease != null) {
            sourceStops.add(new SourceStop(session, SourceKind.TIMING, null));
        }
        return sourceStops;
    }

    private void stopSourceOrDefer(
            Session session,
            SourceKind sourceKind,
            SessionManager.MediaLease mediaLease) {
        stopSourcesOrDefer(List.of(new SourceStop(session, sourceKind, mediaLease)));
    }

    /**
     * Stops revoked sources before their disconnect callbacks. Failed stops stay
     * pending, and every later setup retries them before claiming a new lease.
     */
    private boolean stopSourcesOrDefer(List<SourceStop> sourceStops) {
        var successfulStops = new ArrayList<SourceStop>(sourceStops.size());
        Throwable stopFailure = null;
        for (SourceStop sourceStop : sourceStops) {
            try {
                sourceStopper.stop(sourceStop.session(), sourceStop.kind());
                successfulStops.add(sourceStop);
            } catch (RuntimeException | LinkageError error) {
                if (!pendingSourceStops.contains(sourceStop)) {
                    pendingSourceStops.add(sourceStop);
                }
                stopFailure = appendFailure(
                        stopFailure, sourceStopFailure(sourceStop, error));
            }
        }
        successfulStops.forEach(this::completeSourceStop);
        if (stopFailure == null) {
            return true;
        }
        log.error("Unable to stop revoked AirPlay sources; "
                + "new leases will be rejected until cleanup succeeds", stopFailure);
        return false;
    }

    private void completeSourceStop(SourceStop sourceStop) {
        SessionManager.MediaLease lease = sourceStop.mediaLease();
        if (lease == null) {
            return;
        }
        StreamKind streamKind = sourceStop.kind() == SourceKind.VIDEO
                ? StreamKind.VIDEO : StreamKind.AUDIO;
        awaitCallbacks(lease);
        notifyDisconnect(lease, streamKind);
    }

    private IllegalStateException sourceStopFailure(SourceStop sourceStop, Throwable cause) {
        String sourceName = switch (sourceStop.kind()) {
            case VIDEO -> "video";
            case AUDIO -> "audio";
            case TIMING -> "timing";
        };
        return new IllegalStateException(
                "Unable to stop revoked AirPlay " + sourceName
                        + " source for session " + sourceStop.session().getId(),
                cause);
    }

    private Throwable appendFailure(Throwable failure, Throwable next) {
        if (failure == null) {
            return next;
        }
        failure.addSuppressed(next);
        return failure;
    }

    private static SourceKind sourceKind(StreamKind streamKind) {
        return streamKind == StreamKind.VIDEO ? SourceKind.VIDEO : SourceKind.AUDIO;
    }

    private static void stopSessionSource(Session session, SourceKind sourceKind) {
        switch (sourceKind) {
            case VIDEO -> session.stopVideo();
            case AUDIO -> session.stopAudio();
            case TIMING -> session.stopTiming();
        }
    }

    private void completePendingSourceStops(Session session) {
        // A successful full session stop proves that every source for this
        // detached session has been cleaned up, even if the first local stop failed.
        var completedStops = pendingSourceStops.stream()
                .filter(sourceStop -> sourceStop.session() == session)
                .toList();
        pendingSourceStops.removeAll(completedStops);
        completedStops.forEach(this::completeSourceStop);
    }

    private boolean tryStopSession(Session session) {
        try {
            session.stop();
            return true;
        } catch (RuntimeException | LinkageError error) {
            log.warn("Unable to stop AirPlay session {}", session.getId(), error);
            return false;
        }
    }

    private void notifyDisconnect(
            SessionManager.MediaLease lease,
            StreamKind streamKind) {
        if (lease == null || !lease.markDisconnected()) {
            return;
        }
        try {
            if (streamKind == StreamKind.VIDEO) {
                delegate.onVideoSrcDisconnect();
            } else {
                delegate.onAudioSrcDisconnect();
            }
        } catch (RuntimeException | LinkageError error) {
            log.warn("AirPlay {} consumer disconnect failed", streamKind.name().toLowerCase(), error);
        }
    }

    private boolean hasLease(Session session, StreamKind streamKind) {
        return streamKind == StreamKind.VIDEO
                ? sessionManager.hasVideoLease(session)
                : sessionManager.hasAudioLease(session);
    }

    private boolean ownsLease(
            SessionManager.ControlSession controlSession,
            StreamKind streamKind,
            SessionManager.MediaLease lease) {
        return streamKind == StreamKind.VIDEO
                ? sessionManager.ownsVideoLease(controlSession, lease)
                : sessionManager.ownsAudioLease(controlSession, lease);
    }

    private SessionManager.MediaLease openLease(
            SessionManager.ControlSession controlSession,
            StreamKind streamKind) {
        return streamKind == StreamKind.VIDEO
                ? sessionManager.openVideoLease(controlSession)
                : sessionManager.openAudioLease(controlSession);
    }

    private SessionManager.MediaLease closeLease(
            SessionManager.ControlSession controlSession,
            StreamKind streamKind) {
        return streamKind == StreamKind.VIDEO
                ? sessionManager.closeVideoLease(controlSession)
                : sessionManager.closeAudioLease(controlSession);
    }

    private SessionManager.MediaLease closeLease(
            SessionManager.ControlSession controlSession,
            StreamKind streamKind,
            SessionManager.MediaLease expectedLease) {
        return streamKind == StreamKind.VIDEO
                ? sessionManager.closeVideoLease(controlSession, expectedLease)
                : sessionManager.closeAudioLease(controlSession, expectedLease);
    }

    private void closeChannel(SessionManager.ControlSession controlSession) {
        if (controlSession == null) {
            return;
        }
        try {
            controlSession.closeChannel();
        } catch (RuntimeException | LinkageError error) {
            log.warn("Unable to close stale AirPlay control channel for session {}",
                    controlSession.getSession().getId(), error);
        }
    }

    enum SourceKind {
        VIDEO,
        AUDIO,
        TIMING
    }

    @FunctionalInterface
    interface SourceStopper {
        void stop(Session session, SourceKind sourceKind);
    }

    private record SourceStop(
            Session session,
            SourceKind kind,
            SessionManager.MediaLease mediaLease) {
    }

    @FunctionalInterface
    public interface ControlOperation<T> {
        T run() throws Exception;
    }

    @FunctionalInterface
    public interface MediaSetup<T> {
        T start(AirPlayConsumer airPlayConsumer) throws Exception;
    }
}
