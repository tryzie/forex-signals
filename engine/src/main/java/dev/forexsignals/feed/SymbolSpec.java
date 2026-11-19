package dev.forexsignals.feed;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * Contract details of one symbol as reported by a feed (PLAN §2 "symbol check at startup").
 * Fields a feed does not provide are null. {@link #hash()} covers everything that affects sizing or
 * trading hours, so a change in any of them is detected and stored as a new spec row.
 */
public record SymbolSpec(String code, String feed, String brokerSymbol, Long brokerSymbolId, Integer digits, Integer pipPosition,
                         Double contractSize, Double minVolume, Double volumeStep, Double maxVolume,
                         Map<String, Object> commission, Double swapLong, Double swapShort,
                         Map<String, Object> tradingHours, Map<String, Object> raw) {

    public String hash() {
        // Sorted maps so the hash does not depend on map iteration order.
        String canonical = String.join("|", String.valueOf(brokerSymbol), String.valueOf(digits), String.valueOf(pipPosition),
                String.valueOf(contractSize), String.valueOf(minVolume), String.valueOf(volumeStep), String.valueOf(maxVolume),
                String.valueOf(commission == null ? null : new TreeMap<>(commission)), String.valueOf(swapLong), String.valueOf(swapShort),
                String.valueOf(tradingHours == null ? null : new TreeMap<>(tradingHours)));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
