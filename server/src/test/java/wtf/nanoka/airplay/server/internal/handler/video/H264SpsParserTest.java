package wtf.nanoka.airplay.server.internal.handler.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class H264SpsParserTest {

    @Test
    void readsResolutionFromAvcSps() {
        byte[] sps = {
                0x27, 0x64, 0x00, 0x1f, (byte) 0xac, 0x13, 0x14, 0x50,
                0x54, 0x16, (byte) 0xfa, (byte) 0xe6, (byte) 0xe0, 0x20,
                0x20, 0x20, 0x40
        };

        var format = H264SpsParser.parse(sps);

        assertNotNull(format);
        assertEquals(334, format.width());
        assertEquals(720, format.height());
    }
}
