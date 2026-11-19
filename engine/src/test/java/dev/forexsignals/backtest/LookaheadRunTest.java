package dev.forexsignals.backtest;

import dev.forexsignals.market.Timeframe;
import dev.forexsignals.risk.Rig;
import dev.forexsignals.strategy.api.LookaheadViolation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Criterion C6 end to end: a peeking strategy inside a full backtest aborts the run (nothing catches the violation). */
class LookaheadRunTest {
    @Test
    void peekingStrategyAbortsTheBacktest() {
        var data = EngineRig.m1(List.of("EURUSD"), EngineRig.FROM, EngineRig.FROM.plusSeconds(3 * 86_400), 4);
        var peeker = dev.forexsignals.strategy.LookaheadGuardTest.peeker(ctx -> {
            if (ctx.bars(Timeframe.M15) > 0) ctx.bar(Timeframe.M15, -1); // "what does the next bar look like?"
        });
        Engine e = EngineRig.engine(List.of(peeker), Rig.costs(), data, 1);
        assertThatThrownBy(() -> EngineRig.backtest(e, data, EngineRig.FROM, EngineRig.FROM.plusSeconds(3 * 86_400)))
                .isInstanceOf(LookaheadViolation.class);
    }
}
