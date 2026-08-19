package wtf.nanoka.airplay.app.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

public final class RestartReadiness {

    private RestartReadiness() {
    }

    public static void signal(Path readyPath, String token, long processId) throws IOException {
        Path normalized = readyPath.toAbsolutePath().normalize();
        Path temporary = normalized.resolveSibling(normalized.getFileName() + ".tmp-" + processId);
        String marker = "READY " + token + " " + processId + "\n";
        Files.writeString(temporary, marker, StandardCharsets.US_ASCII,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        try {
            Files.move(temporary, normalized, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, normalized, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static boolean matches(Path readyPath, String token, long processId) {
        if (!Files.isRegularFile(readyPath)) {
            return false;
        }
        try {
            String marker = Files.readString(readyPath, StandardCharsets.US_ASCII).trim();
            return marker.equals("READY " + token + " " + processId);
        } catch (IOException e) {
            return false;
        }
    }
}
