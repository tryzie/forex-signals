package dev.forexsignals.app;

import dev.forexsignals.store.TestDb;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Criterion 7: the shaded jar starts in replay mode, encrypts conf.xml, runs Flyway from the classpath
 * (proves the shade service-file merge), logs JSON; an invalid --mode prints usage and exits 2.
 * Failsafe runs after package, so target/forex-signals.jar exists.
 */
class JarIT {

    private static final Path JAR = Path.of("target/forex-signals.jar");

    private record Run(int exit, List<String> lines) {}

    private static Run run(Path home, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", JAR.toString()));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("FOREXSIGNALS_KEY_FILE", home.resolve("master.key").toString());
        Process p = pb.start();
        List<String> lines = new String(p.getInputStream().readAllBytes()).lines().toList();
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).isTrue();
        return new Run(p.exitValue(), lines);
    }

    @Test
    void invalidModePrintsUsageAndExits2(@TempDir Path home) throws Exception {
        assertThat(JAR).exists();
        Run r = run(home, "--mode=trade");
        assertThat(r.exit()).isEqualTo(2);
        assertThat(String.join("\n", r.lines())).contains("invalid --mode: trade").contains("usage:");
    }

    @Test
    void replayModeStartsMigratesAndLogsJson(@TempDir Path home) throws Exception {
        PostgreSQLContainer c = TestDb.container();
        String dbName = TestDb.createDatabase();
        Path conf = home.resolve("conf.xml");
        Files.writeString(conf, """
                <?xml version="1.0" encoding="UTF-8"?>
                <config>
                  <database host="%s" port="%d" name="%s" user="%s" poolSize="2">
                    <password TYPE="CLEARTEXT">%s</password>
                  </database>
                  <feed primary="synthetic"/>
                </config>
                """.formatted(c.getHost(), c.getMappedPort(5432), dbName, c.getUsername(), c.getPassword()));
        Files.setPosixFilePermissions(conf, PosixFilePermissions.fromString("rw-------"));

        Run r = run(home, "--mode=replay", "--config=" + conf, "--feed=synthetic",
                "--from=2024-03-04T00:00:00Z", "--to=2024-03-05T00:00:00Z", "--report=" + home.resolve("reports"));

        String all = String.join("\n", r.lines());
        assertThat(r.exit()).as(all).isZero();
        // Every line on stdout is one JSON object (Logback + logstash encoder survived shading).
        assertThat(r.lines()).isNotEmpty().allSatisfy(l -> assertThat(l).startsWith("{\"@timestamp\":").endsWith("}"));
        assertThat(all).contains("flyway: 4 migration(s) applied").contains("replay: built candles");
        // conf.xml was rewritten with the password encrypted.
        assertThat(Files.readString(conf)).contains("TYPE=\"ENCRYPTED\"").doesNotContain(c.getPassword());
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(home.resolve("master.key")))).isEqualTo("rw-------");
    }
}
