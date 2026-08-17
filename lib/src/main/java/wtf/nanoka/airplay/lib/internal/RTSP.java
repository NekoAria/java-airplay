package wtf.nanoka.airplay.lib.internal;

import com.dd.plist.BinaryPropertyListParser;
import com.dd.plist.NSDictionary;
import wtf.nanoka.airplay.lib.AudioStreamInfo;
import wtf.nanoka.airplay.lib.MediaStreamInfo;
import wtf.nanoka.airplay.lib.RtspSetupInfo;
import wtf.nanoka.airplay.lib.VideoStreamInfo;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Optional;

@Slf4j
public class RTSP {

    private byte[] ekey;
    private byte[] eiv;

    private String streamConnectionID;

    public RtspSetupInfo setup(InputStream rtspSetupPayload) throws Exception {
        var setup = (NSDictionary) BinaryPropertyListParser.parse(rtspSetupPayload);
        if (setup.containsKey("ekey") && setup.containsKey("eiv")) {
            ekey = (byte[]) setup.get("ekey").toJavaObject();
            eiv = (byte[]) setup.get("eiv").toJavaObject();
            int timingPort = numberValue(setup, "timingPort", 0).intValue();
            String timingProtocol = setup.containsKey("timingProtocol")
                    ? setup.get("timingProtocol").toJavaObject(String.class)
                    : "None";
            log.debug("Received encrypted media key and {} timing on remote port {}", timingProtocol, timingPort);
            return new RtspSetupInfo(Optional.empty(), true, timingPort, timingProtocol);
        } else if (setup.containsKey("streams")) {
            log.debug("RTSP SETUP streams:\n{}", setup.toXMLPropertyList());
            return new RtspSetupInfo(Optional.ofNullable(getMediaStreamInfo(setup)), false, 0, "None");
        } else {
            log.error("Unknown RTSP setup content\n{}", setup.toXMLPropertyList());
            return new RtspSetupInfo(Optional.empty(), false, 0, "None");
        }
    }

    public Optional<MediaStreamInfo> teardown(InputStream rtspTeardownPayload) throws Exception {
        var teardown = (NSDictionary) BinaryPropertyListParser.parse(rtspTeardownPayload);
        log.debug("RTSP TEARDOWN streams:\n{}", teardown.toXMLPropertyList());
        if (teardown.containsKey("streams")) {
            return Optional.ofNullable(getMediaStreamInfo(teardown));
        }
        return Optional.empty();
    }

    private MediaStreamInfo getMediaStreamInfo(NSDictionary request) {
        var streams = ((Object[]) request.get("streams").toJavaObject());
        if (streams.length == 0) {
            throw new IllegalArgumentException("RTSP request contains an empty streams array");
        }
        if (streams.length > 1) {
            log.warn("Request contains more than one stream info");
        }

        //noinspection rawtypes
        HashMap stream = (HashMap) streams[0];
        int type = ((Number) stream.get("type")).intValue();
        switch (type) {

            // video stream
            case 110 -> {
                if (stream.containsKey("streamConnectionID")) {
                    streamConnectionID = Long.toUnsignedString(((Number) stream.get("streamConnectionID")).longValue());
                }
                return new VideoStreamInfo(streamConnectionID);
            }

            // audio stream
            case 96 -> {
                AudioStreamInfo.AudioStreamInfoBuilder builder = new AudioStreamInfo.AudioStreamInfoBuilder();
                if (stream.containsKey("ct")) {
                    int compressionType = ((Number) stream.get("ct")).intValue();
                    builder.compressionType(AudioStreamInfo.CompressionType.fromCode(compressionType));
                }
                if (stream.containsKey("audioFormat")) {
                    long audioFormatCode = ((Number) stream.get("audioFormat")).longValue();
                    builder.audioFormat(AudioStreamInfo.AudioFormat.fromCode(audioFormatCode));
                }
                if (stream.containsKey("spf")) {
                    int samplesPerFrame = ((Number) stream.get("spf")).intValue();
                    builder.samplesPerFrame(samplesPerFrame);
                }
                if (stream.containsKey("sr")) {
                    builder.sampleRate(((Number) stream.get("sr")).intValue());
                }
                if (stream.containsKey("controlPort")) {
                    builder.controlPort(((Number) stream.get("controlPort")).intValue());
                }
                return builder.build();
            }

            default -> {
                log.error("Unknown stream type: {}", type);
                return null;
            }
        }
    }

    private Number numberValue(NSDictionary dictionary, String key, Number defaultValue) {
        if (!dictionary.containsKey(key)) {
            return defaultValue;
        }
        return (Number) dictionary.get(key).toJavaObject();
    }

    public String getStreamConnectionID() {
        return streamConnectionID;
    }

    public byte[] getEkey() {
        return ekey;
    }

    public byte[] getEiv() {
        return eiv;
    }
}
