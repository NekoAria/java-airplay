package wtf.nanoka.airplay.server.internal.handler.session;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/**
 * Tracks in-flight callbacks by media lease so revoking one stream never waits
 * for callbacks that still belong to another active stream or control session.
 */
final class SessionMediaCallbackGate {

    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition drained = lock.newCondition();
    private final Map<SessionManager.MediaLease, Integer> callbacksInFlight = new IdentityHashMap<>();
    private final ThreadLocal<Map<SessionManager.MediaLease, Integer>> callbackDepth = new ThreadLocal<>();

    void dispatch(SessionManager.MediaLease callbackKey, BooleanSupplier isCurrent, Runnable callback) {
        Objects.requireNonNull(callbackKey, "callbackKey");
        lock.lock();
        try {
            if (!isCurrent.getAsBoolean()) {
                return;
            }
            callbacksInFlight.merge(callbackKey, 1, Integer::sum);
            var callbackDepths = callbackDepth.get();
            if (callbackDepths == null) {
                callbackDepths = new IdentityHashMap<>();
                callbackDepth.set(callbackDepths);
            }
            callbackDepths.merge(callbackKey, 1, Integer::sum);
        } finally {
            lock.unlock();
        }

        try {
            callback.run();
        } finally {
            lock.lock();
            try {
                int remainingCallbacks = callbacksInFlight.get(callbackKey) - 1;
                if (remainingCallbacks == 0) {
                    callbacksInFlight.remove(callbackKey);
                } else {
                    callbacksInFlight.put(callbackKey, remainingCallbacks);
                }

                var callbackDepths = callbackDepth.get();
                int remainingDepth = callbackDepths.get(callbackKey) - 1;
                if (remainingDepth == 0) {
                    callbackDepths.remove(callbackKey);
                } else {
                    callbackDepths.put(callbackKey, remainingDepth);
                }
                if (callbackDepths.isEmpty()) {
                    callbackDepth.remove();
                }
                drained.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    boolean isInCallback() {
        var callbackDepths = callbackDepth.get();
        return callbackDepths != null && !callbackDepths.isEmpty();
    }

    void awaitDrained(SessionManager.MediaLease callbackKey) {
        if (callbackKey == null) {
            return;
        }
        boolean interrupted = false;
        lock.lock();
        try {
            var callbackDepths = callbackDepth.get();
            int callbacksOnCurrentThread = callbackDepths == null
                    ? 0 : callbackDepths.getOrDefault(callbackKey, 0);
            while (callbacksInFlight.getOrDefault(callbackKey, 0) > callbacksOnCurrentThread) {
                try {
                    drained.await();
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        } finally {
            lock.unlock();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
