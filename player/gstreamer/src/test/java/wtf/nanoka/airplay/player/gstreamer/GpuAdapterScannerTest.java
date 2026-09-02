package wtf.nanoka.airplay.player.gstreamer;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("native-platform")
class GpuAdapterScannerTest {

    @Test
    void preservesNativeDxgiOrder() {
        List<GpuAdapter> adapters = GpuAdapterScanner.scan();

        adapters.forEach(adapter -> System.out.println(adapter.description()));
        for (int position = 1; position < adapters.size(); position++) {
            assertTrue(adapters.get(position - 1).index() < adapters.get(position).index());
        }
    }
}
