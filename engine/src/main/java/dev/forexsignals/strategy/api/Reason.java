package dev.forexsignals.strategy.api;

/** One rule checked at the signal, passed or not: {rule, observed, threshold, passed} (PLAN §7.3 "why" list). */
public record Reason(String rule, double observed, double threshold, boolean passed) {}
