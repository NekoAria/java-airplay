package wtf.nanoka.airplay.player.h264dump;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class H264DumpTest {

    @Test
    void truncatesConfiguredFileAndWritesVideoChunks(@TempDir Path tempDir) throws IOException {
        Path outputFile = tempDir.resolve("capture.h264");
        Files.writeString(outputFile, "stale content");

        try (H264Dump dump = new H264Dump(outputFile)) {
            dump.onVideo(new byte[]{0, 0, 0, 1, 0x67});
            dump.onVideo(new byte[]{0, 0, 0, 1, 0x65, 1, 2, 3});
        }

        assertArrayEquals(
                new byte[]{0, 0, 0, 1, 0x67, 0, 0, 0, 1, 0x65, 1, 2, 3},
                Files.readAllBytes(outputFile));
    }
}
