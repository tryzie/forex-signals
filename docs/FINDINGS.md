# Findings: three textbook FX entries show no edge after realistic costs

*Forex Signal Platform, Phase 2. Written 2026-11-20. Data: Pepperstone cTrader demo feed (read-only).*

## Summary

Three widely taught entry rules were tested on three years of one-minute data for six instruments, with every cost a
retail trader would pay and with each rule compared to a random-entry control that shares its exits and risk rules.
**None of them shows an edge.** All three fail the pre-registered validation gate; none beats its random control. On
15-minute entries they lose about 0.12 to 0.13R per trade after costs, and they are already slightly negative before
costs. A one-hour diagnostic rerun reduces the cost drag by about a quarter but does not produce a tradable result.
No strategy was enabled for live signals.

## What was tested

| Strategy | Entry (long; short mirrored) | Exits | Symbols |
|---|---|---|---|
| EMA trend pullback | H4 close above EMA50(H4), EMA50(H1) rising; M15 EMA20 > EMA50; pullback to EMA20 within 3 bars; bullish close above EMA20 | stop 1.5 ATR, target 2R, breakeven at +1R then 2.5 ATR trail, 32-bar time stop, exit if an H1 bar closes below EMA50(H1) | EURUSD, GBPUSD, USDJPY, AUDUSD, XAUUSD, XAGUSD |
| Donchian breakout | close above the previous 20-bar high, H4 trend agrees, ATR14/ATR100 in [0.8, 2.0] | stop at the 10-bar low clamped to 1.0-1.5 ATR, target 2R, 10-bar Donchian trail, 48-bar time stop, failed-breakout exit | same six |
| RSI mean reversion | H1 ADX14 < 20; RSI14 crosses back above 30 | stop 1.5 ATR, target 1.5R, no trailing, 16-bar time stop, exit if H1 ADX >= 25 | FX majors only |

Parameters are textbook defaults, shared by all symbols, and were **frozen (hashed and stored) before any test was run**.
Nothing was tuned at any point.

## Method

- **Data:** 10.75 million M1 bars from cTrader (2023-11-19 to 2026-11-19). Every higher timeframe is built from M1 by the
  same candle builder the live system uses, and was checked to match cTrader's own M5 bars exactly over a full week.
- **One code path:** backtest, replay and live run the same engine. A regression test feeds the identical tick stream
  through backtest and replay and requires every signal and trade to match field by field.
- **No lookahead:** strategies see only closed bars through a guarded interface. A test strategy that tries to read the
  next bar makes the build fail. A shuffle test replaces every bar after a date and requires all earlier results to stay
  identical. A read-only reviewer audited the backtester separately and found no lookahead path.
- **Fills:** the signal is raised at the candle close. The fill is the first price at least 10 seconds later (manual
  entry), at the ask for longs and the bid for shorts, plus slippage. Never the signal candle's close. If a stop and a
  target fall inside the same minute, the stop wins.
- **Risk gate on every signal:** 1% risk sizing, R:R >= 1.5, sessions, the major-news blackout, the 3% daily loss cap,
  max 3 open signals, currency exposure limits and the total-cost cap (reject if estimated costs exceed 0.15R).
- **Random-entry control** for each strategy: same symbols, sessions, exits and risk gate, but entries at random times in
  a random direction, at the strategy's own measured signal rate.

### Costs (always on)

| Cost | Value | Source |
|---|---|---|
| Commission | FX 3.00, XAU 3.50, XAG 0.00 USD per side per lot | cTrader symbol specs (XAG pending a demo-statement check) |
| Spread | median per session bucket of one real tick-history week per quarter (72 symbol-weeks) | cTrader tick history |
| Slippage | FX 0.2 pip, USDJPY 0.2 pip, XAU 0.15, XAG 0.02; x2 outside London/New York, x3 in news windows | configuration |
| Fill-model bias | measured on about 34,000 real alert times against real ticks and added as extra slippage | cTrader ticks |
| Swap | broker swap rates, triple on Wednesday | cTrader symbol specs |

### The validation gate (decided before the results)

A strategy passes only if all eight checks pass: >= 200 trades over >= 12 months covering trending and ranging regimes;
expectancy >= +0.10R with a 90% bootstrap CI lower bound above zero (10,000 week-block resamples); max drawdown <= 10R;
profit factor >= 1.2; walk-forward (12 months in, 3 months out, stepped quarterly) out-of-sample expectancy > 0 and
>= 50% of in-sample with >= 50 out-of-sample trades; still positive with spreads x1.5 and slippage x2; no symbol more
than 50% of the profit; and better than its random control with a CI lower bound above zero.

## Results (15-minute entries)

| Strategy | Trades | Net R per trade | Gross R per trade | Cost per trade | 90% CI (net) | Profit factor | Out-of-sample | Verdict |
|---|---|---|---|---|---|---|---|---|
| EMA pullback | 4,992 | -0.129 | -0.025 | 0.104 | -0.161 to -0.097 | 0.78 | -0.136 (3,251 trades) | FAIL |
| Donchian breakout | 4,319 | -0.117 | -0.021 | 0.096 | -0.150 to -0.085 | 0.77 | -0.094 (2,841 trades) | FAIL |
| RSI mean reversion | 857 | -0.129 | -0.010 | 0.119 | -0.194 to -0.062 | 0.78 | -0.156 (537 trades) | FAIL |

**Parent vs random control.** The difference in R per trade between each strategy and its random control:
EMA -0.012 (CI -0.046 to 0.023), Donchian -0.016 (-0.049 to 0.016), RSI -0.020 (-0.105 to 0.067). The entry filters add
nothing measurable over entering at random times with the same exits. This is the central finding: the losses are not
bad luck around a real signal, they are what the exits and costs produce on their own.

Two further observations. Costs alone (about 0.10 to 0.12R per trade) would erase a small gross edge at this timeframe,
but there was no gross edge to erase. And XAGUSD never traded: every silver signal failed the total-cost cap, because
slippage and spread are large next to a 15-minute stop.

### H1-entry variants (diagnostic only, not a validation run)

The same rules one timeframe up (H1 entries, H4/D1 context), with the same frozen parameters, were run as a labelled
diagnostic after the main result. No verdict was recorded.

| Strategy (H1) | Trades | Net R | Gross R | Cost R (vs M15) | vs random control (90% CI) | Gate checks |
|---|---|---|---|---|---|---|
| EMA pullback | 1,925 | -0.025 | +0.051 | 0.077 (0.104) | +0.047 (-0.005 to 0.098) | fail |
| Donchian breakout | 1,520 | +0.026 | +0.100 | 0.074 (0.096) | +0.103 (0.046 to 0.161) | fail |
| RSI mean reversion | 408 | -0.036 | +0.056 | 0.092 (0.119) | +0.091 (-0.044 to 0.222) | fail |

The cost drag is about 25% smaller at H1, not dramatically so. All three still fail the gate (expectancy, drawdown,
profit factor and cost stress). Donchian H1 beats its control, but its own expectancy CI includes zero, it turns negative
under cost stress, and 63% of its positive R comes from gold. It is also one of six variants examined: with six
comparisons, the chance of at least one such win by luck alone is about one in four. It is a hypothesis for a new, separately pre-registered test, not a result.

## What the rigour caught along the way

Several defects were found by the tests and reviews before any result was accepted, each of which would have biased the
numbers: targets rounded below 1.5R and wrongly rejected; synthetic stops filled at bar extremes instead of the stop;
gross R inflated on target exits; a halting kill switch and a compounding account that both cut samples off after losing
streaks (and flattered the random-control comparison); out-of-sample numbers that were hidden in one table but visible
elsewhere; and a spread model that could use data from after a trade. All were fixed, with regression tests, before the
runs reported here.

## Limitations

- One broker's demo feed, three years, six instruments. Results may differ elsewhere.
- Spreads are modelled from one sampled week per quarter (median per session); the cost-stress check widens them.
- Only major scheduled news is filtered; standard high-impact releases are not. Bank of Japan times are approximate.
- Backtests replay each minute bar as four ticks; this is conservative when a stop and target share a minute (1-2 trades
  per strategy).
- For research, validation runs size on a constant 10,000 USD and record but do not act on the kill switch; the live
  rules are stricter.

## Conclusion

These three entry rules, as commonly taught, show no edge on these instruments over these three years. They do no
better than random entries with the same exits, and realistic costs turn a flat result into a steady loss. The value of
the exercise is the apparatus that reached this answer without fooling itself: frozen parameters, one engine for every
mode, enforced closed-bar access, costs on every trade, random controls and a gate fixed in advance.
