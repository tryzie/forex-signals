package dev.forexsignals.strategy;

import dev.forexsignals.market.SymbolCatalog;
import dev.forexsignals.strategy.api.Strategy;
import dev.forexsignals.strategy.impl.RandomControl;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RegistryTest {

    @Test
    void discoversTheBaselinesAndTheirH1Variants() {
        var all = StrategyRegistry.discover();
        assertThat(all).extracting(s -> s.meta().id()).containsExactly(
                "donchian_breakout@1", "donchian_breakout_h1@1", "ema_pullback@1", "ema_pullback_h1@1",
                "rsi_mean_reversion@1", "rsi_mean_reversion_h1@1");
    }

    @Test
    void oneSharedParameterSetNeverMentionsASymbol() {
        var catalog = SymbolCatalog.load(Optional.empty());
        for (Strategy s : StrategyRegistry.discover()) {
            String params = s.meta().params().toString();
            for (var d : catalog.all()) assertThat(params).as(s.meta().id()).doesNotContain(d.code());
            s.meta().params().values().forEach(v -> assertThat(v).as("scalar params only").isInstanceOfAny(Number.class, String.class));
        }
    }

    @Test
    void contextSymbolsAreNeverSignalSymbols() {
        var catalog = SymbolCatalog.load(Optional.empty());
        for (Strategy s : StrategyRegistry.discover()) {
            for (String code : s.meta().symbols()) assertThat(catalog.get(code).role()).as(s.meta().id() + " " + code).isEqualTo(SymbolCatalog.Role.SIGNAL);
        }
        var rsi = StrategyRegistry.byName(StrategyRegistry.discover(), "rsi_mean_reversion");
        assertThat(rsi.meta().symbols()).containsExactly("EURUSD", "GBPUSD", "USDJPY", "AUDUSD");
    }

    @Test
    void paramsHashChangesWithAnyParameterAndControlsGetTheirOwn() {
        var ema = StrategyRegistry.byName(StrategyRegistry.discover(), "ema_pullback");
        assertThat(ParamsHash.of(ema.meta())).isEqualTo(ParamsHash.of(StrategyRegistry.byName(StrategyRegistry.discover(), "ema_pullback").meta()));
        assertThat(ParamsHash.of(new RandomControl(ema, 0.01).meta())).isNotEqualTo(ParamsHash.of(ema.meta()));
        assertThat(ParamsHash.of(new RandomControl(ema, 0.01).meta())).isNotEqualTo(ParamsHash.of(new RandomControl(ema, 0.02).meta()));
    }
}
