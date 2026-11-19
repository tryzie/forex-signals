# ADR 0004: Candle alignment and closing rules

- Status: Accepted (Phase 0, 2026-11-18; implemented Phase 1)

## Decision
- M1, M5, M15 align to UTC minutes. H1, H4, D1 align to the **17:00 New York trading day** (H4 opens at
  17, 21, 01, 05, 09, 13 NY), which matches broker server time (NY + 7 h) through DST changes.
- Bars are built on the feed's price basis (cTrader: bid; Deriv: quote). Higher timeframes are built only
  from closed M1 bars, by the same `CandleBuilder` for live, replay and import.
- A bar closes on the first tick of a later period or at end + 2 s grace, whichever comes first.
  Late ticks are counted and never applied. Periods without ticks produce no bar.
- Bars that started before a (re)connect, a disconnect, or the start of a replay or import window are
  incomplete and are not emitted (added in Phase 1 after a live run stored a partial first minute).

## Consequences
Bars tile time exactly across both DST weekends (tested). A reconnect costs the H4/D1 bar in progress
until missed M1 bars can be backfilled from cTrader trendbars (1b).
