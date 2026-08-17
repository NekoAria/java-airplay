package wtf.nanoka.airplay.server.internal.packet;

import lombok.Data;

@Data
public class VideoPacket {

    private final int payloadType;
    private final int payloadSize;
    private final long timestamp;

    private final byte[] payload;
}
