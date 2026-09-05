package wtf.nanoka.airplay.player.test;

import wtf.nanoka.airplay.lib.VideoStreamInfo;
import wtf.nanoka.airplay.server.AirPlayConsumer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class PlaybackFixture {

    private static final String H264_RESOURCE = "/test-pattern.h264";
    private static final String STREAM_ID = "playback-smoke";
    private static final byte[] FOUR_BYTE_START_CODE = {0, 0, 0, 1};
    private static final int NAL_TYPE_MASK = 0x1f;
    private static final int NON_IDR_PICTURE_NAL_TYPE = 1;
    private static final int IDR_PICTURE_NAL_TYPE = 5;
    private static final int SPS_NAL_TYPE = 7;
    private static final int PPS_NAL_TYPE = 8;
    private static final int ACCESS_UNIT_DELIMITER_NAL_TYPE = 9;
    private static final int FRAME_RATE = 30;
    private static final int FRAME_COUNT = 60;
    private static final int WIDTH = 320;
    private static final int HEIGHT = 180;
    private static final long RTP_SECOND = 1L << 32;
    private static final long FRAME_DELAY_NANOS = TimeUnit.SECONDS.toNanos(1) / FRAME_RATE;
    private static final long SETTLE_DELAY_MILLIS = 500;

    public static void playH264(AirPlayConsumer player) throws InterruptedException {
        Objects.requireNonNull(player, "player");
        List<byte[]> accessUnits = loadH264AccessUnits();
        player.onVideoFormat(new VideoStreamInfo(STREAM_ID));
        player.onVideoFormatDetected(new VideoStreamInfo(
                STREAM_ID, WIDTH, HEIGHT, FRAME_RATE, VideoStreamInfo.Codec.H264));

        for (int frame = 0; frame < accessUnits.size(); frame++) {
            long timestamp = frame * RTP_SECOND / FRAME_RATE;
            player.onVideo(accessUnits.get(frame), timestamp);
            if (frame + 1 < accessUnits.size()) {
                TimeUnit.NANOSECONDS.sleep(FRAME_DELAY_NANOS);
            }
        }
        TimeUnit.MILLISECONDS.sleep(SETTLE_DELAY_MILLIS);
    }

    private static List<byte[]> loadH264AccessUnits() {
        byte[] stream;
        try (InputStream input = PlaybackFixture.class.getResourceAsStream(H264_RESOURCE)) {
            stream = Objects.requireNonNull(input, "Missing " + H264_RESOURCE).readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read the H.264 playback fixture", e);
        }

        List<byte[]> accessUnits = splitAccessUnits(stream);
        if (accessUnits.size() != FRAME_COUNT) {
            throw new IllegalStateException(
                    "Expected " + FRAME_COUNT + " H.264 access units, found " + accessUnits.size());
        }
        byte[] firstAccessUnit = accessUnits.getFirst();
        if (!containsNalType(firstAccessUnit, SPS_NAL_TYPE)
                || !containsNalType(firstAccessUnit, PPS_NAL_TYPE)
                || !containsNalType(firstAccessUnit, IDR_PICTURE_NAL_TYPE)) {
            throw new IllegalStateException("The first H.264 access unit must contain SPS, PPS, and IDR data");
        }
        if (!accessUnits.stream().allMatch(PlaybackFixture::containsCodedPicture)) {
            throw new IllegalStateException("Every H.264 access unit must contain a coded picture");
        }
        return accessUnits;
    }

    private static List<byte[]> splitAccessUnits(byte[] stream) {
        List<Integer> delimiterOffsets = new ArrayList<>();
        for (int offset = 0; offset < stream.length; offset++) {
            int startCodeLength = startCodeLength(stream, offset);
            if (startCodeLength == 0 || offset + startCodeLength >= stream.length) {
                continue;
            }
            if ((stream[offset + startCodeLength] & NAL_TYPE_MASK) == ACCESS_UNIT_DELIMITER_NAL_TYPE) {
                delimiterOffsets.add(offset);
            }
            offset += startCodeLength;
        }
        if (delimiterOffsets.isEmpty() || delimiterOffsets.getFirst() != 0) {
            throw new IllegalStateException("The H.264 fixture must start with an access unit delimiter");
        }

        List<byte[]> accessUnits = new ArrayList<>(delimiterOffsets.size());
        for (int index = 0; index < delimiterOffsets.size(); index++) {
            int delimiterOffset = delimiterOffsets.get(index);
            int accessUnitEnd = index + 1 < delimiterOffsets.size()
                    ? delimiterOffsets.get(index + 1)
                    : stream.length;
            int payloadOffset = findStartCode(
                    stream, delimiterOffset + startCodeLength(stream, delimiterOffset) + 1, accessUnitEnd);
            if (payloadOffset < 0) {
                throw new IllegalStateException("An H.264 access unit delimiter has no picture data");
            }
            accessUnits.add(normalizeStartCodes(
                    Arrays.copyOfRange(stream, payloadOffset, accessUnitEnd)));
        }
        return List.copyOf(accessUnits);
    }

    private static byte[] normalizeStartCodes(byte[] accessUnit) {
        ByteArrayOutputStream normalized = new ByteArrayOutputStream(accessUnit.length);
        for (int offset = 0; offset < accessUnit.length; ) {
            int startCodeLength = startCodeLength(accessUnit, offset);
            if (startCodeLength > 0) {
                normalized.writeBytes(FOUR_BYTE_START_CODE);
                offset += startCodeLength;
            } else {
                normalized.write(accessUnit[offset]);
                offset++;
            }
        }
        return normalized.toByteArray();
    }

    private static boolean containsCodedPicture(byte[] accessUnit) {
        return containsNalType(accessUnit, NON_IDR_PICTURE_NAL_TYPE)
                || containsNalType(accessUnit, IDR_PICTURE_NAL_TYPE);
    }

    private static boolean containsNalType(byte[] accessUnit, int expectedType) {
        for (int offset = 0; offset < accessUnit.length; offset++) {
            int startCodeLength = startCodeLength(accessUnit, offset);
            if (startCodeLength == 0 || offset + startCodeLength >= accessUnit.length) {
                continue;
            }
            if ((accessUnit[offset + startCodeLength] & NAL_TYPE_MASK) == expectedType) {
                return true;
            }
            offset += startCodeLength;
        }
        return false;
    }

    private static int findStartCode(byte[] bytes, int start, int end) {
        for (int offset = start; offset < end; offset++) {
            if (startCodeLength(bytes, offset) > 0) {
                return offset;
            }
        }
        return -1;
    }

    private static int startCodeLength(byte[] bytes, int offset) {
        if (offset + 3 > bytes.length || bytes[offset] != 0 || bytes[offset + 1] != 0) {
            return 0;
        }
        if (bytes[offset + 2] == 1) {
            return 3;
        }
        if (offset + 4 <= bytes.length && bytes[offset + 2] == 0 && bytes[offset + 3] == 1) {
            return 4;
        }
        return 0;
    }

    private PlaybackFixture() {
    }
}
