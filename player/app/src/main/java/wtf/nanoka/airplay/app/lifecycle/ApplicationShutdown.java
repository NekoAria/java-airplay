package wtf.nanoka.airplay.app.lifecycle;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

/**
 * Owns the application quit sequence so UI and restart callers never perform
 * blocking Spring or native-player cleanup on their calling thread.
 */
@Slf4j
public final class ApplicationShutdown {

    static final long DEFAULT_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(10);

    private final Runnable closeApplication;
    private final IntConsumer processExit;
    private final IntConsumer forcedExit;
    private final long timeoutMillis;
    private final ThreadFactory threadFactory;
    private final AtomicBoolean quitRequested = new AtomicBoolean();
    private final AtomicBoolean forcedExitRequested = new AtomicBoolean();

    public ApplicationShutdown(ApplicationContext applicationContext) {
        this(applicationContext,
                System::exit,
                code -> Runtime.getRuntime().halt(code),
                DEFAULT_TIMEOUT_MILLIS);
    }

    ApplicationShutdown(ApplicationContext applicationContext,
                        IntConsumer processExit,
                        IntConsumer forcedExit,
                        long timeoutMillis) {
        this(closeAction(applicationContext), processExit, forcedExit, timeoutMillis);
    }

    private static Runnable closeAction(ApplicationContext applicationContext) {
        Objects.requireNonNull(applicationContext, "applicationContext");
        return () -> SpringApplication.exit(applicationContext, () -> 0);
    }

    ApplicationShutdown(Runnable closeApplication,
                        IntConsumer processExit,
                        IntConsumer forcedExit,
                        long timeoutMillis) {
        this(closeApplication, processExit, forcedExit, timeoutMillis, Thread::new);
    }

    ApplicationShutdown(Runnable closeApplication,
                        IntConsumer processExit,
                        IntConsumer forcedExit,
                        long timeoutMillis,
                        ThreadFactory threadFactory) {
        this.closeApplication = Objects.requireNonNull(closeApplication, "closeApplication");
        this.processExit = Objects.requireNonNull(processExit, "processExit");
        this.forcedExit = Objects.requireNonNull(forcedExit, "forcedExit");
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
        this.timeoutMillis = timeoutMillis;
        this.threadFactory = Objects.requireNonNull(threadFactory, "threadFactory");
    }

    /**
     * Starts the quit sequence once and returns without waiting for cleanup.
     */
    public void requestQuit() {
        if (!quitRequested.compareAndSet(false, true)) {
            return;
        }

        CountDownLatch startGate = null;
        try {
            log.info("Application quit requested");
            startGate = new CountDownLatch(1);
            startShutdownThreads(startGate);
        } catch (Throwable error) {
            requestForcedExit("Unable to prepare the application shutdown threads", error);
        } finally {
            if (startGate != null) {
                startGate.countDown();
            }
        }
    }

    private void startShutdownThreads(CountDownLatch startGate) {
        Thread shutdownWorker = createThread("java-airplay-shutdown", false, () -> {
            awaitStart(startGate);
            runShutdown();
        });
        Thread watchdog = createThread("java-airplay-shutdown-watchdog", true, () -> {
            awaitStart(startGate);
            watch(shutdownWorker);
        });
        watchdog.start();
        shutdownWorker.start();
    }

    private Thread createThread(String name, boolean daemon, Runnable action) {
        Thread thread = Objects.requireNonNull(threadFactory.newThread(action), "threadFactory.newThread()");
        thread.setName(name);
        thread.setDaemon(daemon);
        return thread;
    }

    private static void awaitStart(CountDownLatch startGate) {
        boolean interrupted = false;
        while (true) {
            try {
                startGate.await();
                break;
            } catch (InterruptedException error) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void runShutdown() {
        try {
            closeApplication.run();
        } catch (Throwable error) {
            log.error("Graceful application cleanup failed", error);
        }

        try {
            processExit.accept(0);
        } catch (Throwable error) {
            requestForcedExit("Normal process exit failed", error);
        }
    }

    private void watch(Thread shutdownWorker) {
        boolean interrupted = false;
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try {
            while (shutdownWorker.isAlive()) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    break;
                }
                try {
                    TimeUnit.NANOSECONDS.timedJoin(shutdownWorker, remainingNanos);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }

            if (shutdownWorker.isAlive()) {
                requestForcedExit("Graceful shutdown exceeded " + timeoutMillis + " ms", null);
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
                log.warn("Application shutdown watchdog was interrupted; timeout enforcement continued");
            }
        }
    }

    private void requestForcedExit(String reason, Throwable error) {
        if (!forcedExitRequested.compareAndSet(false, true)) {
            return;
        }
        if (error == null) {
            log.error("{}; forcing process termination", reason);
        } else {
            log.error(reason + "; forcing process termination", error);
        }
        try {
            forcedExit.accept(0);
        } catch (Throwable forcedExitError) {
            log.error("Forced process termination failed", forcedExitError);
        }
    }
}
