package dev.forexsignals.app;

import dev.forexsignals.backtest.FillBias;
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
}
