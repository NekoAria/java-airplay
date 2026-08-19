package wtf.nanoka.airplay.server.internal.handler.video;

import wtf.nanoka.airplay.lib.VideoStreamInfo;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

record VideoCodecConfiguration(
        VideoStreamInfo.Codec codec,
        byte[] parameterSets,
        byte[] sequenceParameterSet) {

    private static final byte[] ANNEX_B_START_CODE = {0, 0, 0, 1};

    static VideoCodecConfiguration parse(byte[] payload) {
        if (isHevcSampleEntry(payload)) {
            return parseHevc(payload);
        }
        return parseAvc(payload);
    }

    private static VideoCodecConfiguration parseAvc(byte[] payload) {
        if (payload == null || payload.length < 7 || payload[0] != 1) {
            throw new IllegalArgumentException("Unsupported video codec configuration");
        }
        if ((payload[4] & 0x03) != 3) {
            throw new IllegalArgumentException("AirPlay video must use four-byte NAL lengths");
        }

        int offset = 6;
        int spsCount = payload[5] & 0x1f;
        List<byte[]> parameterSets = new ArrayList<>();
        byte[] firstSps = null;
        for (int index = 0; index < spsCount; index++) {
            byte[] sps = readNal(payload, offset, "SPS");
            offset += 2 + sps.length;
            if (firstSps == null) {
                firstSps = sps;
            }
            parameterSets.add(sps);
        }
        if (offset >= payload.length) {
            throw new IllegalArgumentException("AVC configuration has no PPS count");
        }
        int ppsCount = payload[offset++] & 0xff;
        for (int index = 0; index < ppsCount; index++) {
            byte[] pps = readNal(payload, offset, "PPS");
            offset += 2 + pps.length;
            parameterSets.add(pps);
        }
        if (firstSps == null || ppsCount == 0) {
            throw new IllegalArgumentException("AVC configuration must contain SPS and PPS data");
        }
        return new VideoCodecConfiguration(
                VideoStreamInfo.Codec.H264, toAnnexB(parameterSets), firstSps);
    }

    private static VideoCodecConfiguration parseHevc(byte[] payload) {
        int sampleEntryEnd = checkedBoxEnd(payload, 0, payload.length, "HEVC sample entry");
        int offset = 86;
        while (offset + 8 <= sampleEntryEnd) {
            int boxEnd = checkedBoxEnd(payload, offset, sampleEntryEnd, "HEVC child box");
            if (matchesType(payload, offset + 4, "hvcC")) {
                return parseHevcDecoderConfiguration(payload, offset + 8, boxEnd);
            }
            offset = boxEnd;
        }
        throw new IllegalArgumentException("HEVC sample entry has no hvcC box");
    }

    private static VideoCodecConfiguration parseHevcDecoderConfiguration(byte[] payload, int offset, int end) {
        if (end - offset < 23 || payload[offset] != 1) {
            throw new IllegalArgumentException("Invalid HEVC decoder configuration record");
        }
        if ((payload[offset + 21] & 0x03) != 3) {
            throw new IllegalArgumentException("AirPlay HEVC must use four-byte NAL lengths");
        }

        int arrayCount = payload[offset + 22] & 0xff;
        int cursor = offset + 23;
        List<byte[]> parameterSets = new ArrayList<>();
        byte[] firstSps = null;
        boolean hasVps = false;
        boolean hasPps = false;
        for (int arrayIndex = 0; arrayIndex < arrayCount; arrayIndex++) {
            if (end - cursor < 3) {
                throw new IllegalArgumentException("HEVC configuration ends inside a NAL array header");
            }
            int nalType = payload[cursor++] & 0x3f;
            int nalCount = readUnsignedShort(payload, cursor);
            cursor += 2;
            for (int nalIndex = 0; nalIndex < nalCount; nalIndex++) {
                byte[] nal = readNal(payload, cursor, end, "HEVC parameter set");
                cursor += 2 + nal.length;
                if (nalType == 32 || nalType == 33 || nalType == 34) {
                    parameterSets.add(nal);
                    hasVps |= nalType == 32;
                    hasPps |= nalType == 34;
                    if (nalType == 33 && firstSps == null) {
                        firstSps = nal;
                    }
                }
            }
        }
        if (!hasVps || firstSps == null || !hasPps) {
            throw new IllegalArgumentException("HEVC configuration must contain VPS, SPS, and PPS data");
        }
        return new VideoCodecConfiguration(
                VideoStreamInfo.Codec.HEVC, toAnnexB(parameterSets), firstSps);
    }

    private static byte[] readNal(byte[] payload, int offset, String name) {
        return readNal(payload, offset, payload.length, name);
    }

    private static byte[] readNal(byte[] payload, int offset, int end, String name) {
        if (end - offset < 2) {
            throw new IllegalArgumentException("Video codec configuration ends before " + name + " length");
        }
        int length = readUnsignedShort(payload, offset);
        if (length == 0 || length > end - offset - 2) {
            throw new IllegalArgumentException("Invalid " + name + " length: " + length);
        }
        return Arrays.copyOfRange(payload, offset + 2, offset + 2 + length);
    }

    private static byte[] toAnnexB(List<byte[]> nalUnits) {
        var output = new ByteArrayOutputStream();
        for (byte[] nal : nalUnits) {
            output.writeBytes(ANNEX_B_START_CODE);
            output.writeBytes(nal);
        }
        return output.toByteArray();
    }

    private static boolean isHevcSampleEntry(byte[] payload) {
        return payload != null && payload.length >= 86
                && (matchesType(payload, 4, "hvc1") || matchesType(payload, 4, "hev1"));
    }

    private static int checkedBoxEnd(byte[] payload, int offset, int parentEnd, String name) {
        if (offset < 0 || parentEnd > payload.length || parentEnd - offset < 8) {
            throw new IllegalArgumentException(name + " header is truncated");
        }
        long size = readUnsignedInt(payload, offset);
        if (size == 0) {
            return parentEnd;
        }
        if (size < 8 || size > parentEnd - offset) {
            throw new IllegalArgumentException(name + " has an invalid size: " + size);
        }
        return offset + (int) size;
    }

    private static boolean matchesType(byte[] payload, int offset, String type) {
        if (offset < 0 || payload.length - offset < 4) {
            return false;
        }
        byte[] expected = type.getBytes(StandardCharsets.US_ASCII);
        return payload[offset] == expected[0]
                && payload[offset + 1] == expected[1]
                && payload[offset + 2] == expected[2]
                && payload[offset + 3] == expected[3];
    }

    private static int readUnsignedShort(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    private static long readUnsignedInt(byte[] bytes, int offset) {
        return ((long) (bytes[offset] & 0xff) << 24)
                | ((long) (bytes[offset + 1] & 0xff) << 16)
                | ((long) (bytes[offset + 2] & 0xff) << 8)
                | (bytes[offset + 3] & 0xffL);
    }
}
