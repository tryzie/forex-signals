package dev.forexsignals.store;

import dev.forexsignals.paper.InstrumentSpec;
import dev.forexsignals.paper.SpreadModel;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static dev.forexsignals.store.Database.instant;
import static dev.forexsignals.store.Database.utc;

/** Phase 2 research data: frozen parameters, runs and their params hashes, validation results, specs and spread samples. */
public final class ResearchStore {

    private final MarketStore store;

    public ResearchStore(MarketStore store) { this.store = store; }

    // ------------------------------------------------------------------------------------ frozen parameters

    /** Records the parameter set; freezing the same hash again keeps the original freeze time. */
    public void freeze(String strategyId, String hash, String paramsJson, Instant at, String note) {
        exec("INSERT INTO frozen_params (strategy_id, params_hash, params, frozen_at, note) VALUES (?,?,?::jsonb,?,?) ON CONFLICT DO NOTHING",
                strategyId, hash, paramsJson, utc(at), note);
    }

    public record Frozen(String strategyId, String hash, Instant frozenAt) {}

    public Optional<Frozen> latestFrozen(String strategyId) {
        String sql = "SELECT strategy_id, params_hash, frozen_at FROM frozen_params WHERE strategy_id = ? ORDER BY frozen_at DESC LIMIT 1";
        try (Connection c = store.db().connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, strategyId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new Frozen(rs.getString(1), rs.getString(2), instant(rs, "frozen_at"))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new Database.StoreException("frozen params", e);
        }
    }

    /**
     * Why out-of-sample numbers must be withheld for this run, or empty if they may be shown: the strategy's parameters
     * must have been frozen before the run started, and the run's params hash must equal the frozen (latest) hash.
     */
    public static Optional<String> oosWithheldReason(Optional<Frozen> frozen, String runHash, Instant runStartedAt) {
        if (frozen.isEmpty()) return Optional.of("parameters not frozen (run --mode=freeze before any out-of-sample test)");
        if (!frozen.get().hash().equals(runHash)) {
            return Optional.of("params hash " + runHash.substring(0, 12) + " differs from the frozen " + frozen.get().hash().substring(0, 12)
                    + " (parameters changed after the freeze)");
        }
        if (!frozen.get().frozenAt().isBefore(runStartedAt)) return Optional.of("parameters frozen at " + frozen.get().frozenAt() + ", after this run started");
        return Optional.empty();
    }

    // ------------------------------------------------------------------------------------ runs

    public UUID startRun(String kind, String feed, Instant from, Instant to, String costProfile, String configHash, String gitSha, Instant startedAt) {
        UUID id = UUID.randomUUID();
        exec("INSERT INTO run (id, kind, feed, from_ts, to_ts, cost_profile, config_hash, git_sha, started_at) VALUES (?,?,?,?,?,?,?,?,?)",
                id, kind, feed, utc(from), utc(to), costProfile, configHash, gitSha, utc(startedAt));
        return id;
    }

    public void runStrategy(UUID run, String strategyId, String hash, String role) {
        exec("INSERT INTO run_strategy (run_id, strategy_id, params_hash, role) VALUES (?,?,?,?)", run, strategyId, hash, role);
    }

    public void finishRun(UUID run, Instant at) { exec("UPDATE run SET finished_at = ? WHERE id = ?", utc(at), run); }

    public void validationResult(String strategy, String version, String hash, UUID run, String verdict, String checksJson) {
        exec("INSERT INTO validation_result (strategy, version, params_hash, run_id, verdict, checks) VALUES (?,?,?,?,?,?::jsonb)",
                strategy, version, hash, run, verdict, checksJson);
    }

    // ------------------------------------------------------------------------------------ inputs

    /** Latest stored specs of a feed, with base/quote supplied by the caller (from the symbol catalogue). */
    public Map<String, InstrumentSpec> specs(String feed, Map<String, String[]> baseQuote) {
        String sql = "SELECT DISTINCT ON (symbol) symbol, contract_size, min_volume, volume_step, pip_position, swap_long, swap_short "
                + "FROM symbol_spec WHERE feed = ? ORDER BY symbol, captured_at DESC, id DESC";
        Map<String, InstrumentSpec> out = new HashMap<>();
        try (Connection c = store.db().connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, feed);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String sym = rs.getString("symbol");
                    String[] bq = baseQuote.get(sym);
                    if (bq == null) continue;
                    out.put(sym, new InstrumentSpec(sym, bq[0], bq[1], rs.getDouble("contract_size"), rs.getDouble("min_volume"), rs.getDouble("volume_step"),
                            Math.pow(10, -rs.getInt("pip_position")), rs.getDouble("swap_long"), rs.getDouble("swap_short")));
                }
            }
        } catch (SQLException e) {
            throw new Database.StoreException("specs", e);
        }
        return out;
    }

    public List<SpreadModel.Sample> spreadSamples(String feed) {
        String sql = "SELECT symbol, label, window_start, session_bucket, median, p95, samples FROM spread_stats "
                + "WHERE feed = ? AND source = 'TICK_SAMPLE' ORDER BY symbol, window_start, session_bucket";
        List<SpreadModel.Sample> out = new ArrayList<>();
        try (Connection c = store.db().connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, feed);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new SpreadModel.Sample(rs.getString(1), rs.getString(2), instant(rs, "window_start"), rs.getString(4),
                            rs.getDouble(5), rs.getDouble(6), rs.getLong(7)));
                }
            }
        } catch (SQLException e) {
            throw new Database.StoreException("spread samples", e);
        }
        return out;
    }

    private void exec(String sql, Object... args) {
        try (Connection c = store.db().connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new Database.StoreException(sql, e);
        }
    }
}
