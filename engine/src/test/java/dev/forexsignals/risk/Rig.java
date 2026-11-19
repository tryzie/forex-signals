package dev.forexsignals.risk;

import dev.forexsignals.market.SymbolCatalog;
import dev.forexsignals.paper.CostModel;
import dev.forexsignals.paper.InstrumentSpec;
import dev.forexsignals.paper.SpreadModel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Shared test fixture: the real Pepperstone cTrader demo specs (2026-11-19), Phase 0 costs, a flat spread model. */
public final class Rig {
    public static final SymbolCatalog CATALOG = SymbolCatalog.load(Optional.empty());

    public static final Map<String, InstrumentSpec> SPECS = Map.of(
            "EURUSD", new InstrumentSpec("EURUSD", "EUR", "USD", 100_000, 0.01, 0.01, 0.0001, -0.71, 0.14),
            "GBPUSD", new InstrumentSpec("GBPUSD", "GBP", "USD", 100_000, 0.01, 0.01, 0.0001, -0.25, -0.25),
            "AUDUSD", new InstrumentSpec("AUDUSD", "AUD", "USD", 100_000, 0.01, 0.01, 0.0001, 0.01, -0.32),
            "USDJPY", new InstrumentSpec("USDJPY", "USD", "JPY", 100_000, 0.01, 0.01, 0.01, 0.32, -1.59),
            "XAUUSD", new InstrumentSpec("XAUUSD", "XAU", "USD", 100, 0.01, 0.01, 0.1, -7.25, 2.47),
            "XAGUSD", new InstrumentSpec("XAGUSD", "XAG", "USD", 5000, 0.01, 0.01, 0.01, -1.17, 0.33),
            "USDX", new InstrumentSpec("USDX", "USD", "IDX", 100, 1, 1, 0.01, 0.01, -0.59));

    public static final CostModel.Settings COSTS = new CostModel.Settings(
            Map.of("FX", 3.00, "XAU", 3.50, "XAG", 0.00),
            Map.of("FX", 0.00002, "FX_JPY", 0.002, "XAU", 0.15, "XAG", 0.02), 1.0, 1.0, Map.of());

    /** Flat spread model: the same median in every bucket and quarter. */
    public static SpreadModel spreads(Map<String, Double> median) {
        List<SpreadModel.Sample> out = new ArrayList<>();
        median.forEach((sym, m) -> {
            for (String b : new String[]{"ASIA", "LONDON", "OVERLAP", "NEW_YORK", "ROLLOVER"}) {
                out.add(new SpreadModel.Sample(sym, "2024Q1", Instant.parse("2024-02-11T00:00:00Z"), b, m, m * 2, 1000));
            }
        });
        return new SpreadModel(out);
    }

    public static final Map<String, Double> MEDIANS = Map.of("EURUSD", 0.00002, "GBPUSD", 0.00003, "AUDUSD", 0.00003, "USDJPY", 0.003,
            "XAUUSD", 0.12, "XAGUSD", 0.03, "USDX", 0.05);

    public static CostModel costs() { return new CostModel(COSTS, SPECS, spreads(MEDIANS)); }

    private Rig() {}
}
