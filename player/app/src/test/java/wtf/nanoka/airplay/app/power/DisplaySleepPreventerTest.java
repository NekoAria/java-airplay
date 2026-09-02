package wtf.nanoka.airplay.app.power;

import com.sun.jna.platform.win32.WinBase;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class DisplaySleepPreventerTest {

    private static final int DISPLAY_REQUIRED = WinBase.ES_CONTINUOUS | WinBase.ES_DISPLAY_REQUIRED;

    @Test
    void setsAndClearsTheDisplayRequirementOnOneDedicatedThread() {
        List<Integer> states = new ArrayList<>();
        List<Long> threadIds = new ArrayList<>();
        long callerThreadId = Thread.currentThread().threadId();

        try (var preventer = new DisplaySleepPreventer(state -> {
            states.add(state);
            threadIds.add(Thread.currentThread().threadId());
            return WinBase.ES_CONTINUOUS;
        })) {
            preventer.preventDisplaySleep();
            preventer.preventDisplaySleep();
            preventer.allowDisplaySleep();
            preventer.allowDisplaySleep();
        }

        assertEquals(List.of(DISPLAY_REQUIRED, WinBase.ES_CONTINUOUS), states);
        assertEquals(1, threadIds.stream().distinct().count());
        assertNotEquals(callerThreadId, threadIds.getFirst());
    }

    @Test
    void closingRestoresTheDefaultExecutionState() {
        List<Integer> states = new ArrayList<>();
        var preventer = new DisplaySleepPreventer(state -> {
            states.add(state);
            return WinBase.ES_CONTINUOUS;
        });

        preventer.preventDisplaySleep();
        preventer.close();
        preventer.preventDisplaySleep();
        preventer.allowDisplaySleep();
        preventer.close();

        assertEquals(List.of(DISPLAY_REQUIRED, WinBase.ES_CONTINUOUS), states);
    }

    @Test
    void retriesAfterWindowsRejectsARequest() {
        List<Integer> states = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();

        try (var preventer = new DisplaySleepPreventer(state -> {
            states.add(state);
            return attempts.getAndIncrement() == 0 ? 0 : WinBase.ES_CONTINUOUS;
        })) {
            preventer.preventDisplaySleep();
            preventer.preventDisplaySleep();
        }

        assertEquals(List.of(DISPLAY_REQUIRED, DISPLAY_REQUIRED, WinBase.ES_CONTINUOUS), states);
    }

    @Test
    void nativeFailuresDoNotBreakPlaybackCallbacks() {
        var preventer = new DisplaySleepPreventer(state -> {
            throw new IllegalStateException("native failure");
        });

        assertDoesNotThrow(preventer::preventDisplaySleep);
        assertDoesNotThrow(preventer::allowDisplaySleep);
        assertDoesNotThrow(preventer::close);
    }
}
