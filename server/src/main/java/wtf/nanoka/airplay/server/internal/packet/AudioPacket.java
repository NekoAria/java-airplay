package wtf.nanoka.airplay.server.internal.packet;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AudioPacket {

    private byte[] encodedAudio;

    private boolean available;
    private int flag;
    private int type;
    private int sequenceNumber;
    private long timestamp;
    private long ssrc;
    private int encodedAudioSize;

    public AudioPacket available(boolean available) {
        this.available = available;
        return this;
    }

    public AudioPacket flag(int flag) {
        this.flag = flag;
        return this;
    }

    public AudioPacket type(int type) {
        this.type = type;
        return this;
    }

    public AudioPacket sequenceNumber(int sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
        return this;
    }

    public AudioPacket timestamp(long timestamp) {
        this.timestamp = timestamp;
        return this;
    }

    public AudioPacket ssrc(long ssrc) {
        this.ssrc = ssrc;
        return this;
    }

    public AudioPacket encodedAudioSize(int encodedAudioSize) {
        this.encodedAudioSize = encodedAudioSize;
        return this;
    }

}
