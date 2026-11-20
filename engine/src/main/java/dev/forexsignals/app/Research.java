package dev.forexsignals.app;

import dev.forexsignals.backtest.FillBias;
import dev.forexsignals.backtest.HypothesisRunner;
import dev.forexsignals.report.HypothesisReport;
import dev.forexsignals.backtest.M1Series;
import dev.forexsignals.backtest.Validator;
import dev.forexsignals.config.ConfigException;
import dev.forexsignals.history.SpreadSampler;
import dev.forexsignals.market.SymbolCatalog;
import dev.forexsignals.paper.CostModel;
import dev.forexsignals.paper.InstrumentSpec;
import dev.forexsignals.paper.SpreadModel;
import dev.forexsignals.report.BacktestReport;
import dev.forexsignals.risk.NewsCalendar;
import dev.forexsignals.risk.RiskGate;
import dev.forexsignals.store.MarketStore;
import dev.forexsignals.store.ResearchStore;
import dev.forexsignals.strategy.ParamsHash;
import dev.forexsignals.strategy.StrategyRegistry;
import dev.forexsignals.strategy.api.Strategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Phase 2 modes: freeze (record params hashes before any out-of-sample test) and validate (the full pipeline + report). */
final class Research {

    private static final Logger log = LoggerFactory.getLogger(Research.class);
    static final String FEED = "ctrader";

    private Research() {}

    static int freeze(CliArgs args, Modes.Context ctx) {
        ResearchStore rs = new ResearchStore(ctx.store());
        Instant now = Instant.now();
        for (Strategy s : StrategyRegistry.discover()) {
            String hash = ParamsHash.of(s.meta());
            var before = rs.latestFrozen(s.meta().id());
            rs.freeze(s.meta().id(), hash, MarketStore.json(s.meta().params()), now, args.opt("note").orElse("Phase 2 freeze"));
            var after = rs.latestFrozen(s.meta().id()).orElseThrow();
            log.info("freeze {} params hash {} -> frozen at {}{}", s.meta().id(), hash, after.frozenAt(),
                    before.isPresent() && before.get().hash().equals(hash) ? " (already frozen, unchanged)" : "");
        }
        return 0;
    }

    /** Costs from conf.xml <costs>: commission per side per lot by class, slippage in price units by class. */
    static CostModel.Settings costSettings(dev.forexsignals.config.AppConfig cfg) {
        Map<String, Double> comm = new LinkedHashMap<>(), slip = new LinkedHashMap<>();
        for (var e : cfg.children("costs", "commission")) {
            String v = e.getAttribute("perSidePerLot");
            if (v.isBlank()) throw new ConfigException("conf.xml costs: commission for class " + e.getAttribute("class") + " is empty");
            comm.put(e.getAttribute("class"), Double.parseDouble(v));
        }
        for (var e : cfg.children("costs", "slippage")) slip.put(e.getAttribute("symbolClass"), Double.parseDouble(e.getAttribute("price")));
        for (String k : List.of("FX", "XAU", "XAG")) if (!comm.containsKey(k)) throw new ConfigException("conf.xml costs: no commission for " + k);
        return new CostModel.Settings(comm, slip, 1.0, 1.0, Map.of());
    }

    /** Pre-registered USDX verification (docs/hyp/eurusd-confirmation.md §10.1). Exit 0 if clean, 4 if not. */
    static int usdxCheck(CliArgs args, Modes.Context ctx) throws Exception {
        MarketStore store = ctx.store();
        String sql = "SELECT digits, contract_size, min_volume, volume_step, trading_hours::text FROM symbol_spec WHERE feed = 'ctrader' "
                + "AND symbol = 'USDX' ORDER BY captured_at DESC LIMIT 1";
        dev.forexsignals.history.UsdxCheck.Spec spec;
        try (var c = store.db().connection(); var ps = c.prepareStatement(sql); var rs = ps.executeQuery()) {
            if (!rs.next()) throw new ConfigException("no stored cTrader spec for USDX");
            @SuppressWarnings("unchecked")
            Map<String, Object> th = new com.fasterxml.jackson.databind.ObjectMapper().readValue(rs.getString(5), Map.class);
            var sched = dev.forexsignals.market.TradingSchedule.fromSpec(th);
            String hours = sched.isPresent() ? sched.get().intervals().stream().map(i -> i[0] + "-" + i[1]).toList().toString() : "none";
            spec = new dev.forexsignals.history.UsdxCheck.Spec(rs.getInt(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4), hours,
                    store.scalarLong("SELECT count(*) FROM feed_event WHERE symbol = 'USDX' AND kind = 'SPEC_CHANGED'"));
        }
        // Reviewed schedule (seconds from Sunday 00:00 New York): Sun 18:00-Mon 16:59, Mon..Thu 20:00-next 16:59, Thu 20:00-Fri 16:55.
        String reviewed = "[64800-147540, 158400-233940, 244800-320340, 331200-406740, 417600-492900]";
        boolean matches = spec.digits() == 3 && spec.contractSize() == 100 && spec.minVolume() == 1 && spec.volumeStep() == 1 && spec.hours().equals(reviewed);
        Instant from = Instant.parse("2023-11-19T00:00:00Z"), to = Instant.parse("2026-11-19T00:00:00Z");
        var r = dev.forexsignals.history.UsdxCheck.run(store, FEED, from, to, spec, matches);
        Path out = dev.forexsignals.history.UsdxCheck.write(Modes.reportDir(args), Instant.now().truncatedTo(ChronoUnit.SECONDS), r);
        log.info("USDX verification: v1={} v2={} ({}) v3={} (syn {} eur {}) v4={} ({} jumps) -> clean={} ; report {}", r.v1(), r.v2(),
                String.format("%.4f", r.coverage()), r.v3(), String.format("%.3f", r.corrSyn()), String.format("%.3f", r.corrEurusd()), r.v4(),
                r.jumps().size(), r.clean(), out);
        return r.clean() ? 0 : 4;
    }

    static int validate(CliArgs args, Modes.Context ctx) throws Exception {
        MarketStore store = ctx.store();
        ResearchStore rs = new ResearchStore(store);
        SymbolCatalog cat = ctx.catalog();
        List<Strategy> all = StrategyRegistry.discover();
        List<String> names = List.of(args.opt("strategies").orElse("ema_pullback,donchian_breakout,rsi_mean_reversion").split(","));
        boolean diagnostic = args.flag("diagnostic");

        // Inputs: specs, spread samples, news calendar, costs, M1 data.
        Map<String, String[]> bq = new HashMap<>();
        cat.all().forEach(d -> bq.put(d.code(), new String[]{d.base(), d.quote()}));
        Map<String, InstrumentSpec> specs = rs.specs(FEED, bq);
        SpreadModel spreads = new SpreadModel(rs.spreadSamples(FEED));
        NewsCalendar news = NewsCalendar.load(Path.of(args.opt("news").orElse("config/news.csv")));
        CostModel.Settings costs = costSettings(ctx.cfg());
        List<String> symbols = cat.all().stream().filter(d -> d.role() == SymbolCatalog.Role.SIGNAL).map(SymbolCatalog.SymbolDef::code).toList();
        for (String s : symbols) {
            if (!specs.containsKey(s)) throw new ConfigException("no cTrader spec stored for " + s + " (run live or import once)");
            if (!spreads.covers(s)) throw new ConfigException("no spread samples for " + s + " (run --mode=spreadsample)");
        }
        Optional<Instant[]> range = store.m1Range(FEED, "EURUSD");
        if (range.isEmpty()) throw new ConfigException("no cTrader M1 history (run --mode=import)");
        Instant from = args.opt("from").map(Instant::parse).orElse(range.get()[0]);
        Instant to = args.opt("to").map(Instant::parse).orElse(range.get()[1].truncatedTo(ChronoUnit.DAYS));
        Map<String, M1Series> m1 = new LinkedHashMap<>();
        for (String s : symbols) {
            m1.put(s, M1Series.load(store, FEED, s, from.minus(Duration.ofDays(1)), to.plus(Duration.ofDays(1))));
            log.info("validate: loaded {} M1 bars of {}", m1.get(s).n, s);
        }

        // Fill-model bias on the sampled weeks (real ticks vs the M1 model); positive mean -> extra slippage.
        List<SpreadSampler.Week> weeks = SpreadSampler.weeks(2024, to);
        Map<String, FillBias.SymbolBias> bias = FillBias.measure(store, FEED, m1, spreads, weeks, symbols, Duration.ofSeconds(10));
        Map<String, Double> extra = new HashMap<>();
        bias.forEach((k, v) -> { if (v.extraSlippage() > 0) extra.put(k, v.extraSlippage()); });
        CostModel.Settings calibrated = costs.withExtraSlippage(extra);
        log.info("validate: fill bias {}", bias);

        Validator.Inputs in = new Validator.Inputs(FEED, from, to, m1, calibrated, specs, spreads, news, cat, RiskGate.Config.defaults(), 20261119L,
                Duration.ofSeconds(10));
        List<Validator.StrategyResult> results = new ArrayList<>();
        for (String name : names) {
            Validator.StrategyResult r = Validator.validate(in, StrategyRegistry.byName(all, name.strip()), rs, store, !diagnostic);
            results.add(r);
            // CLAUDE.md: a baseline that fails on costs gets its H1-entry variant before anything is concluded.
            if (!diagnostic && r.failedOnCosts() && !name.endsWith("_h1")) {
                String h1 = name.strip() + "_h1";
                if (all.stream().anyMatch(s -> s.meta().name().equals(h1)) && names.stream().noneMatch(n -> n.strip().equals(h1))) {
                    log.info("validate: {} failed on costs (gross > 0, net <= 0): running its H1 variant {}", name, h1);
                    results.add(Validator.validate(in, StrategyRegistry.byName(all, h1), rs, store));
                }
            }
        }
        Path out = BacktestReport.write(Modes.reportDir(args), new BacktestReport.Context(Instant.now().truncatedTo(ChronoUnit.SECONDS), FEED, from, to,
                calibrated, spreads, news, bias, weeks.stream().map(w -> w.label() + " " + w.from()).toList(),
                diagnostic ? BacktestReport.DIAGNOSTIC_LABEL : null), results);
        log.info("validate: report written to {}", out);
        results.forEach(r -> log.info("  {} -> {}", r.strategyId(), r.gate().verdict()));
        return 0;
    }

    // ------------------------------------------------------------------------------------ pre-registered hypothesis

    static final String HYPOTHESIS = "eurusd-confirmation";
    static final Path HYPOTHESIS_DOC = Path.of("docs/hyp/eurusd-confirmation.md");
    static final Instant DEV_FROM = Instant.parse("2023-11-19T00:00:00Z"), DEV_TO = Instant.parse("2025-11-19T00:00:00Z"),
            HOLDOUT_TO = Instant.parse("2026-11-19T00:00:00Z");
    static final List<String[]> HYPOTHESIS_PAIRS = List.of(new String[]{"eurusd_donchian_base", "eurusd_donchian_conf"},
            new String[]{"eurusd_ema_base", "eurusd_ema_conf"});

    /**
     * docs/hyp/eurusd-confirmation.md, one stage. Refuses to run unless all four variants' hashes are frozen, unchanged,
     * and written in the document's freeze record. DEVELOPMENT loads M1 only up to the development end (no holdout bar is
     * ever in memory). HOLDOUT needs a finished development run and --confirm-holdout-once=true, and is recorded before it
     * starts so it can never run twice.
     */
    static int hypothesis(CliArgs args, Modes.Context ctx) throws Exception {
        String stageName = args.opt("stage").orElseThrow(() -> new ConfigException("--stage=development|holdout is required")).toUpperCase();
        if (!stageName.equals("DEVELOPMENT") && !stageName.equals("HOLDOUT")) throw new ConfigException("--stage must be development or holdout");
        MarketStore store = ctx.store();
        ResearchStore rs = new ResearchStore(store);
        SymbolCatalog cat = ctx.catalog();

        // 1. Frozen parameters: registry hash == latest frozen hash, frozen before now, and recorded in the document.
        List<Strategy> all = StrategyRegistry.discover();
        String doc = java.nio.file.Files.readString(HYPOTHESIS_DOC);
        Map<String, String> frozen = new LinkedHashMap<>();
        List<Strategy[]> pairs = new ArrayList<>();
        Instant now = Instant.now();
        for (String[] p : HYPOTHESIS_PAIRS) {
            Strategy[] pair = {StrategyRegistry.byName(all, p[0]), StrategyRegistry.byName(all, p[1])};
            for (Strategy s : pair) {
                String hash = ParamsHash.of(s.meta());
                Optional<String> why = ResearchStore.oosWithheldReason(rs.latestFrozen(s.meta().id()), hash, now);
                if (why.isPresent()) throw new ConfigException(s.meta().id() + ": " + why.get() + "; refusing to run the hypothesis");
                if (!doc.contains(hash)) throw new ConfigException(s.meta().id() + ": params hash " + hash + " is not in the freeze record of " + HYPOTHESIS_DOC);
                frozen.put(s.meta().id(), hash);
            }
            pairs.add(pair);
        }
        boolean usdxVerified = cat.all().stream().anyMatch(d -> d.code().equals("USDX") && d.verified());
        if (!usdxVerified) throw new ConfigException("USDX is not verified (docs/hyp §10.1): the gate may not run");

        // 2. Stage guard.
        HypothesisRunner.Stage st;
        Instant loadFrom = DEV_FROM, loadTo;
        if (stageName.equals("DEVELOPMENT")) {
            st = new HypothesisRunner.Stage("DEVELOPMENT", DEV_FROM, DEV_TO);
            loadTo = DEV_TO;
        } else {
            if (!rs.hypothesisDevelopmentDone(HYPOTHESIS)) throw new ConfigException("holdout refused: no finished development run");
            if (!args.flag("confirm-holdout-once")) throw new ConfigException("holdout refused: pass --confirm-holdout-once=true (it can run only once)");
            st = new HypothesisRunner.Stage("HOLDOUT", DEV_TO, HOLDOUT_TO);
            loadTo = HOLDOUT_TO; // warm-up and open-trade state come from the development period; statistics only from the holdout
        }
        long runId = rs.hypothesisStart(HYPOTHESIS, st.name(), now);

        // 3. Inputs, as in Phase 2 validation; data strictly inside [loadFrom, loadTo).
        Map<String, String[]> bq = new HashMap<>();
        cat.all().forEach(d -> bq.put(d.code(), new String[]{d.base(), d.quote()}));
        Map<String, InstrumentSpec> specs = rs.specs(FEED, bq);
        SpreadModel spreads = new SpreadModel(rs.spreadSamples(FEED));
        NewsCalendar news = NewsCalendar.load(Path.of(args.opt("news").orElse("config/news.csv")));
        CostModel.Settings costs = costSettings(ctx.cfg());
        if (!specs.containsKey("EURUSD") || !spreads.covers("EURUSD")) throw new ConfigException("EURUSD spec or spread samples missing");
        Map<String, M1Series> m1 = new LinkedHashMap<>();
        for (String s : List.of("EURUSD", "USDX", "GBPUSD", "AUDUSD", "XAUUSD")) {
            m1.put(s, M1Series.load(store, FEED, s, loadFrom, loadTo));
            log.info("hypothesis: loaded {} M1 bars of {} [{}, {})", m1.get(s).n, s, loadFrom, loadTo);
        }
        List<SpreadSampler.Week> weeks = SpreadSampler.weeks(2024, loadTo).stream().filter(w -> !w.to().isAfter(loadTo)).toList();
        Map<String, FillBias.SymbolBias> bias = FillBias.measure(store, FEED, m1, spreads, weeks, List.of("EURUSD"), Duration.ofSeconds(10));
        Map<String, Double> extra = new HashMap<>();
        bias.forEach((k, v) -> { if (v.extraSlippage() > 0) extra.put(k, v.extraSlippage()); });
        CostModel.Settings calibrated = costs.withExtraSlippage(extra);
        log.info("hypothesis: fill bias {}", bias);
        Validator.Inputs in = new Validator.Inputs(FEED, loadFrom, loadTo, m1, calibrated, specs, spreads, news, cat, RiskGate.Config.defaults(), 20261119L,
                Duration.ofSeconds(10));

        // 4. Run and report.
        List<HypothesisRunner.Confirmation> cs = HypothesisRunner.run(in, store, pairs, st);
        String costsNote = "commission, slippage (session/news multipliers) plus measured fill bias " + extra + " from " + weeks.size()
                + " sampled weeks ending by " + loadTo + ", modelled spread, swap; constant 10,000 USD notional, kill switch recorded not halting";
        Path out = HypothesisReport.write(Modes.reportDir(args), new HypothesisReport.Context(now.truncatedTo(ChronoUnit.SECONDS), st, frozen, costsNote),
                cs, store, FEED);
        rs.hypothesisFinish(runId, Instant.now(), out.toString(), MarketStore.json(HypothesisRunner.summary(cs)));
        log.info("hypothesis {}: report written to {}", st.name(), out);
        return 0;
    }
}
