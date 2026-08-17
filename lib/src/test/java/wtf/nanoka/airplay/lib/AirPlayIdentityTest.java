package wtf.nanoka.airplay.lib;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AirPlayIdentityTest {

    @Test
    void persistsReceiverIdentity(@TempDir Path directory) throws Exception {
        Path identityFile = directory.resolve("identity.key");

        var first = AirPlayIdentity.loadOrCreate(identityFile);
        var second = AirPlayIdentity.loadOrCreate(identityFile);

        assertArrayEquals(first.getPublicKey(), second.getPublicKey());
        assertEquals(first.getDeviceId(), second.getDeviceId());
        assertEquals(32, Files.size(identityFile));
    }
}
