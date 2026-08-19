package wtf.nanoka.airplay.app.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestartReadinessTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsOnlyTheCompleteExpectedTokenAndProcess() throws Exception {
        Path marker = temporaryDirectory.resolve("restart.ready");

        RestartReadiness.signal(marker, "expected-token", 1234);

        assertTrue(RestartReadiness.matches(marker, "expected-token", 1234));
        assertFalse(RestartReadiness.matches(marker, "wrong-token", 1234));
        assertFalse(RestartReadiness.matches(marker, "expected-token", 4321));

        Files.writeString(marker, "READY expected-token", StandardCharsets.US_ASCII);
        assertFalse(RestartReadiness.matches(marker, "expected-token", 1234));
    }
}
