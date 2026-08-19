package wtf.nanoka.airplay.server.internal.handler.video;

import java.util.Arrays;

final class H265SpsParser {

    private H265SpsParser() {
    }

    static Format parse(byte[] sps) {
        if (sps == null || sps.length < 5 || ((sps[0] >> 1) & 0x3f) != 33) {
            return null;
        }
        try {
            var reader = new BitReader(toRbsp(sps));
            reader.skipBits(4);
            int maxSubLayersMinusOne = reader.readBits(3);
            reader.skipBits(1);
            skipProfileTierLevel(reader, maxSubLayersMinusOne);
            reader.readUnsignedExpGolomb();
            int chromaFormatIdc = reader.readUnsignedExpGolomb();
            boolean separateColourPlane = chromaFormatIdc == 3 && reader.readBit();
            int width = reader.readUnsignedExpGolomb();
            int height = reader.readUnsignedExpGolomb();

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

            int chromaArrayType = separateColourPlane ? 0 : chromaFormatIdc;
            int subWidth = chromaArrayType == 1 || chromaArrayType == 2 ? 2 : 1;
            int subHeight = chromaArrayType == 1 ? 2 : 1;
            width -= subWidth * (cropLeft + cropRight);
            height -= subHeight * (cropTop + cropBottom);
            if (width <= 0 || height <= 0) {
                return null;
            }
            return new Format(width, height, 0);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void skipProfileTierLevel(BitReader reader, int maxSubLayersMinusOne) {
        reader.skipBits(96);
        boolean[] profilePresent = new boolean[maxSubLayersMinusOne];
        boolean[] levelPresent = new boolean[maxSubLayersMinusOne];
        for (int index = 0; index < maxSubLayersMinusOne; index++) {
            profilePresent[index] = reader.readBit();
            levelPresent[index] = reader.readBit();
        }
        if (maxSubLayersMinusOne > 0) {
            reader.skipBits((8 - maxSubLayersMinusOne) * 2);
        }
        for (int index = 0; index < maxSubLayersMinusOne; index++) {
            if (profilePresent[index]) {
                reader.skipBits(88);
            }
            if (levelPresent[index]) {
                reader.skipBits(8);
            }
        }
    }

    private static byte[] toRbsp(byte[] nal) {
        byte[] rbsp = new byte[nal.length - 2];
        int output = 0;
        int zeroCount = 0;
        for (int index = 2; index < nal.length; index++) {
            byte value = nal[index];
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
                throw new IllegalArgumentException("Unexpected end of H.265 SPS");
            }
            boolean value = ((bytes[bitIndex / 8] >> (7 - bitIndex % 8)) & 1) != 0;
            bitIndex++;
            return value;
        }

        private int readBits(int count) {
            int value = 0;
            for (int index = 0; index < count; index++) {
                value = (value << 1) | (readBit() ? 1 : 0);
            }
            return value;
        }

        private void skipBits(int count) {
            if (count < 0 || bitIndex + count > bytes.length * 8) {
                throw new IllegalArgumentException("Unexpected end of H.265 SPS");
            }
            bitIndex += count;
        }

        private int readUnsignedExpGolomb() {
            int leadingZeroBits = 0;
            while (!readBit()) {
                if (++leadingZeroBits > 30) {
                    throw new IllegalArgumentException("Invalid H.265 Exp-Golomb value");
                }
            }
            int suffix = leadingZeroBits == 0 ? 0 : readBits(leadingZeroBits);
            return ((1 << leadingZeroBits) - 1) + suffix;
        }
    }
}
