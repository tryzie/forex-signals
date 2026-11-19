package dev.forexsignals.config;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/**
 * AES-256-GCM for conf.xml secrets (PLAN §10, ADR 0006).
 *
 * Stored value: base64( iv[12] || ciphertext || tag[16] ). A fresh random IV is used for every value.
 * The element path (e.g. "config/ctrader/clientSecret") is bound in as additional authenticated data,
 * so an encrypted value copied onto a different element fails to decrypt instead of being silently used.
 *
 * Key file: 32 random bytes, default ~/.forexsignals/master.key, overridden by $FOREXSIGNALS_KEY_FILE.
 * Created on first run with permissions 600 (directory 700). If the file exists with permissions wider
 * than 600, or is owned by another user, the app refuses to start.
 */
public final class ConfigCrypto {

    public static final String KEY_FILE_ENV = "FOREXSIGNALS_KEY_FILE";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    private ConfigCrypto(byte[] keyBytes) {
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    public static Path defaultKeyFile() {
        String env = System.getenv(KEY_FILE_ENV);
        if (env != null && !env.isBlank()) return Path.of(env);
        return Path.of(System.getProperty("user.home"), ".forexsignals", "master.key");
    }

    /**
     * Loads the key, creating it on first run. Throws {@link ConfigException} on unsafe permissions,
     * wrong owner or wrong size; the caller must treat that as "refuse to start".
     */
    public static ConfigCrypto loadOrCreateKey(Path keyFile) {
        try {
            if (!Files.exists(keyFile)) {
                // First run: create the directory (700) and the key file (600) atomically with its permissions.
                Path dir = keyFile.toAbsolutePath().getParent();
                if (dir != null && !Files.exists(dir)) {
                    Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                }
                byte[] fresh = new byte[KEY_BYTES];
                RANDOM.nextBytes(fresh);
                try {
                    Files.createFile(keyFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                    Files.write(keyFile, fresh);
                    return new ConfigCrypto(fresh);
                } catch (FileAlreadyExistsException raced) {
                    // Another process created it in between: fall through and load it with the normal checks.
                }
            }

            // Existing key: permissions must be exactly owner read/write (600) or stricter (400).
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(keyFile);
            for (PosixFilePermission p : perms) {
                if (p != PosixFilePermission.OWNER_READ && p != PosixFilePermission.OWNER_WRITE) {
                    throw new ConfigException("key file " + keyFile + " has permissions "
                            + PosixFilePermissions.toString(perms) + "; must be rw------- (chmod 600). Refusing to start.");
                }
            }
            String owner = Files.getOwner(keyFile).getName();
            String me = System.getProperty("user.name");
            if (me != null && !owner.equals(me)) {
                throw new ConfigException("key file " + keyFile + " is owned by '" + owner + "', not by the service user '" + me + "'. Refusing to start.");
            }
            byte[] bytes = Files.readAllBytes(keyFile);
            if (bytes.length != KEY_BYTES) {
                throw new ConfigException("key file " + keyFile + " must contain exactly " + KEY_BYTES + " bytes, found " + bytes.length);
            }
            return new ConfigCrypto(bytes);
        } catch (IOException e) {
            throw new ConfigException("cannot read or create key file " + keyFile + ": " + e.getMessage(), e);
        }
    }

    /** For tests only: a crypto instance over a given key. */
    static ConfigCrypto forKey(byte[] keyBytes) {
        if (keyBytes.length != KEY_BYTES) throw new IllegalArgumentException("key must be 32 bytes");
        return new ConfigCrypto(keyBytes.clone());
    }

    public String encrypt(String plaintext, String elementPath) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(elementPath.getBytes(StandardCharsets.UTF_8));
            byte[] ctAndTag = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_BYTES + ctAndTag.length];
            System.arraycopy(iv, 0, out, 0, IV_BYTES);
            System.arraycopy(ctAndTag, 0, out, IV_BYTES, ctAndTag.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new ConfigException("encryption failed for " + elementPath, e);
        }
    }

    public String decrypt(String encoded, String elementPath) {
        byte[] all;
        try {
            all = Base64.getDecoder().decode(encoded.strip());
        } catch (IllegalArgumentException e) {
            throw new ConfigException("encrypted value at " + elementPath + " is not valid base64");
        }
        if (all.length < IV_BYTES + TAG_BITS / 8) throw new ConfigException("encrypted value at " + elementPath + " is too short");
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            c.updateAAD(elementPath.getBytes(StandardCharsets.UTF_8));
            byte[] pt = c.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            throw new ConfigException("cannot decrypt " + elementPath + ": wrong key file, or the value was moved/edited");
        } catch (GeneralSecurityException e) {
            throw new ConfigException("decryption failed for " + elementPath, e);
        }
    }
}
