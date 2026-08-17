package wtf.nanoka.airplay.lib;

import wtf.nanoka.airplay.lib.internal.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;

/**
 * Responds on pairing setup, fairplay setup requests, decrypts data
 */
public class AirPlay {

    private final Pairing pairing;
    private final FairPlay fairplay;
    private final RTSP rtsp;

    private FairPlayVideoDecryptor fairPlayVideoDecryptor;
    private FairPlayAudioDecryptor fairPlayAudioDecryptor;

    public AirPlay() {
        this(AirPlayIdentity.random());
    }

    public AirPlay(AirPlayIdentity identity) {
        pairing = new Pairing(identity.getKeyPair());
        fairplay = new FairPlay();
        rtsp = new RTSP();
    }

    /**
     * {@code /pair-setup}
     * <p>
     * Writes EdDSA public key bytes to output stream
     */
    public void pairSetup(OutputStream out) throws Exception {
        pairing.pairSetup(out);
    }

    /**
     * {@code /pair-verify}
     * <p>
     * On first request writes curve25519 public key + encrypted signature bytes to output stream;
     * On second request verifies signature
     */
    public void pairVerify(InputStream in, OutputStream out) throws Exception {
        pairing.pairVerify(in, out);
    }

    /**
     * Pair was verified successfully
     */
    public boolean isPairVerified() {
        return pairing.isPairVerified();
    }

    /**
     * {@code /fp-setup}
     * <p>
     * Writes fp-setup response bytes to output stream
     */
    public void fairPlaySetup(InputStream in, OutputStream out) throws Exception {
        fairplay.fairPlaySetup(in, out);
    }

    /**
     * {@code RTSP SETUP}
     * <p>
     * Sets encrypted EAS key and IV or retrieves media stream info
     */
    public Optional<MediaStreamInfo> rtspSetup(InputStream in) throws Exception {
        return rtspSetupInfo(in).mediaStreamInfo();
    }

    public RtspSetupInfo rtspSetupInfo(InputStream in) throws Exception {
        var setupInfo = rtsp.setup(in);
        if (setupInfo.keySetup()) {
            fairPlayVideoDecryptor = null;
            fairPlayAudioDecryptor = null;
        } else {
            setupInfo.mediaStreamInfo().ifPresent(stream -> {
                switch (stream.getStreamType()) {
                    case VIDEO -> fairPlayVideoDecryptor = null;
                    case AUDIO -> fairPlayAudioDecryptor = null;
                }
            });
        }
        return setupInfo;
    }

    /**
     * {@code RTSP TEARDOWN}
     * <p>
     * Retrieves media stream info
     */
    public Optional<MediaStreamInfo> rtspTeardown(InputStream in) throws Exception {
        return rtsp.teardown(in);
    }


    public byte[] getFairPlayAesKey() {
        return fairplay.decryptAesKey(rtsp.getEkey());
    }

    /**
     * @return {@code true} if we got shared secret during pairing, ekey & stream connection id during RTSP SETUP
     */
    public boolean isFairPlayVideoDecryptorReady() {
        return pairing.getSharedSecret() != null && rtsp.getEkey() != null && rtsp.getStreamConnectionID() != null;
    }

    /**
     * @return {@code true} if we got shared secret during pairing, ekey & eiv during RTSP SETUP
     */
    public boolean isFairPlayAudioDecryptorReady() {
        return pairing.getSharedSecret() != null && rtsp.getEkey() != null && rtsp.getEiv() != null;
    }

    public String getStreamConnectionID() {
        return rtsp.getStreamConnectionID();
    }

    public void decryptVideo(byte[] video) throws Exception {
        if (fairPlayVideoDecryptor == null) {
            if (!isFairPlayVideoDecryptorReady()) {
                throw new IllegalStateException("FairPlayVideoDecryptor not ready!");
            }
            fairPlayVideoDecryptor = new FairPlayVideoDecryptor(getFairPlayAesKey(), pairing.getSharedSecret(), rtsp.getStreamConnectionID());
        }
        fairPlayVideoDecryptor.decrypt(video);
    }

    public void decryptAudio(byte[] audio, int audioLength) throws Exception {
        if (fairPlayAudioDecryptor == null) {
            if (!isFairPlayAudioDecryptorReady()) {
                throw new IllegalStateException("FairPlayAudioDecryptor not ready!");
            }
            fairPlayAudioDecryptor = new FairPlayAudioDecryptor(getFairPlayAesKey(), rtsp.getEiv(), pairing.getSharedSecret());
        }
        fairPlayAudioDecryptor.decrypt(audio, audioLength);
    }
}
