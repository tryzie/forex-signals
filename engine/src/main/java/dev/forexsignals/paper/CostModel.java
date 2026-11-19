package dev.forexsignals.paper;

import dev.forexsignals.market.SessionCalendar;

import java.time.Instant;
import java.util.Map;

/**
 * All costs, in PRICE units per unit of the instrument, so they can be compared with a stop distance (PLAN §3):
 *  commission  USD per side per lot by class (FX / XAU / XAG) -> per unit -> quote currency
 *  slippage    price units by class (FX, FX_JPY, XAU, XAG); x2 outside London/New York, x3 in a news window
 *              (never beyond x3); applied against us on entry and on stop/time/invalidation/exit-now exits, never on targets
 *  spread      real bid/ask when ticks have it; the {@link SpreadModel} median for bid-only history
 *  swap        spec pips per lot per night, per 17:00 New York rollover held (triple on Wednesday)
 * spreadMultiplier and slippageMultiplier are 1 normally and 1.5 / 2 in the cost-stress re-run; extraSlippage is the
 * measured fill bias added when real fills are worse than the M1 model (PLAN §14.4), in price units per symbol.
 */
public final class CostModel {

    public record Settings(Map<String, Double> commissionPerSidePerLot, Map<String, Double> slippage, double spreadMultiplier,
                           double slippageMultiplier, Map<String, Double> extraSlippage) {
        public Settings stressed() { return new Settings(commissionPerSidePerLot, slippage, 1.5, 2.0, extraSlippage); }

        public Settings withExtraSlippage(Map<String, Double> extra) {
            return new Settings(commissionPerSidePerLot, slippage, spreadMultiplier, slippageMultiplier, Map.copyOf(extra));
        }
    }

    private final Settings settings;
    private final Map<String, InstrumentSpec> specs;
    private final SpreadModel spreads;

    public CostModel(Settings settings, Map<String, InstrumentSpec> specs, SpreadModel spreads) {
        this.settings = settings;
        this.specs = specs;
        this.spreads = spreads;
    }

    public Settings settings() { return settings; }

    public InstrumentSpec spec(String symbol) {
        InstrumentSpec s = specs.get(symbol);
        if (s == null) throw new IllegalStateException("no instrument spec for " + symbol);
        return s;
    }

    public SpreadModel spreadModel() { return spreads; }

    public static String commissionClass(String symbol) {
        return switch (symbol) { case "XAUUSD" -> "XAU"; case "XAGUSD" -> "XAG"; default -> "FX"; };
    }

    public static String slippageClass(InstrumentSpec s) {
        return switch (s.symbol()) { case "XAUUSD" -> "XAU"; case "XAGUSD" -> "XAG"; default -> s.quote().equals("JPY") ? "FX_JPY" : "FX"; };
    }

    /** USD value of one unit of quote currency at this price (1 for xxxUSD; 1/price for USDJPY). */
    public double quoteToUsd(String symbol, double price) {
        InstrumentSpec s = spec(symbol);
        if (s.quote().equals("USD")) return 1.0;
        if (s.base().equals("USD")) return 1.0 / price;
        throw new IllegalStateException("no USD conversion for " + symbol + " (signal symbols are all quoted in or against USD)");
    }

    public double commissionUsdPerSidePerLot(String symbol) {
        Double c = settings.commissionPerSidePerLot().get(commissionClass(symbol));
        if (c == null) throw new IllegalStateException("no commission configured for class " + commissionClass(symbol));
        return c;
    }

    /** One side's commission in price units per unit (to compare with spreads and stops). */
    public double commissionPerSideInPrice(String symbol, double price) {
        return commissionUsdPerSidePerLot(symbol) / spec(symbol).contractSize() / quoteToUsd(symbol, price);
    }

    /** Slippage for one fill at time t, in price units; the multipliers never stack beyond x3. */
    public double slippage(String symbol, Instant t, boolean inNewsWindow) {
        double base = settings.slippage().getOrDefault(slippageClass(spec(symbol)), 0.0);
        double m = inNewsWindow ? 3.0 : (SessionCalendar.londonOpen(t) || SessionCalendar.newYorkOpen(t)) ? 1.0 : 2.0;
        return base * m * settings.slippageMultiplier() + settings.extraSlippage().getOrDefault(symbol, 0.0);
    }

    /** Modelled spread (bid-only history) including the stress multiplier. */
    public double modelledSpread(String symbol, Instant t) { return spreads.median(symbol, t) * settings.spreadMultiplier(); }

    /** Swap for one night in price units per unit; positive = credit. */
    public double swapPerNight(String symbol, boolean isLong) {
        InstrumentSpec s = spec(symbol);
        return (isLong ? s.swapLongPips() : s.swapShortPips()) * s.pipSize();
    }
}
