package wtf.nanoka.airplay.lib;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AirPlayBonjourTest {

    @Test
    void closesNetworkServicesConcurrentlyAndExactlyOnce() throws InterruptedException {
        int serviceCount = 5;
        CountDownLatch allClosesStarted = new CountDownLatch(serviceCount);
        CountDownLatch releaseCloses = new CountDownLatch(1);
        AtomicInteger closeCalls = new AtomicInteger();
        List<AutoCloseable> services = new ArrayList<>();
        for (int index = 0; index < serviceCount; index++) {
            services.add(() -> {
                closeCalls.incrementAndGet();
                allClosesStarted.countDown();
                releaseCloses.await();
            });
        }

        Thread closer = Thread.startVirtualThread(() -> AirPlayBonjour.closeAll(services));
        try {
            assertTrue(allClosesStarted.await(2, TimeUnit.SECONDS));
        } finally {
            releaseCloses.countDown();
            closer.join(TimeUnit.SECONDS.toMillis(2));
        }

        assertFalse(closer.isAlive());
        assertEquals(serviceCount, closeCalls.get());
    }

    @Test
    void waitsForEveryServiceWhenInterruptedAndRestoresCallerInterrupt() throws InterruptedException {
        CountDownLatch allClosesStarted = new CountDownLatch(2);
        CountDownLatch releaseCloses = new CountDownLatch(1);
        AtomicInteger closeCalls = new AtomicInteger();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        List<AutoCloseable> services = List.of(
                blockingClose(allClosesStarted, releaseCloses, closeCalls),
                blockingClose(allClosesStarted, releaseCloses, closeCalls));

        Thread closer = Thread.startVirtualThread(() -> {
            AirPlayBonjour.closeAll(services);
            interruptRestored.set(Thread.currentThread().isInterrupted());
        });
        try {
            assertTrue(allClosesStarted.await(1, TimeUnit.SECONDS));
            closer.interrupt();
            assertTrue(closer.isAlive());
        } finally {
            releaseCloses.countDown();
            closer.join(TimeUnit.SECONDS.toMillis(2));
        }

        assertFalse(closer.isAlive());
        assertEquals(services.size(), closeCalls.get());
        assertTrue(interruptRestored.get());
    }

    private static AutoCloseable blockingClose(
            CountDownLatch closeStarted,
            CountDownLatch releaseClose,
            AtomicInteger closeCalls) {
        return () -> {
            closeStarted.countDown();
            releaseClose.await();
            closeCalls.incrementAndGet();
        };
    }
}
