package wtf.nanoka.airplay.player.gstreamer;

import org.junit.jupiter.api.Test;
import wtf.nanoka.airplay.lib.AudioStreamInfo;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GstAudioFormatTest {

    @Test
    void buildsAlacMagicCookieFromTheNegotiatedFrameLengthBitDepthAndSampleRate() {
        GstAudioFormat.Configuration format = GstAudioFormat.from(audioInfo(
                AudioStreamInfo.CompressionType.ALAC,
                AudioStreamInfo.AudioFormat.ALAC_48000_24_2,
                48_000,
                352));

        assertEquals(AudioStreamInfo.CompressionType.ALAC, format.compressionType());
        assertEquals(48_000, format.sampleRate());
        assertEquals(352, format.samplesPerFrame());
        assertEquals(
                "audio/x-alac,mpegversion=(int)4,channels=(int)2,rate=(int)48000,stream-format=raw,"
                        + "codec_data=(buffer)00000024616c616300000000000001600018280a0e0200ff00000000000000000000bb80",
                format.caps());
    }

    @Test
    void buildsAacEldAudioSpecificConfigForEverySupportedRateAndChannelCount() {
        Map<AudioStreamInfo.AudioFormat, String> expectedCodecData = Map.of(
                AudioStreamInfo.AudioFormat.AAC_ELD_16000_1, "f8f03000",
                AudioStreamInfo.AudioFormat.AAC_ELD_24000_1, "f8ec3000",
                AudioStreamInfo.AudioFormat.AAC_ELD_44100_1, "f8e83000",
                AudioStreamInfo.AudioFormat.AAC_ELD_44100_2, "f8e85000",
                AudioStreamInfo.AudioFormat.AAC_ELD_48000_1, "f8e63000",
                AudioStreamInfo.AudioFormat.AAC_ELD_48000_2, "f8e65000");

        for (var entry : expectedCodecData.entrySet()) {
            int sampleRate = sampleRate(entry.getKey());
            int channels = entry.getKey().name().endsWith("_1") ? 1 : 2;
            GstAudioFormat.Configuration format = GstAudioFormat.from(audioInfo(
                    AudioStreamInfo.CompressionType.AAC_ELD, entry.getKey(), sampleRate, 480));

            assertEquals(sampleRate, format.sampleRate());
            assertTrue(format.caps().contains("channels=(int)" + channels));
            assertTrue(format.caps().contains("rate=(int)" + sampleRate));
            assertTrue(format.caps().endsWith("codec_data=(buffer)" + entry.getValue()));
        }
    }

    @Test
    void aacEldFrameLengthFlagDistinguishes480From512Samples() {
        GstAudioFormat.Configuration shortFrame = GstAudioFormat.from(audioInfo(
                AudioStreamInfo.CompressionType.AAC_ELD,
                AudioStreamInfo.AudioFormat.AAC_ELD_44100_2,
                44_100,
                480));
        GstAudioFormat.Configuration longFrame = GstAudioFormat.from(audioInfo(
                AudioStreamInfo.CompressionType.AAC_ELD,
                AudioStreamInfo.AudioFormat.AAC_ELD_44100_2,
                44_100,
                512));

        assertTrue(shortFrame.caps().endsWith("codec_data=(buffer)f8e85000"));
        assertTrue(longFrame.caps().endsWith("codec_data=(buffer)f8e84000"));
    }

    @Test
    void rejectsMissingContradictoryAndUnsupportedNegotiationData() {
        assertThrows(NullPointerException.class, () -> GstAudioFormat.from(
                new AudioStreamInfo.AudioStreamInfoBuilder()
                        .compressionType(AudioStreamInfo.CompressionType.ALAC)
                        .sampleRate(44_100)
                        .samplesPerFrame(352)
                        .build()));
        assertThrows(IllegalArgumentException.class, () -> GstAudioFormat.from(audioInfo(
                AudioStreamInfo.CompressionType.ALAC,
                AudioStreamInfo.AudioFormat.ALAC_44100_16_2,
                48_000,
                352)));
        assertThrows(IllegalArgumentException.class, () -> GstAudioFormat.from(audioInfo(
                AudioStreamInfo.CompressionType.AAC_ELD,
                AudioStreamInfo.AudioFormat.ALAC_44100_16_2,
                44_100,
                352)));
        assertThrows(IllegalArgumentException.class, () -> GstAudioFormat.from(audioInfo(
                AudioStreamInfo.CompressionType.AAC,
                AudioStreamInfo.AudioFormat.AAC_LC_44100_2,
                44_100,
                480)));
        assertThrows(IllegalArgumentException.class, () -> GstAudioFormat.from(audioInfo(
                AudioStreamInfo.CompressionType.ALAC,
                AudioStreamInfo.AudioFormat.ALAC_44100_16_2,
                44_100,
                0)));
        assertThrows(IllegalArgumentException.class, () -> GstAudioFormat.from(audioInfo(
                AudioStreamInfo.CompressionType.AAC_ELD,
                AudioStreamInfo.AudioFormat.AAC_ELD_44100_2,
                44_100,
                352)));
    }

    private int sampleRate(AudioStreamInfo.AudioFormat format) {
        if (format.name().contains("16000")) {
            return 16_000;
        }
        if (format.name().contains("24000")) {
            return 24_000;
        }
        if (format.name().contains("44100")) {
            return 44_100;
        }
        return 48_000;
    }

    private AudioStreamInfo audioInfo(
            AudioStreamInfo.CompressionType compressionType,
            AudioStreamInfo.AudioFormat audioFormat,
            int sampleRate,
            int samplesPerFrame) {
        return new AudioStreamInfo.AudioStreamInfoBuilder()
                .compressionType(compressionType)
                .audioFormat(audioFormat)
                .sampleRate(sampleRate)
                .samplesPerFrame(samplesPerFrame)
                .build();
    }
}
