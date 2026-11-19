package dev.forexsignals.feed;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/**
 * Contract details of one symbol as reported by a feed (PLAN §2 "symbol check at startup").
 * Fields a feed does not provide are null. {@link #hash()} covers everything that affects sizing or
 * trading hours, so a change in any of them is detected and stored as a new spec row.
 */
public record SymbolSpec(String code, String feed, String brokerSymbol, Long brokerSymbolId, Integer digits, Integer pipPosition,
                         Double contractSize, Double minVolume, Double volumeStep, Double maxVolume,
                         Map<String, Object> commission, Double swapLong, Double swapShort,
                         Map<String, Object> tradingHours, Map<String, Object> raw) {

    private static final com.fasterxml.jackson.databind.ObjectMapper CANONICAL = new com.fasterxml.jackson.databind.ObjectMapper()
            .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /**
     * SHA-256 of a canonical, key-sorted JSON of everything that affects sizing or trading hours. Nested maps are
     * sorted too, so the hash never depends on map iteration order (Map.of order changes between JVM runs, which
     * produced false SPEC_CHANGED alerts on every restart before this was canonical).
     */
    public String hash() {
        java.util.Map<String, Object> m = new java.util.TreeMap<>();
        m.put("brokerSymbol", brokerSymbol);
        m.put("digits", digits);
        m.put("pipPosition", pipPosition);
        m.put("contractSize", contractSize);
        m.put("minVolume", minVolume);
        m.put("volumeStep", volumeStep);
        m.put("maxVolume", maxVolume);
        m.put("commission", commission);
        m.put("swapLong", swapLong);
        m.put("swapShort", swapShort);
        m.put("tradingHours", tradingHours);
        try {
            byte[] canonical = CANONICAL.writeValueAsBytes(m);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
