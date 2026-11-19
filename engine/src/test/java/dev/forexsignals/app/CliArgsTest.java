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
        assertThatThrownBy(() -> CliArgs.parse(new String[]{"--mode=live", "--a=1", "--a=2"})).hasMessageContaining("twice");
    }

    @Test
    void invalidModePrintsUsageAndReturnsExitCode2() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(new String[]{"--mode=bogus"}, new PrintStream(err, true, StandardCharsets.UTF_8));
        assertThat(code).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("invalid --mode: bogus").contains("usage:");
    }
}
