package dev.forexsignals.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.forexsignals.market.Candle;
import dev.forexsignals.market.SymbolCatalog;
import dev.forexsignals.market.Tick;
import dev.forexsignals.market.Timeframe;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static dev.forexsignals.store.Database.instant;
import static dev.forexsignals.store.Database.utc;

/**
 * All market-data SQL in one place (the I/O boundary). Methods are plain JDBC, one statement each,
 * so what hits the database is visible in one read. Inserts of market data are idempotent
 * (ON CONFLICT DO NOTHING), which is what makes the importer safe to kill and restart.
 */
public final class MarketStore {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Database db;

    public MarketStore(Database db) { this.db = db; }

    public Database db() { return db; }

    public static String json(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalArgumentException("not serialisable to JSON: " + o, e);
        }
    }

    private static void setDouble(PreparedStatement ps, int i, double v) throws SQLException {
        if (Double.isNaN(v)) ps.setNull(i, Types.DOUBLE);
        else ps.setDouble(i, v);
    }

    private static double getDouble(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? Double.NaN : v;
    }

    // ------------------------------------------------------------------------------------ ticks

    /**
     * Inserts ticks in one statement (unnest of arrays); duplicates (same feed, symbol, ts, seq) are ignored.
     * Returns the exact number of rows inserted.
     */
    public int insertTicks(List<Tick> ticks) {
        if (ticks.isEmpty()) return 0;
        int n = ticks.size();
        String[] feed = new String[n], sym = new String[n], ts = new String[n], recv = new String[n];
        Double[] bid = new Double[n], ask = new Double[n], quote = new Double[n];
        Long[] seq = new Long[n];
        for (int i = 0; i < n; i++) {
            Tick t = ticks.get(i);
            feed[i] = t.feed(); sym[i] = t.symbol(); ts[i] = t.ts().toString(); recv[i] = t.recvTs().toString();
            bid[i] = nanToNull(t.bid()); ask[i] = nanToNull(t.ask()); quote[i] = nanToNull(t.quote()); seq[i] = t.seq();
        }
        String sql = "INSERT INTO tick (feed, symbol, ts, bid, ask, quote, recv_ts, seq) "
                + "SELECT * FROM unnest(?::text[], ?::text[], ?::timestamptz[], ?::float8[], ?::float8[], ?::float8[], ?::timestamptz[], ?::int8[]) "
                + "ON CONFLICT DO NOTHING";
        try (Connection c = db.connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setArray(1, c.createArrayOf("text", feed));
            ps.setArray(2, c.createArrayOf("text", sym));
            ps.setArray(3, c.createArrayOf("text", ts));
            ps.setArray(4, c.createArrayOf("float8", bid));
            ps.setArray(5, c.createArrayOf("float8", ask));
            ps.setArray(6, c.createArrayOf("float8", quote));
            ps.setArray(7, c.createArrayOf("text", recv));
            ps.setArray(8, c.createArrayOf("int8", seq));
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new Database.StoreException("insert ticks", e);
        }
    }

    /** Streams recorded ticks in replay order (ts, then arrival seq). Uses a server-side cursor. */
    public long streamTicks(String feed, List<String> symbols, Instant from, Instant to, Consumer<Tick> sink) {
        String sql = "SELECT feed, symbol, ts, bid, ask, quote, recv_ts, seq FROM tick WHERE feed = ? AND symbol = ANY (?) "
                + "AND ts >= ? AND ts < ? ORDER BY ts, seq";
        long n = 0;
        try (Connection c = db.connection()) {
            c.setAutoCommit(false); // required for cursor-based fetching in pgjdbc
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setFetchSize(5000);
                ps.setString(1, feed);
                ps.setArray(2, c.createArrayOf("text", symbols.toArray()));
                ps.setObject(3, utc(from));
                ps.setObject(4, utc(to));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        sink.accept(new Tick(rs.getString("feed"), rs.getString("symbol"), instant(rs, "ts"), getDouble(rs, "bid"),
                                getDouble(rs, "ask"), getDouble(rs, "quote"), instant(rs, "recv_ts"), rs.getLong("seq")));
                        n++;
                    }
                }
            } finally {
                c.rollback();
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new Database.StoreException("stream ticks", e);
        }
        return n;
    }

    // ------------------------------------------------------------------------------------ candles

    /**
     * Inserts M1 candles in one statement; an existing (feed, symbol, open_time) row is kept untouched
     * (ON CONFLICT DO NOTHING). Returns the exact number of rows inserted.
     */
    public int insertM1(List<Candle> candles) {
        if (candles.isEmpty()) return 0;
        try (Connection c = db.connection()) {
            return insertM1(c, candles);
        } catch (SQLException e) {
            throw new Database.StoreException("insert candle_m1", e);
        }
    }

    /**
     * Importer page write: inserts the bars and adds the number actually inserted to the chunk's row count
     * in ONE transaction, so import_progress.rows stays exact even if the process is killed.
     */
    public int insertM1Page(Chunk chunk, List<Candle> candles) {
        try (Connection c = db.connection()) {
            c.setAutoCommit(false);
            try {
                int n = candles.isEmpty() ? 0 : insertM1(c, candles);
                try (PreparedStatement ps = c.prepareStatement("UPDATE import_progress SET rows = rows + ?, updated_at = now() "
                        + "WHERE feed = ? AND symbol = ? AND chunk_start = ?")) {
                    ps.setInt(1, n);
                    ps.setString(2, chunk.feed());
                    ps.setString(3, chunk.symbol());
                    ps.setObject(4, utc(chunk.start()));
                    ps.executeUpdate();
                }
                c.commit();
                return n;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new Database.StoreException("insert candle_m1 page", e);
        }
    }

    private int insertM1(Connection c, List<Candle> candles) throws SQLException {
        int n = candles.size();
        String[] feed = new String[n], sym = new String[n], open = new String[n], src = new String[n];
        Double[] o = new Double[n], h = new Double[n], l = new Double[n], cl = new Double[n], smin = new Double[n], smed = new Double[n], smax = new Double[n];
        Integer[] ticks = new Integer[n];
        for (int i = 0; i < n; i++) {
            Candle k = candles.get(i);
            if (k.timeframe() != Timeframe.M1) throw new IllegalArgumentException("not M1: " + k);
            feed[i] = k.feed(); sym[i] = k.symbol(); open[i] = k.openTime().toString();
            o[i] = k.open(); h[i] = k.high(); l[i] = k.low(); cl[i] = k.close(); ticks[i] = k.tickCount();
            smin[i] = nanToNull(k.spreadMin()); smed[i] = nanToNull(k.spreadMed()); smax[i] = nanToNull(k.spreadMax());
            src[i] = k.source() == Candle.Source.IMPORT ? "IMPORT" : "BUILT";
        }
        String sql = "INSERT INTO candle_m1 (feed, symbol, open_time, open, high, low, close, tick_count, spread_min, spread_med, spread_max, source) "
                + "SELECT * FROM unnest(?::text[], ?::text[], ?::timestamptz[], ?::float8[], ?::float8[], ?::float8[], ?::float8[], ?::int4[], "
                + "?::float8[], ?::float8[], ?::float8[], ?::text[]) ON CONFLICT DO NOTHING";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setArray(1, c.createArrayOf("text", feed));
            ps.setArray(2, c.createArrayOf("text", sym));
            ps.setArray(3, c.createArrayOf("text", open));
            ps.setArray(4, c.createArrayOf("float8", o));
            ps.setArray(5, c.createArrayOf("float8", h));
            ps.setArray(6, c.createArrayOf("float8", l));
            ps.setArray(7, c.createArrayOf("float8", cl));
            ps.setArray(8, c.createArrayOf("int4", ticks));
            ps.setArray(9, c.createArrayOf("float8", smin));
            ps.setArray(10, c.createArrayOf("float8", smed));
            ps.setArray(11, c.createArrayOf("float8", smax));
            ps.setArray(12, c.createArrayOf("text", src));
            return ps.executeUpdate();
        }
    }

    private static Double nanToNull(double v) { return Double.isNaN(v) ? null : v; }

    /** Derived candles are rebuildable, so an existing row is replaced. */
    public int upsertDerived(List<Candle> candles) {
        if (candles.isEmpty()) return 0;
        String sql = "INSERT INTO candle (feed, symbol, timeframe, open_time, close_time, open, high, low, close, tick_count, "
                + "spread_min, spread_med, spread_max) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) "
                + "ON CONFLICT (feed, symbol, timeframe, open_time) DO UPDATE SET close_time = EXCLUDED.close_time, "
                + "open = EXCLUDED.open, high = EXCLUDED.high, low = EXCLUDED.low, close = EXCLUDED.close, "
                + "tick_count = EXCLUDED.tick_count, spread_min = EXCLUDED.spread_min, spread_med = EXCLUDED.spread_med, "
                + "spread_max = EXCLUDED.spread_max";
        try (Connection c = db.connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (Candle k : candles) {
                ps.setString(1, k.feed());
                ps.setString(2, k.symbol());
                ps.setString(3, k.timeframe().name());
                ps.setObject(4, utc(k.openTime()));
                ps.setObject(5, utc(k.closeTime()));
                ps.setDouble(6, k.open());
                ps.setDouble(7, k.high());
                ps.setDouble(8, k.low());
                ps.setDouble(9, k.close());
                ps.setInt(10, k.tickCount());
                setDouble(ps, 11, k.spreadMin());
                setDouble(ps, 12, k.spreadMed());
                setDouble(ps, 13, k.spreadMax());
                ps.addBatch();
            }
            return sum(ps.executeBatch());
        } catch (SQLException e) {
            throw new Database.StoreException("upsert candle", e);
        }
    }

    /** Streams M1 candles in time order. */
    public long streamM1(String feed, String symbol, Instant from, Instant to, Consumer<Candle> sink) {
        String sql = "SELECT * FROM candle_m1 WHERE feed = ? AND symbol = ? AND open_time >= ? AND open_time < ? ORDER BY open_time";
        long n = 0;
        try (Connection c = db.connection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setFetchSize(10_000);
                ps.setString(1, feed);
                ps.setString(2, symbol);
                ps.setObject(3, utc(from));
                ps.setObject(4, utc(to));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Instant open = instant(rs, "open_time");
                        sink.accept(new Candle(feed, symbol, Timeframe.M1, open, open.plusSeconds(60), rs.getDouble("open"),
                                rs.getDouble("high"), rs.getDouble("low"), rs.getDouble("close"), rs.getInt("tick_count"),
                                getDouble(rs, "spread_min"), getDouble(rs, "spread_med"), getDouble(rs, "spread_max"),
                                "IMPORT".equals(rs.getString("source")) ? Candle.Source.IMPORT : Candle.Source.BUILT));
                        n++;
                    }
                }
            } finally {
                c.rollback();
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new Database.StoreException("stream candle_m1", e);
        }
        return n;
    }

    public List<Candle> loadDerived(String feed, String symbol, Timeframe tf, Instant from, Instant to) {
        String sql = "SELECT * FROM candle WHERE feed = ? AND symbol = ? AND timeframe = ? AND open_time >= ? AND open_time < ? ORDER BY open_time";
        List<Candle> out = new ArrayList<>();
        try (Connection c = db.connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, feed);
            ps.setString(2, symbol);
            ps.setString(3, tf.name());
            ps.setObject(4, utc(from));
            ps.setObject(5, utc(to));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Candle(feed, symbol, tf, instant(rs, "open_time"), instant(rs, "close_time"), rs.getDouble("open"),
                            rs.getDouble("high"), rs.getDouble("low"), rs.getDouble("close"), rs.getInt("tick_count"),
                            getDouble(rs, "spread_min"), getDouble(rs, "spread_med"), getDouble(rs, "spread_max"), Candle.Source.DERIVED));
                }
            }
        } catch (SQLException e) {
            throw new Database.StoreException("load candle", e);
        }
        return out;
    }

    public long countM1(String feed, String symbol) {
        return scalarLong("SELECT count(*) FROM candle_m1 WHERE feed = ? AND symbol = ?", feed, symbol);
    }

    public Optional<Instant[]> m1Range(String feed, String symbol) {
        String sql = "SELECT min(open_time) AS a, max(open_time) AS b FROM candle_m1 WHERE feed = ? AND symbol = ?";
        try (Connection c = db.connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, feed);
            ps.setString(2, symbol);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Instant a = instant(rs, "a");
                return a == null ? Optional.empty() : Optional.of(new Instant[]{a, instant(rs, "b")});
            }
        } catch (SQLException e) {
            throw new Database.StoreException("m1 range", e);
        }
    }

    // ------------------------------------------------------------------------------------ import progress

    public record Chunk(String feed, String symbol, Instant start, Instant end, String status, int rows, int attempts) {}

    /** Registers chunks (idempotent) and returns all chunks for the symbol that are not DONE, oldest first. */
    public List<Chunk> openChunks(String feed, String symbol, List<Instant[]> ranges) {
        String ins = "INSERT INTO import_progress (feed, symbol, chunk_start, chunk_end, status) VALUES (?,?,?,?,'PENDING') ON CONFLICT DO NOTHING";
        String sel = "SELECT * FROM import_progress WHERE feed = ? AND symbol = ? AND status <> 'DONE' AND chunk_start >= ? AND chunk_end <= ? ORDER BY chunk_start";
        List<Chunk> out = new ArrayList<>();
        try (Connection c = db.connection()) {
            try (PreparedStatement ps = c.prepareStatement(ins)) {
                for (Instant[] r : ranges) {
                    ps.setString(1, feed);
                    ps.setString(2, symbol);
                    ps.setObject(3, utc(r[0]));
                    ps.setObject(4, utc(r[1]));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            if (ranges.isEmpty()) return out;
            try (PreparedStatement ps = c.prepareStatement(sel)) {
                ps.setString(1, feed);
                ps.setString(2, symbol);
                ps.setObject(3, utc(ranges.getFirst()[0]));
                ps.setObject(4, utc(ranges.getLast()[1]));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(chunk(rs));
                }
            }
        } catch (SQLException e) {
            throw new Database.StoreException("import_progress open chunks", e);
        }
        return out;
    }

    public List<Chunk> chunks(String feed, String symbol) {
        List<Chunk> out = new ArrayList<>();
        try (Connection c = db.connection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM import_progress WHERE feed = ? AND symbol = ? ORDER BY chunk_start")) {
            ps.setString(1, feed);
            ps.setString(2, symbol);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(chunk(rs));
            }
        } catch (SQLException e) {
            throw new Database.StoreException("import_progress list", e);
        }
        return out;
    }

    private static Chunk chunk(ResultSet rs) throws SQLException {
        return new Chunk(rs.getString("feed"), rs.getString("symbol"), instant(rs, "chunk_start"), instant(rs, "chunk_end"),
                rs.getString("status"), rs.getInt("rows"), rs.getInt("attempts"));
    }

    /** status = DONE / FAILED / PENDING; rowsAdded is added to the running total; attempts incremented when asked. */
    public void updateChunk(Chunk ch, String status, int rowsAdded, boolean countAttempt, String error) {
        String sql = "UPDATE import_progress SET status = ?, rows = rows + ?, attempts = attempts + ?, last_error = ?, updated_at = now() "
                + "WHERE feed = ? AND symbol = ? AND chunk_start = ?";
        try (Connection c = db.connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setInt(2, rowsAdded);
            ps.setInt(3, countAttempt ? 1 : 0);
            ps.setString(4, error == null ? null : error.substring(0, Math.min(error.length(), 2000)));
            ps.setString(5, ch.feed());
            ps.setString(6, ch.symbol());
            ps.setObject(7, utc(ch.start()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new Database.StoreException("import_progress update", e);
        }
    }

    // ------------------------------------------------------------------------------------ symbols and specs

    public void upsertSymbol(SymbolCatalog.SymbolDef d) {
        String sql = "INSERT INTO symbol (code, role, asset_class, base_ccy, quote_ccy, cluster, verified) VALUES (?,?,?,?,?,?,?) "
                + "ON CONFLICT (code) DO UPDATE SET role = EXCLUDED.role, asset_class = EXCLUDED.asset_class, base_ccy = EXCLUDED.base_ccy, "
                + "quote_ccy = EXCLUDED.quote_ccy, cluster = EXCLUDED.cluster, verified = EXCLUDED.verified, updated_at = now()";
        exec(sql, d.code(), d.role().name(), d.assetClass(), d.base(), d.quote(), d.cluster(), d.verified());
    }

    public void upsertSymbolFeed(String feed, String code, String brokerSymbol, Long brokerId, boolean required, boolean available, Instant at) {
        String sql = "INSERT INTO symbol_feed (feed, code, broker_symbol, broker_symbol_id, required, available, checked_at) VALUES (?,?,?,?,?,?,?) "
                + "ON CONFLICT (feed, code) DO UPDATE SET broker_symbol = EXCLUDED.broker_symbol, broker_symbol_id = EXCLUDED.broker_symbol_id, "
                + "required = EXCLUDED.required, available = EXCLUDED.available, checked_at = EXCLUDED.checked_at";
        exec(sql, feed, code, brokerSymbol, brokerId, required, available, utc(at));
    }

    public Optional<String> latestSpecHash(String feed, String symbol) {
        String sql = "SELECT spec_hash FROM symbol_spec WHERE feed = ? AND symbol = ? ORDER BY captured_at DESC, id DESC LIMIT 1";
        try (Connection c = db.connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, feed);
            ps.setString(2, symbol);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new Database.StoreException("latest spec", e);
        }
    }

    public void insertSpec(dev.forexsignals.feed.SymbolSpec s, Instant at) {
        String sql = "INSERT INTO symbol_spec (symbol, feed, digits, pip_position, contract_size, min_volume, volume_step, max_volume, "
                + "commission, swap_long, swap_short, trading_hours, raw, spec_hash, captured_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?::jsonb,?,?,?::jsonb,?::jsonb,?,?)";
        exec(sql, s.code(), s.feed(), s.digits(), s.pipPosition(), s.contractSize(), s.minVolume(), s.volumeStep(), s.maxVolume(),
                json(s.commission()), s.swapLong(), s.swapShort(), json(s.tradingHours()), json(s.raw()), s.hash(), utc(at));
    }

    // ------------------------------------------------------------------------------------ events, gaps, spread stats

    public void feedEvent(Instant ts, String feed, String symbol, String kind, Map<String, ?> detail) {
        exec("INSERT INTO feed_event (ts, feed, symbol, kind, detail) VALUES (?,?,?,?,?::jsonb)",
                utc(ts), feed, symbol, kind, json(detail));
    }

    public long countFeedEvents(String feed, String kind) {
        return scalarLong("SELECT count(*) FROM feed_event WHERE feed = ? AND kind = ?", feed, kind);
    }

    public void insertGaps(Instant runAt, String feed, String symbol, List<dev.forexsignals.history.GapReport.Gap> gaps) {
        if (gaps.isEmpty()) return;
        String sql = "INSERT INTO gap_report (run_at, feed, symbol, gap_start, gap_end, minutes, expected, reason) VALUES (?,?,?,?,?,?,?,?)";
        try (Connection c = db.connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (var g : gaps) {
                ps.setObject(1, utc(runAt));
                ps.setString(2, feed);
                ps.setString(3, symbol);
                ps.setObject(4, utc(g.start()));
                ps.setObject(5, utc(g.end()));
                ps.setLong(6, g.minutes());
                ps.setBoolean(7, g.expected());
                ps.setString(8, g.reason());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new Database.StoreException("insert gaps", e);
        }
    }

    public void upsertSpreadStat(String feed, String symbol, String bucket, Instant from, Instant to, double median, double p95, long samples) {
        String sql = "INSERT INTO spread_stats (feed, symbol, session_bucket, window_start, window_end, median, p95, samples) "
                + "VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (feed, symbol, session_bucket, window_start) DO UPDATE SET "
                + "window_end = EXCLUDED.window_end, median = EXCLUDED.median, p95 = EXCLUDED.p95, samples = EXCLUDED.samples, computed_at = now()";
        exec(sql, feed, symbol, bucket, utc(from), utc(to), median, p95, samples);
    }

    // ------------------------------------------------------------------------------------ helpers

    public long scalarLong(String sql, Object... args) {
        try (Connection c = db.connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new Database.StoreException(sql, e);
        }
    }

    private void exec(String sql, Object... args) {
        try (Connection c = db.connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                Object a = args[i];
                if (a instanceof Double d && d.isNaN()) ps.setNull(i + 1, Types.DOUBLE);
                else ps.setObject(i + 1, a);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new Database.StoreException(sql, e);
        }
    }

    private static int sum(int[] counts) {
        int n = 0;
        // reWriteBatchedInserts reports rewritten statements as SUCCESS_NO_INFO (-2): each counts as one row.
        for (int c : counts) n += c >= 0 ? c : (c == java.sql.Statement.SUCCESS_NO_INFO ? 1 : 0);
        return n;
    }
}
