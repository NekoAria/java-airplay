package wtf.nanoka.airplay.player.gstreamer;

import org.junit.jupiter.api.Test;
import wtf.nanoka.airplay.lib.VideoStreamInfo;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class VideoAccessUnitGateTest {

    @Test
    void prependsSeparatelyDeliveredH264ParameterSetsToTheFirstIdr() {
        var gate = new VideoAccessUnitGate();
        byte[] parameterSets = accessUnits(7, 8);
        byte[] idr = accessUnits(5);

        gate.activate(VideoStreamInfo.Codec.H264);
        assertNull(gate.accept(parameterSets));
        assertArrayEquals(concatenate(parameterSets, idr), gate.accept(idr));
    }

    @Test
    void prependsSeparatelyDeliveredHevcParameterSetsToTheFirstIrap() {
        var gate = new VideoAccessUnitGate();
        byte[] parameterSets = hevcAccessUnits(32, 33, 34);
        byte[] irap = hevcAccessUnits(19);

        gate.activate(VideoStreamInfo.Codec.HEVC);
        assertNull(gate.accept(parameterSets));
        assertArrayEquals(concatenate(parameterSets, irap), gate.accept(irap));
    }

    @Test
    void doesNotDuplicateParameterSetsAlreadyAttachedToTheRandomAccessUnit() {
        var gate = new VideoAccessUnitGate();
        byte[] combined = accessUnits(7, 8, 5);

        gate.activate(VideoStreamInfo.Codec.H264);
        assertArrayEquals(combined, gate.accept(combined));
    }

    @Test
    void restartReplaysCachedParameterSetsButCodecSwitchClearsThem() {
        var gate = new VideoAccessUnitGate();
        byte[] h264Parameters = accessUnits(7, 8);
        byte[] h264Idr = accessUnits(5);

        gate.activate(VideoStreamInfo.Codec.H264);
        assertArrayEquals(concatenate(h264Parameters, h264Idr),
                gate.accept(concatenate(h264Parameters, h264Idr)));
        gate.prepareRestart();
        gate.activate(VideoStreamInfo.Codec.H264);
        assertArrayEquals(concatenate(h264Parameters, h264Idr), gate.accept(h264Idr));

        gate.activate(VideoStreamInfo.Codec.HEVC);
        assertArrayEquals(hevcAccessUnits(19), gate.accept(hevcAccessUnits(19)));
    }

    private byte[] accessUnits(int... nalTypes) {
        var output = new ByteArrayOutputStream();
        for (int nalType : nalTypes) {
            output.writeBytes(new byte[]{0, 0, 0, 1, (byte) nalType, 1});
        }
        return output.toByteArray();
    }

    private byte[] hevcAccessUnits(int... nalTypes) {
        var output = new ByteArrayOutputStream();
        for (int nalType : nalTypes) {
            output.writeBytes(new byte[]{0, 0, 0, 1, (byte) (nalType << 1), 1});
        }
        return output.toByteArray();
    }

    private byte[] concatenate(byte[] first, byte[] second) {
        byte[] combined = new byte[first.length + second.length];
        System.arraycopy(first, 0, combined, 0, first.length);
        System.arraycopy(second, 0, combined, first.length, second.length);
        return combined;
    }
}
