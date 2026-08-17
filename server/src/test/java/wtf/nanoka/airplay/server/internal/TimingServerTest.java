package wtf.nanoka.airplay.server.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimingServerTest {

    @Test
    void convertsNtpFixedPointToUnixNanos() {
        long unixEpoch = 2_208_988_800L << 32;
        assertEquals(0, TimingServer.ntpToUnixNanos(unixEpoch));
        assertEquals(500_000_000L, TimingServer.ntpToUnixNanos(unixEpoch | 0x8000_0000L));
    }
}
