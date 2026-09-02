package wtf.nanoka.airplay.player.gstreamer;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtpAudioTimingTest {

    @Test
    void sequenceTrackerAcceptsForwardProgressAndWrapButRejectsDuplicatesAndLatePackets() {
        var tracker = new RtpAudioTiming.SequenceTracker();

        assertTrue(tracker.accept(65_534));
        assertTrue(tracker.accept(65_535));
        assertTrue(tracker.accept(0));
        assertFalse(tracker.accept(0));
        assertFalse(tracker.accept(65_535));
        assertTrue(tracker.accept(2));

        tracker.reset();
        assertTrue(tracker.accept(40_000));
    }

    @Test
    void timelinePreservesLegitimateTimestampWrapWithForwardSequenceNumbers() {
        var timeline = new RtpAudioTiming.Timeline();
        long duration = samplesToNanos(256, 44_100);

        assertEquals(0, timeline.presentationTime(0xffff_ff00L, 65_535, 44_100, duration));
        assertEquals(samplesToNanos(512, 44_100),
                timeline.presentationTime(0x0000_0100L, 0, 44_100, duration));
    }

    @Test
    void timelineTreatsBackwardTimestampAsDiscontinuityInsteadOfTwentySevenHoursInTheFuture() {
        var timeline = new RtpAudioTiming.Timeline();
        long duration = samplesToNanos(100, 44_100);

        assertEquals(0, timeline.presentationTime(1_000, 10, 44_100, duration));
        assertEquals(duration, timeline.presentationTime(900, 11, 44_100, duration));
        assertEquals(duration + samplesToNanos(200, 44_100),
                timeline.presentationTime(1_100, 12, 44_100, duration));
    }

    @Test
    void timelineNeverMovesBackwardWhenPacketsAreMissingOrUntimestamped() {
        var timeline = new RtpAudioTiming.Timeline();
        long duration = samplesToNanos(480, 48_000);

        assertEquals(0, timeline.presentationTime(10_000, 100, 48_000, duration));
        assertEquals(duration * 3, timeline.presentationTime(11_440, 103, 48_000, duration));
        assertEquals(duration * 4, timeline.presentationTime(-1, -1, 48_000, duration));
    }

    private long samplesToNanos(long samples, int sampleRate) {
        return samples * TimeUnit.SECONDS.toNanos(1) / sampleRate;
    }
}
