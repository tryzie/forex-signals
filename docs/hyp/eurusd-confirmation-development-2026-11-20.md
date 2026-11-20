# EURUSD cross-market confirmation: DEVELOPMENT stage

Pre-registration: `docs/hyp/eurusd-confirmation.md` (approved and frozen 2026-11-20). Run at 2026-11-20T10:30:24Z UTC.

Window: **2023-11-19T00:00:00Z to 2025-11-19T00:00:00Z** (signals raised in [from, to)).

> **Development stage only.** No holdout data was loaded. The holdout runs exactly once, after the owner has seen this result.

Costs: commission, slippage (session/news multipliers) plus measured fill bias {EURUSD=1.0118767945700792E-5} from 8 sampled weeks ending by 2025-11-19T00:00:00Z, modelled spread, swap; constant 10,000 USD notional, kill switch recorded not halting

## Frozen parameter hashes

| Variant | Params hash (frozen) | Run hash |
|---|---|---|
| eurusd_donchian_base@1 | `607a7a4e6a7fe6e7a5a1bea524874cbc6c0b482940fce20972d19da0dc0e9243` | `607a7a4e6a7fe6e7a5a1bea524874cbc6c0b482940fce20972d19da0dc0e9243` |
| eurusd_donchian_conf@1 | `99574b97938d01a9f3dc84ee18e19ad1147d3be95286d9fbd735fcbd2aea0e65` | `99574b97938d01a9f3dc84ee18e19ad1147d3be95286d9fbd735fcbd2aea0e65` |
| eurusd_ema_base@1 | `8ec743544e2b5f266ebf796fdf1794f55782e73f601c8ae9d2795f7eb6a50d03` | `8ec743544e2b5f266ebf796fdf1794f55782e73f601c8ae9d2795f7eb6a50d03` |
| eurusd_ema_conf@1 | `b60eb3ccf0897e4b104df5d4f7cdbc9b7ee556a4d14a4647e8471db0f1bec176` | `b60eb3ccf0897e4b104df5d4f7cdbc9b7ee556a4d14a4647e8471db0f1bec176` |

## All four variants

Trades are shown first: below 200 trades a confirmed variant is INCONCLUSIVE, whatever its estimate. Net R per trade after all costs. CIs: 10,000 week-block bootstrap resamples; raw = 90%, adjusted = Bonferroni for two hypotheses (95%). **A pass needs the adjusted lower bounds > 0.**

| Variant | Trades | Net R | Gross R | Cost R | Raw 90% CI | Adj. 95% CI | PF | Max DD (R) | Stress net R | vs plain control | raw CI | adj. CI | Verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| eurusd_donchian_base@1 | **998** | -0.157 | -0.060 | 0.097 | -0.217 .. -0.100 | -0.229 .. -0.089 | 0.69 | 165.3 | -0.169 | -0.063 | -0.134 .. 0.008 | -0.147 .. 0.021 | comparison only (base) |
| eurusd_donchian_conf@1 | **736** | -0.156 | -0.066 | 0.090 | -0.220 .. -0.093 | -0.233 .. -0.082 | 0.69 | 127.9 | -0.188 | -0.087 | -0.172 .. -0.007 | -0.186 .. 0.010 | **FAIL** |
| eurusd_ema_base@1 | **1307** | -0.201 | -0.098 | 0.103 | -0.271 .. -0.131 | -0.286 .. -0.119 | 0.67 | 275.4 | -0.155 | -0.047 | -0.114 .. 0.018 | -0.128 .. 0.030 | comparison only (base) |
| eurusd_ema_conf@1 | **1051** | -0.198 | -0.095 | 0.103 | -0.274 .. -0.119 | -0.290 .. -0.104 | 0.67 | 223.7 | -0.143 | -0.059 | -0.131 .. 0.009 | -0.144 .. 0.024 | **FAIL** |

## Tests (a) and (b)

| Confirmed variant | Trades | (a) vs base: diff | raw 90% CI | adj. 95% CI | (b) vs confirmed random control: diff | raw 90% CI | adj. 95% CI |
|---|---|---|---|---|---|---|---|
| eurusd_donchian_conf@1 | **736** | +0.000 | -0.033 .. 0.034 | -0.039 .. 0.040 | -0.080 | -0.170 .. 0.011 | -0.188 .. 0.029 |
| eurusd_ema_conf@1 | **1051** | +0.004 | -0.031 .. 0.037 | -0.038 .. 0.042 | -0.090 | -0.170 .. -0.013 | -0.184 .. 0.002 |

## What the filter removed

Base trades matched to the confirmed variant on signal time and direction.

| Pair | Base trades kept | Kept mean R | Base trades removed | Removed mean R | Base total R | Confirmed total R |
|---|---|---|---|---|---|---|
| eurusd_donchian_base@1 / eurusd_donchian_conf@1 | 700 | -0.157 | 298 | -0.157 | -156.5 | -115.1 |
| eurusd_ema_base@1 / eurusd_ema_conf@1 | 968 | -0.211 | 339 | -0.173 | -262.8 | -207.6 |

Note: a confirmed trade can differ from its base twin when the risk gate state differs (e.g. max open signals), so kept + removed need not equal the confirmed count exactly.

## Controls

| Control | Rate per eligible bar | Trades | Net R |
|---|---|---|---|
| plain random control of eurusd_donchian_base@1 | 0.04517 | 652 | -0.094 |
| plain random control of eurusd_donchian_conf@1 | 0.03109 | 434 | -0.070 |
| confirmed random control of eurusd_donchian_conf@1 (base rate, then the gate) | 0.04517 | 303 | -0.077 |
| plain random control of eurusd_ema_base@1 | 0.13011 | 2205 | -0.154 |
| plain random control of eurusd_ema_conf@1 | 0.08388 | 1512 | -0.138 |
| confirmed random control of eurusd_ema_conf@1 (base rate, then the gate) | 0.13011 | 918 | -0.107 |

## Verdict details (confirmed variants)

### eurusd_donchian_conf@1: FAIL

Validation-gate checks (checks 2 and 8 are judged on the adjusted intervals above; raw shown here):

| # | Check | Status | Observed | Rule |
|---|---|---|---|---|
| 1 | sample | PASS | 736 trades over 22.7 months; regimes: traded in 9 trending and 8 ranging months (H4 ADX14 terciles: <= 22.8 ranging, >= 29.0 trending) | >= 200 trades, >= 12 months, trending + ranging |
| 2 | expectancy | FAIL | -0.156R (90% CI -0.220..-0.093) | mean >= 0.10R and CI low > 0 |
| 3 | drawdown | FAIL | 127.94R | <= 10R |
| 4 | profit factor | FAIL | 0.69 | >= 1.2 |
| 5 | walk-forward | FAIL | OOS -0.166R over 468 trades vs IS -0.157R | OOS > 0, >= 50% of IS, >= 50 OOS trades |
| 6 | cost stress | FAIL | -0.204R with spread x1.5, slippage x2 | > 0 |
| 7 | concentration | PASS | n/a: single-symbol strategy | <= 50% of positive R |
| 8 | beats chance | FAIL | diff -0.087R (90% CI -0.172..-0.007) | CI low of (strategy - control) > 0 |

Why not a pass:

- expectancy -0.156R, adjusted 95% CI low -0.233
- vs plain random control: adjusted CI low -0.186
- (a) vs base without confirmation: adjusted CI low -0.039
- (b) vs confirmed random control: adjusted CI low -0.188
- check 3 drawdown: 127.94R
- check 4 profit factor: 0.69
- check 5 walk-forward: OOS -0.166R over 468 trades vs IS -0.157R
- check 6 cost stress: -0.204R with spread x1.5, slippage x2

Rejections by the risk gate: {COST_VS_STOP=491, OUTSIDE_SESSION=55, NEWS_BLACKOUT=12, DAILY_LOSS_CAP=11, CURRENCY_EXPOSURE=161}

### eurusd_ema_conf@1: FAIL

Validation-gate checks (checks 2 and 8 are judged on the adjusted intervals above; raw shown here):

| # | Check | Status | Observed | Rule |
|---|---|---|---|---|
| 1 | sample | PASS | 1051 trades over 22.5 months; regimes: traded in 9 trending and 8 ranging months (H4 ADX14 terciles: <= 22.8 ranging, >= 29.0 trending) | >= 200 trades, >= 12 months, trending + ranging |
| 2 | expectancy | FAIL | -0.198R (90% CI -0.274..-0.119) | mean >= 0.10R and CI low > 0 |
| 3 | drawdown | FAIL | 223.67R | <= 10R |
| 4 | profit factor | FAIL | 0.67 | >= 1.2 |
| 5 | walk-forward | FAIL | OOS -0.173R over 700 trades vs IS -0.204R | OOS > 0, >= 50% of IS, >= 50 OOS trades |
| 6 | cost stress | FAIL | -0.248R with spread x1.5, slippage x2 | > 0 |
| 7 | concentration | PASS | n/a: single-symbol strategy | <= 50% of positive R |
| 8 | beats chance | FAIL | diff -0.059R (90% CI -0.131..0.009) | CI low of (strategy - control) > 0 |

Why not a pass:

- expectancy -0.198R, adjusted 95% CI low -0.290
- vs plain random control: adjusted CI low -0.144
- (a) vs base without confirmation: adjusted CI low -0.038
- (b) vs confirmed random control: adjusted CI low -0.184
- check 3 drawdown: 223.67R
- check 4 profit factor: 0.67
- check 5 walk-forward: OOS -0.173R over 700 trades vs IS -0.204R
- check 6 cost stress: -0.248R with spread x1.5, slippage x2

Rejections by the risk gate: {COST_VS_STOP=1913, OUTSIDE_SESSION=232, NEWS_BLACKOUT=14, DAILY_LOSS_CAP=130, CURRENCY_EXPOSURE=615}

## USDX_SYN cross-check (descriptive, never in the gate)

- eurusd_donchian_conf@1: 733 of 736 kept signals (99.6%) had USDX_SYN on the same side of its EMA20(H1) as the broker USDX; 0 without a comparable bar. Descriptive only: USDX_SYN is never a gate input.
- eurusd_ema_conf@1: 1040 of 1051 kept signals (99.0%) had USDX_SYN on the same side of its EMA20(H1) as the broker USDX; 0 without a comparable bar. Descriptive only: USDX_SYN is never a gate input.

## Disclosures

- The base entry rules were tested in Phase 2 on all three years, so no window, including the holdout, is fresh for them; the confirmation gate and the split were fixed before any run.
- Two confirmed variants: CI tests use Bonferroni-adjusted 95% intervals; raw 90% shown for information.
- Verdicts here are Stage 1 (development) only. A final PASS needs Stage 2 (holdout, run once) as well; a pass would allow SHADOW tracking only.
