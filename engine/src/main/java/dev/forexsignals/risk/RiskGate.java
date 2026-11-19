package dev.forexsignals.risk;

import dev.forexsignals.market.SessionCalendar;
import dev.forexsignals.market.SymbolCatalog;
import dev.forexsignals.paper.CostModel;
import dev.forexsignals.strategy.api.Direction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The risk gate (PLAN §9). Every proposal passes here; all checks are evaluated and stored, the first failure is the
 * rejection reason. Sizing: lots = floor_to_step(equity x risk% / (R x contract x quoteToUsd + round-trip commission
 * per lot)), so the expected commission is inside the 1% risk. Only an approved candidate gets a {@link RiskApproved}.
 */
public final class RiskGate {

    public record Config(double riskPct, double dailyLossPct, double killSwitchPct, int maxOpen, double minRR,
                         int maxPerCurrencyDirection, int maxNetUsd, double maxCostR, double spreadCapMultiple) {
        public static Config defaults() { return new Config(1.0, 3.0, 10.0, 3, 1.5, 2, 2, 0.15, 2.5); }
    }

    /** What the gate needs to know about one proposal at alert time (prices: bid/ask at the alert). */
    public record Candidate(String strategyId, SymbolCatalog.SymbolDef symbol, Direction direction, Instant at, double bid, double ask,
                            double stop, double target, boolean strategyLive, boolean feedHealthy) {
        public double reference() { return direction == Direction.LONG ? ask : bid; }
    }

    /** An open (PENDING or ACTIVE) position for the exposure checks. */
    public record Exposure(SymbolCatalog.SymbolDef symbol, Direction direction) {}

    /** The account state the gate reads (the engine's book). */
    public interface Book {
        double equity();

        double dayStartEquity();

        boolean killSwitchTripped();

        List<Exposure> open();
    }

    public record Check(RejectionReason reason, boolean passed, double observed, double threshold) {}

    public record Sized(Candidate candidate, double lots, double riskUsd, double estCostR, double rUnit) {}

    public record Decision(RejectionReason firstFailure, List<Check> checks, Sized sized, RiskApproved<Sized> approved) {
        public boolean isApproved() { return approved != null; }
    }

    private final Config cfg;
    private final CostModel costs;
    private final NewsCalendar news;

    public RiskGate(Config cfg, CostModel costs, NewsCalendar news) {
        this.cfg = cfg;
        this.costs = costs;
        this.news = news;
    }

    public Config config() { return cfg; }

    public NewsCalendar news() { return news; }

    public Decision evaluate(Candidate c, Book book) {
        List<Check> checks = new ArrayList<>();
        int s = c.direction().sign();
        double ref = c.reference();
        double r = Math.abs(ref - c.stop());
        String code = c.symbol().code();

        // 1. NO_STOP: missing, or on the wrong side of the reference.
        boolean stopOk = !Double.isNaN(c.stop()) && r > 0 && s * (ref - c.stop()) > 0;
        checks.add(new Check(RejectionReason.NO_STOP, stopOk, c.stop(), ref));
        // 2. RR_TOO_LOW: on price distances from the spread-inclusive reference (tolerance for binary rounding).
        double rr = stopOk ? s * (c.target() - ref) / r : 0;
        checks.add(new Check(RejectionReason.RR_TOO_LOW, rr >= cfg.minRR() - 1e-9, rr, cfg.minRR()));
        // 3. COST_VS_STOP: spread + commission both sides + entry and exit slippage, over the stop distance.
        boolean inNews = news != null && news.status(c.at(), currencies(c)) != NewsCalendar.Status.CLEAR;
        double spread = c.ask() - c.bid();
        double cost = spread + 2 * costs.commissionPerSideInPrice(code, ref) + 2 * costs.slippage(code, c.at(), inNews);
        double costR = stopOk ? cost / r : Double.POSITIVE_INFINITY;
        checks.add(new Check(RejectionReason.COST_VS_STOP, costR <= cfg.maxCostR(), costR, cfg.maxCostR()));
        // 4. SPREAD_CAP: spread above 2.5 x the modelled median for this symbol and session bucket.
        double cap = costs.spreadModel() != null && costs.spreadModel().covers(code) ? cfg.spreadCapMultiple() * costs.spreadModel().median(code, c.at()) : Double.POSITIVE_INFINITY;
        checks.add(new Check(RejectionReason.SPREAD_CAP, spread <= cap, spread, cap));
        // 5. OUTSIDE_SESSION (London or New York open, not after the Friday cut-off).
        checks.add(new Check(RejectionReason.OUTSIDE_SESSION, SessionCalendar.entryAllowed(c.at()), 0, 0));
        // 6. NEWS_BLACKOUT, and its fallback variant when the calendar does not cover the time.
        NewsCalendar.Status ns = news == null ? NewsCalendar.Status.CLEAR : news.status(c.at(), currencies(c));
        checks.add(new Check(RejectionReason.NEWS_BLACKOUT, ns != NewsCalendar.Status.BLACKOUT, 0, 0));
        checks.add(new Check(RejectionReason.NEWS_BLACKOUT_FALLBACK, ns != NewsCalendar.Status.FALLBACK_BLACKOUT, 0, 0));
        // 7. DAILY_LOSS_CAP: realized + floating since 17:00 NY <= -3% of the equity at 17:00.
        double dayPnl = book.equity() - book.dayStartEquity();
        double dayCap = -cfg.dailyLossPct() / 100 * book.dayStartEquity();
        checks.add(new Check(RejectionReason.DAILY_LOSS_CAP, dayPnl > dayCap, dayPnl, dayCap));
        // 8. KILL_SWITCH (tripped at -10% from the peak; manual reset only).
        checks.add(new Check(RejectionReason.KILL_SWITCH, !book.killSwitchTripped(), 0, 0));
        // 9. MAX_OPEN.
        checks.add(new Check(RejectionReason.MAX_OPEN, book.open().size() < cfg.maxOpen(), book.open().size(), cfg.maxOpen()));
        // 10. CURRENCY_EXPOSURE and 11. USD_NET_EXPOSURE, counting this signal.
        List<Exposure> after = new ArrayList<>(book.open());
        after.add(new Exposure(c.symbol(), c.direction()));
        Map<String, Integer> legs = legs(after);
        int worst = legs.values().stream().mapToInt(Math::abs).max().orElse(0);
        checks.add(new Check(RejectionReason.CURRENCY_EXPOSURE, worst <= cfg.maxPerCurrencyDirection(), worst, cfg.maxPerCurrencyDirection()));
        int usdNet = usdNet(after);
        checks.add(new Check(RejectionReason.USD_NET_EXPOSURE, Math.abs(usdNet) <= cfg.maxNetUsd(), usdNet, cfg.maxNetUsd()));
        // 12. FEED_UNHEALTHY.
        checks.add(new Check(RejectionReason.FEED_UNHEALTHY, c.feedHealthy(), 0, 0));
        // 13. CONTEXT_ONLY_SYMBOL: only SIGNAL symbols are ever traded (USDX, USDX_SYN, data-only pairs never).
        checks.add(new Check(RejectionReason.CONTEXT_ONLY_SYMBOL, c.symbol().role() == SymbolCatalog.Role.SIGNAL, 0, 0));
        // 14. SIZE_BELOW_MIN: sizing never rounds up.
        double lots = 0, riskUsd = 0;
        if (stopOk) {
            var spec = costs.spec(code);
            double q2u = costs.quoteToUsd(code, ref);
            double riskPerLot = r * spec.contractSize() * q2u + 2 * costs.commissionUsdPerSidePerLot(code);
            double raw = book.equity() * cfg.riskPct() / 100 / riskPerLot;
            lots = Math.floor(raw / spec.volumeStep() + 1e-9) * spec.volumeStep();
            lots = Math.round(lots / spec.volumeStep()) * spec.volumeStep(); // clean binary noise
            riskUsd = lots * riskPerLot;
        }
        double minVol = stopOk ? costs.spec(code).minVolume() : 0;
        checks.add(new Check(RejectionReason.SIZE_BELOW_MIN, stopOk && lots >= minVol - 1e-9, lots, minVol));

        RejectionReason first = checks.stream().filter(k -> !k.passed()).map(Check::reason).findFirst().orElse(null);
        // 15. STRATEGY_NOT_LIVE: recorded, never a rejection; it only stops delivery.
        checks.add(new Check(RejectionReason.STRATEGY_NOT_LIVE, c.strategyLive(), 0, 0));
        Sized sized = new Sized(c, lots, riskUsd, costR, r);
        return new Decision(first, List.copyOf(checks), sized, first == null ? new RiskApproved<>(sized, c.strategyLive()) : null);
    }

    private static java.util.Set<String> currencies(Candidate c) {
        return NewsCalendar.currencies(c.symbol().base(), c.symbol().quote());
    }

    /** Per currency (or cluster, e.g. METALS), signed count: + for each signal long that leg, - for each short. */
    static Map<String, Integer> legs(List<Exposure> open) {
        Map<String, Integer> out = new HashMap<>();
        for (Exposure e : open) {
            String base = e.symbol().cluster() != null ? e.symbol().cluster() : e.symbol().base();
            out.merge(base + (e.direction() == Direction.LONG ? "+" : "-"), 1, Integer::sum);
            out.merge(e.symbol().quote() + (e.direction() == Direction.LONG ? "-" : "+"), 1, Integer::sum);
        }
        return out;
    }

    /** Net USD count: +1 per short-USD leg, -1 per long-USD leg. */
    static int usdNet(List<Exposure> open) {
        int n = 0;
        for (Exposure e : open) {
            int s = e.direction().sign();
            if (e.symbol().quote().equals("USD")) n += s;        // long EURUSD = short USD -> +1
            else if (e.symbol().base().equals("USD")) n -= s;    // long USDJPY = long USD -> -1
        }
        return n;
    }
}
