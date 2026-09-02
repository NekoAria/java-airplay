package wtf.nanoka.airplay.player.gstreamer;

import wtf.nanoka.airplay.lib.AudioStreamInfo;

import java.util.Locale;
import java.util.Objects;

/**
 * Validates an AirPlay audio format and builds the decoder caps without
 * touching native GStreamer state.
 */
final class GstAudioFormat {

    private GstAudioFormat() {
    }

    static Configuration from(AudioStreamInfo streamInfo) {
        Objects.requireNonNull(streamInfo, "streamInfo");
        AudioStreamInfo.CompressionType compressionType = Objects.requireNonNull(
                streamInfo.getCompressionType(), "streamInfo.compressionType");
        AudioStreamInfo.AudioFormat audioFormat = Objects.requireNonNull(
                streamInfo.getAudioFormat(), "streamInfo.audioFormat");
        int samplesPerFrame = streamInfo.getSamplesPerFrame();
        if (samplesPerFrame <= 0) {
            throw new IllegalArgumentException("AirPlay audio samplesPerFrame must be positive");
        }

        FormatDetails details = details(audioFormat);
        if (details.compressionType() != compressionType) {
            throw new IllegalArgumentException(
                    "AirPlay audio format " + audioFormat + " does not match " + compressionType);
        }
        if (streamInfo.getSampleRate() != details.sampleRate()) {
            throw new IllegalArgumentException(
                    "AirPlay audio sample rate " + streamInfo.getSampleRate()
                            + " does not match " + audioFormat + " (" + details.sampleRate() + ")");
        }

        String caps = compressionType == AudioStreamInfo.CompressionType.ALAC
                ? alacCaps(details, samplesPerFrame)
                : aacEldCaps(details, samplesPerFrame);
        return new Configuration(compressionType, details.sampleRate(), samplesPerFrame, caps);
    }

    private static FormatDetails details(AudioStreamInfo.AudioFormat audioFormat) {
        return switch (audioFormat) {
            case ALAC_44100_16_2 -> alac(44_100, 16);
            case ALAC_44100_24_2 -> alac(44_100, 24);
            case ALAC_48000_16_2 -> alac(48_000, 16);
            case ALAC_48000_24_2 -> alac(48_000, 24);
            case AAC_ELD_16000_1 -> aacEld(16_000, 1);
            case AAC_ELD_24000_1 -> aacEld(24_000, 1);
            case AAC_ELD_44100_1 -> aacEld(44_100, 1);
            case AAC_ELD_44100_2 -> aacEld(44_100, 2);
            case AAC_ELD_48000_1 -> aacEld(48_000, 1);
            case AAC_ELD_48000_2 -> aacEld(48_000, 2);
            default -> throw new IllegalArgumentException("Unsupported GStreamer audio format: " + audioFormat);
        };
    }

    private static FormatDetails alac(int sampleRate, int bitDepth) {
        return new FormatDetails(AudioStreamInfo.CompressionType.ALAC, sampleRate, 2, bitDepth);
    }

    private static FormatDetails aacEld(int sampleRate, int channels) {
        return new FormatDetails(AudioStreamInfo.CompressionType.AAC_ELD, sampleRate, channels, 16);
    }

    private static String alacCaps(FormatDetails details, int samplesPerFrame) {
        // 36-byte ALAC atom: header followed by frame length, bit depth, channels and sample rate.
        String codecData = String.format(Locale.ROOT,
                "00000024616c616300000000%08x00%02x280a0e%02x00ff0000000000000000%08x",
                samplesPerFrame, details.bitDepth(), details.channels(), details.sampleRate());
        return "audio/x-alac,mpegversion=(int)4,channels=(int)" + details.channels()
                + ",rate=(int)" + details.sampleRate()
                + ",stream-format=raw,codec_data=(buffer)" + codecData;
    }

    private static String aacEldCaps(FormatDetails details, int samplesPerFrame) {
        int frequencyIndex = switch (details.sampleRate()) {
            case 16_000 -> 8;
            case 24_000 -> 6;
            case 44_100 -> 4;
            case 48_000 -> 3;
            default -> throw new IllegalArgumentException(
                    "Unsupported AAC-ELD sample rate: " + details.sampleRate());
        };
        // AAC-ELD uses frameLengthFlag=1 for 480 samples and 0 for 512 samples.
        long frameLengthFlag = switch (samplesPerFrame) {
            case 480 -> 0x1000L;
            case 512 -> 0L;
            default -> throw new IllegalArgumentException(
                    "Unsupported AAC-ELD frame length: " + samplesPerFrame);
        };
        long audioSpecificConfig = (31L << 27)
                | (7L << 21)
                | ((long) frequencyIndex << 17)
                | ((long) details.channels() << 13)
                | frameLengthFlag;
        String codecData = String.format(Locale.ROOT, "%08x", audioSpecificConfig);
        return "audio/mpeg,mpegversion=(int)4,channels=(int)" + details.channels()
                + ",rate=(int)" + details.sampleRate()
                + ",stream-format=raw,codec_data=(buffer)" + codecData;
    }

    record Configuration(
            AudioStreamInfo.CompressionType compressionType,
            int sampleRate,
            int samplesPerFrame,
            String caps) {
    }

    private record FormatDetails(
            AudioStreamInfo.CompressionType compressionType,
            int sampleRate,
            int channels,
            int bitDepth) {
    }
}
