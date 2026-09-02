package wtf.nanoka.airplay.app.power;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the Windows display awake while video is being presented.
 * SetThreadExecutionState stores requirements on the calling thread, so one
 * long-lived platform thread owns both the request and its release.
 */
@Slf4j
public final class DisplaySleepPreventer implements AutoCloseable {

    private static final int DISPLAY_REQUIRED = WinBase.ES_CONTINUOUS | WinBase.ES_DISPLAY_REQUIRED;
    private static final int DEFAULT_EXECUTION_STATE = WinBase.ES_CONTINUOUS;

    private final ExecutionStateApi executionStateApi;
    private final ExecutorService worker;
    private boolean displayRequired;
    private boolean closed;

    private DisplaySleepPreventer() {
        executionStateApi = null;
        worker = null;
    }

    DisplaySleepPreventer(ExecutionStateApi executionStateApi) {
        this.executionStateApi = Objects.requireNonNull(executionStateApi, "executionStateApi");
        worker = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon(true).name("windows-display-awake").factory());
    }

    public static DisplaySleepPreventer create() {
        if (!Platform.isWindows()) {
            return new DisplaySleepPreventer();
        }
        try {
            return new DisplaySleepPreventer(Kernel32.INSTANCE::SetThreadExecutionState);
        } catch (RuntimeException | LinkageError error) {
            log.warn("Unable to initialize Windows display sleep prevention: {}", error.getMessage());
            return new DisplaySleepPreventer();
        }
    }

    public synchronized void preventDisplaySleep() {
        if (worker == null || closed || displayRequired) {
            return;
        }
        if (applyExecutionState(DISPLAY_REQUIRED, "prevent Windows display sleep")) {
            displayRequired = true;
            log.info("Preventing Windows from turning off the display while video is active");
        }
    }

    public synchronized void allowDisplaySleep() {
        if (worker == null || closed || !displayRequired) {
            return;
        }
        if (applyExecutionState(DEFAULT_EXECUTION_STATE, "restore the Windows display sleep policy")) {
            displayRequired = false;
            log.info("Restored the Windows display sleep policy");
        }
    }

    @Override
    public void close() {
        ExecutorService workerToStop;
        synchronized (this) {
            if (worker == null || closed) {
                return;
            }
            if (displayRequired) {
                applyExecutionState(DEFAULT_EXECUTION_STATE, "restore the Windows display sleep policy");
                displayRequired = false;
            }
            closed = true;
            worker.shutdown();
            workerToStop = worker;
        }
        awaitTermination(workerToStop);
    }

    private boolean applyExecutionState(int state, String operation) {
        Future<Integer> result;
        try {
            result = worker.submit(() -> executionStateApi.setThreadExecutionState(state));
        } catch (RejectedExecutionException error) {
            log.warn("Unable to {}: {}", operation, error.getMessage());
            return false;
        }

        boolean interrupted = false;
        try {
            while (true) {
                try {
                    int previousState = result.get();
                    if (previousState == 0) {
                        log.warn("Unable to {}: SetThreadExecutionState returned failure", operation);
                        return false;
                    }
                    return true;
                } catch (InterruptedException error) {
                    interrupted = true;
                }
            }
        } catch (ExecutionException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            log.warn("Unable to {}: {}", operation, cause.getMessage());
            return false;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void awaitTermination(ExecutorService executor) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                    }
                    return;
                } catch (InterruptedException error) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @FunctionalInterface
    interface ExecutionStateApi {
        int setThreadExecutionState(int state);
    }
}
