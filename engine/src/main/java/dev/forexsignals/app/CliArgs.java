package dev.forexsignals.app;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Command line: {@code java -jar forex-signals.jar --mode=<mode> [--key=value ...]}.
 * Every option is {@code --key=value}; a bare {@code --flag} means {@code flag=true}.
 * Parsing never exits the JVM; {@link Main} turns a {@link UsageException} into exit code 2.
 */
public record CliArgs(Mode mode, Map<String, String> options) {

    public enum Mode { LIVE, REPLAY, IMPORT, GAPREPORT, SPOTCHECK, CHATID, SPREADSAMPLE }

    public static final String USAGE = """
            usage: java -jar forex-signals.jar --mode=<live|replay|import|gapreport|spotcheck|chatid|spreadsample> [options]

              common    --config=<path>      conf.xml (default ~/.forexsignals/conf.xml, or $FOREXSIGNALS_CONF)
                        --feed=<deriv|ctrader|synthetic>   default: <feed primary> in conf.xml
                        --symbols=A,B        limit to these catalogue codes
              live      [--duration=PT10M] [--seed=1]
                        stream ticks, record spreads, build candles
              replay    --from=<ISO> --to=<ISO> [--source=db|feed] [--compare-m5] [--write-candles]
                        [--report=<dir>]     rebuild candles from recorded ticks
              import    [--years=3] [--from=<ISO>] [--to=<ISO>]
                        resumable M1 history import (month chunks, paced)
              gapreport [--report=<dir>] [--list-min=5]
              spotcheck [--per-tf=20] [--seed=1] [--report=<dir>]
              spreadsample [--symbols=A,B]   one cTrader tick-history week per quarter -> spread model (resumable)
              chatid    [--wait=PT2M]        print the Telegram chat ID of whoever pressed Start on the bot
                        (token read from the encrypted conf.xml, never shown; no database needed)

            Unknown options are rejected (exit code 2).
            """;

    /** Every option name the code reads (any mode). Anything else is a usage error. */
    public static final Set<String> KNOWN_OPTIONS = Set.of(
            "mode", "config", "feed", "symbols", "duration", "seed", "from", "to", "source",
            "compare-m5", "write-candles", "years", "report", "list-min", "per-tf", "wait");

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
            if (!KNOWN_OPTIONS.contains(key)) throw new UsageException("unknown option: --" + key);
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
