package wtf.nanoka.airplay.server.internal.handler.util;

import com.dd.plist.BinaryPropertyListWriter;
import com.dd.plist.NSArray;
import com.dd.plist.NSData;
import com.dd.plist.NSDictionary;
import wtf.nanoka.airplay.lib.AirPlayIdentity;
import wtf.nanoka.airplay.server.AirPlayConfig;
import wtf.nanoka.airplay.server.AirPlayConsumer;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
public class PropertyListUtil {

    public static byte[] prepareInfoResponse(AirPlayConfig airPlayConfig, AirPlayIdentity identity,
                                             boolean txtAirPlayOnly) throws Exception {
        if (txtAirPlayOnly) {
            NSDictionary response = new NSDictionary();
            response.put("txtAirPlay", new NSData(encodeTxtRecord(identity)));
            return BinaryPropertyListWriter.writeToArray(response);
        }

        NSDictionary audioFormat100 = new NSDictionary();
        audioFormat100.put("audioInputFormats", 67108860);
        audioFormat100.put("audioOutputFormats", 67108860);
        audioFormat100.put("type", 100);

        NSDictionary audioFormat101 = new NSDictionary();
        audioFormat101.put("audioInputFormats", 67108860);
        audioFormat101.put("audioOutputFormats", 67108860);
        audioFormat101.put("type", 101);

        NSArray audioFormats = new NSArray(audioFormat100, audioFormat101);

        NSDictionary audioLatency100 = new NSDictionary();
        audioLatency100.put("audioType", "default");
        audioLatency100.put("inputLatencyMicros", false);
        audioLatency100.put("type", 100);

        NSDictionary audioLatency101 = new NSDictionary();
        audioLatency101.put("audioType", "default");
        audioLatency101.put("inputLatencyMicros", false);
        audioLatency101.put("type", 101);

        NSArray audioLatencies = new NSArray(audioLatency100, audioLatency101);

        NSDictionary display = new NSDictionary();
        display.put("features", 14);
        display.put("height", airPlayConfig.getResolvedHeight());
        display.put("heightPhysical", false);
        display.put("heightPixels", airPlayConfig.getResolvedHeight());
        display.put("maxFPS", airPlayConfig.getResolvedFps());
        display.put("overscanned", false);
        display.put("refreshRate", 1.0 / airPlayConfig.getResolvedFps());
        display.put("rotation", false);
        display.put("uuid", "e5f7a68d-7b0f-4305-984b-974f677a150b");
        display.put("width", airPlayConfig.getResolvedWidth());
        display.put("widthPhysical", false);
        display.put("widthPixels", airPlayConfig.getResolvedWidth());

        NSArray displays = new NSArray(display);

        NSDictionary response = new NSDictionary();
        response.put("audioFormats", audioFormats);
        response.put("audioLatencies", audioLatencies);
        response.put("deviceID", identity.getDeviceId());
        response.put("displays", displays);
        response.put("features", 130367356919L);
        response.put("initialVolume", 0.0);
        response.put("keepAliveSendStatsAsBody", 1);
        response.put("macAddress", identity.getDeviceId());
        response.put("model", "AppleTV3,2");
        response.put("name", airPlayConfig.getServerName());
        response.put("pk", new NSData(identity.getPublicKey()));
        response.put("pi", "b08f5a79-db29-4384-b456-a4784d9e6055");
        response.put("sourceVersion", "220.68");
        response.put("statusFlags", 68);
        response.put("vv", 2);
        // response.put("pk", new NSData("XYMxJlYMsZoUGTcneJbw/UN7poAeshCsTDnZAHLXDag="));

        return BinaryPropertyListWriter.writeToArray(response);
    }

    private static byte[] encodeTxtRecord(AirPlayIdentity identity) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("deviceid", identity.getDeviceId());
        properties.put("features", "0x5A7FFFF7,0x1E");
        properties.put("srcvers", "220.68");
        properties.put("flags", "0x44");
        properties.put("vv", "2");
        properties.put("model", "AppleTV3,2C");
        properties.put("pw", "false");
        properties.put("pk", identity.getPublicKeyHex());

        var output = new ByteArrayOutputStream();
        properties.forEach((key, value) -> {
            byte[] entry = (key + "=" + value).getBytes(StandardCharsets.UTF_8);
            if (entry.length > 255) {
                throw new IllegalArgumentException("mDNS TXT entry is too long: " + key);
            }
            output.write(entry.length);
            output.writeBytes(entry);
        });
        return output.toByteArray();
    }

    public static byte[] prepareSetupAudioResponse(int dataPort, int controlPort) throws Exception {
        NSArray streams = new NSArray(1);
        NSDictionary dataStream = new NSDictionary();
        dataStream.put("dataPort", dataPort);
        dataStream.put("type", 96);
        dataStream.put("controlPort", controlPort);
        streams.setValue(0, dataStream);

        NSDictionary response = new NSDictionary();
        response.put("streams", streams);

        return BinaryPropertyListWriter.writeToArray(response);
    }

    public static byte[] prepareSetupVideoResponse(int dataPort) throws Exception {
        NSArray streams = new NSArray(1);
        NSDictionary dataStream = new NSDictionary();
        dataStream.put("dataPort", dataPort);
        dataStream.put("type", 110);
        streams.setValue(0, dataStream);

        NSDictionary response = new NSDictionary();
        response.put("streams", streams);

        return BinaryPropertyListWriter.writeToArray(response);
    }

    public static byte[] prepareSetupTimingResponse(int timingPort) throws Exception {
        NSDictionary response = new NSDictionary();
        response.put("eventPort", 0);
        response.put("timingPort", timingPort);
        return BinaryPropertyListWriter.writeToArray(response);
    }

    public static byte[] prepareServerInfoResponse() {
        NSDictionary response = new NSDictionary();
        response.put("features", 119); // 130367356919L -> leads to HTTP fp-setup, fp-setup2
        response.put("protovers", 1.0);
        response.put("srcvers", 101.28);
        return response.toXMLPropertyList().getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] preparePlaybackInfoResponse(AirPlayConsumer.PlaybackInfo playbackInfo) {
        NSDictionary response = new NSDictionary();
        response.put("duration", playbackInfo.duration());
        NSDictionary loadedTimeRanges = new NSDictionary();
        loadedTimeRanges.put("duration", playbackInfo.duration());
        loadedTimeRanges.put("start", 0.0);
        response.put("loadedTimeRanges", new NSArray(loadedTimeRanges));
        response.put("playbackBufferEmpty", true);
        response.put("playbackBufferFull", false);
        response.put("playbackLikelyToKeepUp", true);
        response.put("position", playbackInfo.position());
        response.put("rate", 1);
        response.put("readyToPlay", true);
        NSDictionary seekableTimeRanges = new NSDictionary();
        seekableTimeRanges.put("duration", playbackInfo.duration());
        seekableTimeRanges.put("start", 0.0);
        response.put("seekableTimeRanges", new NSArray(seekableTimeRanges));
        log.debug("Playback info:\n{}", response.toXMLPropertyList());
        return response.toXMLPropertyList().getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] prepareEventRequest(String sessionId, String listUri) {
        NSDictionary headers = new NSDictionary();
        headers.put("X-Playback-Session-Id", sessionId);

        NSDictionary request = new NSDictionary();
        request.put("FCUP_Response_ClientInfo", 0);
        request.put("FCUP_Response_ClientRef", 0);
        request.put("FCUP_Response_Headers", headers);
        request.put("FCUP_Response_RequestID", 0);
        request.put("FCUP_Response_URL", listUri);
        request.put("sessionID", 1);

        NSDictionary wrapper = new NSDictionary();
        wrapper.put("request", request);
        wrapper.put("sessionID", 1);
        wrapper.put("type", "unhandledURLRequest");

        return wrapper.toXMLPropertyList().getBytes(StandardCharsets.UTF_8);
    }
}
