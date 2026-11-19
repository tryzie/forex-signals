package dev.forexsignals.market;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Last N ticks per symbol in a ring buffer (the trade tracker reads the latest bid/ask from here in Phase 3).
 * Used only on the single-threaded engine loop.
 */
public final class TickBuffer {
    private final int capacity;
    private final Map<String, Ring> rings = new HashMap<>();

    public TickBuffer(int capacity) { this.capacity = capacity; }

    public void add(Tick t) { rings.computeIfAbsent(t.symbol(), s -> new Ring(capacity)).add(t); }

    public Optional<Tick> latest(String symbol) {
        Ring r = rings.get(symbol);
        return r == null || r.size == 0 ? Optional.empty() : Optional.of(r.items[(r.head - 1 + capacity) % capacity]);
    }

    /** The latest held tick of a symbol stamped at or before t (the alert-time reference price). */
    public Optional<Tick> latestAtOrBefore(String symbol, java.time.Instant t) {
        Ring r = rings.get(symbol);
        if (r == null) return Optional.empty();
        for (int i = 1; i <= r.size; i++) {
            Tick k = r.items[(r.head - i + capacity) % capacity];
            if (!k.ts().isAfter(t)) return Optional.of(k);
        }
        return Optional.empty();
    }

    /** Ticks currently held for a symbol, oldest first. */
    public java.util.List<Tick> recent(String symbol) {
        Ring r = rings.get(symbol);
        if (r == null) return java.util.List.of();
        java.util.List<Tick> out = new java.util.ArrayList<>(r.size);
        for (int i = 0; i < r.size; i++) out.add(r.items[(r.head - r.size + i + capacity) % capacity]);
        return out;
    }

    private static final class Ring {
        final Tick[] items;
        int head, size;

        Ring(int capacity) { items = new Tick[capacity]; }

        void add(Tick t) {
            items[head] = t;
            head = (head + 1) % items.length;
            if (size < items.length) size++;
        }
    }
}
