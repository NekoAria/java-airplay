package wtf.nanoka.airplay.lib;

import java.util.Locale;

public final class AirPlayFeatures {

    public static final long BASE_MASK = 130_367_356_919L;
    public static final long SCREEN_MULTI_CODEC = 1L << 42;

    private AirPlayFeatures() {
    }

    public static long receiverMask(boolean hevcEnabled) {
        return hevcEnabled ? BASE_MASK | SCREEN_MULTI_CODEC : BASE_MASK;
    }

    public static String txtValue(boolean hevcEnabled) {
        long mask = receiverMask(hevcEnabled);
        return String.format(Locale.ROOT, "0x%X,0x%X", mask & 0xffff_ffffL, mask >>> 32);
    }
}
