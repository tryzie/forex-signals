# ADR 0005: M1-bar backtests with adverse-first synthetic ticks

- Status: Accepted (Phase 0, 2026-11-18). Used from Phase 2.

## Decision
Only M1 is imported (3 years). For multi-year backtests each M1 bid bar becomes four synthetic ticks:
open, then the adverse extreme, then the favourable extreme, then close. The ask is bid + the modelled
spread. If stop and target fall in the same bar, the stop wins, and how often that happens is reported.
Recorded live ticks or cTrader tick history give the high-fidelity cross-check on recent weeks.

## Consequences
Results are deliberately conservative. Phase 1 provides the M1 import, the derived timeframes and the
tick replay path that this relies on.
