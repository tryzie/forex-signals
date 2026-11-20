package dev.forexsignals.store;

import dev.forexsignals.strategy.ParamsHash;
import dev.forexsignals.strategy.StrategyRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchStoreIT {
    @Test
    void freezeKeepsTheFirstFreezeTimeAndRunsStoreTheirParamsHash() {
        try (Database db = TestDb.freshMigrated()) {
            MarketStore ms = new MarketStore(db);
            ResearchStore rs = new ResearchStore(ms);
            var ema = StrategyRegistry.byName(StrategyRegistry.discover(), "ema_pullback");
            String hash = ParamsHash.of(ema.meta());
            Instant t1 = Instant.parse("2026-11-19T20:00:00Z");
            rs.freeze(ema.meta().id(), hash, MarketStore.json(ema.meta().params()), t1, "first");
            rs.freeze(ema.meta().id(), hash, MarketStore.json(ema.meta().params()), t1.plusSeconds(600), "again");
            assertThat(rs.latestFrozen(ema.meta().id())).get().extracting(ResearchStore.Frozen::frozenAt).isEqualTo(t1);
            var run = rs.startRun("BACKTEST", "ctrader", t1, t1, "p", hash, null, t1.plusSeconds(60));
            rs.runStrategy(run, ema.meta().id(), hash, "PARENT");
            rs.validationResult("ema_pullback", "1", hash, run, "FAIL", "{\"checks\":[]}");
            assertThat(ms.scalarLong("SELECT count(*) FROM run_strategy WHERE params_hash = ?", hash)).isEqualTo(1);
            assertThat(ms.scalarLong("SELECT count(*) FROM validation_result WHERE verdict = 'FAIL'")).isEqualTo(1);
        }
    }

    @Test
    void holdoutRunsExactlyOnceAndOnlyAfterAFinishedDevelopmentRun() {
        try (Database db = TestDb.freshMigrated()) {
            ResearchStore rs = new ResearchStore(new MarketStore(db));
            Instant t = Instant.parse("2026-11-20T12:00:00Z");
            assertThat(rs.hypothesisDevelopmentDone("h")).isFalse();
            long dev = rs.hypothesisStart("h", "DEVELOPMENT", t);
            assertThat(rs.hypothesisDevelopmentDone("h")).as("started, not finished").isFalse();
            rs.hypothesisFinish(dev, t.plusSeconds(60), "r.md", "{}");
            assertThat(rs.hypothesisDevelopmentDone("h")).isTrue();
            rs.hypothesisStart("h", "HOLDOUT", t.plusSeconds(120));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> rs.hypothesisStart("h", "HOLDOUT", t.plusSeconds(180)))
                    .isInstanceOf(Database.StoreException.class);
            rs.hypothesisStart("other", "HOLDOUT", t.plusSeconds(180)); // a different hypothesis is independent
        }
    }
}
