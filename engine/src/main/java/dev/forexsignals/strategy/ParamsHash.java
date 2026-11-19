package dev.forexsignals.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.forexsignals.strategy.api.StrategyMeta;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * SHA-256 of a canonical, key-sorted JSON of everything that defines a strategy version's behaviour: name, version,
 * symbols, timeframes and parameters. Stored with every run; the report refuses to show out-of-sample numbers when it
 * differs from the frozen hash.
 */
public final class ParamsHash {
    private static final ObjectMapper JSON = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private ParamsHash() {}

    public static String of(StrategyMeta m) {
        Map<String, Object> canon = new TreeMap<>();
        canon.put("name", m.name());
        canon.put("version", m.version());
        canon.put("symbols", m.symbols());
        canon.put("entryTf", m.entryTf().name());
        canon.put("contextTfs", m.contextTfs().stream().map(Enum::name).toList());
        canon.put("params", m.params());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(canon)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
