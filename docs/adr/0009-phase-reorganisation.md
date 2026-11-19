# ADR 0009: Move tracker, risk gate, sizing and the validation gate into Phase 2

- Status: Accepted
- Date: 2026-11-19
- Decider: project owner (approved with the Phase 2 criteria)

## Context
The original plan built the strategy engine and backtester in Phase 2, the risk gate, sizing and trade tracker in
Phase 3, and metrics, bootstrap, walk-forward and the validation gate in Phase 5. The approved Phase 2 constraints
need all of them at once: random-entry controls must keep the parent's exits, sessions and risk gate; every run must
include costs and the total-cost filter (maxCostR); and baselines must be judged by the validation gate. A backtester
without the gate and tracker would produce results that change later, when the missing parts arrive.

## Decision
- **Phase 2:** strategy engine, indicators, guards, baselines and controls, trade tracker, risk gate (all 15
  rejection reasons), position sizer, exposure book, cost model, shared Engine, backtester, news CSV (major events),
  frozen parameters, metrics, block bootstrap, walk-forward, cost stress, validation gate, reports.
- **Phase 3:** live signal service and journal, paper broker on live ticks, live news refresh and stale fallback,
  kill-switch operations.
- **Phase 5:** paper trading of live signals, the 100-signal counter, daily report, SHADOW to LIVE switch.

## Consequences
Phase 2 is larger, but every Phase 2 result already includes the full risk gate and costs, and the same tracker and
gate code serve live in Phase 3. No strategy can be switched to LIVE before Phase 5.
