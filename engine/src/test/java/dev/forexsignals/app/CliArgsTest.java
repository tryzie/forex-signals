package dev.forexsignals.app;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CliArgsTest {

    @Test
    void parsesModeAndOptions() throws Exception {
        CliArgs a = CliArgs.parse(new String[]{"--mode=replay", "--feed=deriv", "--compare-m5"});
        assertThat(a.mode()).isEqualTo(CliArgs.Mode.REPLAY);
        assertThat(a.opt("feed")).contains("deriv");
        assertThat(a.flag("compare-m5")).isTrue();
        assertThat(a.flag("other")).isFalse();
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> CliArgs.parse(new String[]{"--mode=trade"})).hasMessageContaining("invalid --mode");
        assertThatThrownBy(() -> CliArgs.parse(new String[]{})).hasMessageContaining("missing --mode");
        assertThatThrownBy(() -> CliArgs.parse(new String[]{"--mode=live", "x"})).hasMessageContaining("unexpected");
        assertThatThrownBy(() -> CliArgs.parse(new String[]{"--mode=live", "--seed=1", "--seed=2"})).hasMessageContaining("twice");
    }

    @Test
    void invalidModePrintsUsageAndReturnsExitCode2() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(new String[]{"--mode=bogus"}, new PrintStream(err, true, StandardCharsets.UTF_8));
        assertThat(code).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("invalid --mode: bogus").contains("usage:");
    }

    @Test
    void rejectsUnknownOptionWithExitCode2() {
        assertThatThrownBy(() -> CliArgs.parse(new String[]{"--mode=replay", "--compare_m5"}))
                .isInstanceOf(CliArgs.UsageException.class)
                .hasMessage("unknown option: --compare_m5");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(new String[]{"--mode=replay", "--compare_m5"}, new PrintStream(err, true, StandardCharsets.UTF_8));
        assertThat(code).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("unknown option: --compare_m5").contains("usage:");
    }

    @Test
    void acceptsEveryKnownOption() throws Exception {
        CliArgs a = CliArgs.parse(new String[]{"--mode=replay", "--config=/tmp/c.xml", "--feed=deriv",
                "--symbols=EURUSD,GBPUSD", "--duration=PT10M", "--seed=7", "--from=2026-11-09T00:00:00Z",
                "--to=2026-11-10T00:00:00Z", "--source=db", "--compare-m5", "--write-candles", "--years=3",
                "--report=data/reports", "--list-min=5", "--per-tf=20", "--wait=PT1M", "--note=x", "--strategies=a", "--news=n.csv", "--diagnostic"});
        assertThat(a.options()).hasSize(CliArgs.KNOWN_OPTIONS.size() - 1); // "mode" is removed
        for (String key : CliArgs.KNOWN_OPTIONS) {
            if (!key.equals("mode")) assertThat(a.options()).containsKey(key);
            assertThat(CliArgs.USAGE).contains("--" + key);
        }
    }
}
