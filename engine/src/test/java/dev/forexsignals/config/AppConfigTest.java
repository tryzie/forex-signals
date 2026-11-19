package dev.forexsignals.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** conf.xml round-trip encrypt/decrypt (Phase 1 acceptance) plus key-file safety rules. */
class AppConfigTest {

    // Obviously fake test values; nothing here is a real credential.
    private static final String CONF = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!-- a comment that must survive the rewrite -->
            <config>
              <database host="localhost" port="5432" name="forex" user="forex">
                <password TYPE="CLEARTEXT">test-db-pass-123</password>
              </database>
              <ctrader host="demo.ctraderapi.com" port="5035" accountNumber="1234567">
                <clientId TYPE="CLEARTEXT">test-client-id</clientId>
                <clientSecret TYPE="CLEARTEXT">test-client-secret</clientSecret>
                <accessToken TYPE="CLEARTEXT"></accessToken>
                <refreshToken TYPE="CLEARTEXT"></refreshToken>
              </ctrader>
              <import maxReqPerSec="4"/>
            </config>
            """;

    private static Path write(Path dir, String content) throws Exception {
        Path f = dir.resolve("conf.xml");
        Files.writeString(f, content, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"));
        return f;
    }

    @Test
    void cleartextIsEncryptedOnLoadAndDecryptsIdenticallyOnReload(@TempDir Path dir) throws Exception {
        Path conf = write(dir, CONF);
        Path key = dir.resolve("keys/master.key");

        AppConfig first = AppConfig.load(conf, ConfigCrypto.loadOrCreateKey(key));
        assertThat(first.database().password().value()).isEqualTo("test-db-pass-123");
        assertThat(first.ctrader().clientSecret().value()).isEqualTo("test-client-secret");

        // The file on disk no longer contains any plaintext secret, and every non-empty secret is ENCRYPTED.
        String onDisk = Files.readString(conf);
        assertThat(onDisk).doesNotContain("test-db-pass-123", "test-client-id", "test-client-secret");
        assertThat(onDisk).contains("<password TYPE=\"ENCRYPTED\">", "<clientSecret TYPE=\"ENCRYPTED\">");
        assertThat(onDisk).contains("a comment that must survive the rewrite");
        // Empty CLEARTEXT values are "not configured" and stay as they were.
        assertThat(onDisk).contains("<accessToken TYPE=\"CLEARTEXT\"/>").doesNotContain("accessToken TYPE=\"ENCRYPTED\"");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(conf))).isEqualTo("rw-------");

        // Reload: nothing left to encrypt, so the file is not rewritten, and values are identical.
        String before = Files.readString(conf);
        AppConfig second = AppConfig.load(conf, ConfigCrypto.loadOrCreateKey(key));
        assertThat(Files.readString(conf)).isEqualTo(before);
        assertThat(second.database().password().value()).isEqualTo("test-db-pass-123");
        assertThat(second.ctrader().clientId().value()).isEqualTo("test-client-id");
        assertThat(second.ctrader().accessToken().isSet()).isFalse();
        assertThat(second.ctrader().credentialsPresent()).isFalse();
    }

    @Test
    void keyFileIsCreatedWith600AndWiderPermissionsAreRefused(@TempDir Path dir) throws Exception {
        Path key = dir.resolve("fs/master.key");
        ConfigCrypto.loadOrCreateKey(key);
        assertThat(Files.size(key)).isEqualTo(32);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(key))).isEqualTo("rw-------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(key.getParent()))).isEqualTo("rwx------");

        Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-r-----"));
        assertThatThrownBy(() -> ConfigCrypto.loadOrCreateKey(key))
                .isInstanceOf(ConfigException.class).hasMessageContaining("chmod 600");

        Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-------"));
        Files.write(key, new byte[16]);
        assertThatThrownBy(() -> ConfigCrypto.loadOrCreateKey(key)).hasMessageContaining("exactly 32 bytes");
    }

    @Test
    void wrongKeyFailsWithoutLeakingTheValue(@TempDir Path dir) throws Exception {
        Path conf = write(dir, CONF);
        AppConfig.load(conf, ConfigCrypto.loadOrCreateKey(dir.resolve("a.key")));
        assertThatThrownBy(() -> AppConfig.load(conf, ConfigCrypto.loadOrCreateKey(dir.resolve("b.key"))))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("wrong key file")
                .hasMessageNotContaining("test-db-pass-123");
    }

    @Test
    void encryptedValueMovedToAnotherElementIsRejected(@TempDir Path dir) throws Exception {
        Path conf = write(dir, CONF);
        Path key = dir.resolve("m.key");
        AppConfig.load(conf, ConfigCrypto.loadOrCreateKey(key));
        String s = Files.readString(conf);
        // Copy the encrypted DB password onto the clientId element: the path (AAD) no longer matches.
        String pw = s.replaceAll("(?s).*<password TYPE=\"ENCRYPTED\">([^<]+)</password>.*", "$1");
        String tampered = s.replaceAll("<clientId TYPE=\"ENCRYPTED\">[^<]+</clientId>", "<clientId TYPE=\"ENCRYPTED\">" + pw + "</clientId>");
        Files.writeString(conf, tampered);
        assertThatThrownBy(() -> AppConfig.load(conf, ConfigCrypto.loadOrCreateKey(key)))
                .hasMessageContaining("config/ctrader/clientId");
    }

    @Test
    void updateSecretPersistsEncrypted(@TempDir Path dir) throws Exception {
        Path conf = write(dir, CONF);
        Path key = dir.resolve("k.key");
        AppConfig cfg = AppConfig.load(conf, ConfigCrypto.loadOrCreateKey(key));
        cfg.updateSecret("config/ctrader/accessToken", "refreshed-test-token");
        assertThat(Files.readString(conf)).doesNotContain("refreshed-test-token").contains("<accessToken TYPE=\"ENCRYPTED\">");
        assertThat(AppConfig.load(conf, ConfigCrypto.loadOrCreateKey(key)).ctrader().accessToken().value()).isEqualTo("refreshed-test-token");
    }

    @Test
    void tokenPairIsPersistedInOneRewrite(@TempDir Path dir) throws Exception {
        Path conf = write(dir, CONF);
        Path key = dir.resolve("p.key");
        AppConfig cfg = AppConfig.load(conf, ConfigCrypto.loadOrCreateKey(key));
        cfg.updateSecrets(java.util.Map.of("config/ctrader/accessToken", "new-access", "config/ctrader/refreshToken", "new-refresh"));
        AppConfig re = AppConfig.load(conf, ConfigCrypto.loadOrCreateKey(key));
        assertThat(re.ctrader().accessToken().value()).isEqualTo("new-access");
        assertThat(re.ctrader().refreshToken().value()).isEqualTo("new-refresh");
        assertThatThrownBy(() -> cfg.updateSecrets(java.util.Map.of("config/ctrader/accessToken", "x", "config/nope", "y")))
                .hasMessageContaining("config/nope");
        assertThat(AppConfig.load(conf, ConfigCrypto.loadOrCreateKey(key)).ctrader().accessToken().value())
                .as("nothing written when any path is unknown").isEqualTo("new-access");
    }

    @Test
    void secretToStringNeverRevealsTheValue() {
        assertThat(new Secret("abc").toString()).isEqualTo("Secret[set]").doesNotContain("abc");
        assertThat(new AppConfig.Database("h", 1, "n", "u", new Secret("hunter2"), 1).toString()).doesNotContain("hunter2");
    }

    @Test
    void doctypeIsRejected(@TempDir Path dir) throws Exception {
        Path conf = write(dir, "<?xml version=\"1.0\"?><!DOCTYPE config [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><config>&x;</config>");
        assertThatThrownBy(() -> AppConfig.load(conf, ConfigCrypto.loadOrCreateKey(dir.resolve("d.key"))))
                .isInstanceOf(ConfigException.class);
    }

    @Test
    void importRateAboveFourPerSecondIsRejected(@TempDir Path dir) throws Exception {
        Path conf = write(dir, CONF.replace("maxReqPerSec=\"4\"", "maxReqPerSec=\"5\""));
        AppConfig cfg = AppConfig.load(conf, ConfigCrypto.loadOrCreateKey(dir.resolve("r.key")));
        assertThatThrownBy(cfg::importSettings).hasMessageContaining("(0, 4]");
    }

    @Test
    void committedExampleParsesAndHasNoSecrets(@TempDir Path dir) throws Exception {
        Path example = Path.of(System.getProperty("forexsignals.repoRoot", "..")).resolve("conf.example.xml");
        Path copy = write(dir, Files.readString(example));
        AppConfig cfg = AppConfig.load(copy, ConfigCrypto.loadOrCreateKey(dir.resolve("e.key")));
        assertThat(Files.readString(copy)).isEqualTo(Files.readString(example)); // nothing to encrypt
        assertThat(cfg.ctrader().credentialsPresent()).isFalse();
        assertThat(cfg.deriv().endpoint()).startsWith("wss://");
        assertThat(cfg.importSettings().maxReqPerSec()).isEqualTo(4.0);
        assertThat(cfg.candleGraceSec()).isEqualTo(2);
        assertThatThrownBy(cfg::database).hasMessageContaining("password> is empty");
    }
}
