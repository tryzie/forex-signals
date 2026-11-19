package dev.forexsignals.backtest;

import dev.forexsignals.paper.CostModel;
import dev.forexsignals.report.BacktestReport;
import dev.forexsignals.risk.NewsCalendar;
import dev.forexsignals.risk.Rig;
import dev.forexsignals.risk.RiskGate;
import dev.forexsignals.strategy.StrategyRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The validation pipeline end to end on synthetic data (no database): control, stress, gate, report notes. */
class ValidatorTest {

    @Test
    void pipelineRunsParentControlAndStressAndTheReportCarriesItsNotes(@TempDir Path dir) throws Exception {
        var data = EngineRig.m1(List.of("EURUSD", "USDJPY", "XAUUSD", "XAGUSD", "GBPUSD", "AUDUSD"), EngineRig.FROM, EngineRig.TO, 8);
        var news = new NewsCalendar(List.of(), Instant.parse("2023-01-01T00:00:00Z"), Instant.parse("2030-01-01T00:00:00Z"));
        var in = new Validator.Inputs("synthetic", EngineRig.FROM, EngineRig.TO, data, Rig.COSTS, Rig.SPECS, Rig.spreads(Rig.MEDIANS), news,
                Rig.CATALOG, RiskGate.Config.defaults(), 3, Duration.ofSeconds(10));
        var r = Validator.validate(in, StrategyRegistry.byName(StrategyRegistry.discover(), "ema_pullback"), null, null);

        assertThat(r.parent().role()).isEqualTo("PARENT");
        assertThat(r.control().strategyId()).isEqualTo("ema_pullback__random@1");
        assertThat(r.controlProbability()).isEqualTo((double) r.parent().proposals() / r.parent().eligibleBars());
        assertThat(r.stressed().metrics().trades() == 0 || r.stressed().metrics().avgCostR() >= r.parent().metrics().avgCostR() - 1e-9)
                .as("stressed costs are never cheaper").isTrue();
        assertThat(r.oosWithheld()).isPresent();
        assertThat(r.repricedStress().trades()).as("same trade set as the parent").isEqualTo(r.parent().metrics().trades());
        if (r.parent().metrics().trades() > 0) assertThat(r.repricedStress().expectancy()).isLessThan(r.parent().metrics().expectancy());
        assertThat(r.gate().verdict().name()).isNotEqualTo("PASS"); // 3 months of synthetic data cannot pass (sample, WITHHELD)

        Path html = BacktestReport.write(dir, new BacktestReport.Context(Instant.parse("2026-11-20T00:00:00Z"), "synthetic", EngineRig.FROM, EngineRig.TO,
                Rig.COSTS, Rig.spreads(Rig.MEDIANS), news, Map.of(), List.of("2024Q1")), List.of(r));
        String text = Files.readString(html);
        assertThat(text).contains(BacktestReport.FILL_NOTE.substring(0, 40)).contains("MODELLED").contains("ONLY MAJOR events")
                .contains("OUT-OF-SAMPLE NUMBERS WITHHELD").contains("No strategy is enabled for live signals");
        assertThat(Files.list(dir).filter(p -> p.toString().endsWith(".json")).count()).isEqualTo(1);
    }
}
