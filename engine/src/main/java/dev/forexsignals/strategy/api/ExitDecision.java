package dev.forexsignals.strategy.api;

import java.util.List;

/**
 * A strategy's exit instruction for an open trade: EXIT_NOW (an "exit now" alert; filled after the manual latency) or
 * MOVE_STOP to a new level (the tracker only ever tightens a stop).
 */
public record ExitDecision(Kind kind, double stopLevel, String rule, List<Reason> reasons) {
    public enum Kind { EXIT_NOW, MOVE_STOP }

    public static ExitDecision exitNow(String rule, List<Reason> reasons) {
        return new ExitDecision(Kind.EXIT_NOW, Double.NaN, rule, reasons);
    }

    public static ExitDecision moveStop(double level, String rule) {
        return new ExitDecision(Kind.MOVE_STOP, level, rule, List.of());
    }
}
