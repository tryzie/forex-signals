package dev.forexsignals.app;

import dev.forexsignals.config.AppConfig;
import dev.forexsignals.config.ConfigCrypto;
import dev.forexsignals.config.ConfigException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.nio.file.Path;

/**
 * Entry point of forex-signals.jar:
 *   java -jar forex-signals.jar --mode=live|replay|import|gapreport|spotcheck [--config=...] [options]
 *
 * Startup for every mode: parse arguments, load conf.xml (encrypting any CLEARTEXT secrets),
 * connect to Postgres and apply Flyway migrations from the classpath, then run the mode.
 * Exit codes: 0 ok, 1 failure, 2 usage error, 3 replay check found unexplained mismatches.
 * This program never places, modifies or closes orders (CLAUDE.md rule 1).
 */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private Main() {}

    public static void main(String[] argv) {
        int code = run(argv, System.err);
        System.exit(code);
    }

    static int run(String[] argv, PrintStream err) {
        CliArgs args;
        try {
            args = CliArgs.parse(argv);
        } catch (CliArgs.UsageException e) {
            err.println("error: " + e.getMessage());
            err.print(CliArgs.USAGE);
            return 2;
        }

        try {
            Path confFile = args.opt("config").map(Path::of).orElse(AppConfig.defaultConfFile());
            AppConfig cfg = AppConfig.load(confFile, ConfigCrypto.loadOrCreateKey(ConfigCrypto.defaultKeyFile()));
            log.info("forex-signals starting: mode={} config={}", args.mode(), confFile);
            if (args.mode() == CliArgs.Mode.CHATID) {
                // Telegram setup helper: no database, prints to stdout.
                return dev.forexsignals.delivery.TelegramChatId.run(cfg,
                        java.time.Duration.parse(args.opt("wait").orElse("PT2M")), System.out);
            }
            try (Modes.Context ctx = Modes.context(cfg)) {
                return switch (args.mode()) {
                    case LIVE -> Modes.live(args, ctx);
                    case REPLAY -> Modes.replay(args, ctx);
                    case IMPORT -> Modes.importHistory(args, ctx);
                    case GAPREPORT -> Modes.gapReport(args, ctx);
                    case SPOTCHECK -> Modes.spotCheck(args, ctx);
                    case SPREADSAMPLE -> Modes.spreadSample(args, ctx);
                    case FREEZE -> Research.freeze(args, ctx);
                    case VALIDATE -> Research.validate(args, ctx);
                    case USDXCHECK -> Research.usdxCheck(args, ctx);
                    case HYPOTHESIS -> Research.hypothesis(args, ctx);
                    case CHATID -> throw new IllegalStateException("handled above");
                };
            }
        } catch (ConfigException e) {
            log.error("configuration error: {}", e.getMessage());
            err.println("configuration error: " + e.getMessage());
            return 1;
        } catch (Exception e) {
            log.error("{} failed", args.mode(), e);
            return 1;
        }
    }
}
