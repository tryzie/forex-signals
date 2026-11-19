package dev.forexsignals.strategy.api;

/**
 * Thrown by the strategy context when a strategy asks for anything not closed at the evaluation time: a negative
 * "bars ago", a bar or indicator value stamped after now, or an undeclared timeframe/indicator. The engine never
 * catches it: the run fails (PLAN risk register #3, Phase 2 criterion C).
 */
public final class LookaheadViolation extends RuntimeException {
    public LookaheadViolation(String message) { super(message); }
}
