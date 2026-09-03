package wtf.nanoka.airplay.server.internal;

import org.junit.jupiter.api.Test;
import wtf.nanoka.airplay.lib.AirPlayIdentity;
import wtf.nanoka.airplay.lib.AudioStreamInfo;
import wtf.nanoka.airplay.lib.VideoStreamInfo;
import wtf.nanoka.airplay.server.AirPlayConfig;
import wtf.nanoka.airplay.server.AirPlayConsumer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlServerTest {

    private static final String KEY_SETUP_REQUEST = "one_mirroring_app/06_RTSP_SETUP_request.bin";
    private static final String AUDIO_SETUP_REQUEST = "one_mirroring_app/10_RTSP_SETUP_request.bin";
    private static final String OK_STATUS = "RTSP/1.0 200 OK";
    private static final String SERVICE_UNAVAILABLE_STATUS = "RTSP/1.0 503 Service Unavailable";
    private static final String CONNECTION_CLOSED = "connection-closed";
    private static final byte[] HEADER_TERMINATOR = {'\r', '\n', '\r', '\n'};
    private static final Pattern CONTENT_LENGTH_HEADER =
            Pattern.compile("(?im)^Content-Length:\\s*(\\d+)\\s*$");

    @Test
    void stopClosesAcceptedChannelsBeforeTheNextGenerationStarts() throws Exception {
        var server = new ControlServer(config(), new NoopConsumer(), AirPlayIdentity.random());
        try {
            server.start();
            assertTrue(server.getPort() > 0);
            try (var client = connect(server)) {
                assertTimeout(Duration.ofSeconds(10), server::stop);
                assertTrue(connectionIsClosed(client));
            }

            assertCanRestart(server);
        } finally {
            server.stop();
        }
    }

    @Test
    void externalStopDoesNotDeadlockWhenDisconnectCallbackReentersStop() throws Exception {
        var consumer = new DisconnectReentrantStopConsumer();
        var server = new ControlServer(config(), consumer, AirPlayIdentity.random());
        consumer.server = server;
        try {
            server.start();
            try (var client = connect(server)) {
                assertSuccessfulSetup(client);

                var stopFailure = new AtomicReference<Throwable>();
                Thread stopper = Thread.ofPlatform().daemon(true).name("external-airplay-stop").start(() -> {
                    try {
                        server.stop();
                    } catch (Throwable failure) {
                        stopFailure.set(failure);
                    }
                });
                stopper.join(10_000);

                assertFalse(stopper.isAlive());
                assertNull(stopFailure.get());
                assertTrue(consumer.disconnectReturned.await(10, TimeUnit.SECONDS));
            }

            assertCanRestart(server);
        } finally {
            server.stop();
        }
    }

    @Test
    void consumerStopThenStartFailsFastDuringAudioFormat() throws Exception {
        var consumer = new ReentrantServerStopConsumer();
        var server = new ControlServer(config(), consumer, AirPlayIdentity.random());
        consumer.server = server;
        try {
            server.start();
            try (var client = connect(server)) {
                assertResponse(OK_STATUS, client, resource(KEY_SETUP_REQUEST));
                String setupOutcome = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                    try {
                        return exchange(client, resource(AUDIO_SETUP_REQUEST));
                    } catch (IOException closedDuringStop) {
                        return CONNECTION_CLOSED;
                    }
                });
                assertTrue(CONNECTION_CLOSED.equals(setupOutcome)
                        || SERVICE_UNAVAILABLE_STATUS.equals(setupOutcome));
                assertTrue(consumer.restartAttempted.await(10, TimeUnit.SECONDS));
                assertTrue(consumer.restartFailure.get() instanceof IllegalStateException);
            }

            assertCanRestart(server);
        } finally {
            server.stop();
        }
    }

    @Test
    void reconnectingHandshakeKeepsTheSharedSessionWhenTheOldOwnerCloses() throws Exception {
        var consumer = new DisconnectTrackingConsumer();
        var server = new ControlServer(config(), consumer, AirPlayIdentity.random());
        try {
            server.start();
            try (var first = connect(server); var reconnecting = connect(server)) {
                assertSuccessfulSetup(first);
                assertResponse(OK_STATUS, reconnecting, feedbackRequest());

                first.close();
                assertTrue(consumer.disconnected.await(10, TimeUnit.SECONDS));

                assertSuccessfulSetup(reconnecting);
                assertEquals(2, consumer.formatCount.get());
            }
        } finally {
            server.stop();
        }
    }

    @Test
    void failedAudioSetupRollsBackPartiallyStartedServersAndCanRetry() throws Exception {
        var consumer = new ThrowOnceAudioFormatConsumer();
        var server = new ControlServer(config(), consumer, AirPlayIdentity.random());
        try {
            server.start();
            try (var client = connect(server)) {
                assertResponse(OK_STATUS, client, resource(KEY_SETUP_REQUEST));
                assertResponse(SERVICE_UNAVAILABLE_STATUS, client, resource(AUDIO_SETUP_REQUEST));
                assertResponse(OK_STATUS, client, resource(AUDIO_SETUP_REQUEST));
            }
        } finally {
            server.stop();
        }
    }

    private static void assertCanRestart(ControlServer server) throws InterruptedException {
        server.start();
        assertTrue(server.getPort() > 0);
    }

    private static Socket connect(ControlServer server) throws IOException {
        var client = new Socket(InetAddress.getLoopbackAddress(), server.getPort());
        client.setSoTimeout(10_000);
        return client;
    }

    private static void assertSuccessfulSetup(Socket client) throws IOException {
        assertResponse(OK_STATUS, client, resource(KEY_SETUP_REQUEST));
        assertResponse(OK_STATUS, client, resource(AUDIO_SETUP_REQUEST));
    }

    private static void assertResponse(String expectedStatus, Socket client, byte[] request) throws IOException {
        assertEquals(expectedStatus, exchange(client, request));
    }

    private static boolean connectionIsClosed(Socket client) throws IOException {
        try {
            return client.getInputStream().read() < 0;
        } catch (SocketException closedWithReset) {
            // Linux epoll may report a server-side close as a TCP reset instead of EOF.
            return true;
        }
    }

    private static String exchange(Socket client, byte[] request) throws IOException {
        client.getOutputStream().write(request);
        client.getOutputStream().flush();
        return readResponse(client.getInputStream());
    }

    private static String readResponse(InputStream input) throws IOException {
        var headerBytes = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < HEADER_TERMINATOR.length) {
            int next = input.read();
            if (next < 0) {
                throw new IOException("Control connection closed before a complete response");
            }
            headerBytes.write(next);
            byte expected = HEADER_TERMINATOR[matched];
            matched = next == expected ? matched + 1 : (next == '\r' ? 1 : 0);
        }
        String headers = headerBytes.toString(StandardCharsets.ISO_8859_1);
        var contentLength = CONTENT_LENGTH_HEADER.matcher(headers);
        int bodyLength = contentLength.find() ? Integer.parseInt(contentLength.group(1)) : 0;
        if (input.readNBytes(bodyLength).length != bodyLength) {
            throw new IOException("Control connection closed before the complete response body");
        }
        return headers.substring(0, headers.indexOf("\r\n"));
    }

    private static byte[] feedbackRequest() {
        return ("POST /feedback RTSP/1.0\r\n"
                + "CSeq: 20\r\n"
                + "Active-Remote: 1589992423\r\n"
                + "Content-Length: 0\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] resource(String path) throws IOException {
        try (var input = ControlServerTest.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("Missing test resource: " + path);
            }
            return input.readAllBytes();
        }
    }

    private static AirPlayConfig config() {
        var config = new AirPlayConfig();
        config.setAudioJitterPackets(4);
        config.setRequirePairing(false);
        return config;
    }

    private static final class DisconnectReentrantStopConsumer extends NoopConsumer {
        private final CountDownLatch disconnectReturned = new CountDownLatch(1);
        private ControlServer server;

        @Override
        public void onAudioSrcDisconnect() {
            server.stop();
            disconnectReturned.countDown();
        }
    }

    private static final class ReentrantServerStopConsumer extends NoopConsumer {
        private final AtomicBoolean stopInitiated = new AtomicBoolean();
        private final CountDownLatch restartAttempted = new CountDownLatch(1);
        private final AtomicReference<Throwable> restartFailure = new AtomicReference<>();
        private ControlServer server;

        @Override
        public void onAudioFormat(AudioStreamInfo audioStreamInfo) {
            if (!stopInitiated.compareAndSet(false, true)) {
                return;
            }
            server.stop();
            try {
                server.start();
            } catch (Throwable failure) {
                restartFailure.set(failure);
            } finally {
                restartAttempted.countDown();
            }
        }
    }

    private static final class DisconnectTrackingConsumer extends NoopConsumer {
        private final CountDownLatch disconnected = new CountDownLatch(1);
        private final AtomicInteger formatCount = new AtomicInteger();

        @Override public void onAudioFormat(AudioStreamInfo audioStreamInfo) { formatCount.incrementAndGet(); }
        @Override public void onAudioSrcDisconnect() { disconnected.countDown(); }
    }

    private static final class ThrowOnceAudioFormatConsumer extends NoopConsumer {
        private final AtomicBoolean shouldFail = new AtomicBoolean(true);

        @Override
        public void onAudioFormat(AudioStreamInfo audioStreamInfo) {
            if (shouldFail.compareAndSet(true, false)) {
                throw new IllegalStateException("simulated player setup failure");
            }
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
