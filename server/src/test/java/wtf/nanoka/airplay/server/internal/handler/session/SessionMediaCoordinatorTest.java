package wtf.nanoka.airplay.server.internal.handler.session;

import org.junit.jupiter.api.Test;
import wtf.nanoka.airplay.lib.AirPlayIdentity;
import wtf.nanoka.airplay.lib.AudioStreamInfo;
import wtf.nanoka.airplay.lib.VideoStreamInfo;
import wtf.nanoka.airplay.server.AirPlayConsumer;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionMediaCoordinatorTest {

    @Test
    void newerSessionRejectsOldFramesTeardownAndDisconnects() throws Exception {
        var sessions = sessions();
        var consumer = new RecordingConsumer();
        var coordinator = new SessionMediaCoordinator(sessions, consumer);
        var firstSession = sessions.getSession("first");
        var secondSession = sessions.getSession("second");
        var firstChannelCloses = new AtomicInteger();
        var firstControl = sessions.openControlSession(firstSession, firstChannelCloses::incrementAndGet);
        var secondControl = sessions.openControlSession(secondSession, () -> { });

        AirPlayConsumer firstVideo = setupVideo(coordinator, firstControl, "first-video");
        AirPlayConsumer firstAudio = setupAudio(coordinator, firstControl);
        firstVideo.onVideo(new byte[]{1}, 100);
        firstAudio.onAudio(new byte[]{2}, 200, 3);

        AirPlayConsumer secondVideo = setupVideo(coordinator, secondControl, "second-video");

        assertEquals(1, firstChannelCloses.get());
        assertEquals(1, consumer.videoDisconnects.get());
        assertEquals(1, consumer.audioDisconnects.get());
        assertTrue(sessions.isActiveSession(secondSession));

        firstVideo.onVideo(new byte[]{4}, 400);
        firstAudio.onAudio(new byte[]{5}, 500, 6);
        firstVideo.onVideoSrcDisconnect();
        firstAudio.onAudioSrcDisconnect();
        coordinator.disconnectAll(firstControl);
        secondVideo.onVideo(new byte[]{7}, 700);

        assertEquals(List.of("1@100", "7@700"), consumer.videoFrames);
        assertEquals(List.of("2@200#3"), consumer.audioFrames);
        assertEquals(1, consumer.videoDisconnects.get());
        assertEquals(1, consumer.audioDisconnects.get());

        coordinator.controlDisconnected(firstControl);
        assertNull(sessions.findSession("first"));
        assertSame(secondSession, sessions.findSession("second"));

        coordinator.controlDisconnected(secondControl);
        secondVideo.onVideo(new byte[]{8}, 800);
        assertNull(sessions.findSession("second"));
        assertEquals(2, consumer.videoDisconnects.get());
        assertEquals(List.of("1@100", "7@700"), consumer.videoFrames);
    }

    @Test
    void repeatedSetupInvalidatesOnlyTheReplacedStream() throws Exception {
        var sessions = sessions();
        var consumer = new RecordingConsumer();
        var coordinator = new SessionMediaCoordinator(sessions, consumer);
        var session = sessions.getSession("repeated");
        var control = sessions.openControlSession(session, () -> { });

        AirPlayConsumer firstVideo = setupVideo(coordinator, control, "video-1");
        AirPlayConsumer audio = setupAudio(coordinator, control);
        AirPlayConsumer secondVideo = setupVideo(coordinator, control, "video-2");

        firstVideo.onVideo(new byte[]{1}, 10);
        audio.onAudio(new byte[]{2}, 20, 30);
        secondVideo.onVideo(new byte[]{3}, 40);

        assertEquals(List.of("3@40"), consumer.videoFrames);
        assertEquals(List.of("2@20#30"), consumer.audioFrames);
        assertEquals(1, consumer.videoDisconnects.get());
        assertEquals(0, consumer.audioDisconnects.get());
        assertTrue(sessions.isActiveSession(session));

        coordinator.disconnectAll(control);
        assertEquals(2, consumer.videoDisconnects.get());
        assertEquals(1, consumer.audioDisconnects.get());
        assertFalse(sessions.isActiveSession(session));
    }

    @Test
    void failedStaleSourceStopIsRetriedBeforeGrantingLease() throws Exception {
        var sessions = sessions();
        var consumer = new RecordingConsumer();
        var firstSession = sessions.getSession("stop-failure-first");
        var secondSession = sessions.getSession("stop-failure-second");
        var sourceStopAttempts = new CopyOnWriteArrayList<SessionMediaCoordinator.SourceKind>();
        var failNextVideoStop = new AtomicBoolean(true);
        var coordinator = new SessionMediaCoordinator(sessions, consumer, (session, sourceKind) -> {
            if (session != firstSession) {
                return;
            }
            sourceStopAttempts.add(sourceKind);
            if (sourceKind == SessionMediaCoordinator.SourceKind.VIDEO
                    && failNextVideoStop.getAndSet(false)) {
                throw new IllegalStateException("simulated video stop failure");
            }
        });
        var firstChannelCloses = new AtomicInteger();
        var firstControl = sessions.openControlSession(
                firstSession, firstChannelCloses::incrementAndGet);
        var secondControl = sessions.openControlSession(secondSession, () -> { });
        AirPlayConsumer firstVideo = setupVideo(coordinator, firstControl, "first-video");
        AirPlayConsumer firstAudio = setupAudio(coordinator, firstControl);
        firstVideo.onVideo(new byte[]{1}, 10);
        firstAudio.onAudio(new byte[]{2}, 20, 30);
        var replacementSetupCalls = new AtomicInteger();
        SessionMediaCoordinator.MediaSetup<AirPlayConsumer> replacementSetup = replacement -> {
            replacementSetupCalls.incrementAndGet();
            replacement.onVideoFormat(new VideoStreamInfo("replacement"));
            return replacement;
        };

        var rejectedSetup = coordinator.setupVideo(secondControl, replacementSetup);

        assertTrue(rejectedSetup.isEmpty());
        assertEquals(0, replacementSetupCalls.get());
        assertEquals(List.of(
                SessionMediaCoordinator.SourceKind.VIDEO,
                SessionMediaCoordinator.SourceKind.AUDIO), sourceStopAttempts);
        assertEquals(1, firstChannelCloses.get());
        assertEquals(0, consumer.videoDisconnects.get());
        assertEquals(1, consumer.audioDisconnects.get());
        assertFalse(sessions.isActiveSession(secondSession));

        firstVideo.onVideo(new byte[]{3}, 40);
        firstAudio.onAudio(new byte[]{4}, 50, 60);
        assertEquals(List.of("1@10"), consumer.videoFrames);
        assertEquals(List.of("2@20#30"), consumer.audioFrames);

        AirPlayConsumer replacementVideo = coordinator.setupVideo(
                secondControl, replacementSetup).orElseThrow();
        replacementVideo.onVideo(new byte[]{5}, 70);

        assertEquals(List.of(
                SessionMediaCoordinator.SourceKind.VIDEO,
                SessionMediaCoordinator.SourceKind.AUDIO,
                SessionMediaCoordinator.SourceKind.VIDEO), sourceStopAttempts);
        assertEquals(1, replacementSetupCalls.get());
        assertEquals(1, consumer.videoDisconnects.get());
        assertEquals(List.of("1@10", "5@70"), consumer.videoFrames);
        assertTrue(sessions.isActiveSession(secondSession));
    }

    @Test
    void disconnectAllStopFailureBlocksNewLeaseUntilCleanupSucceeds() throws Exception {
        var sessions = sessions();
        var consumer = new RecordingConsumer();
        var session = sessions.getSession("teardown-stop-failure");
        var videoStopAttempts = new AtomicInteger();
        var sourceStopAttempts = new CopyOnWriteArrayList<SessionMediaCoordinator.SourceKind>();
        var coordinator = new SessionMediaCoordinator(sessions, consumer, (stoppedSession, sourceKind) -> {
            assertSame(session, stoppedSession);
            sourceStopAttempts.add(sourceKind);
            if (sourceKind == SessionMediaCoordinator.SourceKind.VIDEO
                    && videoStopAttempts.incrementAndGet() <= 2) {
                throw new IllegalStateException("simulated video stop failure");
            }
        });
        var control = sessions.openControlSession(session, () -> { });
        AirPlayConsumer staleVideo = setupVideo(coordinator, control, "teardown-old-video");
        AirPlayConsumer staleAudio = setupAudio(coordinator, control);

        coordinator.disconnectAll(control);

        assertEquals(List.of(
                SessionMediaCoordinator.SourceKind.VIDEO,
                SessionMediaCoordinator.SourceKind.AUDIO), sourceStopAttempts);
        assertEquals(0, consumer.videoDisconnects.get());
        assertEquals(1, consumer.audioDisconnects.get());
        assertFalse(sessions.isActiveSession(session));
        staleVideo.onVideo(new byte[]{1}, 10);
        staleAudio.onAudio(new byte[]{2}, 20, 30);
        assertTrue(consumer.videoFrames.isEmpty());
        assertTrue(consumer.audioFrames.isEmpty());

        var replacementSetupCalls = new AtomicInteger();
        SessionMediaCoordinator.MediaSetup<AirPlayConsumer> replacementSetup = leased -> {
            replacementSetupCalls.incrementAndGet();
            leased.onVideoFormat(new VideoStreamInfo("teardown-new-video"));
            return leased;
        };
        assertTrue(coordinator.setupVideo(control, replacementSetup).isEmpty());
        assertEquals(0, replacementSetupCalls.get());
        assertEquals(0, consumer.videoDisconnects.get());

        AirPlayConsumer replacement = coordinator.setupVideo(control, replacementSetup).orElseThrow();
        replacement.onVideo(new byte[]{3}, 40);

        assertEquals(List.of(
                SessionMediaCoordinator.SourceKind.VIDEO,
                SessionMediaCoordinator.SourceKind.AUDIO,
                SessionMediaCoordinator.SourceKind.VIDEO,
                SessionMediaCoordinator.SourceKind.VIDEO), sourceStopAttempts);
        assertEquals(1, replacementSetupCalls.get());
        assertEquals(1, consumer.videoDisconnects.get());
        assertEquals(List.of("3@40"), consumer.videoFrames);
        assertTrue(sessions.isActiveSession(session));
    }

    @Test
    void disconnectVideoStopFailureBlocksReplacementUntilRetrySucceeds() throws Exception {
        var sessions = sessions();
        var consumer = new RecordingConsumer();
        var session = sessions.getSession("stream-teardown-stop-failure");
        var stopAttempts = new AtomicInteger();
        var coordinator = new SessionMediaCoordinator(sessions, consumer, (stoppedSession, sourceKind) -> {
            assertSame(session, stoppedSession);
            assertEquals(SessionMediaCoordinator.SourceKind.VIDEO, sourceKind);
            if (stopAttempts.incrementAndGet() <= 2) {
                throw new IllegalStateException("simulated stream teardown stop failure");
            }
        });
        var control = sessions.openControlSession(session, () -> { });
        AirPlayConsumer staleVideo = setupVideo(coordinator, control, "stream-teardown-old-video");

        coordinator.disconnectVideo(control);

        assertEquals(1, stopAttempts.get());
        assertEquals(0, consumer.videoDisconnects.get());
        assertFalse(sessions.isActiveSession(session));
        staleVideo.onVideo(new byte[]{1}, 10);
        assertTrue(consumer.videoFrames.isEmpty());

        var replacementSetupCalls = new AtomicInteger();
        SessionMediaCoordinator.MediaSetup<AirPlayConsumer> replacementSetup = leased -> {
            replacementSetupCalls.incrementAndGet();
            leased.onVideoFormat(new VideoStreamInfo("stream-teardown-new-video"));
            return leased;
        };
        assertTrue(coordinator.setupVideo(control, replacementSetup).isEmpty());
        assertEquals(2, stopAttempts.get());
        assertEquals(0, replacementSetupCalls.get());

        AirPlayConsumer replacement = coordinator.setupVideo(control, replacementSetup).orElseThrow();
        replacement.onVideo(new byte[]{2}, 20);

        assertEquals(3, stopAttempts.get());
        assertEquals(1, replacementSetupCalls.get());
        assertEquals(1, consumer.videoDisconnects.get());
        assertEquals(List.of("2@20"), consumer.videoFrames);
        assertTrue(sessions.isActiveSession(session));
    }

    @Test
    void rollbackStopFailureBlocksRetryUntilCleanupSucceeds() throws Exception {
        var sessions = sessions();
        var consumer = new RecordingConsumer();
        var session = sessions.getSession("rollback-stop-failure");
        var videoStopAttempts = new AtomicInteger();
        var coordinator = new SessionMediaCoordinator(sessions, consumer, (stoppedSession, sourceKind) -> {
            assertSame(session, stoppedSession);
            assertEquals(SessionMediaCoordinator.SourceKind.VIDEO, sourceKind);
            if (videoStopAttempts.incrementAndGet() <= 2) {
                throw new IllegalStateException("simulated rollback stop failure");
            }
        });
        var control = sessions.openControlSession(session, () -> { });
        var failedConsumer = new AtomicReference<AirPlayConsumer>();

        var failure = assertThrows(IllegalStateException.class, () -> coordinator.setupVideo(
                control,
                leased -> {
                    failedConsumer.set(leased);
                    leased.onVideoFormat(new VideoStreamInfo("rollback-failed-video"));
                    throw new IllegalStateException("video bind failed");
                }));

        assertEquals("video bind failed", failure.getMessage());
        assertEquals(1, videoStopAttempts.get());
        assertEquals(0, consumer.videoDisconnects.get());
        assertFalse(sessions.isActiveSession(session));
        failedConsumer.get().onVideo(new byte[]{1}, 10);
        assertTrue(consumer.videoFrames.isEmpty());

        var replacementSetupCalls = new AtomicInteger();
        SessionMediaCoordinator.MediaSetup<AirPlayConsumer> replacementSetup = leased -> {
            replacementSetupCalls.incrementAndGet();
            leased.onVideoFormat(new VideoStreamInfo("rollback-new-video"));
            return leased;
        };
        assertTrue(coordinator.setupVideo(control, replacementSetup).isEmpty());
        assertEquals(2, videoStopAttempts.get());
        assertEquals(0, replacementSetupCalls.get());
        assertEquals(0, consumer.videoDisconnects.get());

        AirPlayConsumer replacement = coordinator.setupVideo(control, replacementSetup).orElseThrow();
        replacement.onVideo(new byte[]{2}, 20);

        assertEquals(3, videoStopAttempts.get());
        assertEquals(1, replacementSetupCalls.get());
        assertEquals(1, consumer.videoDisconnects.get());
        assertEquals(List.of("2@20"), consumer.videoFrames);
        assertTrue(sessions.isActiveSession(session));
    }

    @Test
    void successfulSessionStopCompletesDeferredDisconnect() throws Exception {
        var sessions = sessions();
        var consumer = new RecordingConsumer();
        var sessionId = "deferred-control-disconnect";
        var session = sessions.getSession(sessionId);
        var failSourceStop = new AtomicBoolean(true);
        var coordinator = new SessionMediaCoordinator(sessions, consumer, (stoppedSession, sourceKind) -> {
            assertSame(session, stoppedSession);
            assertEquals(SessionMediaCoordinator.SourceKind.VIDEO, sourceKind);
            if (failSourceStop.getAndSet(false)) {
                throw new IllegalStateException("simulated control disconnect stop failure");
            }
        });
        var control = sessions.openControlSession(session, () -> { });
        setupVideo(coordinator, control, "deferred-control-video");

        coordinator.controlDisconnected(control);

        assertNull(sessions.findSession(sessionId));
        assertEquals(1, consumer.videoDisconnects.get());

        var replacementControl = sessions.openControlSession(
                sessions.getSession(sessionId), () -> { });
        var replacementSetupCalls = new AtomicInteger();
        coordinator.setupVideo(replacementControl, leased -> {
            replacementSetupCalls.incrementAndGet();
            return leased;
        }).orElseThrow();
        assertEquals(1, replacementSetupCalls.get());
    }

    @Test
    void preservesDetectedCodecAndTimedMediaMetadata() throws Exception {
        var sessions = sessions();
        var consumer = new RecordingConsumer();
        var coordinator = new SessionMediaCoordinator(sessions, consumer);
        var session = sessions.getSession("metadata");
        var control = sessions.openControlSession(session, () -> { });

        AirPlayConsumer video = setupVideo(coordinator, control, "metadata-video");
        AirPlayConsumer audio = setupAudio(coordinator, control);
        video.onVideoFormatDetected(new VideoStreamInfo(
                "metadata-video", 3840, 2160, 60, VideoStreamInfo.Codec.HEVC));
        video.onVideo(new byte[]{9}, 123_456);
        audio.onAudio(new byte[]{10}, 654_321, 65_535);

        assertEquals(List.of(VideoStreamInfo.Codec.HEVC), consumer.detectedCodecs);
        assertEquals(List.of("9@123456"), consumer.videoFrames);
        assertEquals(List.of("10@654321#65535"), consumer.audioFrames);
    }

    @Test
    void inFlightFrameCompletesBeforeTakeoverResetsTheConsumer() throws Exception {
        var sessions = sessions();
        var consumer = new BlockingConsumer();
        var coordinator = new SessionMediaCoordinator(sessions, consumer);
        var firstControl = sessions.openControlSession(sessions.getSession("concurrent-first"), () -> { });
        var secondControl = sessions.openControlSession(sessions.getSession("concurrent-second"), () -> { });
        AirPlayConsumer firstVideo = setupVideo(coordinator, firstControl, "first");

        Thread frameThread = Thread.ofPlatform().name("test-old-video-frame").start(
                () -> firstVideo.onVideo(new byte[]{10}, 10));
        assertTrue(consumer.frameEntered.await(2, TimeUnit.SECONDS));

        var takeoverStarted = new CountDownLatch(1);
        Thread takeoverThread = Thread.ofPlatform().name("test-video-takeover").start(() -> {
            takeoverStarted.countDown();
            try {
                setupVideo(coordinator, secondControl, "second");
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        });
        assertTrue(takeoverStarted.await(2, TimeUnit.SECONDS));
        Thread.sleep(100);
        assertTrue(takeoverThread.isAlive(), "Takeover must wait for the in-flight frame");
        assertFalse(consumer.events.contains("video-disconnect"));

        consumer.releaseFrame.countDown();
        join(frameThread);
        join(takeoverThread);

        assertEquals(List.of(
                "format:first",
                "frame-start:10",
                "frame-end:10",
                "video-disconnect",
                "format:second"), consumer.events);
    }

    @Test
    void takeoverStopsSourceBeforeDrainingBlockedFrame() throws Exception {
        var sessions = sessions();
        var firstSession = sessions.getSession("stop-before-drain");
        var consumer = new SourceStopBlockingConsumer(firstSession.getVideoServer()::isRunning);
        var coordinator = new SessionMediaCoordinator(sessions, consumer);
        var firstControl = sessions.openControlSession(firstSession, () -> { });
        var secondControl = sessions.openControlSession(
                sessions.getSession("stop-before-drain-next"), () -> { });
        AirPlayConsumer firstVideo = coordinator.setupVideo(firstControl, leased -> {
            firstSession.getVideoServer().start(leased);
            leased.onVideoFormat(new VideoStreamInfo("first"));
            return leased;
        }).orElseThrow();
        assertTrue(firstSession.getVideoServer().isRunning());

        var frameFailure = new AtomicReference<Throwable>();
        Thread frameThread = Thread.ofPlatform().name("test-source-blocked-video-frame").start(() -> {
            try {
                firstVideo.onVideo(new byte[]{10}, 10);
            } catch (Throwable failure) {
                frameFailure.set(failure);
            }
        });
        assertTrue(consumer.frameEntered.await(2, TimeUnit.SECONDS));

        var takeoverFailure = new AtomicReference<Throwable>();
        Thread takeoverThread = Thread.ofPlatform().name("test-stop-before-drain-takeover").start(() -> {
            try {
                setupVideo(coordinator, secondControl, "second");
            } catch (Throwable failure) {
                takeoverFailure.set(failure);
            }
        });

        try {
            takeoverThread.join(5_000);
            assertFalse(takeoverThread.isAlive(),
                    "Takeover must stop the old source before waiting for its blocked callback");
            join(frameThread);
            assertNull(frameFailure.get());
            assertNull(takeoverFailure.get());
            assertFalse(firstSession.getVideoServer().isRunning());
            assertEquals(List.of(
                    "format:first",
                    "frame-start",
                    "frame-end",
                    "video-disconnect",
                    "format:second"), consumer.events);
        } finally {
            firstSession.stopVideo();
            join(frameThread);
            join(takeoverThread);
            coordinator.stopAll();
            sessions.stopAll();
        }
    }

    @Test
    void staleControlDisconnectDoesNotWaitForCurrentOwnerCallback() throws Exception {
        var sessions = sessions();
        var consumer = new BlockingConsumer();
        var coordinator = new SessionMediaCoordinator(sessions, consumer);
        var firstSession = sessions.getSession("stale-control");
        var secondSession = sessions.getSession("current-owner");
        var firstControl = sessions.openControlSession(firstSession, () -> { });
        var secondControl = sessions.openControlSession(secondSession, () -> { });
        setupVideo(coordinator, firstControl, "first");
        AirPlayConsumer secondVideo = setupVideo(coordinator, secondControl, "second");

        Thread frameThread = Thread.ofPlatform().name("test-current-owner-frame").start(
                () -> secondVideo.onVideo(new byte[]{20}, 20));
        assertTrue(consumer.frameEntered.await(2, TimeUnit.SECONDS));

        var disconnectFailure = new AtomicReference<Throwable>();
        Thread staleDisconnectThread = Thread.ofPlatform().name("test-stale-control-disconnect").start(() -> {
            try {
                coordinator.controlDisconnected(firstControl);
            } catch (Throwable failure) {
                disconnectFailure.set(failure);
            }
        });

        try {
            staleDisconnectThread.join(1_000);
            assertFalse(staleDisconnectThread.isAlive(),
                    "A stale control disconnect must not wait for the current owner's callback");
            assertNull(disconnectFailure.get());
            assertTrue(frameThread.isAlive());
            assertSame(secondSession, sessions.findSession("current-owner"));
        } finally {
            consumer.releaseFrame.countDown();
            join(frameThread);
            join(staleDisconnectThread);
            coordinator.stopAll();
            sessions.stopAll();
        }
    }

    @Test
    void disconnectingOneLeaseDoesNotWaitForAnotherLeaseCallback() throws Exception {
        var sessions = sessions();
        var consumer = new BlockingConsumer();
        var coordinator = new SessionMediaCoordinator(sessions, consumer);
        var session = sessions.getSession("independent-leases");
        var control = sessions.openControlSession(session, () -> { });
        AirPlayConsumer video = setupVideo(coordinator, control, "video");
        setupAudio(coordinator, control);

        Thread frameThread = Thread.ofPlatform().name("test-blocked-video-during-audio-disconnect").start(
                () -> video.onVideo(new byte[]{30}, 30));
        assertTrue(consumer.frameEntered.await(2, TimeUnit.SECONDS));

        var disconnectFailure = new AtomicReference<Throwable>();
        Thread audioDisconnectThread = Thread.ofPlatform().name("test-independent-audio-disconnect").start(() -> {
            try {
                coordinator.disconnectAudio(control);
            } catch (Throwable failure) {
                disconnectFailure.set(failure);
            }
        });

        try {
            audioDisconnectThread.join(1_000);
            assertFalse(audioDisconnectThread.isAlive(),
                    "Disconnecting audio must not wait for a callback owned by the video lease");
            assertNull(disconnectFailure.get());
            assertTrue(frameThread.isAlive());
            assertEquals(1, consumer.audioDisconnects.get());
            assertTrue(sessions.isActiveSession(session));
        } finally {
            consumer.releaseFrame.countDown();
            join(frameThread);
            join(audioDisconnectThread);
            coordinator.stopAll();
            sessions.stopAll();
        }
    }

    @Test
    void reentrantStopDuringSetupPreventsCommitAndRejectsTheStaleResult() throws Exception {
        var sessions = sessions();
        var consumer = new ReentrantFormatStopConsumer();
        var coordinator = new SessionMediaCoordinator(sessions, consumer);
        consumer.stopAction = coordinator::stopAll;
        var session = sessions.getSession("reentrant-setup-stop");
        var control = sessions.openControlSession(session, () -> { });
        var committed = new AtomicBoolean();

        var result = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> coordinator.setupVideo(
                control,
                leased -> {
                    leased.onVideoFormat(new VideoStreamInfo("reentrant-setup-stop"));
                    return leased;
                },
                () -> committed.set(true)));

        assertTrue(result.isEmpty());
        assertFalse(committed.get());
        assertFalse(sessions.isActiveSession(session));
    }

    @Test
    void consumerCanReenterStopWithoutUpgradingACallbackLock() throws Exception {
        var sessions = sessions();
        var consumer = new ReentrantStopConsumer();
        var coordinator = new SessionMediaCoordinator(sessions, consumer);
        consumer.stopAction = coordinator::stopAll;
        var session = sessions.getSession("reentrant-stop");
        var control = sessions.openControlSession(session, () -> { });
        AirPlayConsumer video = setupVideo(coordinator, control, "reentrant-stop");

        assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> video.onVideo(new byte[]{1}, 1));

        assertEquals(List.of("frame-start", "video-disconnect", "frame-end"), consumer.events);
        assertFalse(sessions.isActiveSession(session));
    }

    @Test
    void setupFailureRollsBackTheLeaseAndAllowsRetry() throws Exception {
        var sessions = sessions();
        var consumer = new RecordingConsumer();
        var coordinator = new SessionMediaCoordinator(sessions, consumer);
        var session = sessions.getSession("retry");
        var control = sessions.openControlSession(session, () -> { });
        var failedConsumer = new AirPlayConsumer[1];
        var committed = new AtomicBoolean();

        var failure = assertThrows(IllegalStateException.class, () -> coordinator.setupAudio(
                control,
                leased -> {
                    failedConsumer[0] = leased;
                    leased.onAudioFormat(audioInfo());
                    throw new IllegalStateException("audio bind failed");
                },
                () -> committed.set(true)));

        assertEquals("audio bind failed", failure.getMessage());
        assertFalse(committed.get());
        assertEquals(1, consumer.audioDisconnects.get());
        assertFalse(sessions.isActiveSession(session));
        failedConsumer[0].onAudio(new byte[]{1}, 1, 1);
        assertTrue(consumer.audioFrames.isEmpty());

        AirPlayConsumer retry = setupAudio(coordinator, control);
        retry.onAudio(new byte[]{2}, 2, 2);
        assertEquals(List.of("2@2#2"), consumer.audioFrames);
        assertTrue(sessions.isActiveSession(session));
    }

    @Test
    void handshakeControlReferenceKeepsTheSharedSessionAliveBeforeMediaClaim() throws Exception {
        var sessions = sessions();
        var consumer = new RecordingConsumer();
        var coordinator = new SessionMediaCoordinator(sessions, consumer);
        var session = sessions.getSession("handshake-overlap");
        var firstControl = sessions.openControlSession(session, () -> { });
        setupVideo(coordinator, firstControl, "old-owner");
        var reconnectingControl = sessions.openControlSession(session, () -> { });

        coordinator.controlDisconnected(firstControl);

        assertSame(session, sessions.findSession("handshake-overlap"));
        AirPlayConsumer replacement = setupVideo(coordinator, reconnectingControl, "new-owner");
        replacement.onVideo(new byte[]{4}, 4);
        assertEquals(List.of("4@4"), consumer.videoFrames);
    }

    @Test
    void keySetupClaimsTimingOwnershipBeforeReplacingTheIncumbent() throws Exception {
        var sessions = sessions();
        var consumer = new RecordingConsumer();
        var coordinator = new SessionMediaCoordinator(sessions, consumer);
        var session = sessions.getSession("timing-takeover");
        var firstChannelCloses = new AtomicInteger();
        var firstControl = sessions.openControlSession(session, firstChannelCloses::incrementAndGet);
        var secondControl = sessions.openControlSession(session, () -> { });
        coordinator.setupTiming(firstControl, () -> {
            session.getTimingServer().start(InetAddress.getLoopbackAddress(), 9);
            return session.getTimingServer().getPort();
        }).orElseThrow();
        AirPlayConsumer firstVideo = setupVideo(coordinator, firstControl, "timing-owner");

        coordinator.setupTiming(secondControl, () -> {
            session.getTimingServer().start(InetAddress.getLoopbackAddress(), 10);
            return session.getTimingServer().getPort();
        }).orElseThrow();

        assertEquals(1, firstChannelCloses.get());
        assertEquals(1, consumer.videoDisconnects.get());
        firstVideo.onVideo(new byte[]{1}, 1);
        assertTrue(consumer.videoFrames.isEmpty());
        assertTrue(session.getTimingServer().getPort() > 0);
        assertTrue(sessions.isActiveSession(session));

        coordinator.controlDisconnected(secondControl);
        assertEquals(0, session.getTimingServer().getPort());
        assertFalse(sessions.isActiveSession(session));
        coordinator.controlDisconnected(firstControl);
    }

    @Test
    void revokedControlCannotRunControlOperations() throws Exception {
        var sessions = sessions();
        var coordinator = new SessionMediaCoordinator(sessions, new RecordingConsumer());
        var session = sessions.getSession("timing-generation");
        var firstControl = sessions.openControlSession(session, () -> { });
        var secondControl = sessions.openControlSession(session, () -> { });
        setupVideo(coordinator, firstControl, "first-owner");
        setupVideo(coordinator, secondControl, "second-owner");
        var staleTimingStarted = new AtomicBoolean();

        assertTrue(coordinator.runControlOperation(firstControl, () -> {
            staleTimingStarted.set(true);
            return 1;
        }).isEmpty());
        assertFalse(staleTimingStarted.get());
        assertEquals(2, coordinator.runControlOperation(secondControl, () -> 2).orElseThrow());
    }

    @Test
    void sourceDisconnectIsIdempotentUntilTheStreamReconnects() throws Exception {
        var sessions = sessions();
        var consumer = new RecordingConsumer();
        var coordinator = new SessionMediaCoordinator(sessions, consumer);
        var session = sessions.getSession("source-disconnect");
        var control = sessions.openControlSession(session, () -> { });
        AirPlayConsumer video = setupVideo(coordinator, control, "first-connection");

        video.onVideoSrcDisconnect();
        video.onVideoSrcDisconnect();
        assertEquals(1, consumer.videoDisconnects.get());

        video.onVideoFormat(new VideoStreamInfo("reconnected"));
        coordinator.disconnectVideo(control);
        assertEquals(2, consumer.videoDisconnects.get());
    }

    @Test
    void stopAllRejectsSetupsUntilTheCoordinatorResumes() throws Exception {
        var sessions = sessions();
        var consumer = new RecordingConsumer();
        var coordinator = new SessionMediaCoordinator(sessions, consumer);
        var closes = new AtomicInteger();
        var firstSession = sessions.getSession("before-stop");
        var firstControl = sessions.openControlSession(firstSession, closes::incrementAndGet);
        setupVideo(coordinator, firstControl, "before-stop");

        coordinator.stopAll();
        assertEquals(1, closes.get());
        assertEquals(1, consumer.videoDisconnects.get());
        var rejectedControl = sessions.openControlSession(sessions.getSession("while-stopped"), () -> { });
        assertTrue(coordinator.setupVideo(rejectedControl, leased -> leased).isEmpty());

        sessions.stopAll();
        coordinator.resume();
        var resumedSession = sessions.getSession("after-resume");
        var resumedControl = sessions.openControlSession(resumedSession, () -> { });
        AirPlayConsumer resumedVideo = setupVideo(coordinator, resumedControl, "after-resume");
        resumedVideo.onVideo(new byte[]{3}, 3);

        assertEquals(List.of("3@3"), consumer.videoFrames);
        assertTrue(sessions.isActiveSession(resumedSession));
    }

    @Test
    void staleControlCannotRemoveOrReclaimTheReplacementWithTheSameSessionId() throws Exception {
        var sessions = sessions();
        var consumer = new RecordingConsumer();
        var coordinator = new SessionMediaCoordinator(sessions, consumer);
        var session = sessions.getSession("shared-id");
        var firstChannelCloses = new AtomicInteger();
        var firstControl = sessions.openControlSession(session, firstChannelCloses::incrementAndGet);
        var secondControl = sessions.openControlSession(session, () -> { });
        AirPlayConsumer firstVideo = setupVideo(coordinator, firstControl, "first-generation");

        AirPlayConsumer secondVideo = setupVideo(coordinator, secondControl, "second-generation");
        assertEquals(1, firstChannelCloses.get());
        var staleCommit = new AtomicBoolean();
        assertTrue(coordinator.setupVideo(
                firstControl,
                leased -> leased,
                () -> staleCommit.set(true)).isEmpty());
        assertFalse(staleCommit.get());

        coordinator.controlDisconnected(firstControl);
        assertSame(session, sessions.findSession("shared-id"));
        firstVideo.onVideo(new byte[]{1}, 1);
        secondVideo.onVideo(new byte[]{2}, 2);
        assertEquals(List.of("2@2"), consumer.videoFrames);

        coordinator.controlDisconnected(secondControl);
        assertNull(sessions.findSession("shared-id"));
    }

    private static SessionManager sessions() {
        return new SessionManager(AirPlayIdentity.random(), 4);
    }

    private static AirPlayConsumer setupVideo(
            SessionMediaCoordinator coordinator,
            SessionManager.ControlSession control,
            String streamId) throws Exception {
        return coordinator.setupVideo(control, leased -> {
            leased.onVideoFormat(new VideoStreamInfo(streamId));
            return leased;
        }).orElseThrow();
    }

    private static AirPlayConsumer setupAudio(
            SessionMediaCoordinator coordinator,
            SessionManager.ControlSession control) throws Exception {
        return coordinator.setupAudio(control, leased -> {
            leased.onAudioFormat(audioInfo());
            return leased;
        }).orElseThrow();
    }

    private static AudioStreamInfo audioInfo() {
        return new AudioStreamInfo.AudioStreamInfoBuilder()
                .compressionType(AudioStreamInfo.CompressionType.AAC_ELD)
                .audioFormat(AudioStreamInfo.AudioFormat.AAC_ELD_44100_2)
                .samplesPerFrame(480)
                .sampleRate(44_100)
                .build();
    }

    private static void join(Thread thread) throws InterruptedException {
        thread.join(2_000);
        assertFalse(thread.isAlive(), () -> thread.getName() + " did not finish");
    }

    private static class RecordingConsumer extends NoopConsumer {
        private final List<String> videoFrames = new CopyOnWriteArrayList<>();
        private final List<String> audioFrames = new CopyOnWriteArrayList<>();
        private final List<VideoStreamInfo.Codec> detectedCodecs = new CopyOnWriteArrayList<>();
        private final AtomicInteger videoDisconnects = new AtomicInteger();
        private final AtomicInteger audioDisconnects = new AtomicInteger();

        @Override
        public void onVideoFormatDetected(VideoStreamInfo videoStreamInfo) {
            detectedCodecs.add(videoStreamInfo.getCodec());
        }

        @Override
        public void onVideo(byte[] bytes) {
            videoFrames.add(Byte.toUnsignedInt(bytes[0]) + "@untimed");
        }

        @Override
        public void onVideo(byte[] bytes, long timestamp) {
            videoFrames.add(Byte.toUnsignedInt(bytes[0]) + "@" + timestamp);
        }

        @Override
        public void onVideoSrcDisconnect() {
            videoDisconnects.incrementAndGet();
        }

        @Override
        public void onAudio(byte[] bytes) {
            audioFrames.add(Byte.toUnsignedInt(bytes[0]) + "@untimed");
        }

        @Override
        public void onAudio(byte[] bytes, long timestamp, int sequenceNumber) {
            audioFrames.add(Byte.toUnsignedInt(bytes[0]) + "@" + timestamp + "#" + sequenceNumber);
        }

        @Override
        public void onAudioSrcDisconnect() {
            audioDisconnects.incrementAndGet();
        }
    }

    private static final class ReentrantFormatStopConsumer extends NoopConsumer {
        private Runnable stopAction;

        @Override
        public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
            stopAction.run();
        }
    }

    private static final class ReentrantStopConsumer extends NoopConsumer {
        private final List<String> events = new CopyOnWriteArrayList<>();
        private Runnable stopAction;

        @Override
        public void onVideo(byte[] bytes) {
            events.add("frame-start");
            stopAction.run();
            events.add("frame-end");
        }

        @Override
        public void onVideoSrcDisconnect() {
            events.add("video-disconnect");
        }
    }

    private static final class SourceStopBlockingConsumer extends NoopConsumer {
        private final BooleanSupplier sourceRunning;
        private final List<String> events = new CopyOnWriteArrayList<>();
        private final CountDownLatch frameEntered = new CountDownLatch(1);

        private SourceStopBlockingConsumer(BooleanSupplier sourceRunning) {
            this.sourceRunning = sourceRunning;
        }

        @Override
        public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
            events.add("format:" + videoStreamInfo.getStreamConnectionId());
        }

        @Override
        public void onVideo(byte[] bytes) {
            events.add("frame-start");
            frameEntered.countDown();
            while (sourceRunning.getAsBoolean()) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            }
            events.add("frame-end");
        }

        @Override
        public void onVideoSrcDisconnect() {
            events.add("video-disconnect");
        }
    }

    private static final class BlockingConsumer extends NoopConsumer {
        private final List<String> events = new CopyOnWriteArrayList<>();
        private final CountDownLatch frameEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFrame = new CountDownLatch(1);
        private final AtomicInteger audioDisconnects = new AtomicInteger();

        @Override
        public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
            events.add("format:" + videoStreamInfo.getStreamConnectionId());
        }

        @Override
        public void onVideo(byte[] bytes) {
            onVideo(bytes, -1);
        }

        @Override
        public void onVideo(byte[] bytes, long timestamp) {
            int value = Byte.toUnsignedInt(bytes[0]);
            events.add("frame-start:" + value);
            frameEntered.countDown();
            try {
                if (!releaseFrame.await(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to release the old frame");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
            events.add("frame-end:" + value);
        }

        @Override
        public void onVideoSrcDisconnect() {
            events.add("video-disconnect");
        }

        @Override
        public void onAudioSrcDisconnect() {
            audioDisconnects.incrementAndGet();
        }
    }

    private static class NoopConsumer implements AirPlayConsumer {
        @Override public void onVideoFormat(VideoStreamInfo videoStreamInfo) { }
        @Override public void onVideo(byte[] bytes) { }
        @Override public void onVideoSrcDisconnect() { }
        @Override public void onAudioFormat(AudioStreamInfo audioStreamInfo) { }
        @Override public void onAudio(byte[] bytes) { }
        @Override public void onAudioSrcDisconnect() { }
    }
}
