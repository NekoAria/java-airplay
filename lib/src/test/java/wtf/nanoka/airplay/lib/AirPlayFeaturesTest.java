package wtf.nanoka.airplay.lib;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AirPlayFeaturesTest {

    @Test
    void addsScreenMultiCodecBitOnlyWhenHevcIsEnabled() {
        assertEquals(130_367_356_919L, AirPlayFeatures.receiverMask(false));
        assertEquals(4_528_413_868_023L, AirPlayFeatures.receiverMask(true));
        assertEquals("0x5A7FFFF7,0x1E", AirPlayFeatures.txtValue(false));
        assertEquals("0x5A7FFFF7,0x41E", AirPlayFeatures.txtValue(true));
    }
}
