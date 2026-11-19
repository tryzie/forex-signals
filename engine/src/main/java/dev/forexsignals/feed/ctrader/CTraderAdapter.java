package dev.forexsignals.feed.ctrader;

import com.xtrader.protocol.openapi.v2.ProtoOAAccountAuthReq;
import com.xtrader.protocol.openapi.v2.ProtoOAApplicationAuthReq;
import com.xtrader.protocol.openapi.v2.ProtoOAGetAccountListByAccessTokenReq;
import com.xtrader.protocol.openapi.v2.ProtoOAGetAccountListByAccessTokenRes;
import com.xtrader.protocol.openapi.v2.ProtoOAGetTickDataReq;
import com.xtrader.protocol.openapi.v2.ProtoOAGetTickDataRes;
import com.xtrader.protocol.openapi.v2.ProtoOAGetTrendbarsReq;
import com.xtrader.protocol.openapi.v2.ProtoOAGetTrendbarsRes;
import com.xtrader.protocol.openapi.v2.ProtoOARefreshTokenReq;
import com.xtrader.protocol.openapi.v2.ProtoOARefreshTokenRes;
import com.xtrader.protocol.openapi.v2.ProtoOASpotEvent;
import com.xtrader.protocol.openapi.v2.ProtoOASubscribeSpotsReq;
import com.xtrader.protocol.openapi.v2.ProtoOASymbolByIdReq;
import com.xtrader.protocol.openapi.v2.ProtoOASymbolByIdRes;
import com.xtrader.protocol.openapi.v2.ProtoOASymbolsListReq;
import com.xtrader.protocol.openapi.v2.ProtoOASymbolsListRes;
import com.xtrader.protocol.openapi.v2.model.ProtoOAClientPermissionScope;
import com.xtrader.protocol.openapi.v2.model.ProtoOACtidTraderAccount;
import com.xtrader.protocol.openapi.v2.model.ProtoOALightSymbol;
import com.xtrader.protocol.openapi.v2.model.ProtoOAPayloadType;
import com.xtrader.protocol.openapi.v2.model.ProtoOAQuoteType;
import com.xtrader.protocol.openapi.v2.model.ProtoOASymbol;
import com.xtrader.protocol.openapi.v2.model.ProtoOATickData;
import com.xtrader.protocol.openapi.v2.model.ProtoOATrendbar;
import com.xtrader.protocol.openapi.v2.model.ProtoOATrendbarPeriod;
import com.xtrader.protocol.proto.commons.ProtoMessage;
import dev.forexsignals.config.AppConfig;
import dev.forexsignals.config.ConfigException;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * cTrader Open API feed on the Pepperstone demo account (primary feed; "1b: pending" until Spotware
 * approves the app). Coded against the vendored protos; unit tests cover framing and decoding.
 *
 * Read-only by construction (CLAUDE.md rule 1):
 *  - refuses to run unless the token's permission scope is SCOPE_VIEW (the "accounts" scope);
 *  - refuses live accounts: only a demo account matching conf.xml accountNumber is used;
 *  - sends only auth, symbol, spot-subscription, trendbar, tick-data and token-refresh requests.
 * Prices on the wire are integers in 1/100000 of a unit.
 */
public final class CTraderAdapter implements FeedAdapter, HistorySource {

    private static final Logger log = LoggerFactory.getLogger(CTraderAdapter.class);
    public static final String FEED = "ctrader";
    static final double PRICE_SCALE = 100_000.0;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final AppConfig.CTrader cfg;
    private final TokenStore tokens;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong seq = new AtomicLong();
    private final Map<String, Long> symbolIds = new ConcurrentHashMap<>();
    private volatile CTraderConnection conn;
    private volatile long ctidTraderAccountId;
    private Thread supervisor;

    /** Where refreshed tokens are persisted (encrypted conf.xml in production). */
    public interface TokenStore {
        String accessToken();
        String refreshToken();
        void save(String accessToken, String refreshToken);
    }

    public CTraderAdapter(AppConfig.CTrader cfg, TokenStore tokens) {
        this.cfg = cfg;
        this.tokens = tokens;
    }

    @Override public String name() { return FEED; }

    @Override public String feed() { return FEED; }

    @Override public PriceBasis priceBasis() { return PriceBasis.BID; }

    // ------------------------------------------------------------------------------------ connect + auth

    /** Opens a connection, authorises the application and the demo account. */
    private synchronized CTraderConnection connect(java.util.function.Consumer<ProtoMessage> events, java.util.function.Consumer<String> onClosed)
            throws Exception {
        CTraderConnection c = CTraderConnection.open(cfg.host(), cfg.port(), events, onClosed);
        try {
            c.request(ProtoOAPayloadType.PROTO_OA_APPLICATION_AUTH_REQ_VALUE, ProtoOAApplicationAuthReq.newBuilder()
                    .setClientId(cfg.clientId().value()).setClientSecret(cfg.clientSecret().value()).build(), TIMEOUT);
            ProtoOAGetAccountListByAccessTokenRes accounts;
            try {
                accounts = accountList(c);
            } catch (CTraderConnection.CTraderError e) {
                if (!e.isTokenProblem()) throw e;
                refreshToken(c);
                accounts = accountList(c);
            }
            ctidTraderAccountId = chooseDemoAccount(accounts, cfg.accountNumber());
            c.request(ProtoOAPayloadType.PROTO_OA_ACCOUNT_AUTH_REQ_VALUE, ProtoOAAccountAuthReq.newBuilder()
                    .setCtidTraderAccountId(ctidTraderAccountId).setAccessToken(tokens.accessToken()).build(), TIMEOUT);
            log.info("cTrader: authorised demo account {} (read-only scope)", cfg.accountNumber());
            return c;
        } catch (Exception e) {
            c.close();
            throw e;
        }
    }

    private ProtoOAGetAccountListByAccessTokenRes accountList(CTraderConnection c) throws Exception {
        ProtoMessage m = c.request(ProtoOAPayloadType.PROTO_OA_GET_ACCOUNTS_BY_ACCESS_TOKEN_REQ_VALUE,
                ProtoOAGetAccountListByAccessTokenReq.newBuilder().setAccessToken(tokens.accessToken()).build(), TIMEOUT);
        return ProtoOAGetAccountListByAccessTokenRes.parseFrom(m.getPayload());
    }

    /**
     * SIGNALS ONLY enforcement at the broker: the token must be view-only, and only the configured
     * DEMO account is accepted. Returns its ctidTraderAccountId.
     */
    static long chooseDemoAccount(ProtoOAGetAccountListByAccessTokenRes res, String accountNumber) {
        if (!res.hasPermissionScope() || res.getPermissionScope() != ProtoOAClientPermissionScope.SCOPE_VIEW) {
            throw new ConfigException("cTrader token scope is " + (res.hasPermissionScope() ? res.getPermissionScope() : "unknown")
                    + ". This app only runs with a read-only token: issue a new one with the 'accounts' scope (docs/SETUP-ACCOUNTS.md §3).");
        }
        if (accountNumber == null || accountNumber.isBlank()) {
            // Never guess the account: list what the token can see (account numbers are not secrets) and stop.
            StringBuilder sb = new StringBuilder();
            for (ProtoOACtidTraderAccount a : res.getCtidTraderAccountList()) {
                sb.append(sb.isEmpty() ? "" : ", ").append(a.getTraderLogin()).append(a.hasIsLive() && !a.getIsLive() ? " (demo" : " (LIVE")
                        .append(a.hasBrokerTitleShort() ? ", " + a.getBrokerTitleShort() : "").append(')');
            }
            throw new ConfigException("cTrader accountNumber is empty in conf.xml. Accounts this read-only token can see: "
                    + (sb.isEmpty() ? "none" : sb) + ". Set accountNumber to the DEMO one.");
        }
        for (ProtoOACtidTraderAccount a : res.getCtidTraderAccountList()) {
            if (String.valueOf(a.getTraderLogin()).equals(accountNumber)) {
                if (!a.hasIsLive() || a.getIsLive()) {
                    throw new ConfigException("cTrader account " + accountNumber + " is not marked as a demo account; refusing to use it.");
                }
                return a.getCtidTraderAccountId();
            }
        }
        throw new ConfigException("cTrader account " + accountNumber + " is not among the accounts this token can see. Check accountNumber in conf.xml.");
    }

    private void refreshToken(CTraderConnection c) throws Exception {
        ProtoMessage m = c.request(ProtoOAPayloadType.PROTO_OA_REFRESH_TOKEN_REQ_VALUE,
                ProtoOARefreshTokenReq.newBuilder().setRefreshToken(tokens.refreshToken()).build(), TIMEOUT);
        ProtoOARefreshTokenRes r = ProtoOARefreshTokenRes.parseFrom(m.getPayload());
        tokens.save(r.getAccessToken(), r.getRefreshToken());
        log.info("cTrader: access token refreshed (expires in {} s) and saved encrypted", r.getExpiresIn());
    }

    private CTraderConnection historyConnection() throws Exception {
        CTraderConnection c = conn;
        if (c == null || !c.isOpen()) {
            c = connect(m -> {}, why -> {});
            conn = c;
        }
        return c;
    }

    // ------------------------------------------------------------------------------------ symbols

    @Override
    public Map<String, SymbolSpec> loadSymbols(List<SymbolDef> wanted) throws Exception {
        CTraderConnection c = historyConnection();
        ProtoOASymbolsListRes list = ProtoOASymbolsListRes.parseFrom(c.request(ProtoOAPayloadType.PROTO_OA_SYMBOLS_LIST_REQ_VALUE,
                ProtoOASymbolsListReq.newBuilder().setCtidTraderAccountId(ctidTraderAccountId).build(), TIMEOUT).getPayload());
        Map<String, Long> idByName = new HashMap<>();
        for (ProtoOALightSymbol s : list.getSymbolList()) idByName.put(s.getSymbolName(), s.getSymbolId());

        Map<String, SymbolDef> byId = new LinkedHashMap<>();
        Map<String, String> nameByCode = new HashMap<>();
        for (SymbolDef d : wanted) {
            String name = d.on(FEED).orElseThrow().brokerSymbol();
            Long id = idByName.get(name);
            if (id != null) {
                byId.put(String.valueOf(id), d);
                symbolIds.put(d.code(), id);
                nameByCode.put(d.code(), name);
            }
        }
        Map<String, SymbolSpec> out = new LinkedHashMap<>();
        if (byId.isEmpty()) return out;
        ProtoOASymbolByIdReq.Builder req = ProtoOASymbolByIdReq.newBuilder().setCtidTraderAccountId(ctidTraderAccountId);
        byId.keySet().forEach(id -> req.addSymbolId(Long.parseLong(id)));
        ProtoOASymbolByIdRes full = ProtoOASymbolByIdRes.parseFrom(c.request(ProtoOAPayloadType.PROTO_OA_SYMBOL_BY_ID_REQ_VALUE, req.build(), TIMEOUT).getPayload());
        for (ProtoOASymbol s : full.getSymbolList()) {
            SymbolDef d = byId.get(String.valueOf(s.getSymbolId()));
            if (d != null) out.put(d.code(), toSpec(d.code(), nameByCode.get(d.code()), s));
        }
        return out;
    }

    /** Broker symbol -> SymbolSpec. Volumes on the wire are in cents of units; lots = volume / lotSize. */
    static SymbolSpec toSpec(String code, String brokerName, ProtoOASymbol s) {
        double lotCents = s.hasLotSize() && s.getLotSize() > 0 ? s.getLotSize() : Double.NaN;
        Map<String, Object> commission = new LinkedHashMap<>();
        commission.put("type", s.getCommissionType().name());
        commission.put("preciseRate", s.hasPreciseTradingCommissionRate() ? s.getPreciseTradingCommissionRate() / 1e8 : null);
        commission.put("preciseMin", s.hasPreciseMinCommission() ? s.getPreciseMinCommission() / 1e8 : null);
        commission.put("minType", s.getMinCommissionType().name());
        commission.put("minAsset", s.getMinCommissionAsset());
        List<Map<String, Object>> sched = new ArrayList<>();
        s.getScheduleList().forEach(i -> sched.add(Map.of("startSecond", i.getStartSecond(), "endSecond", i.getEndSecond())));
        Map<String, Object> hours = new LinkedHashMap<>();
        hours.put("timeZone", s.getScheduleTimeZone());
        hours.put("intervals", sched);
        hours.put("holidays", s.getHolidayCount());
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("symbolId", s.getSymbolId());
        raw.put("tradingMode", s.getTradingMode().name());
        raw.put("swapCalculationType", s.getSwapCalculationType().name());
        raw.put("measurementUnits", s.getMeasurementUnits());
        return new SymbolSpec(code, FEED, brokerName, s.getSymbolId(), s.getDigits(), s.getPipPosition(),
                Double.isNaN(lotCents) ? null : lotCents / 100.0,
                s.hasMinVolume() && !Double.isNaN(lotCents) ? s.getMinVolume() / lotCents : null,
                s.hasStepVolume() && !Double.isNaN(lotCents) ? s.getStepVolume() / lotCents : null,
                s.hasMaxVolume() && !Double.isNaN(lotCents) ? s.getMaxVolume() / lotCents : null,
                commission, s.hasSwapLong() ? s.getSwapLong() : null, s.hasSwapShort() ? s.getSwapShort() : null, hours, raw);
    }

    // ------------------------------------------------------------------------------------ live spots

    @Override
    public void start(List<SymbolDef> symbols, Map<String, SymbolSpec> specs, FeedListener listener) {
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("already started");
        Map<Long, String> codeById = new HashMap<>();
        for (SymbolDef d : symbols) {
            Long id = symbolIds.get(d.code());
            if (id != null) codeById.put(id, d.code());
        }
        supervisor = Thread.ofVirtual().name("ctrader-live").start(() -> {
            Backoff backoff = new Backoff(Duration.ofSeconds(60));
            while (running.get()) {
                AtomicBoolean dead = new AtomicBoolean();
                Object lock = new Object();
                SpotMerger merger = new SpotMerger(codeById);
                try (CTraderConnection live = connect(
                        m -> {
                            int type = m.getPayloadType();
                            if (type == ProtoOAPayloadType.PROTO_OA_ACCOUNTS_TOKEN_INVALIDATED_EVENT_VALUE
                                    || type == ProtoOAPayloadType.PROTO_OA_CLIENT_DISCONNECT_EVENT_VALUE
                                    || type == ProtoOAPayloadType.PROTO_OA_ACCOUNT_DISCONNECT_EVENT_VALUE) {
                                // Token invalidated or session dropped by the server: end this connection. The supervisor
                                // reconnects with backoff, and connect() refreshes the token when it is rejected.
                                log.warn("cTrader: server event {} ; reconnecting", ProtoOAPayloadType.forNumber(type));
                                dead.set(true);
                                synchronized (lock) { lock.notifyAll(); }
                                listener.onStatus(FeedListener.Status.DISCONNECTED, "server event " + ProtoOAPayloadType.forNumber(type));
                                return;
                            }
                            if (type != ProtoOAPayloadType.PROTO_OA_SPOT_EVENT_VALUE) return;
                            try {
                                Tick t = merger.onSpot(ProtoOASpotEvent.parseFrom(m.getPayload()), Instant.now(), seq.incrementAndGet());
                                if (t != null) listener.onTick(t);
                            } catch (Exception e) {
                                log.warn("cTrader: bad spot event: {}", e.toString());
                            }
                        },
                        why -> {
                            dead.set(true);
                            synchronized (lock) { lock.notifyAll(); }
                            listener.onStatus(FeedListener.Status.DISCONNECTED, why);
                        })) {
                    live.request(ProtoOAPayloadType.PROTO_OA_SUBSCRIBE_SPOTS_REQ_VALUE, ProtoOASubscribeSpotsReq.newBuilder()
                            .setCtidTraderAccountId(ctidTraderAccountId).addAllSymbolId(codeById.keySet()).build(), TIMEOUT);
                    listener.onStatus(FeedListener.Status.CONNECTED, "subscribed spots for " + codeById.values());
                    backoff.reset();
                    synchronized (lock) {
                        while (running.get() && !dead.get()) lock.wait(1000);
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (ConfigException e) {
                    // Wrong scope / live account / unknown account: never retry, this needs a human.
                    log.error("cTrader: {}", e.getMessage());
                    listener.onStatus(FeedListener.Status.DISCONNECTED, e.getMessage());
                    running.set(false);
                    return;
                } catch (Exception e) {
                    if (!dead.get()) listener.onStatus(FeedListener.Status.DISCONNECTED, "connect failed: " + e.getMessage());
                }
                if (!running.get()) return;
                try {
                    Thread.sleep(backoff.next());
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
    }

    /**
     * Spot events only carry the side(s) that changed, so the last bid and ask are remembered per symbol.
     * A tick is emitted once both sides are known.
     */
    static final class SpotMerger {
        private final Map<Long, String> codeById;
        private final Map<Long, long[]> last = new HashMap<>(); // {bid, ask} in 1/100000

        SpotMerger(Map<Long, String> codeById) { this.codeById = codeById; }

        Tick onSpot(ProtoOASpotEvent e, Instant recv, long seq) {
            String code = codeById.get(e.getSymbolId());
            if (code == null) return null;
            long[] ba = last.computeIfAbsent(e.getSymbolId(), k -> new long[]{0, 0});
            if (e.hasBid()) ba[0] = e.getBid();
            if (e.hasAsk()) ba[1] = e.getAsk();
            if (!e.hasBid() && !e.hasAsk()) return null; // trendbar-only update
            if (ba[0] == 0 || ba[1] == 0) return null;
            Instant ts = e.hasTimestamp() ? Instant.ofEpochMilli(e.getTimestamp()) : recv;
            return new Tick(FEED, code, ts, ba[0] / PRICE_SCALE, ba[1] / PRICE_SCALE, recv, seq);
        }
    }

    // ------------------------------------------------------------------------------------ history

    @Override
    public Duration maxSpan(Timeframe tf) {
        if (tf == null) return Duration.ofDays(7);       // tick data: one week per request, paged by hasMore
        if (tf == Timeframe.M1) return Duration.ofDays(3); // under the documented M1 window (about 5 weeks of M5)
        return Duration.ofDays(20);
    }

    private long symbolId(SymbolDef s) {
        Long id = symbolIds.get(s.code());
        if (id == null) throw new IllegalStateException("symbol " + s.code() + " not loaded on cTrader (call loadSymbols first)");
        return id;
    }

    private volatile dev.forexsignals.history.Pacer pacer;

    @Override
    public void usePacer(dev.forexsignals.history.Pacer pacer) { this.pacer = pacer; }

    private ProtoMessage history(int type, com.google.protobuf.MessageLite req) throws Exception {
        if (pacer != null) pacer.acquire();
        try {
            return historyConnection().request(type, req, Duration.ofSeconds(60));
        } catch (CTraderConnection.CTraderError e) {
            if (e.isRateLimit()) throw new RateLimitedException(e.getMessage());
            if (e.isTokenProblem()) {
                // Token invalidated or expired mid-session: drop the connection; the next request reconnects,
                // which refreshes the token. The caller's retry policy repeats this request.
                CTraderConnection c = conn;
                conn = null;
                if (c != null) c.close();
            }
            throw e;
        }
    }

    @Override
    public List<Candle> fetchM1(SymbolDef symbol, Instant from, Instant to) throws Exception {
        return fetchCandles(symbol, Timeframe.M1, from, to).stream()
                .map(c -> new Candle(c.feed(), c.symbol(), c.timeframe(), c.openTime(), c.closeTime(), c.open(), c.high(), c.low(), c.close(),
                        c.tickCount(), Double.NaN, Double.NaN, Double.NaN, Candle.Source.IMPORT))
                .toList();
    }

    @Override
    public List<Candle> fetchCandles(SymbolDef symbol, Timeframe tf, Instant from, Instant to) throws Exception {
        ProtoOATrendbarPeriod period = switch (tf) {
            case M1 -> ProtoOATrendbarPeriod.M1;
            case M5 -> ProtoOATrendbarPeriod.M5;
            case M15 -> ProtoOATrendbarPeriod.M15;
            case H1 -> ProtoOATrendbarPeriod.H1;
            case H4, D1 -> throw new IllegalArgumentException("cTrader " + tf + " bars use server-day alignment; derive from M1 instead");
        };
        List<Candle> out = new ArrayList<>();
        Instant end = to;
        // Trendbars come back newest-first from toTimestamp; if hasMore, ask again for the older remainder.
        while (end.isAfter(from)) {
            ProtoOAGetTrendbarsRes r = ProtoOAGetTrendbarsRes.parseFrom(history(ProtoOAPayloadType.PROTO_OA_GET_TRENDBARS_REQ_VALUE,
                    ProtoOAGetTrendbarsReq.newBuilder().setCtidTraderAccountId(ctidTraderAccountId).setSymbolId(symbolId(symbol))
                            .setPeriod(period).setFromTimestamp(from.toEpochMilli()).setToTimestamp(end.toEpochMilli()).build()).getPayload());
            List<Candle> page = decodeTrendbars(r.getTrendbarList(), symbol.code(), tf);
            out.addAll(page);
            if (!r.getHasMore() || page.isEmpty()) break;
            end = page.stream().map(Candle::openTime).min(Comparator.naturalOrder()).orElseThrow();
        }
        return out.stream().filter(c -> !c.openTime().isBefore(from) && c.openTime().isBefore(to))
                .sorted(Comparator.comparing(Candle::openTime)).distinct().toList();
    }

    /** Trendbar wire format: low absolute, open/high/close as deltas above low, time in UTC minutes. */
    static List<Candle> decodeTrendbars(List<ProtoOATrendbar> bars, String code, Timeframe tf) {
        List<Candle> out = new ArrayList<>(bars.size());
        for (ProtoOATrendbar b : bars) {
            long low = b.getLow();
            Instant open = Instant.ofEpochSecond(b.getUtcTimestampInMinutes() * 60L);
            out.add(new Candle(FEED, code, tf, open, open.plus(tf.duration()), (low + b.getDeltaOpen()) / PRICE_SCALE,
                    (low + b.getDeltaHigh()) / PRICE_SCALE, low / PRICE_SCALE, (low + b.getDeltaClose()) / PRICE_SCALE,
                    (int) b.getVolume(), Double.NaN, Double.NaN, Double.NaN, Candle.Source.DERIVED));
        }
        return out;
    }

    @Override
    public List<Tick> fetchTicks(SymbolDef symbol, Instant from, Instant to) throws Exception {
        List<long[]> bids = tickSide(symbol, ProtoOAQuoteType.BID, from, to);
        List<long[]> asks = tickSide(symbol, ProtoOAQuoteType.ASK, from, to);
        return mergeBidAsk(symbol.code(), bids, asks, seq);
    }

    /** One quote side for [from, to), oldest first, paging backwards while hasMore. Each entry {tsMillis, price}. */
    private List<long[]> tickSide(SymbolDef symbol, ProtoOAQuoteType side, Instant from, Instant to) throws Exception {
        List<long[]> all = new ArrayList<>();
        java.util.Set<Long> boundaries = new java.util.HashSet<>();
        long end = to.toEpochMilli();
        while (end > from.toEpochMilli()) {
            ProtoOAGetTickDataRes r = ProtoOAGetTickDataRes.parseFrom(history(ProtoOAPayloadType.PROTO_OA_GET_TICKDATA_REQ_VALUE,
                    ProtoOAGetTickDataReq.newBuilder().setCtidTraderAccountId(ctidTraderAccountId).setSymbolId(symbolId(symbol))
                            .setType(side).setFromTimestamp(from.toEpochMilli()).setToTimestamp(end).build()).getPayload());
            List<long[]> page = decodeTickData(r.getTickDataList());
            all.addAll(page);
            if (!r.getHasMore() || page.isEmpty()) break;
            end = page.getLast()[0]; // oldest in this page (decoded newest-first)
            boundaries.add(end);
        }
        all.removeIf(t -> t[0] < from.toEpochMilli() || t[0] >= to.toEpochMilli());
        all.sort(Comparator.comparingLong(t -> t[0]));
        // Consecutive pages can both contain the ticks of their boundary millisecond. Drop repeats only there;
        // equal ticks at any other millisecond are genuine and kept.
        List<long[]> dedup = new ArrayList<>(all.size());
        for (long[] t : all) {
            boolean repeat = !dedup.isEmpty() && dedup.getLast()[0] == t[0] && dedup.getLast()[1] == t[1] && boundaries.contains(t[0]);
            if (!repeat) dedup.add(t);
        }
        return dedup;
    }

    /** Tick data wire format: newest first; first entry absolute, later entries are deltas from the previous one. */
    static List<long[]> decodeTickData(List<ProtoOATickData> data) {
        List<long[]> out = new ArrayList<>(data.size());
        long ts = 0, px = 0;
        for (int i = 0; i < data.size(); i++) {
            ts = i == 0 ? data.get(i).getTimestamp() : ts + data.get(i).getTimestamp();
            px = i == 0 ? data.get(i).getTick() : px + data.get(i).getTick();
            out.add(new long[]{ts, px});
        }
        return out;
    }

    /**
     * Rebuilds the quote stream from the two one-sided histories exactly like live spot events: one tick at
     * every bid OR ask change, carrying the unchanged side. (cTrader records each side only when it changes.
     * An ask-only change at the start of a bar makes the bar open at the carried bid, which is how cTrader's
     * own trendbars are built: measured 2026-11-19, see PLAN §14.3.) Ticks before the first bid are skipped.
     */
    static List<Tick> mergeBidAsk(String code, List<long[]> bids, List<long[]> asks, AtomicLong seq) {
        List<Tick> out = new ArrayList<>(bids.size() + asks.size());
        int i = 0, j = 0;
        long bid = 0, ask = 0;
        while (i < bids.size() || j < asks.size()) {
            long tb = i < bids.size() ? bids.get(i)[0] : Long.MAX_VALUE, ta = j < asks.size() ? asks.get(j)[0] : Long.MAX_VALUE;
            long ts = Math.min(tb, ta);
            // Apply every change at this millisecond (both sides may change at once), then emit one tick.
            while (i < bids.size() && bids.get(i)[0] == ts) bid = bids.get(i++)[1];
            while (j < asks.size() && asks.get(j)[0] == ts) ask = asks.get(j++)[1];
            if (bid == 0) continue;
            Instant t = Instant.ofEpochMilli(ts);
            out.add(new Tick(FEED, code, t, bid / PRICE_SCALE, ask == 0 ? Double.NaN : ask / PRICE_SCALE, t, seq.incrementAndGet()));
        }
        return out;
    }

    @Override
    public void close() {
        running.set(false);
        if (supervisor != null) supervisor.interrupt();
        CTraderConnection c = conn;
        if (c != null) c.close();
    }
}
