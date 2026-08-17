package wtf.nanoka.airplay.server.internal.handler.video;

import java.util.Arrays;

final class H264SpsParser {

    private H264SpsParser() {
    }

    static Format parse(byte[] sps) {
        if (sps == null || sps.length < 4 || (sps[0] & 0x1f) != 7) {
            return null;
        }
        try {
            var reader = new BitReader(toRbsp(sps));
            int profileIdc = reader.readBits(8);
            reader.readBits(8);
            reader.readBits(8);
            reader.readUnsignedExpGolomb();

            int chromaFormatIdc = 1;
            boolean separateColourPlane = false;
            if (isHighProfile(profileIdc)) {
                chromaFormatIdc = reader.readUnsignedExpGolomb();
                if (chromaFormatIdc == 3) {
                    separateColourPlane = reader.readBit();
                }
                reader.readUnsignedExpGolomb();
                reader.readUnsignedExpGolomb();
                reader.readBit();
                if (reader.readBit()) {
                    int scalingLists = chromaFormatIdc != 3 ? 8 : 12;
                    for (int i = 0; i < scalingLists; i++) {
                        if (reader.readBit()) {
                            skipScalingList(reader, i < 6 ? 16 : 64);
                        }
                    }
                }
            }

            reader.readUnsignedExpGolomb();
            int picOrderCountType = reader.readUnsignedExpGolomb();
            if (picOrderCountType == 0) {
                reader.readUnsignedExpGolomb();
            } else if (picOrderCountType == 1) {
                reader.readBit();
                reader.readSignedExpGolomb();
                reader.readSignedExpGolomb();
                int cycleCount = reader.readUnsignedExpGolomb();
                for (int i = 0; i < cycleCount; i++) {
                    reader.readSignedExpGolomb();
                }
            }
            reader.readUnsignedExpGolomb();
            reader.readBit();
            int widthInMbs = reader.readUnsignedExpGolomb() + 1;
            int heightInMapUnits = reader.readUnsignedExpGolomb() + 1;
            boolean frameMbsOnly = reader.readBit();
            if (!frameMbsOnly) {
                reader.readBit();
            }
            reader.readBit();

            int cropLeft = 0;
            int cropRight = 0;
            int cropTop = 0;
            int cropBottom = 0;
            if (reader.readBit()) {
                cropLeft = reader.readUnsignedExpGolomb();
                cropRight = reader.readUnsignedExpGolomb();
                cropTop = reader.readUnsignedExpGolomb();
                cropBottom = reader.readUnsignedExpGolomb();
            }

            int subWidthC = separateColourPlane ? 1 : chromaFormatIdc == 3 ? 1 : 2;
            int subHeightC = separateColourPlane ? 1 : chromaFormatIdc == 1 ? 2 : 1;
            int cropUnitX = subWidthC;
            int cropUnitY = subHeightC * (frameMbsOnly ? 1 : 2);
            int width = widthInMbs * 16 - cropUnitX * (cropLeft + cropRight);
            int height = (2 - (frameMbsOnly ? 1 : 0)) * heightInMapUnits * 16
                    - cropUnitY * (cropTop + cropBottom);
            return new Format(width, height, 0);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isHighProfile(int profileIdc) {
        return switch (profileIdc) {
            case 44, 83, 86, 100, 110, 118, 122, 128, 134, 135, 138, 139, 244 -> true;
            default -> false;
        };
    }

    private static void skipScalingList(BitReader reader, int size) {
        int lastScale = 8;
        int nextScale = 8;
        for (int i = 0; i < size; i++) {
            if (nextScale != 0) {
                int deltaScale = reader.readSignedExpGolomb();
                nextScale = (lastScale + deltaScale + 256) % 256;
            }
            lastScale = nextScale == 0 ? lastScale : nextScale;
        }
    }

    private static byte[] toRbsp(byte[] nal) {
        byte[] rbsp = new byte[nal.length - 1];
        int output = 0;
        int zeroCount = 0;
        for (int i = 1; i < nal.length; i++) {
            byte value = nal[i];
            if (zeroCount == 2 && value == 3) {
                zeroCount = 0;
                continue;
            }
            rbsp[output++] = value;
            zeroCount = value == 0 ? zeroCount + 1 : 0;
        }
        return Arrays.copyOf(rbsp, output);
    }

    record Format(int width, int height, double fps) {
    }

    private static final class BitReader {
        private final byte[] bytes;
        private int bitIndex;

        private BitReader(byte[] bytes) {
            this.bytes = bytes;
        }

        private boolean readBit() {
            if (bitIndex >= bytes.length * 8) {
                throw new IllegalArgumentException("Unexpected end of H.264 SPS");
            }
            boolean value = ((bytes[bitIndex / 8] >> (7 - (bitIndex % 8))) & 1) != 0;
            bitIndex++;
            return value;
        }

        private int readBits(int count) {
            int value = 0;
            for (int i = 0; i < count; i++) {
                value = (value << 1) | (readBit() ? 1 : 0);
            }
            return value;
        }

        private int readUnsignedExpGolomb() {
            int leadingZeroBits = 0;
            while (!readBit()) {
                leadingZeroBits++;
                if (leadingZeroBits > 31) {
                    throw new IllegalArgumentException("Invalid H.264 Exp-Golomb value");
                }
            }
            int suffix = leadingZeroBits == 0 ? 0 : readBits(leadingZeroBits);
            return ((1 << leadingZeroBits) - 1) + suffix;
        }

        private int readSignedExpGolomb() {
            int value = readUnsignedExpGolomb();
            return (value & 1) == 0 ? -(value / 2) : (value + 1) / 2;
        }
    }
}
