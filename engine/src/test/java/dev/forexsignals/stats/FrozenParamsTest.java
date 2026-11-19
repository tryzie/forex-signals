package dev.forexsignals.stats;

import dev.forexsignals.store.ResearchStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Criterion G24: OOS numbers are shown only for a parameter set frozen before the run and unchanged since. */
class FrozenParamsTest {
    static final Instant RUN = Instant.parse("2026-11-20T10:00:00Z");

    @Test
    void oosIsWithheldUnlessFrozenBeforeTheRunWithTheSameHash() {
        var frozen = Optional.of(new ResearchStore.Frozen("ema_pullback@1", "a".repeat(64), RUN.minusSeconds(3600)));
        assertThat(ResearchStore.oosWithheldReason(frozen, "a".repeat(64), RUN)).isEmpty();
        assertThat(ResearchStore.oosWithheldReason(Optional.empty(), "a".repeat(64), RUN)).get().asString().contains("not frozen");
        assertThat(ResearchStore.oosWithheldReason(frozen, "b".repeat(64), RUN)).get().asString().contains("changed after the freeze");
        var late = Optional.of(new ResearchStore.Frozen("ema_pullback@1", "a".repeat(64), RUN.plusSeconds(1)));
        assertThat(ResearchStore.oosWithheldReason(late, "a".repeat(64), RUN)).get().asString().contains("after this run started");
    }
}
