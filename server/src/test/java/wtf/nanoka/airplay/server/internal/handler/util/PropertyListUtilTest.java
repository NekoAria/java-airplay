package wtf.nanoka.airplay.server.internal.handler.util;

import com.dd.plist.BinaryPropertyListParser;
import com.dd.plist.NSDictionary;
import org.junit.jupiter.api.Test;
import wtf.nanoka.airplay.lib.AirPlayFeatures;
import wtf.nanoka.airplay.lib.AirPlayIdentity;
import wtf.nanoka.airplay.server.AirPlayConfig;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertyListUtilTest {

    @Test
    void advertisesHevcInInfoAndEmbeddedTxtRecords() throws Exception {
        var config = new AirPlayConfig();
        config.setHevc(true);
        AirPlayIdentity identity = AirPlayIdentity.random();

        NSDictionary info = (NSDictionary) BinaryPropertyListParser.parse(
                PropertyListUtil.prepareInfoResponse(config, identity, false));
        Number features = (Number) info.get("features").toJavaObject();
        assertEquals(AirPlayFeatures.receiverMask(true), features.longValue());

        NSDictionary txtInfo = (NSDictionary) BinaryPropertyListParser.parse(
                PropertyListUtil.prepareInfoResponse(config, identity, true));
        byte[] txtRecord = (byte[]) txtInfo.get("txtAirPlay").toJavaObject();
        assertTrue(new String(txtRecord, StandardCharsets.ISO_8859_1)
                .contains("features=" + AirPlayFeatures.txtValue(true)));
    }
}
