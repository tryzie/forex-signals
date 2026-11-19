package dev.forexsignals.strategy.api;

public enum Direction {
    LONG(1), SHORT(-1);

    private final int sign;

    Direction(int sign) { this.sign = sign; }

    /** +1 for LONG, -1 for SHORT: profit = sign * (exit - entry). */
    public int sign() { return sign; }

    public Direction opposite() { return this == LONG ? SHORT : LONG; }
}
