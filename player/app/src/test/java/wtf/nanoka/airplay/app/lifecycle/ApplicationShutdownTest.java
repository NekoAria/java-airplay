package wtf.nanoka.airplay.app.lifecycle;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import javax.swing.SwingUtilities;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationShutdownTest {

    private static final Runnable NO_OP_CLEANUP = () -> {};
    private static final IntConsumer NO_OP_EXIT = ignored -> {};

    @Test
    void productionCleanupClosesTheSpringContextBeforeProcessExit() throws InterruptedException {
        CountDownLatch processExit = new CountDownLatch(1);
        AtomicBoolean activeAtExit = new AtomicBoolean(true);
        try (var context = new GenericApplicationContext()) {
            context.refresh();
            var shutdown = new ApplicationShutdown(
                    context,
                    code -> {
                        activeAtExit.set(context.isActive());
                        processExit.countDown();
                    },
                    NO_OP_EXIT,
                    1_000);

            shutdown.requestQuit();

            assertTrue(processExit.await(1, TimeUnit.SECONDS));
            assertFalse(activeAtExit.get());
            assertFalse(context.isActive());
        }
    }

    @Test
    void quitReturnsImmediatelyAndRunsCleanupOnceOnANonDaemonWorker() throws InterruptedException {
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        CountDownLatch processExit = new CountDownLatch(1);
        AtomicBoolean workerDaemon = new AtomicBoolean(true);
        AtomicInteger cleanupCalls = new AtomicInteger();
        AtomicInteger processExitCalls = new AtomicInteger();
        AtomicInteger forcedExitCalls = new AtomicInteger();
        List<String> events = new CopyOnWriteArrayList<>();
        var shutdown = new ApplicationShutdown(
                () -> {
                    events.add("cleanup");
                    cleanupCalls.incrementAndGet();
                    workerDaemon.set(Thread.currentThread().isDaemon());
                    cleanupStarted.countDown();
                    awaitUninterruptibly(releaseCleanup);
                },
                code -> {
                    events.add("exit:" + code);
                    processExitCalls.incrementAndGet();
                    processExit.countDown();
                },
                code -> forcedExitCalls.incrementAndGet(),
                1_000);

        try {
            assertTimeout(Duration.ofSeconds(1), shutdown::requestQuit);
            assertTrue(cleanupStarted.await(1, TimeUnit.SECONDS));
            assertTimeout(Duration.ofSeconds(1), shutdown::requestQuit);
            assertEquals(1, cleanupCalls.get());
            assertFalse(workerDaemon.get());
        } finally {
            releaseCleanup.countDown();
        }

        assertTrue(processExit.await(1, TimeUnit.SECONDS));
        assertEquals(List.of("cleanup", "exit:0"), events);
        assertEquals(1, processExitCalls.get());
        assertEquals(0, forcedExitCalls.get());
    }

    @Test
    void requestFromSwingEventThreadReturnsBeforeCleanupCompletes() throws InterruptedException {
        CountDownLatch eventHandlerReturned = new CountDownLatch(1);
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        CountDownLatch processExit = new CountDownLatch(1);
        AtomicBoolean cleanupOnEventThread = new AtomicBoolean(true);
        var shutdown = new ApplicationShutdown(
                () -> {
                    cleanupOnEventThread.set(SwingUtilities.isEventDispatchThread());
                    cleanupStarted.countDown();
                    awaitUninterruptibly(releaseCleanup);
                },
                code -> processExit.countDown(),
                NO_OP_EXIT,
                1_000);

        SwingUtilities.invokeLater(() -> {
            shutdown.requestQuit();
            eventHandlerReturned.countDown();
        });

        try {
            assertTrue(eventHandlerReturned.await(1, TimeUnit.SECONDS));
            assertTrue(cleanupStarted.await(1, TimeUnit.SECONDS));
            assertFalse(cleanupOnEventThread.get());
        } finally {
            releaseCleanup.countDown();
        }
        assertTrue(processExit.await(1, TimeUnit.SECONDS));
    }

    @Test
    void concurrentQuitRequestsStartOnlyOneShutdown() throws InterruptedException {
        int callerCount = 16;
        CountDownLatch callersReady = new CountDownLatch(callerCount);
        CountDownLatch releaseCallers = new CountDownLatch(1);
        CountDownLatch callersDone = new CountDownLatch(callerCount);
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        CountDownLatch processExit = new CountDownLatch(1);
        AtomicInteger cleanupCalls = new AtomicInteger();
        var shutdown = new ApplicationShutdown(
                () -> {
                    cleanupCalls.incrementAndGet();
                    cleanupStarted.countDown();
                    awaitUninterruptibly(releaseCleanup);
                },
                code -> processExit.countDown(),
                NO_OP_EXIT,
                1_000);

        for (int caller = 0; caller < callerCount; caller++) {
            Thread.ofVirtual().start(() -> {
                callersReady.countDown();
                awaitUninterruptibly(releaseCallers);
                shutdown.requestQuit();
                callersDone.countDown();
            });
        }

        try {
            assertTrue(callersReady.await(1, TimeUnit.SECONDS));
            releaseCallers.countDown();
            assertTrue(callersDone.await(1, TimeUnit.SECONDS));
            assertTrue(cleanupStarted.await(1, TimeUnit.SECONDS));
            assertEquals(1, cleanupCalls.get());
        } finally {
            releaseCallers.countDown();
            releaseCleanup.countDown();
        }
        assertTrue(processExit.await(1, TimeUnit.SECONDS));
    }

    @Test
    void threadPreparationFailureForcesExitAndConsumesDuplicateRequests() throws InterruptedException {
        CountDownLatch forcedExit = new CountDownLatch(1);
        AtomicInteger cleanupCalls = new AtomicInteger();
        AtomicInteger processExitCalls = new AtomicInteger();
        AtomicInteger forcedExitCalls = new AtomicInteger();
        var shutdown = new ApplicationShutdown(
                cleanupCalls::incrementAndGet,
                code -> processExitCalls.incrementAndGet(),
                code -> {
                    forcedExitCalls.incrementAndGet();
                    forcedExit.countDown();
                },
                1_000,
                action -> {
                    throw new SecurityException("thread creation denied");
                });

        assertTimeout(Duration.ofSeconds(1), shutdown::requestQuit);
        shutdown.requestQuit();

        assertTrue(forcedExit.await(1, TimeUnit.SECONDS));
        assertEquals(0, cleanupCalls.get());
        assertEquals(0, processExitCalls.get());
        assertEquals(1, forcedExitCalls.get());
    }

    @Test
    void threadStartFailureForcesExit() throws InterruptedException {
        CountDownLatch forcedExit = new CountDownLatch(1);
        AtomicInteger createdThreads = new AtomicInteger();
        AtomicInteger cleanupCalls = new AtomicInteger();
        AtomicInteger processExitCalls = new AtomicInteger();
        var shutdown = new ApplicationShutdown(
                cleanupCalls::incrementAndGet,
                code -> processExitCalls.incrementAndGet(),
                code -> forcedExit.countDown(),
                1_000,
                action -> createdThreads.incrementAndGet() == 1
                        ? new StartFailureThread(action)
                        : new Thread(action));

        assertTimeout(Duration.ofSeconds(1), shutdown::requestQuit);

        assertTrue(forcedExit.await(1, TimeUnit.SECONDS));
        assertEquals(2, createdThreads.get());
        assertEquals(0, cleanupCalls.get());
        assertEquals(0, processExitCalls.get());
    }

    @Test
    void cleanupFailureStillRequestsNormalProcessExit() throws InterruptedException {
        CountDownLatch processExit = new CountDownLatch(1);
        AtomicInteger forcedExitCalls = new AtomicInteger();
        var shutdown = new ApplicationShutdown(
                () -> {
                    throw new IllegalStateException("cleanup failed");
                },
                code -> processExit.countDown(),
                code -> forcedExitCalls.incrementAndGet(),
                1_000);

        shutdown.requestQuit();

        assertTrue(processExit.await(1, TimeUnit.SECONDS));
        assertEquals(0, forcedExitCalls.get());
    }

    @Test
    void normalProcessExitFailureRequestsForcedExitOnce() throws InterruptedException {
        CountDownLatch forcedExit = new CountDownLatch(1);
        AtomicInteger forcedExitCalls = new AtomicInteger();
        var shutdown = new ApplicationShutdown(
                NO_OP_CLEANUP,
                code -> {
                    throw new SecurityException("exit denied");
                },
                code -> {
                    forcedExitCalls.incrementAndGet();
                    forcedExit.countDown();
                },
                1_000);

        shutdown.requestQuit();

        assertTrue(forcedExit.await(1, TimeUnit.SECONDS));
        assertEquals(1, forcedExitCalls.get());
    }

    @Test
    void watchdogForcesExitWhenCleanupBlocks() throws InterruptedException {
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        CountDownLatch forcedExit = new CountDownLatch(1);
        AtomicInteger forcedExitCalls = new AtomicInteger();
        var shutdown = new ApplicationShutdown(
                () -> {
                    cleanupStarted.countDown();
                    awaitUninterruptibly(releaseCleanup);
                },
                NO_OP_EXIT,
                code -> {
                    forcedExitCalls.incrementAndGet();
                    forcedExit.countDown();
                },
                100);

        try {
            shutdown.requestQuit();
            assertTrue(cleanupStarted.await(1, TimeUnit.SECONDS));
            assertTrue(forcedExit.await(2, TimeUnit.SECONDS));
            assertEquals(1, forcedExitCalls.get());
        } finally {
            releaseCleanup.countDown();
        }
    }

    @Test
    void watchdogInterruptDoesNotDisarmForcedExit() throws InterruptedException {
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        CountDownLatch forcedExit = new CountDownLatch(1);
        AtomicInteger forcedExitCalls = new AtomicInteger();
        List<Thread> shutdownThreads = new CopyOnWriteArrayList<>();
        var shutdown = new ApplicationShutdown(
                () -> {
                    cleanupStarted.countDown();
                    awaitUninterruptibly(releaseCleanup);
                },
                NO_OP_EXIT,
                code -> {
                    forcedExitCalls.incrementAndGet();
                    forcedExit.countDown();
                },
                100,
                action -> {
                    boolean interruptOnStart = shutdownThreads.size() == 1;
                    Thread thread = new Thread(() -> {
                        if (interruptOnStart) {
                            Thread.currentThread().interrupt();
                        }
                        action.run();
                    });
                    shutdownThreads.add(thread);
                    return thread;
                });

        try {
            shutdown.requestQuit();
            assertTrue(cleanupStarted.await(1, TimeUnit.SECONDS));
            assertTrue(forcedExit.await(2, TimeUnit.SECONDS));
            assertEquals(1, forcedExitCalls.get());

            Thread watchdog = shutdownThreads.getLast();
            watchdog.join(1_000);
            assertFalse(watchdog.isAlive());
            assertTrue(watchdog.isInterrupted());
        } finally {
            releaseCleanup.countDown();
        }
    }

    @Test
    void completedShutdownDoesNotTriggerWatchdog() throws InterruptedException {
        CountDownLatch processExit = new CountDownLatch(1);
        CountDownLatch forcedExit = new CountDownLatch(1);
        var shutdown = new ApplicationShutdown(
                NO_OP_CLEANUP,
                code -> processExit.countDown(),
                code -> forcedExit.countDown(),
                100);

        shutdown.requestQuit();

        assertTrue(processExit.await(1, TimeUnit.SECONDS));
        assertFalse(forcedExit.await(300, TimeUnit.MILLISECONDS));
    }

    @Test
    void productionWatchdogAllowsTenSecondsForNativeCleanup() {
        assertEquals(TimeUnit.SECONDS.toMillis(10), ApplicationShutdown.DEFAULT_TIMEOUT_MILLIS);
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(NullPointerException.class, () -> new ApplicationShutdown(null));
        assertThrows(IllegalArgumentException.class,
                () -> new ApplicationShutdown(NO_OP_CLEANUP, NO_OP_EXIT, NO_OP_EXIT, 0));
    }

    private static final class StartFailureThread extends Thread {
        private StartFailureThread(Runnable action) {
            super(action);
        }

        @Override
        public synchronized void start() {
            throw new SecurityException("thread start denied");
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException error) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
