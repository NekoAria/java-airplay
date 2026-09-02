package wtf.nanoka.airplay.player.gstreamer;

import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GstTestSupportTest {

    @Test
    void failsWhenNativeGStreamerIsRequired() {
        var cause = new UnsatisfiedLinkError("missing native library");

        var failure = assertThrows(AssertionError.class, () ->
                GstTestSupport.failOrAbort(true, "GStreamer is required", cause));

        assertEquals("GStreamer is required", failure.getMessage());
        assertSame(cause, failure.getCause());
    }

    @Test
    void abortsWhenNativeGStreamerIsOptional() {
        var aborted = assertThrows(TestAbortedException.class, () ->
                GstTestSupport.failOrAbort(false, "GStreamer is optional", null));

        assertEquals("GStreamer is optional", aborted.getMessage());
    }
}
