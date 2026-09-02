package wtf.nanoka.airplay.player.gstreamer;

import java.util.concurrent.TimeUnit;

/** Pure RTP ordering and presentation-time state used by the native audio feeder. */
final class RtpAudioTiming {

    private static final long RTP_TIMESTAMP_MASK = 0xffff_ffffL;
    private static final long RTP_TIMESTAMP_HALF_RANGE = 0x8000_0000L;

    private RtpAudioTiming() {
    }

    static int normalizeSequence(int sequenceNumber) {
        return sequenceNumber & 0xffff;
    }

    static int forwardSequenceDistance(int previous, int current) {
        return (normalizeSequence(current) - normalizeSequence(previous)) & 0xffff;
    }

    static boolean isStrictlyForwardSequence(int previous, int current) {
        int distance = forwardSequenceDistance(previous, current);
        return distance > 0 && distance < 0x8000;
    }

    static final class SequenceTracker {
        private Integer lastSequence;

        boolean accept(int sequenceNumber) {
            if (sequenceNumber < 0) {
                return true;
            }
            int normalized = normalizeSequence(sequenceNumber);
            if (lastSequence == null) {
                lastSequence = normalized;
                return true;
            }
            if (!isStrictlyForwardSequence(lastSequence, normalized)) {
                return false;
            }
            lastSequence = normalized;
            return true;
        }

        void reset() {
            lastSequence = null;
        }
    }

    static final class Timeline {
        private long lastTimestamp = -1;
        private int lastSequence = -1;
        private long lastPresentationTime;
        private long nextSyntheticPresentationTime;

        long presentationTime(long timestamp, int sequenceNumber, int sampleRate, long duration) {
            long presentationTime = nextSyntheticPresentationTime;
            if (timestamp >= 0 && lastTimestamp >= 0
                    && sequenceMovesForward(sequenceNumber)) {
                long timestampDelta = (timestamp - lastTimestamp) & RTP_TIMESTAMP_MASK;
                if (timestampDelta < RTP_TIMESTAMP_HALF_RANGE) {
                    long deltaNanos = timestampDelta * TimeUnit.SECONDS.toNanos(1) / sampleRate;
                    presentationTime = Math.max(
                            nextSyntheticPresentationTime,
                            lastPresentationTime + deltaNanos);
                }
            }

            if (timestamp >= 0) {
                lastTimestamp = timestamp;
            }
            if (sequenceNumber >= 0) {
                lastSequence = normalizeSequence(sequenceNumber);
            }
            lastPresentationTime = presentationTime;
            nextSyntheticPresentationTime = presentationTime + duration;
            return presentationTime;
        }

        private boolean sequenceMovesForward(int sequenceNumber) {
            return sequenceNumber < 0
                    || lastSequence < 0
                    || isStrictlyForwardSequence(lastSequence, sequenceNumber);
        }
    }
}
