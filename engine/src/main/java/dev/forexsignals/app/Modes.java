package dev.forexsignals.app;

import dev.forexsignals.config.AppConfig;
import dev.forexsignals.config.ConfigException;
import dev.forexsignals.feed.FeedAdapter;
import dev.forexsignals.feed.FeedHealth;
import dev.forexsignals.feed.FeedListener;
import dev.forexsignals.feed.SymbolSpec;
import dev.forexsignals.feed.SymbolVerifier;
import dev.forexsignals.feed.SyntheticFeed;
import dev.forexsignals.feed.ctrader.CTraderAdapter;
import dev.forexsignals.feed.deriv.DerivAdapter;
import dev.forexsignals.engine.MarketPipeline;
import dev.forexsignals.history.GapReport;
import dev.forexsignals.history.HistorySource;
import dev.forexsignals.history.M1Importer;
import dev.forexsignals.history.Pacer;
import dev.forexsignals.history.SpotCheck;
import dev.forexsignals.history.SpreadStats;
import dev.forexsignals.history.TrendbarComparator;
import dev.forexsignals.market.Candle;
import dev.forexsignals.market.PriceBasis;
import dev.forexsignals.market.SymbolCatalog;
import dev.forexsignals.market.SymbolCatalog.SymbolDef;
import dev.forexsignals.market.Tick;
import dev.forexsignals.market.Timeframe;
import dev.forexsignals.store.AsyncWriter;
import dev.forexsignals.store.Database;
import dev.forexsignals.store.MarketStore;
import dev.forexsignals.time.SimulatedClock;
import dev.forexsignals.time.WallClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The five run modes. Each is one linear method: read options, build the same components, run, report.
 * Exit codes: 0 ok, 1 failure, 3 replay check found unexplained mismatches.
 */
final class Modes {

    private static final Logger log = LoggerFactory.getLogger(Modes.class);

    private Modes() {}

    /** Shared startup: config, symbol catalogue, database (migrated, partitions around now). */
    record Context(AppConfig cfg, SymbolCatalog catalog, Database db, MarketStore store) implements AutoCloseable {
        @Override public void close() { db.close(); }
    }

    static Context context(AppConfig cfg) {
        SymbolCatalog catalog = SymbolCatalog.load(cfg.symbolsFile());
        Database db = Database.connect(cfg.database());
        db.migrate();
        db.ensurePartitionsAroundNow(Instant.now());
        return new Context(cfg, catalog, db, new MarketStore(db));
    }

    static String feedName(CliArgs args, AppConfig cfg) { return args.opt("feed").orElse(cfg.primaryFeed()); }

    /** Builds the adapter for a feed. cTrader without credentials is reported as "1b: pending". */
    static FeedAdapter adapter(String feed, AppConfig cfg, CliArgs args) {
        return switch (feed) {
            case "synthetic" -> new SyntheticFeed(Long.parseLong(args.opt("seed").orElse("1")));
            case "deriv" -> new DerivAdapter(cfg.deriv().endpoint(), cfg.deriv().appId());
            case "ctrader" -> {
                AppConfig.CTrader ct = cfg.ctrader();
                if (!ct.tokensPresent()) {
                    throw new ConfigException("cTrader feed is '1b: pending': clientId, clientSecret, accessToken and accountNumber "
                            + "are not all set in " + cfg.file() + " (see docs/SETUP-ACCOUNTS.md §3).");
                }
                yield new CTraderAdapter(ct, new CTraderAdapter.TokenStore() {
                    @Override public String accessToken() { return cfg.ctrader().accessToken().value(); }
                    @Override public String refreshToken() { return cfg.ctrader().refreshToken().value(); }
                    @Override public void save(String access, String refresh) {
                        cfg.updateSecrets(Map.of("config/ctrader/accessToken", access, "config/ctrader/refreshToken", refresh));
                    }
                });
            }
            default -> throw new ConfigException("unknown feed '" + feed + "' (use deriv, ctrader or synthetic)");
        };
    }

    static HistorySource history(String feed, AppConfig cfg, CliArgs args) {
        FeedAdapter a = adapter(feed, cfg, args);
        if (a instanceof HistorySource h) return h;
        a.close();
        throw new ConfigException("feed '" + feed + "' has no history API (use deriv or ctrader)");
    }

    static Path reportDir(CliArgs args) { return Path.of(args.opt("report").orElse("data/reports")); }

    // ======================================================================================== live

    /**
     * Live: connect, verify symbols, stream ticks through the shared MarketPipeline on one engine-loop
     * thread; ticks, candles and feed events are written asynchronously. Spread stats are recomputed
     * hourly over the last 7 days. Runs until stopped (or for --duration=PT10M).
     */
    private static final java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CountDownLatch> drainedRef =
            new java.util.concurrent.atomic.AtomicReference<>();

    static int live(CliArgs args, Context ctx) throws Exception {
        String feed = feedName(args, ctx.cfg());
        AppConfig.Health hc = ctx.cfg().health();
        AppConfig.Recording rc = ctx.cfg().recording();
        try (FeedAdapter adapter = adapter(feed, ctx.cfg(), args)) {
            List<SymbolDef> wanted = ctx.catalog().onFeed(feed, args.opt("symbols"));
            Map<String, SymbolSpec> specs = adapter.loadSymbols(wanted);
            List<SymbolDef> symbols = SymbolVerifier.verify(feed, wanted, specs, ctx.store(), Instant.now());
            log.info("live: feed {} ({} basis), symbols {}", feed, adapter.priceBasis(), symbols.stream().map(SymbolDef::code).toList());

            Duration flush = Duration.ofMillis(rc.flushMs());
            try (AsyncWriter<Tick> ticks = new AsyncWriter<>("ticks", rc.batchSize(), flush, ctx.store()::insertTicks);
                 AsyncWriter<Candle> candles = new AsyncWriter<>("candles", 500, flush, batch -> writeCandles(ctx.store(), batch));
                 AsyncWriter<Runnable> events = new AsyncWriter<>("events", 50, flush, batch -> batch.forEach(Modes::runQuietly))) {

                Map<String, SymbolDef> bySymbol = new LinkedHashMap<>();
                symbols.forEach(d -> bySymbol.put(d.code(), d));
                WallClock clock = new WallClock();
                FeedHealth health = new FeedHealth(feed, bySymbol, Duration.ofSeconds(hc.staleSecFx()), Duration.ofSeconds(hc.staleSecMetal()),
                        clock.now(), (ts, f, sym, kind, detail) -> events.add(() -> ctx.store().feedEvent(ts, f, sym, kind, detail)));
                MarketPipeline pipeline = new MarketPipeline(feed, adapter.priceBasis(), Duration.ofSeconds(ctx.cfg().candleGraceSec()),
                        candles::add,
                        (t, boundary) -> events.add(() -> ctx.store().feedEvent(t.recvTs(), feed, t.symbol(), "LATE_TICK",
                                Map.of("tickTs", t.ts().toString(), "boundary", String.valueOf(boundary)))),
                        ticks::add, health);

                // Engine loop: the only thread that touches the pipeline. Adapter threads only enqueue.
                LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
                AtomicBoolean stop = new AtomicBoolean();
                adapter.start(symbols, specs, new FeedListener() {
                    @Override public void onTick(Tick tick) { queue.add(tick); }
                    @Override public void onStatus(Status status, String detail) { queue.add(new Object[]{status, detail}); }
                });
                Thread spreadJob = Thread.ofVirtual().name("spread-stats").start(() -> {
                    while (!stop.get()) {
                        try {
                            Thread.sleep(Duration.ofHours(1));
                            Instant to = Instant.now();
                            // Keep monthly partitions ahead of now, so a long-running live process never hits
                            // "no partition for row" (there is deliberately no DEFAULT partition).
                            ctx.db().ensurePartitionsAroundNow(to);
                            for (SymbolDef d : symbols) SpreadStats.compute(ctx.store(), feed, d, to.minus(Duration.ofDays(7)), to);
                        } catch (InterruptedException e) {
                            return;
                        } catch (RuntimeException e) {
                            log.warn("spread stats failed: {}", e.toString());
                        }
                    }
                });
                // SIGTERM / Ctrl-C: stop the loop, then wait until the writers have drained (the JVM halts as soon
                // as all hooks return, so the hook must wait for the try-with-resources below to finish).
                java.util.concurrent.CountDownLatch drained = new java.util.concurrent.CountDownLatch(1);
                drainedRef.set(drained);
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    stop.set(true);
                    try { drained.await(30, TimeUnit.SECONDS); } catch (InterruptedException ignored) { /* exiting */ }
                }));
                Instant until = args.opt("duration").map(d -> Instant.now().plus(Duration.parse(d))).orElse(Instant.MAX);
                Instant nextTimeStep = Instant.EPOCH;
                while (!stop.get() && Instant.now().isBefore(until)) {
                    Object ev = queue.poll(250, TimeUnit.MILLISECONDS);
                    if (ev instanceof Tick t) pipeline.onTick(t);
                    else if (ev instanceof Object[] st) {
                        // Any connect/disconnect means ticks may be missing around now: bars already forming are incomplete.
                        pipeline.markIncompleteBefore(clock.now());
                        health.onStatus((FeedListener.Status) st[0], (String) st[1], clock.now());
                    }
                    if (!clock.now().isBefore(nextTimeStep)) {
                        pipeline.onTime(clock.now());
                        nextTimeStep = clock.now().plusMillis(500);
                    }
                }
                // No finishAt(): a forming bar is incomplete and must not be stored as closed.
                spreadJob.interrupt();
                log.info("live: stopped after {} ticks; {} incomplete bar(s) around connects/disconnects not stored",
                        pipeline.ticks(), pipeline.incompleteDropped());
            } // writers closed here: everything queued is written
            log.info("live: writers drained");
        } finally {
            java.util.concurrent.CountDownLatch d = drainedRef.get();
            if (d != null) d.countDown();
        }
        return 0;
    }

    /** One failing feed-event insert must not drop the rest of its batch. */
    static void runQuietly(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            log.error("feed event write failed: {}", e.toString());
        }
    }

    static void writeCandles(MarketStore store, List<Candle> batch) {
        List<Candle> m1 = new ArrayList<>(), derived = new ArrayList<>();
        for (Candle c : batch) (c.timeframe() == Timeframe.M1 ? m1 : derived).add(c);
        store.insertM1(m1);
        store.upsertDerived(derived);
    }

    // ======================================================================================== replay

    /**
     * Replay: ticks for [from, to) from the database (--source=db, recorded live) or straight from the feed's
     * tick history (--source=feed) go through the same MarketPipeline with a simulated clock. With
     * --compare-m5, the built M5 candles are compared with the feed's own M5 candles (cTrader trendbars) and
     * a mismatch report is written. Exit code 3 if any mismatch is unexplained.
     */
    static int replay(CliArgs args, Context ctx) throws Exception {
        String feed = feedName(args, ctx.cfg());
        Instant from = Instant.parse(args.opt("from").orElseThrow(() -> new ConfigException("replay needs --from=<ISO instant>")));
        Instant to = Instant.parse(args.opt("to").orElseThrow(() -> new ConfigException("replay needs --to=<ISO instant>")));
        String source = args.opt("source").orElse("db");
        boolean compare = args.flag("compare-m5");
        boolean writeCandles = args.flag("write-candles");
        List<SymbolDef> symbols = ctx.catalog().onFeed(feed, args.opt("symbols"));

        HistorySource hist = (compare || source.equals("feed")) ? history(feed, ctx.cfg(), args) : null;
        PriceBasis basis = hist != null ? hist.priceBasis() : adapterBasis(feed);
        try {
            if (hist instanceof FeedAdapter fa) {
                // Symbol lookup also resolves broker ids (cTrader) and doubles as the startup symbol check.
                symbols = SymbolVerifier.verify(feed, symbols, fa.loadSymbols(symbols), ctx.store(), Instant.now());
            }
            Pacer pacer = Pacer.realTime(Math.min(ctx.cfg().importSettings().maxReqPerSec(), hist == null ? 4.0 : hist.maxRequestsPerSecond()));
            if (hist != null) hist.usePacer(pacer);

            // 1. Collect the input ticks in replay order.
            List<Tick> input = new ArrayList<>();
            if (source.equals("db")) {
                ctx.store().streamTicks(feed, symbols.stream().map(SymbolDef::code).toList(), from, to, input::add);
            } else if (source.equals("feed")) {
                for (SymbolDef d : symbols) {
                    Duration span = hist.maxSpan(null);
                    for (Instant a = from; a.isBefore(to); a = a.plus(span)) {
                        Instant b = a.plus(span).isBefore(to) ? a.plus(span) : to;
                        final Instant fa = a, fb = b;
                        input.addAll(fetchWithRetry(pacer, ctx.cfg().importSettings(), () -> hist.fetchTicks(d, fa, fb)));
                    }
                    log.info("replay: fetched ticks for {} ({} so far)", d.code(), input.size());
                }
                input.sort(Comparator.comparing(Tick::ts).thenComparing(Tick::symbol).thenComparingLong(Tick::seq));
            } else {
                throw new ConfigException("--source must be db or feed");
            }
            log.info("replay: {} ticks for {} symbol(s), {} .. {} (source {}, basis {})", input.size(), symbols.size(), from, to, source, basis);

            // 2. Same pipeline as live, driven by a simulated clock.
            Map<String, List<Candle>> builtM5 = new HashMap<>();
            Map<Timeframe, Integer> counts = new LinkedHashMap<>();
            List<Candle> toWrite = new ArrayList<>();
            SimulatedClock clock = new SimulatedClock(from);
            MarketPipeline pipeline = new MarketPipeline(feed, basis, Duration.ofSeconds(ctx.cfg().candleGraceSec()), c -> {
                counts.merge(c.timeframe(), 1, Integer::sum);
                if (c.timeframe() == Timeframe.M5) builtM5.computeIfAbsent(c.symbol(), k -> new ArrayList<>()).add(c);
                if (writeCandles) toWrite.add(c);
            }, null, null, null);
            pipeline.markIncompleteBefore(from); // bars that began before the window are partial
            for (Tick t : input) {
                clock.advanceTo(t.ts());
                pipeline.onTime(clock.now());
                pipeline.onTick(t);
            }
            clock.advanceTo(to);
            pipeline.onTime(clock.now());
            pipeline.finishAt(to);
            if (writeCandles) writeCandles(ctx.store(), toWrite);
            Map<String, Long> late = new LinkedHashMap<>();
            symbols.forEach(d -> late.put(d.code(), pipeline.lateTicks(d.code())));
            log.info("replay: built candles {} late ticks {} incomplete bars dropped at the window start {}", counts, late, pipeline.incompleteDropped());

            // 3. Optional check against the feed's own M5 candles.
            if (!compare) return 0;
            List<TrendbarComparator.Report> reports = new ArrayList<>();
            for (SymbolDef d : symbols) {
                List<Candle> ref = new ArrayList<>();
                Duration span = hist.maxSpan(Timeframe.M5);
                for (Instant a = from; a.isBefore(to); a = a.plus(span)) {
                    Instant b = a.plus(span).isBefore(to) ? a.plus(span) : to;
                    final Instant fa = a, fb = b;
                    ref.addAll(fetchWithRetry(pacer, ctx.cfg().importSettings(), () -> hist.fetchCandles(d, Timeframe.M5, fa, fb)));
                }
                reports.add(TrendbarComparator.compare(feed, d.code(), "M5", from, to, builtM5.getOrDefault(d.code(), List.of()), ref, d.pointSize()));
            }
            String note = "Input: " + source + " ticks, price basis " + basis + ". Reference: the feed's own M5 candles"
                    + (feed.equals("ctrader") ? " (cTrader trendbars, bid)." : " (Deriv ticks_history candles, quote). Stand-in until cTrader (1b) is approved.");
            Path out = TrendbarComparator.write(reportDir(args), Instant.now().truncatedTo(ChronoUnit.SECONDS), reports, note);
            long unexplained = reports.stream().mapToLong(TrendbarComparator.Report::unexplained).sum();
            log.info("replay M5 check: {} report(s), unexplained mismatches {}, written to {}", reports.size(), unexplained, out);
            for (TrendbarComparator.Report r : reports) {
                log.info("  {} built={} reference={} matched={} diffs={} unexplained={}", r.symbol(), r.built(), r.reference(), r.matched(),
                        r.diffs().size(), r.unexplained());
            }
            return unexplained == 0 ? 0 : 3;
        } finally {
            if (hist != null) hist.close();
        }
    }

    /** One paced history request with the importer's retry policy (backoff with jitter on rate limits and I/O errors). */
    static <T> List<T> fetchWithRetry(Pacer pacer, AppConfig.Import ic, java.util.concurrent.Callable<List<T>> call) throws Exception {
        dev.forexsignals.feed.Backoff backoff = new dev.forexsignals.feed.Backoff(Duration.ofSeconds(ic.backoffMaxSec()));
        for (int attempt = 1; ; attempt++) {
            // Pacing happens inside the source, per network request (see HistorySource.usePacer).
            try {
                return call.call();
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                if (attempt >= ic.maxAttempts()) throw e;
                Duration wait = backoff.next();
                log.warn("history request failed (attempt {}): {}; retrying in {} ms", attempt, e.toString(), wait.toMillis());
                Thread.sleep(wait);
            }
        }
    }

    private static PriceBasis adapterBasis(String feed) {
        return feed.equals("deriv") ? PriceBasis.QUOTE : PriceBasis.BID;
    }

    // ======================================================================================== import

    static int importHistory(CliArgs args, Context ctx) throws Exception {
        String feed = feedName(args, ctx.cfg());
        AppConfig.Import ic = ctx.cfg().importSettings();
        int years = Integer.parseInt(args.opt("years").orElse(String.valueOf(ic.years())));
        Instant to = args.opt("to").map(Instant::parse).orElse(Instant.now().truncatedTo(ChronoUnit.DAYS));
        Instant from = args.opt("from").map(Instant::parse).orElse(to.atZone(java.time.ZoneOffset.UTC).minusYears(years).toInstant());
        if (feed.equals("deriv") && from.isBefore(to.minus(Duration.ofDays(365)))) {
            log.warn("Deriv M1 history only reaches about one year back (measured 2026-11-19); older chunks would come back empty. "
                    + "The 3-year import needs cTrader (1b). Importing what Deriv has.");
        }
        try (HistorySource hist = history(feed, ctx.cfg(), args)) {
            List<SymbolDef> symbols = ctx.catalog().onFeed(feed, args.opt("symbols"));
            if (hist instanceof FeedAdapter fa) symbols = SymbolVerifier.verify(feed, symbols, fa.loadSymbols(symbols), ctx.store(), Instant.now());
            double rate = Math.min(ic.maxReqPerSec(), hist.maxRequestsPerSecond());
            M1Importer importer = new M1Importer(hist, ctx.store(), Pacer.realTime(rate),
                    new M1Importer.Settings(rate, ic.maxAttempts(), Duration.ofSeconds(ic.backoffMaxSec())), d -> Thread.sleep(d));
            Thread hook = new Thread(importer::requestStop);
            Runtime.getRuntime().addShutdownHook(hook);
            log.info("import: feed {} {} .. {} at <= {} req/s for {}", feed, from, to, rate, symbols.stream().map(SymbolDef::code).toList());
            M1Importer.Result r = importer.run(symbols, from, to);
            log.info("import finished: chunks done {} failed {} rows inserted {} requests {}", r.chunksDone(), r.chunksFailed(), r.rowsInserted(), r.requests());
            return r.chunksFailed() == 0 ? 0 : 1;
        }
    }

    // ======================================================================================== reports

    static int gapReport(CliArgs args, Context ctx) throws Exception {
        String feed = feedName(args, ctx.cfg());
        int listMin = Integer.parseInt(args.opt("list-min").orElse("5"));
        Instant runAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        List<GapReport.SymbolResult> results = new ArrayList<>();
        for (SymbolDef d0 : ctx.catalog().onFeed(feed, args.opt("symbols"))) {
            if (ctx.store().countM1(feed, d0.code()) == 0) continue;
            // Use the broker's own schedule from the stored spec when there is one (cTrader); else the default profile.
            SymbolDef d = ctx.store().latestTradingHours(feed, d0.code()).flatMap(dev.forexsignals.market.TradingSchedule::fromSpec)
                    .map(d0::withHours).orElse(d0);
            log.info("gap report {} {}: hours from {}", feed, d.code(), d.hours() instanceof dev.forexsignals.market.TradingSchedule ? "broker spec" : "default profile " + d.hours());
            results.add(GapReport.run(ctx.store(), feed, d, runAt, listMin));
        }
        Path out = GapReport.write(reportDir(args), feed, runAt, results, listMin);
        log.info("gap report for {} symbol(s) written to {}", results.size(), out);
        return 0;
    }

    /** Phase 2 spread model: sample one tick-history week per quarter for the signal symbols (default). */
    static int spreadSample(CliArgs args, Context ctx) throws Exception {
        String feed = feedName(args, ctx.cfg());
        try (HistorySource hist = history(feed, ctx.cfg(), args)) {
            List<SymbolDef> symbols = ctx.catalog().onFeed(feed, args.opt("symbols")).stream()
                    .filter(d -> args.opt("symbols").isPresent() || d.role() == SymbolCatalog.Role.SIGNAL).toList();
            if (hist instanceof FeedAdapter fa) symbols = SymbolVerifier.verify(feed, symbols, fa.loadSymbols(symbols), ctx.store(), Instant.now());
            hist.usePacer(Pacer.realTime(Math.min(ctx.cfg().importSettings().maxReqPerSec(), hist.maxRequestsPerSecond())));
            var weeks = dev.forexsignals.history.SpreadSampler.weeks(2024, Instant.now());
            log.info("spread sample: {} week(s) {} for {}", weeks.size(), weeks.stream().map(w -> w.label() + "@" + w.from()).toList(),
                    symbols.stream().map(SymbolDef::code).toList());
            dev.forexsignals.history.SpreadSampler.run(hist, ctx.store(), symbols, weeks);
        }
        return 0;
    }

    static int spotCheck(CliArgs args, Context ctx) throws Exception {
        String feed = feedName(args, ctx.cfg());
        int per = Integer.parseInt(args.opt("per-tf").orElse("20"));
        long seed = Long.parseLong(args.opt("seed").orElse("1"));
        Map<String, List<Candle>> picked = new LinkedHashMap<>();
        Map<String, Integer> digits = new HashMap<>();
        for (SymbolDef d : ctx.catalog().onFeed(feed, args.opt("symbols"))) {
            Optional<Instant[]> range = ctx.store().m1Range(feed, d.code());
            if (range.isEmpty()) continue;
            picked.put(d.code(), SpotCheck.pick(ctx.store(), feed, adapterBasis(feed), d, range.get()[0], range.get()[1].plusSeconds(60), per, seed));
            digits.put(d.code(), d.digits());
        }
        Path out = SpotCheck.write(reportDir(args), feed, Instant.now().truncatedTo(ChronoUnit.SECONDS), picked, digits);
        log.info("spot-check for {} symbol(s) written to {}", picked.size(), out);
        return 0;
    }
}
