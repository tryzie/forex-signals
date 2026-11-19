package dev.forexsignals.app;

import java.io.PrintStream;

/**
 * Entry point of forex-signals.jar. Modes are wired in as they are built during Phase 1.
 * Exit codes: 0 ok, 1 runtime failure, 2 usage error.
 */
public final class Main {

    private Main() {}

    public static void main(String[] argv) {
        int code = run(argv, System.err);
        if (code != 0) System.exit(code);
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
        err.println("mode " + args.mode() + " is not wired yet");
        return 1;
    }
}
