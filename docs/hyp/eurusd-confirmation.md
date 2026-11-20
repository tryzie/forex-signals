# Pre-registration: EURUSD entries with cross-market confirmation

- Status: **APPROVED 2026-11-20 (owner). Rules frozen as written below. Parameter hashes are recorded in §11 before the
  first run.** Nothing had been built or run for this hypothesis when it was approved.
- Written: 2026-11-20, before any test of the confirmation filter.
- Branch: `research/eurusd-confirmation`. `main` is untouched.
- Once approved, this document is frozen. Any change after approval (definition, parameter, threshold, data split) makes
  a new hypothesis with a new pre-registration, and this one is still reported.

## 1. Hypothesis

H1: A EURUSD entry taken only when the US dollar is weak (or strong) across other markets on the same closed H1 bar has a
higher after-cost expectancy than the same entry without that confirmation, and higher than random entries that pass the
same confirmation.

H0 (what we expect if the filter has no value): the confirmed entries do no better than the unconfirmed base entries and
no better than confirmed random entries.

## 2. What is already known (disclosed, because it limits what this test can show)

- Phase 2 (docs/FINDINGS.md) ran both base entries on all six symbols over the full three years, including the last 12
  months. On EURUSD alone, at M15 after costs: EMA pullback -0.176R per trade (923 trades), Donchian breakout -0.113R
  (918 trades). In the last 12 months: EMA -0.160R (298), Donchian -0.043R (294).
- So the **holdout is unseen only for the confirmation filter**, not for the base entries. The H1-entry diagnostic also
  covered the holdout period.
- The base entries have no edge on EURUSD. For a confirmed variant to pass, the filter has to turn roughly -0.11 to
  -0.18R per trade into at least +0.10R. That is a large change for a filter; the prior expectation is that it fails.

## 3. Variants (all four are reported, whatever the results)

| ID | Entry | Confirmation | Role |
|---|---|---|---|
| `eurusd_donchian_base@1` | Donchian breakout, EURUSD only | none | base |
| `eurusd_donchian_conf@1` | Donchian breakout, EURUSD only | gate in §5 | test |
| `eurusd_ema_base@1` | EMA pullback, EURUSD only | none | base |
| `eurusd_ema_conf@1` | EMA pullback, EURUSD only | gate in §5 | test |

Each variant also gets its random-entry controls (§6). No other variant will be run for this hypothesis. Results for all
four are published together in one report; none may be dropped.

## 4. Entry bases (frozen: the Phase 2 rules and parameters, unchanged)

- Entry timeframe **M15**, context H1/H4, exactly as in PLAN §7.3 and the Phase 2 code (frozen 2026-11-19 20:29 UTC).
  The only change is the symbol list: EURUSD only.
- Donchian: 20-bar breakout excluding the signal bar, H4 close vs EMA50(H4), ATR14/ATR100 in [0.8, 2.0], stop at the
  10-bar extreme clamped to 1.0-1.5 ATR14, target 2.0R, 10-bar Donchian trail, 48-bar time stop, failed-breakout exit
  within 4 bars.
- EMA pullback: H4 close vs EMA50(H4) and EMA50(H1) slope over 5 bars, EMA20 vs EMA50 on M15, pullback within
  0.25 ATR of EMA20 in the last 3 bars, trigger bar beyond EMA20 in the trend direction, stop 1.5 ATR14, target 2.0R,
  breakeven at +1R then 2.5 ATR chandelier, 32-bar time stop, H1 invalidation.

## 5. Confirmation gate (frozen definitions)

Applied after the base entry has produced a signal, before the risk gate. It is a hard filter: the signal is kept only if
the gate agrees with its direction; there is no partial credit and no change to stop, target or size.

**Reference bar.** For a signal at EURUSD M15 close time T, the reference bar is the latest H1 bar whose close time is
**at or before T minus 1 minute**. At T = 10:15, 10:30 or 10:45 this is the 09:00-10:00 bar; at T = 10:00 it is the
08:00-09:00 bar. The one-minute margin makes the rule identical in backtest and live: an H1 bar of another symbol that
closes at the same instant as the signal may not have been built yet when EURUSD is evaluated, so it is never used.
H1 bars are aligned to the 17:00 New York day (PLAN §5), built from M1 bid candles of each symbol.

**Per-symbol state on the reference bar** (H1, bid closes, EMA period 20, SMA-seeded, standard 2/(n+1) smoothing,
values only after the warm-up of 3 x 20 + 10 = 70 closed H1 bars):

- `up(X)`: the reference bar of X exists and its close > EMA20(H1) of X at that bar.
- `down(X)`: the reference bar of X exists and its close < EMA20(H1) of X at that bar.
- A close exactly equal to its EMA20 is neither up nor down.
- "Exists" means X has an H1 bar with exactly the reference bar's open time. A missing bar (no ticks, market closed,
  data gap) counts as not agreeing.

**Gate.**
- EURUSD **long** allowed only if `down(USDX)` AND at least 2 of {`up(GBPUSD)`, `up(AUDUSD)`, `up(XAUUSD)`}.
- EURUSD **short** allowed only if `up(USDX)` AND at least 2 of {`down(GBPUSD)`, `down(AUDUSD)`, `down(XAUUSD)`}.
- If the USDX reference bar is missing, the gate fails. USDX has a daily close of about 3 hours (16:59-20:00 New York);
  it falls outside the London and New York entry sessions, so in practice this costs no in-session signals.

**Parameters (frozen):** USDX source per decision O1 below; EMA period 20 on H1 for USDX and all three peers; peers =
GBPUSD, AUDUSD, XAUUSD; agreement threshold 2 of 3; reference-bar margin 1 minute. Nothing in this section is tuned.

**Context-only rule.** USDX, GBPUSD, AUDUSD and XAUUSD are read as context only. Only EURUSD is traded (the risk gate
already rejects non-SIGNAL symbols; GBPUSD/AUDUSD/XAUUSD are not traded here because the strategy lists only EURUSD).

## 6. Controls and comparisons

1. **Plain random control** (existing check 8): random entries on EURUSD at the base strategy's measured signal rate,
   random direction, same exits, sessions and risk gate.
2. **Confirmed random control** (new test b): random entries at the **base** strategy's measured signal rate per eligible
   bar, random direction, then the same confirmation gate applied to that random direction; same exits, sessions, risk
   gate. It answers: does the entry rule add anything once the confirmation is in place?
3. **Base vs confirmed** (new test a): the confirmed variant against the same base without confirmation.

All controls use the run's fixed seed and the parameters frozen here.

## 7. Data, costs and execution (unchanged from Phase 2)

- cTrader M1 history: **development** 2023-11-19 to 2025-11-19 (24 months); **holdout** 2025-11-19 to 2026-11-19
  (12 months). Warm-up uses earlier bars only where they exist (the first weeks of development produce no signals).
- Costs: commission FX 3.00 USD per side per lot, slippage 0.2 pip (x2 outside London/New York, x3 in news windows) plus
  the measured fill-model bias, modelled spread from the 12 sampled weeks (only weeks finished before each trade), swap.
- Fills: first price at or after signal + 10 s; M1 fill model as in Phase 2. Risk gate as in Phase 2, including the
  0.15R total-cost cap and the major-news blackout (config/news.csv).
- Validation conventions as accepted for Phase 2: constant 10,000 USD notional, kill switch recorded but not halting.

## 8. Success criteria (fixed now)

A confirmed variant succeeds only if **all** of the following hold. The base variants are reported for comparison; they
cannot "succeed" for this hypothesis.

**Stage 1, development period (24 months):**
- The existing validation gate (PLAN §8), all 8 checks, unchanged: >= 200 trades over >= 12 months covering trending and
  ranging regimes; expectancy >= +0.10R with a 90% week-block bootstrap CI lower bound > 0; max drawdown <= 10R;
  profit factor >= 1.2; walk-forward (12 months in, 3 months out, stepped 3 months) out-of-sample > 0 and >= 50% of
  in-sample with >= 50 out-of-sample trades; positive with spreads x1.5 and slippage x2; no symbol > 50% of positive R
  (trivially one symbol here: check 7 is reported as not applicable); beats the plain random control (CI lower bound > 0).
- **(a)** Confirmed minus base, R per trade: 90% week-block bootstrap CI lower bound > 0. Also reported: the mean R of
  the base trades the filter removed (expected negative if the filter adds value) and total R of both.
- **(b)** Confirmed minus confirmed random control, R per trade: 90% CI lower bound > 0.

**Stage 2, holdout (12 months), run once after Stage 1 is reported:**
- Expectancy > 0 and >= 50% of the Stage 1 expectancy, with >= 50 holdout trades (fewer is INCONCLUSIVE).
- (a) and (b) point estimates > 0 in the holdout (the holdout is too short for their CIs to be decisive; the CIs are
  reported anyway).

**Verdicts:** PASS only if Stage 1 and Stage 2 both pass. Any FAIL check is FAIL; too few trades is INCONCLUSIVE, which
counts as not passing. A pass would make the variant **eligible for SHADOW paper-tracking only**; nothing goes live.

**Multiplicity, disclosed:** two confirmed variants are tested against the same thresholds, with no correction. The
report will also show 95% CIs so that a borderline pass can be judged against a stricter bar. A single pass among the
four would be treated with caution, not as a discovery.

**Statistical power, disclosed:** the bases made about 625 EURUSD trades in the development window. If confirmation keeps
25-40% of them, a confirmed variant has roughly 150-250 trades. Below 200 it is INCONCLUSIVE on check 1, and with about
200 trades a CI lower bound above zero needs a mean around +0.12R or more. An INCONCLUSIVE result is a valid outcome and
will be reported as such.

## 9. What has to be built after approval (no results are looked at while building)

- A declared cross-symbol context in the strategy API: a strategy may name other symbols, a timeframe and indicators; the
  context returns their closed bars only, under the same lookahead guard, plus tests (including the 1-minute reference
  rule and a peeking test across symbols).
- The confirmation gate as a wrapper around an unchanged base strategy, and the confirmed random control.
- Development/holdout split runs, tests (a) and (b), and a report that always contains all four variants.
- Freeze: the four variants' parameter hashes are stored with `--mode=freeze` and checked against this document before
  the first run. The holdout run is a separate, later command.

## 10. Owner decisions (approved 2026-11-20)

- **O1. USDX source: the broker's USDX, after verification (§10.1).** The synthetic USDX_SYN is NEVER a gate input (it is
  about 58% EURUSD and would partly confirm EURUSD with itself). It appears only as a descriptive cross-check in the
  report. If the broker USDX cannot be verified clean, nothing is run: the owner is told first, and the synthetic is not
  substituted.
- **O2. Peer up/down: close vs EMA20(H1) on the reference bar**, the same rule as USDX.
- **O3. Entry timeframe: M15**, the pre-committed default (H1 would be informed by the H1 diagnostic).

### 10.1 USDX verification criteria (fixed before any USDX check is computed)

The broker USDX (`USDX` on the Pepperstone cTrader demo) is "verified clean" only if all hold over 2023-11-19 to
2026-11-19:

- **V1 spec:** the stored cTrader spec matches what was reviewed (digits 3, lot size 100, min volume 1, volume step 1,
  schedule Sun 18:00 to Fri 16:55 New York with a daily close 16:59-20:00), and no SPEC_CHANGED event exists for USDX.
- **V2 coverage:** during London or New York session hours on weekdays, an H1 USDX bar exists for >= 98% of the hours in
  which an H1 EURUSD bar exists.
- **V3 consistency:** H1 log returns of USDX correlate >= 0.90 with H1 log returns of the ICE formula USDX_SYN
  (computed from bid closes of the six inputs), and <= -0.80 with EURUSD.
- **V4 no artificial jumps:** no H1 bar where USDX moves more than 0.30% while USDX_SYN moves less than a third of that
  (such jumps would be contract rolls or data errors, which flip "close vs EMA20" for reasons unrelated to the dollar).
  If such jumps exist, USDX is NOT verified clean and the owner is told before any run.

The result of this check is reported to the owner. Only if all four pass is `verified="true"` set for USDX.

## 10.2 Additional rules locked by the owner (2026-11-20)

- **All four variants are reported together, always** (§3).
- **Trade count is shown prominently.** A confirmed variant with fewer than 200 development trades is INCONCLUSIVE, not a
  pass, however good its point estimate.
- **Multiple comparisons (two confirmed variants): Bonferroni.** For every confidence-interval test (expectancy CI, beats
  plain random control, (a) beats base, (b) beats confirmed random control) the report shows the raw 90% interval AND the
  Bonferroni-adjusted interval for two hypotheses (95%). **A PASS requires the adjusted (95%) lower bounds > 0.** The raw
  intervals are shown for information.
- **The holdout runs exactly once**, after development is complete and the owner has seen the development result. The
  software refuses a second holdout run and refuses a holdout run before a development run exists. No peeking, no re-runs.
- **Parameters must be frozen to run at all:** the hypothesis run refuses to start unless all four variants' parameter
  hashes match the frozen hashes recorded in §11.

## 11. Freeze record

(Filled in when the build is complete and before the first development run.)
