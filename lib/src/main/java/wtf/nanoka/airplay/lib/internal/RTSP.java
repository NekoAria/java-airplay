package wtf.nanoka.airplay.lib.internal;

import com.dd.plist.BinaryPropertyListParser;
import com.dd.plist.NSDictionary;
import lombok.extern.slf4j.Slf4j;
import wtf.nanoka.airplay.lib.AudioStreamInfo;
import wtf.nanoka.airplay.lib.MediaStreamInfo;
import wtf.nanoka.airplay.lib.RtspSetupInfo;
import wtf.nanoka.airplay.lib.VideoStreamInfo;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Optional;

@Slf4j
public class RTSP {

    private byte[] ekey;
    private byte[] eiv;
    private String streamConnectionID;

    public RtspSetupInfo setup(InputStream rtspSetupPayload) throws Exception {
        PendingSetup pendingSetup = prepareSetup(rtspSetupPayload);
        commit(pendingSetup);
        return pendingSetup.setupInfo();
    }

    public synchronized PendingSetup prepareSetup(InputStream rtspSetupPayload) throws Exception {
        var setup = (NSDictionary) BinaryPropertyListParser.parse(rtspSetupPayload);
        if (setup.containsKey("ekey") && setup.containsKey("eiv")) {
            byte[] nextEkey = (byte[]) setup.get("ekey").toJavaObject();
            byte[] nextEiv = (byte[]) setup.get("eiv").toJavaObject();
            int timingPort = numberValue(setup, "timingPort", 0).intValue();
            String timingProtocol = setup.containsKey("timingProtocol")
                    ? setup.get("timingProtocol").toJavaObject(String.class)
                    : "None";
            log.debug("Received encrypted media key and {} timing on remote port {}", timingProtocol, timingPort);
            return new PendingSetup(
                    this,
                    new RtspSetupInfo(Optional.empty(), true, timingPort, timingProtocol),
                    nextEkey,
                    nextEiv,
                    null);
        }
        if (setup.containsKey("streams")) {
            log.debug("RTSP SETUP streams:\n{}", setup.toXMLPropertyList());
            ParsedMediaStream parsedStream = parseMediaStream(setup);
            return new PendingSetup(
                    this,
                    new RtspSetupInfo(Optional.ofNullable(parsedStream.streamInfo()), false, 0, "None"),
                    null,
                    null,
                    parsedStream.streamConnectionId());
        }

        log.error("Unknown RTSP setup content\n{}", setup.toXMLPropertyList());
        return new PendingSetup(
                this,
                new RtspSetupInfo(Optional.empty(), false, 0, "None"),
                null,
                null,
                null);
    }

    public synchronized void commit(PendingSetup pendingSetup) {
        if (pendingSetup.owner != this) {
            throw new IllegalArgumentException("RTSP setup belongs to another AirPlay session");
        }
        if (pendingSetup.setupInfo().keySetup()) {
            ekey = pendingSetup.ekey.clone();
            eiv = pendingSetup.eiv.clone();
        }
        if (pendingSetup.streamConnectionId != null) {
            streamConnectionID = pendingSetup.streamConnectionId;
        }
    }

    public synchronized Optional<MediaStreamInfo> teardown(InputStream rtspTeardownPayload) throws Exception {
        var teardown = (NSDictionary) BinaryPropertyListParser.parse(rtspTeardownPayload);
        log.debug("RTSP TEARDOWN streams:\n{}", teardown.toXMLPropertyList());
        if (teardown.containsKey("streams")) {
            return Optional.ofNullable(parseMediaStream(teardown).streamInfo());
        }
        return Optional.empty();
    }

    private ParsedMediaStream parseMediaStream(NSDictionary request) {
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
            case 110 -> {
                boolean hasConnectionId = stream.containsKey("streamConnectionID");
                String parsedConnectionId = hasConnectionId
                        ? Long.toUnsignedString(((Number) stream.get("streamConnectionID")).longValue())
                        : streamConnectionID;
                return new ParsedMediaStream(
                        new VideoStreamInfo(parsedConnectionId),
                        hasConnectionId ? parsedConnectionId : null);
            }
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
                    builder.samplesPerFrame(((Number) stream.get("spf")).intValue());
                }
                if (stream.containsKey("sr")) {
                    builder.sampleRate(((Number) stream.get("sr")).intValue());
                }
                if (stream.containsKey("controlPort")) {
                    builder.controlPort(((Number) stream.get("controlPort")).intValue());
                }
                return new ParsedMediaStream(builder.build(), null);
            }
            default -> {
                log.error("Unknown stream type: {}", type);
                return new ParsedMediaStream(null, null);
            }
        }
    }

    private Number numberValue(NSDictionary dictionary, String key, Number defaultValue) {
        if (!dictionary.containsKey(key)) {
            return defaultValue;
        }
        return (Number) dictionary.get(key).toJavaObject();
    }

    public synchronized String getStreamConnectionID() {
        return streamConnectionID;
    }

    public synchronized byte[] getEkey() {
        return ekey;
    }

    public synchronized byte[] getEiv() {
        return eiv;
    }

    private record ParsedMediaStream(MediaStreamInfo streamInfo, String streamConnectionId) {
    }

    public static final class PendingSetup {
        private final RTSP owner;
        private final RtspSetupInfo setupInfo;
        private final byte[] ekey;
        private final byte[] eiv;
        private final String streamConnectionId;

        private PendingSetup(
                RTSP owner,
                RtspSetupInfo setupInfo,
                byte[] ekey,
                byte[] eiv,
                String streamConnectionId) {
            this.owner = owner;
            this.setupInfo = setupInfo;
            this.ekey = ekey;
            this.eiv = eiv;
            this.streamConnectionId = streamConnectionId;
        }

        public RtspSetupInfo setupInfo() {
            return setupInfo;
        }
    }
}
