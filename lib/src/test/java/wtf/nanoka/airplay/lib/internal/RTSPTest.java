package wtf.nanoka.airplay.lib.internal;

import com.dd.plist.BinaryPropertyListWriter;
import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;
import org.junit.jupiter.api.Test;
import wtf.nanoka.airplay.lib.VideoStreamInfo;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RTSPTest {

    @Test
    void pendingKeySetupDoesNotMutateStateBeforeCommit() throws Exception {
        var rtsp = new RTSP();
        var request = new NSDictionary();
        request.put("ekey", new byte[]{1, 2, 3});
        request.put("eiv", new byte[]{4, 5, 6});

        RTSP.PendingSetup pending = rtsp.prepareSetup(input(request));

        assertNull(rtsp.getEkey());
        assertNull(rtsp.getEiv());
        assertTrue(pending.setupInfo().keySetup());
        rtsp.commit(pending);
        assertArrayEquals(new byte[]{1, 2, 3}, rtsp.getEkey());
        assertArrayEquals(new byte[]{4, 5, 6}, rtsp.getEiv());
    }

    @Test
    void pendingAndTeardownStreamIdsCannotOverwriteCommittedSetup() throws Exception {
        var rtsp = new RTSP();
        rtsp.setup(input(videoSetup(10)));

        RTSP.PendingSetup pending = rtsp.prepareSetup(input(videoSetup(20)));
        var pendingInfo = (VideoStreamInfo) pending.setupInfo().mediaStreamInfo().orElseThrow();
        assertEquals("20", pendingInfo.getStreamConnectionId());
        assertEquals("10", rtsp.getStreamConnectionID());

        rtsp.teardown(input(videoSetup(30)));
        assertEquals("10", rtsp.getStreamConnectionID());

        rtsp.commit(pending);
        assertEquals("20", rtsp.getStreamConnectionID());
    }

    private static NSDictionary videoSetup(long streamConnectionId) {
        var stream = new NSDictionary();
        stream.put("type", 110);
        stream.put("streamConnectionID", streamConnectionId);
        var request = new NSDictionary();
        request.put("streams", new NSArray(stream));
        return request;
    }

    private static ByteArrayInputStream input(NSDictionary dictionary) throws Exception {
        return new ByteArrayInputStream(BinaryPropertyListWriter.writeToArray(dictionary));
    }
}
