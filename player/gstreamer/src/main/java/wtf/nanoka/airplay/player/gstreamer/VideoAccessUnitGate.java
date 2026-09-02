package wtf.nanoka.airplay.player.gstreamer;

import wtf.nanoka.airplay.lib.VideoStreamInfo;

import java.util.Arrays;
import java.util.Objects;

/**
 * Holds codec configuration while suppressing predicted frames until a random
 * access unit can safely start or restart a decoder.
 */
final class VideoAccessUnitGate {

    private VideoStreamInfo.Codec codec = VideoStreamInfo.Codec.UNKNOWN;
    private byte[] parameterSets;
    private boolean prependParameterSets;
    private boolean awaitingRandomAccessUnit;

    void activate(VideoStreamInfo.Codec nextCodec) {
        Objects.requireNonNull(nextCodec, "nextCodec");
        if (nextCodec == VideoStreamInfo.Codec.UNKNOWN) {
            throw new IllegalArgumentException("A video access-unit gate requires a known codec");
        }
        if (codec != nextCodec) {
            parameterSets = null;
            prependParameterSets = false;
        }
        codec = nextCodec;
        awaitingRandomAccessUnit = true;
    }

    void prepareRestart() {
        prependParameterSets = parameterSets != null;
    }

    void deactivate() {
        codec = VideoStreamInfo.Codec.UNKNOWN;
        parameterSets = null;
        prependParameterSets = false;
        awaitingRandomAccessUnit = false;
    }

    byte[] accept(byte[] accessUnit) {
        Objects.requireNonNull(accessUnit, "accessUnit");
        if (codec == VideoStreamInfo.Codec.UNKNOWN) {
            return null;
        }

        byte[] detectedParameterSets = leadingParameterSets(accessUnit, codec);
        if (detectedParameterSets != null) {
            parameterSets = detectedParameterSets;
        }
        boolean randomAccessUnit = containsRandomAccessUnit(accessUnit, codec);
        if (awaitingRandomAccessUnit && !randomAccessUnit) {
            return null;
        }

        byte[] accepted = accessUnit;
        if ((prependParameterSets || awaitingRandomAccessUnit)
                && detectedParameterSets == null && parameterSets != null) {
            accepted = concatenate(parameterSets, accessUnit);
        }
        prependParameterSets = false;
        awaitingRandomAccessUnit = false;
        return accepted;
    }

    private boolean containsRandomAccessUnit(byte[] accessUnit, VideoStreamInfo.Codec activeCodec) {
        int offset = 0;
        while (offset + 5 <= accessUnit.length && hasStartCode(accessUnit, offset)) {
            int nalStart = offset + 4;
            int next = findStartCode(accessUnit, nalStart + 1);
            int nalType = activeCodec == VideoStreamInfo.Codec.HEVC
                    ? (accessUnit[nalStart] >> 1) & 0x3f
                    : accessUnit[nalStart] & 0x1f;
            if (activeCodec == VideoStreamInfo.Codec.H264 && nalType == 5) {
                return true;
            }
            if (activeCodec == VideoStreamInfo.Codec.HEVC && nalType >= 16 && nalType <= 21) {
                return true;
            }
            offset = next >= 0 ? next : accessUnit.length;
        }
        return false;
    }

    private byte[] leadingParameterSets(byte[] accessUnit, VideoStreamInfo.Codec activeCodec) {
        int offset = 0;
        int parameterSetsEnd = 0;
        while (offset + 5 <= accessUnit.length && hasStartCode(accessUnit, offset)) {
            int nalStart = offset + 4;
            int next = findStartCode(accessUnit, nalStart + 1);
            int nalEnd = next >= 0 ? next : accessUnit.length;
            int nalType = activeCodec == VideoStreamInfo.Codec.HEVC
                    ? (accessUnit[nalStart] >> 1) & 0x3f
                    : accessUnit[nalStart] & 0x1f;
            boolean parameterSet = activeCodec == VideoStreamInfo.Codec.HEVC
                    ? nalType >= 32 && nalType <= 34
                    : nalType == 7 || nalType == 8;
            if (!parameterSet) {
                break;
            }
            parameterSetsEnd = nalEnd;
            offset = nalEnd;
        }
        return parameterSetsEnd == 0 ? null : Arrays.copyOf(accessUnit, parameterSetsEnd);
    }

    private boolean hasStartCode(byte[] bytes, int offset) {
        return bytes.length - offset >= 4
                && bytes[offset] == 0 && bytes[offset + 1] == 0
                && bytes[offset + 2] == 0 && bytes[offset + 3] == 1;
    }

    private int findStartCode(byte[] bytes, int offset) {
        for (int index = offset; index <= bytes.length - 4; index++) {
            if (hasStartCode(bytes, index)) {
                return index;
            }
        }
        return -1;
    }

    private byte[] concatenate(byte[] first, byte[] second) {
        byte[] combined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, combined, first.length, second.length);
        return combined;
    }
}
