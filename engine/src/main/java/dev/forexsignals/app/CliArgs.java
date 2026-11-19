package dev.forexsignals.app;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Command line: {@code java -jar forex-signals.jar --mode=<mode> [--key=value ...]}.
 * Every option is {@code --key=value}; a bare {@code --flag} means {@code flag=true}.
 * Parsing never exits the JVM; {@link Main} turns a {@link UsageException} into exit code 2.
 */
public record CliArgs(Mode mode, Map<String, String> options) {

    public enum Mode { LIVE, REPLAY, IMPORT, GAPREPORT, SPOTCHECK }

    public static final String USAGE = """
            usage: java -jar forex-signals.jar --mode=<live|replay|import|gapreport|spotcheck> [options]

              common    --config=<path>      conf.xml (default ~/.forexsignals/conf.xml, or $FOREXSIGNALS_CONF)
              live      --feed=<deriv|ctrader|synthetic>      stream ticks, record spreads, build candles
              replay    --feed=<f> --from=<ISO> --to=<ISO> [--source=db|feed] [--symbols=A,B]
                        [--compare-m5] [--report=<dir>]   rebuild candles from recorded ticks
              import    --feed=<f> [--symbols=A,B] [--years=3] [--from=<ISO>] [--to=<ISO>]
                        resumable M1 history import (month chunks, paced)
              gapreport --feed=<f> [--symbols=A,B] [--report=<dir>]
              spotcheck --feed=<f> [--symbols=A,B] [--per-tf=20] [--seed=1] [--report=<dir>]
            """;

    public static final class UsageException extends Exception {
        public UsageException(String message) { super(message); }
    }

    public static CliArgs parse(String[] argv) throws UsageException {
        Map<String, String> options = new LinkedHashMap<>();
        for (String a : argv) {
            if (!a.startsWith("--") || a.length() == 2) throw new UsageException("unexpected argument: " + a);
            String body = a.substring(2);
            int eq = body.indexOf('=');
            String key = eq < 0 ? body : body.substring(0, eq);
            String value = eq < 0 ? "true" : body.substring(eq + 1);
            if (key.isBlank()) throw new UsageException("empty option name: " + a);
            if (options.put(key, value) != null) throw new UsageException("option given twice: --" + key);
        }
        String modeText = options.remove("mode");
        if (modeText == null) throw new UsageException("missing --mode");
        Mode mode;
        try {
            mode = Mode.valueOf(modeText.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new UsageException("invalid --mode: " + modeText);
        }
        return new CliArgs(mode, Map.copyOf(options));
    }

    public Optional<String> opt(String key) { return Optional.ofNullable(options.get(key)); }

    public boolean flag(String key) { return "true".equalsIgnoreCase(options.getOrDefault(key, "false")); }
}
