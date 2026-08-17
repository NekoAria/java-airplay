package wtf.nanoka.airplay.lib;

import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.Utils;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.SecureRandom;

public final class AirPlayIdentity {

    private static final int SEED_LENGTH = 32;

    private final KeyPair keyPair;
    private final byte[] publicKey;
    private final String deviceId;

    private AirPlayIdentity(byte[] seed) {
        if (seed.length != SEED_LENGTH) {
            throw new IllegalArgumentException("AirPlay identity seed must contain 32 bytes");
        }

        var parameters = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);
        var privateKeySpec = new EdDSAPrivateKeySpec(seed, parameters);
        var publicKeySpec = new EdDSAPublicKeySpec(privateKeySpec.getA(), parameters);
        var privateKey = new EdDSAPrivateKey(privateKeySpec);
        var edPublicKey = new EdDSAPublicKey(publicKeySpec);
        keyPair = new KeyPair(edPublicKey, privateKey);
        publicKey = edPublicKey.getAbyte();

        byte[] deviceBytes = publicKey.clone();
        deviceBytes[0] = (byte) ((deviceBytes[0] | 0x02) & 0xfe);
        deviceId = String.format("%02X:%02X:%02X:%02X:%02X:%02X",
                deviceBytes[0], deviceBytes[1], deviceBytes[2],
                deviceBytes[3], deviceBytes[4], deviceBytes[5]);
    }

    public static AirPlayIdentity random() {
        byte[] seed = new byte[SEED_LENGTH];
        new SecureRandom().nextBytes(seed);
        return new AirPlayIdentity(seed);
    }

    public static AirPlayIdentity loadOrCreate(Path identityFile) throws IOException {
        Path absolutePath = identityFile.toAbsolutePath();
        Path parent = absolutePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        if (Files.exists(absolutePath)) {
            return new AirPlayIdentity(Files.readAllBytes(absolutePath));
        }

        byte[] seed = new byte[SEED_LENGTH];
        new SecureRandom().nextBytes(seed);
        try {
            Files.write(absolutePath, seed, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            setOwnerOnlyPermissions(absolutePath);
            return new AirPlayIdentity(seed);
        } catch (FileAlreadyExistsException ignored) {
            return new AirPlayIdentity(Files.readAllBytes(absolutePath));
        }
    }

    private static void setOwnerOnlyPermissions(Path identityFile) {
        try {
            Files.setPosixFilePermissions(identityFile, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows protects the file through the owning user's profile ACL.
        }
    }

    public KeyPair getKeyPair() {
        return keyPair;
    }

    public byte[] getPublicKey() {
        return publicKey.clone();
    }

    public String getPublicKeyHex() {
        return Utils.bytesToHex(publicKey);
    }

    public String getDeviceId() {
        return deviceId;
    }
}
