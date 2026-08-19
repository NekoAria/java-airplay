package wtf.nanoka.airplay.server.internal.handler.video;

import org.junit.jupiter.api.Test;
import wtf.nanoka.airplay.lib.VideoStreamInfo;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoCodecConfigurationTest {

    private static final byte[] AIRPLAY_HEVC_CONFIGURATION = HexFormat.of().parseHex("""
            000000eb68766331000000000000ffff00000000000000000000020000000200
            0b42087000480000004800000000000000010448455643000000000000000000
            0000000000000000000000000000000000000018ffff0000007f687663430101
            60000000b0000000000096f000fcfdf8f800000b03a00001001840010c01ffff
            016000000300b0000003000003009608c090a100010032420101016000000300
            b00000030000030096a0016a20021c711e2023b914842e7f13f0bfa1bf50ffaa
            08fd54a6e020202010a2000100074401c072f05b2400000012636f6c726e636c
            6300010001000100000000
            """.replaceAll("\\s", ""));

    @Test
    void parsesCapturedAirPlayHevcSampleEntry() {
        VideoCodecConfiguration configuration = VideoCodecConfiguration.parse(AIRPLAY_HEVC_CONFIGURATION);

        assertEquals(VideoStreamInfo.Codec.HEVC, configuration.codec());
        assertEquals(50, configuration.sequenceParameterSet().length);
        assertTrue(configuration.parameterSets().length > 80);
        assertEquals(0, configuration.parameterSets()[0]);
        assertEquals(1, configuration.parameterSets()[3]);

        H265SpsParser.Format format = H265SpsParser.parse(configuration.sequenceParameterSet());
        assertNotNull(format);
        assertTrue(format.width() >= 1920);
        assertTrue(format.height() >= 1080);
    }

    @Test
    void parsesAvcConfigurationWithMultipleParameterSets() {
        byte[] sps = {
                0x27, 0x64, 0x00, 0x1f, (byte) 0xac, 0x13, 0x14, 0x50,
                0x54, 0x16, (byte) 0xfa, (byte) 0xe6, (byte) 0xe0, 0x20,
                0x20, 0x20, 0x40
        };
        byte[] payload = new byte[6 + 2 + sps.length + 1 + 2 + 3];
        payload[0] = 1;
        payload[4] = (byte) 0xff;
        payload[5] = (byte) 0xe1;
        payload[6] = 0;
        payload[7] = (byte) sps.length;
        System.arraycopy(sps, 0, payload, 8, sps.length);
        int ppsOffset = 8 + sps.length;
        payload[ppsOffset] = 1;
        payload[ppsOffset + 1] = 0;
        payload[ppsOffset + 2] = 3;
        payload[ppsOffset + 3] = 0x28;
        payload[ppsOffset + 4] = 1;
        payload[ppsOffset + 5] = 2;

        VideoCodecConfiguration configuration = VideoCodecConfiguration.parse(payload);

        assertEquals(VideoStreamInfo.Codec.H264, configuration.codec());
        assertEquals(sps.length + 3 + 8, configuration.parameterSets().length);
    }

    @Test
    void rejectsTruncatedHevcBox() {
        byte[] truncated = AIRPLAY_HEVC_CONFIGURATION.clone();
        truncated[3] = (byte) 0xff;
        assertThrows(IllegalArgumentException.class, () -> VideoCodecConfiguration.parse(truncated));
    }
}
