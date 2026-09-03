package wtf.nanoka.airplay.app.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.support.GenericApplicationContext;
import wtf.nanoka.airplay.app.PlayerApp;
import wtf.nanoka.airplay.player.gstreamer.ui.ReceiverSettings;
import wtf.nanoka.airplay.server.AirPlayConfig;
import wtf.nanoka.airplay.server.AirPlayConsumer;
import wtf.nanoka.airplay.server.AirPlayServer;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserSettingsControllerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsEveryEditableSettingAndPreservesUnknownProperties() throws Exception {
        Path settingsFile = temporaryDirectory.resolve("application.properties");
        Files.writeString(settingsFile, "logging.level.wtf.nanoka=DEBUG\n", StandardCharsets.UTF_8);
        var controller = new UserSettingsController(null, settingsFile);

        var result = controller.save(validSettings());

        assertTrue(result.success(), result.message());
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(settingsFile, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        assertEquals("Living Room", properties.getProperty("airplay.serverName"));
        assertEquals("true", properties.getProperty("airplay.hevc"));
        assertEquals("quality", properties.getProperty("player.gstreamer.renderMode"));
        assertEquals("true", properties.getProperty("player.gstreamer.aggressiveFrameDropping"));
        assertEquals("DEBUG", properties.getProperty("logging.level.wtf.nanoka"));
    }

    @Test
    void rejectsInvalidValuesWithoutCreatingAFile() {
        Path settingsFile = temporaryDirectory.resolve("invalid.properties");
        ReceiverSettings valid = validSettings();
        ReceiverSettings invalid = new ReceiverSettings(
                valid.serverName(), "12", valid.height(), valid.fps(), valid.identityFile(),
                valid.audioJitterPackets(), valid.requirePairing(), valid.hevcEnabled(),
                valid.playerImplementation(), valid.trayEnabled(), valid.swingEnabled(),
                valid.videoDecoder(), valid.gpuAdapter(), valid.videoQueueDepth(),
                valid.aggressiveFrameDropping(), valid.renderMode());

        var result = new UserSettingsController(null, settingsFile).save(invalid);

        assertFalse(result.success());
        assertFalse(Files.exists(settingsFile));
    }

    @Test
    void acceptsHevcWithFfmpegButStillRejectsUnsupportedPlayers() {
        Path ffmpegSettingsFile = temporaryDirectory.resolve("ffmpeg.properties");
        Path vlcSettingsFile = temporaryDirectory.resolve("vlc.properties");

        var ffmpegResult = new UserSettingsController(null, ffmpegSettingsFile)
                .save(settingsWithPlayer("ffmpeg"));
        var vlcResult = new UserSettingsController(null, vlcSettingsFile)
                .save(settingsWithPlayer("vlc"));

        assertTrue(ffmpegResult.success(), ffmpegResult.message());
        assertFalse(vlcResult.success());
        assertFalse(Files.exists(vlcSettingsFile));
    }

    @Test
    void reconstructsRestartCommandWithoutDependingOnProcessArguments() throws Exception {
        try (var context = new GenericApplicationContext()) {
            context.getBeanFactory().registerSingleton("applicationArguments",
                    new DefaultApplicationArguments("--airplay.serverName=Restarted Receiver"));
            context.refresh();
            var controller = new UserSettingsController(context,
                    temporaryDirectory.resolve("application.properties"));

            var command = controller.currentRestartCommand();

            assertFalse(command.isEmpty());
            assertTrue(command.stream().noneMatch(argument -> argument.contains("-agentlib:jdwp")));
            assertTrue(command.contains("--airplay.serverName=Restarted Receiver"));
            assertTrue(command.contains("-jar") || command.contains(PlayerApp.class.getName()));
        }
    }

    @Test
    void failedRestartValidationDoesNotRequestQuitAndCanBeRetried() {
        AtomicInteger processStarts = new AtomicInteger();
        AtomicInteger quitRequests = new AtomicInteger();
        UserSettingsController.ProcessStarter processStarter = ignored -> {
            processStarts.incrementAndGet();
            return TestProcess.exited(1);
        };

        try (var context = new GenericApplicationContext()) {
            context.getBeanFactory().registerSingleton("applicationArguments", new DefaultApplicationArguments());
            context.refresh();
            var controller = new UserSettingsController(
                    context,
                    temporaryDirectory.resolve("application.properties"),
                    processStarter,
                    quitRequests::incrementAndGet);

            var first = controller.restart();
            var retry = controller.restart();

            assertFalse(first.success());
            assertFalse(retry.success());
            assertEquals(2, processStarts.get());
            assertEquals(0, quitRequests.get());
        }
    }

    @Test
    void successfulRestartStopsOldServiceBeforeReplacementReadyAndFinalQuit() throws Exception {
        AtomicInteger processStarts = new AtomicInteger();
        AtomicInteger quitRequests = new AtomicInteger();
        AtomicReference<Path> readinessFile = new AtomicReference<>();
        var readinessCompleted = new CountDownLatch(1);
        AtomicReference<Exception> readinessFailure = new AtomicReference<>();
        List<String> restartEvents = new CopyOnWriteArrayList<>();
        var replacement = TestProcess.running(42_424L);
        UserSettingsController.ProcessStarter processStarter = processBuilder -> {
            int startNumber = processStarts.incrementAndGet();
            return switch (startNumber) {
                case 1 -> TestProcess.exited(0);
                case 2 -> {
                    restartEvents.add("replacement-started");
                    var environment = processBuilder.environment();
                    Path readyFile = Path.of(environment.get(UserSettingsController.RESTART_READY_FILE_ENV));
                    String readyToken = environment.get(UserSettingsController.RESTART_READY_TOKEN_ENV);
                    readinessFile.set(readyFile);
                    Thread signaler = Thread.ofPlatform()
                            .name("test-replacement-readiness")
                            .daemon(true)
                            .unstarted(() -> {
                                try {
                                    Thread.sleep(100);
                                    RestartReadiness.signal(readyFile, readyToken, replacement.pid());
                                    restartEvents.add("replacement-ready");
                                } catch (Exception failure) {
                                    readinessFailure.set(failure);
                                    replacement.destroy();
                                } finally {
                                    readinessCompleted.countDown();
                                }
                            });
                    signaler.start();
                    yield replacement;
                }
                default -> throw new AssertionError("Unexpected process start " + startNumber);
            };
        };

        try (var context = new GenericApplicationContext()) {
            context.getBeanFactory().registerSingleton("applicationArguments", new DefaultApplicationArguments());
            context.getBeanFactory().registerSingleton("airPlayServer", recordingAirPlayServer(restartEvents));
            context.refresh();
            var controller = new UserSettingsController(
                    context,
                    temporaryDirectory.resolve("application.properties"),
                    processStarter,
                    () -> {
                        restartEvents.add("quit-requested");
                        quitRequests.incrementAndGet();
                    });

            var first = controller.restart();
            var duplicate = controller.restart();

            assertTrue(first.success(), first.message());
            assertFalse(duplicate.success());
            assertEquals("A restart is already in progress.", duplicate.message());
            assertTrue(readinessCompleted.await(1, TimeUnit.SECONDS));
            assertNull(readinessFailure.get());
            assertEquals(List.of(
                    "old-service-stopped",
                    "replacement-started",
                    "replacement-ready",
                    "quit-requested"), restartEvents);
            assertEquals(2, processStarts.get());
            assertEquals(1, quitRequests.get());
            assertFalse(Files.exists(readinessFile.get()));
        }
    }

    private AirPlayServer recordingAirPlayServer(List<String> restartEvents) {
        var airPlayConfig = new AirPlayConfig();
        airPlayConfig.setIdentityFile(temporaryDirectory.resolve("restart-identity.key").toString());
        AirPlayConsumer noOpConsumer = (AirPlayConsumer) Proxy.newProxyInstance(
                AirPlayConsumer.class.getClassLoader(),
                new Class<?>[]{AirPlayConsumer.class},
                (proxy, method, arguments) -> null);
        return new AirPlayServer(airPlayConfig, noOpConsumer) {
            @Override
            public void stop() {
                restartEvents.add("old-service-stopped");
                super.stop();
            }
        };
    }

    private ReceiverSettings validSettings() {
        return settingsWithPlayer("gstreamer");
    }

    private ReceiverSettings settingsWithPlayer(String playerImplementation) {
        return new ReceiverSettings(
                "Living Room", "3840", "2160", "60",
                temporaryDirectory.resolve("identity.key").toString(), 4,
                true, true, playerImplementation, true, true,
                "d3d12h264dec", "0", 3, true, "quality");
    }

    private static final class TestProcess extends Process {
        private final long pid;
        private final int exitCode;
        private final CountDownLatch termination;

        private TestProcess(long pid, int exitCode, boolean running) {
            this.pid = pid;
            this.exitCode = exitCode;
            termination = new CountDownLatch(running ? 1 : 0);
        }

        static TestProcess exited(int exitCode) {
            return new TestProcess(1L, exitCode, false);
        }

        static TestProcess running(long pid) {
            return new TestProcess(pid, 0, true);
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() throws InterruptedException {
            termination.await();
            return exitCode;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return termination.await(timeout, unit);
        }

        @Override
        public int exitValue() {
            if (isAlive()) {
                throw new IllegalThreadStateException("Process is still running");
            }
            return exitCode;
        }

        @Override
        public void destroy() {
            termination.countDown();
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }

        @Override
        public boolean isAlive() {
            return termination.getCount() > 0;
        }

        @Override
        public long pid() {
            return pid;
        }
    }
}
