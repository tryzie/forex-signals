package dev.forexsignals.feed.deriv;

import com.fasterxml.jackson.databind.JsonNode;
import dev.forexsignals.feed.Backoff;
import dev.forexsignals.feed.FeedAdapter;
import dev.forexsignals.feed.FeedListener;
import dev.forexsignals.feed.SymbolSpec;
import dev.forexsignals.history.HistorySource;
import dev.forexsignals.market.Candle;
import dev.forexsignals.market.PriceBasis;
import dev.forexsignals.market.SymbolCatalog.SymbolDef;
import dev.forexsignals.market.Tick;
import dev.forexsignals.market.Timeframe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deriv fallback feed (PLAN §1), new API, public market-data endpoint, no token.
 *
 * Live: subscribes to "ticks" for each symbol; each tick carries bid, ask and Deriv's mid "quote".
 * History: "ticks_history" (style ticks or candles), which Deriv serves as quote-only. Candles for this
 * feed are therefore built on the QUOTE basis. Deriv prices are Deriv's own quotes: results are tagged
 * "deriv" and never pooled with cTrader.
 *
 * Measured on 2026-11-19: M1 candles go back about one year. Beyond that Deriv silently returns recent
 * bars instead of an error, so every history answer is filtered to the requested window and anything
 * outside is dropped (and reported).
 */
public final class DerivAdapter implements FeedAdapter, HistorySource {

    private static final Logger log = LoggerFactory.getLogger(DerivAdapter.class);
    public static final String FEED = "deriv";
    /** One request covers at most this many candles or ticks (Deriv's maximum count is 5000). */
    static final int MAX_COUNT = 5000;

    private final String endpoint, appId;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong seq = new AtomicLong();
    private volatile DerivConnection conn;
    private Thread supervisor;

    public DerivAdapter(String endpoint, String appId) {
        this.endpoint = endpoint;
        this.appId = appId;
    }

    @Override public String name() { return FEED; }

    @Override public String feed() { return FEED; }

    @Override public PriceBasis priceBasis() { return PriceBasis.QUOTE; }

    /** Deriv allows about 220 calls a minute: stay at 3 per second. */
    @Override
    public double maxRequestsPerSecond() { return 3.0; }

    private synchronized DerivConnection historyConnection() throws Exception {
        if (conn == null || !conn.isOpen()) {
            conn = DerivConnection.open(endpoint, appId, msg -> {}, why -> log.info("deriv history connection closed: {}", why));
        }
        return conn;
    }

    // ------------------------------------------------------------------------------------ symbols

    @Override
    public Map<String, SymbolSpec> loadSymbols(List<SymbolDef> wanted) throws Exception {
        JsonNode resp = historyConnection().request(Map.of("active_symbols", "brief"), Duration.ofSeconds(30));
        return parseActiveSymbols(resp, wanted);
    }

    /** active_symbols answer -> specs for the wanted symbols present on Deriv. */
    static Map<String, SymbolSpec> parseActiveSymbols(JsonNode resp, List<SymbolDef> wanted) {
        Map<String, JsonNode> byBroker = new HashMap<>();
        for (JsonNode s : resp.path("active_symbols")) {
            String name = s.has("underlying_symbol") ? s.path("underlying_symbol").asText() : s.path("symbol").asText();
            byBroker.put(name, s);
        }
        Map<String, SymbolSpec> out = new LinkedHashMap<>();
        for (SymbolDef d : wanted) {
            String broker = d.on(FEED).map(f -> f.brokerSymbol()).orElse(null);
            JsonNode s = broker == null ? null : byBroker.get(broker);
            if (s == null) continue;
            double pip = s.path("pip_size").asDouble(Double.NaN);
            Integer digits = Double.isNaN(pip) || pip <= 0 ? null : (int) Math.round(-Math.log10(pip));
            Map<String, Object> raw = new LinkedHashMap<>();
            s.fields().forEachRemaining(e -> { if (!e.getKey().equals("trade_count")) raw.put(e.getKey(), e.getValue().asText()); });
            // Deriv lists options underlyings: lot size, volume limits and commission do not apply and stay null.
            out.put(d.code(), new SymbolSpec(d.code(), FEED, broker, null, digits, null, null, null, null, null,
                    null, null, null, null, raw));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------ live

    @Override
    public void start(List<SymbolDef> symbols, Map<String, SymbolSpec> specs, FeedListener listener) {
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("already started");
        Map<String, String> codeByBroker = new HashMap<>();
        for (SymbolDef d : symbols) codeByBroker.put(d.on(FEED).orElseThrow().brokerSymbol(), d.code());

        // Supervisor: connect, subscribe, wait until the socket dies, back off, repeat.
        supervisor = Thread.ofVirtual().name("deriv-live").start(() -> {
            Backoff backoff = new Backoff(Duration.ofSeconds(60));
            while (running.get()) {
                AtomicBoolean dead = new AtomicBoolean();
                Object lock = new Object();
                try (DerivConnection live = DerivConnection.open(endpoint, appId,
                        msg -> {
                            Tick t = parseTick(msg, codeByBroker, Instant.now(), seq.incrementAndGet());
                            if (t != null) listener.onTick(t);
                        },
                        why -> {
                            dead.set(true);
                            synchronized (lock) { lock.notifyAll(); }
                            listener.onStatus(FeedListener.Status.DISCONNECTED, why);
                        })) {
                    for (String broker : codeByBroker.keySet()) {
                        live.request(Map.of("ticks", broker, "subscribe", 1), Duration.ofSeconds(30));
                    }
                    listener.onStatus(FeedListener.Status.CONNECTED, "subscribed " + codeByBroker.keySet());
                    backoff.reset();
                    synchronized (lock) {
                        while (running.get() && !dead.get()) lock.wait(1000);
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    if (!dead.get()) listener.onStatus(FeedListener.Status.DISCONNECTED, "connect/subscribe failed: " + e.getMessage());
                }
                if (!running.get()) return;
                Duration wait = backoff.next();
                log.warn("deriv: reconnecting in {} ms", wait.toMillis());
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
    }

    /** A "tick" stream message -> Tick (null for anything else). Deriv epochs are whole seconds. */
    static Tick parseTick(JsonNode msg, Map<String, String> codeByBroker, Instant recv, long seq) {
        if (!"tick".equals(msg.path("msg_type").asText())) return null;
        JsonNode t = msg.path("tick");
        String code = codeByBroker.get(t.path("symbol").asText());
        if (code == null || !t.has("epoch")) return null;
        return new Tick(FEED, code, Instant.ofEpochSecond(t.path("epoch").asLong()),
                t.path("bid").asDouble(Double.NaN), t.path("ask").asDouble(Double.NaN), t.path("quote").asDouble(Double.NaN), recv, seq);
    }

    // ------------------------------------------------------------------------------------ history

    @Override
    public Duration maxSpan(Timeframe tf) {
        // Candles: <= 5000 per request. Ticks: one hour (about 1 tick per second at most -> < 5000).
        return tf == null ? Duration.ofHours(1) : tf.duration().multipliedBy(MAX_COUNT - 10);
    }

    private JsonNode history(Map<String, Object> req) throws Exception {
        try {
            return historyConnection().request(req, Duration.ofSeconds(60));
        } catch (DerivConnection.DerivError e) {
            if (e.isRateLimit()) throw new RateLimitedException(e.getMessage());
            throw e;
        }
    }

    @Override
    public List<Candle> fetchM1(SymbolDef symbol, Instant from, Instant to) throws Exception {
        return fetchCandles(symbol, Timeframe.M1, from, to).stream()
                .map(c -> new Candle(c.feed(), c.symbol(), c.timeframe(), c.openTime(), c.closeTime(), c.open(), c.high(), c.low(), c.close(),
                        c.tickCount(), c.spreadMin(), c.spreadMed(), c.spreadMax(), Candle.Source.IMPORT))
                .toList();
    }

    @Override
    public List<Candle> fetchCandles(SymbolDef symbol, Timeframe tf, Instant from, Instant to) throws Exception {
        if (tf == Timeframe.H4 || tf == Timeframe.D1) {
            throw new IllegalArgumentException("Deriv candles are UTC-aligned; only M1..H1 match our bars, not " + tf);
        }
        long gran = tf.minutes() * 60L;
        long start = Math.floorDiv(from.getEpochSecond() + gran - 1, gran) * gran; // align up: Deriv starts its first bar AT start
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("ticks_history", symbol.on(FEED).orElseThrow().brokerSymbol());
        req.put("style", "candles");
        req.put("granularity", gran);
        req.put("start", start);
        req.put("end", to.getEpochSecond() - 1);
        req.put("count", MAX_COUNT);
        List<Candle> all = parseCandles(history(req), symbol.code(), tf);
        List<Candle> in = all.stream().filter(c -> !c.openTime().isBefore(from) && c.openTime().isBefore(to)).toList();
        if (!all.isEmpty() && in.isEmpty()) {
            log.warn("deriv {} {}: all {} returned bars are outside {} .. {} (beyond Deriv's history depth?)", symbol.code(), tf, all.size(), from, to);
        }
        return in;
    }

    static List<Candle> parseCandles(JsonNode resp, String code, Timeframe tf) {
        List<Candle> out = new ArrayList<>();
        for (JsonNode c : resp.path("candles")) {
            Instant open = Instant.ofEpochSecond(c.path("epoch").asLong());
            out.add(new Candle(FEED, code, tf, open, open.plus(tf.duration()), c.path("open").asDouble(), c.path("high").asDouble(),
                    c.path("low").asDouble(), c.path("close").asDouble(), 0, Double.NaN, Double.NaN, Double.NaN, Candle.Source.DERIVED));
        }
        return out;
    }

    @Override
    public List<Tick> fetchTicks(SymbolDef symbol, Instant from, Instant to) throws Exception {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("ticks_history", symbol.on(FEED).orElseThrow().brokerSymbol());
        req.put("style", "ticks");
        req.put("start", from.getEpochSecond());
        req.put("end", to.getEpochSecond() - 1);
        req.put("count", MAX_COUNT);
        List<Tick> ticks = parseHistoryTicks(history(req), symbol.code(), seq);
        if (ticks.size() >= MAX_COUNT) {
            // Possibly truncated: split the window and fetch both halves (rare: > 5000 ticks per hour).
            Instant mid = from.plus(Duration.between(from, to).dividedBy(2));
            List<Tick> out = new ArrayList<>(fetchTicks(symbol, from, mid));
            out.addAll(fetchTicks(symbol, mid, to));
            return out;
        }
        return ticks.stream().filter(t -> !t.ts().isBefore(from) && t.ts().isBefore(to)).toList();
    }

    /** ticks_history (style ticks) -> quote-only ticks (bid and ask unknown). */
    static List<Tick> parseHistoryTicks(JsonNode resp, String code, AtomicLong seq) {
        JsonNode prices = resp.path("history").path("prices"), times = resp.path("history").path("times");
        List<Tick> out = new ArrayList<>(prices.size());
        for (int i = 0; i < prices.size() && i < times.size(); i++) {
            Instant ts = Instant.ofEpochSecond(times.get(i).asLong());
            out.add(new Tick(FEED, code, ts, Double.NaN, Double.NaN, prices.get(i).asDouble(), ts, seq.incrementAndGet()));
        }
        return out;
    }

    @Override
    public void close() {
        running.set(false);
        if (supervisor != null) supervisor.interrupt();
        DerivConnection c = conn;
        if (c != null) c.close();
    }
}
