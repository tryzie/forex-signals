# CLAUDE.md — Forex Signal Platform

Real-time forex SIGNAL platform for demo-account testing. It tells the user when to
enter, why, and when to exit. It never places orders. Full plan: `docs/PLAN.md`.

## Non-negotiable rules
1. **SIGNALS ONLY.** Never place, modify or close orders on any account, demo or live.
   - The cTrader OAuth token must use the `accounts` (read-only) scope, never `trading`.
   - No code may build or send cTrader order messages (`ProtoOANewOrderReq`,
     `ProtoOAAmendPositionSLTPReq`, `ProtoOAClosePositionReq`, `ProtoOACancelOrderReq`, ...).
     A unit test scans the code for these names and fails the build if any appear.
   - Execution, if ever added, is a separate, explicitly approved phase and demo-only.
2. **Closed candles only.** Strategies are evaluated when a candle closes. No lookahead
   and no repainting indicators. Ticks are used only to track stops, targets and spreads.
3. **One code path.** Backtest, replay and live run the same engine. The only differences
   are the clock (simulated or wall clock) and the data source. The regression test
   "backtest equals replay" must always pass.
4. **Costs always on.** Every backtest and paper trade includes spread, commission and
   slippage. All three are configurable (`conf.xml` and the `cost_profile` table).
5. **Validation gate.** A strategy cannot send live signals (dashboard or Telegram) until it
   passes the validation gate in `docs/PLAN.md` §8. Until then it runs in SHADOW mode,
   where signals are journaled but not delivered.
6. **Risk gate.** Every signal passes the risk gate. Strategies have no way to bypass it,
   because the signal service only accepts objects the gate approved.
7. **No secrets in git.** Tokens and passwords live in `conf.xml` (gitignored and
   AES-encrypted at startup). The master key is kept in a file outside the repo (chmod 600).
   Raw transcripts, `inbox/`, market-data dumps and `data/` are gitignored.
8. **Honest numbers.** Never claim performance that hasn't been measured. No made-up
   "confidence %". Any score shown comes from measured history, with its sample size
   and confidence interval.
9. **Synthetic or demo data only.** Never use real client or employer data.
10. **Small commits, tests first.** Run the full test suite before every commit. Never
    skip, disable or `@Ignore` a failing test to get green.

## User decisions (Phase 0)
- Primary feed: cTrader Open API on a Pepperstone demo account. Fallback: Deriv, behind the same `FeedAdapter`.
- Symbols: EURUSD, GBPUSD, USDJPY, AUDUSD, XAUUSD, XAGUSD, plus USDX (context only:
  no signals until its contract specs and trading hours are verified). Symbols missing on a feed are optional there.
- Timeframes: M15 entries, H1/H4 context. M5 comes later, once costs are modelled.
- Data-only symbols: USDCAD, USDSEK, USDCHF. They're inputs to the synthetic index "USDX_SYN"
  (labelled as approximating USDX, used only as a context filter). They never generate signals or affect sizing.
- Account: USD 10,000, Pepperstone **Razor** on cTrader. Commission is per side, per lot, per instrument
  class (checked against the demo statement). Slippage is in price units per symbol (FX 0.2 pips, XAU 0.15,
  XAG 0.02), ×2 outside London and New York, ×3 in news windows. The paper fill uses the price
  10 s after the alert. Never use the signal candle's close as the fill price.
- History: M1 only (3 years). Higher timeframes are always derived from M1. The import must be resumable and
  idempotent. Data stays out of git.
- Stale news calendar = fail safe (fallback windows 08:00–10:30 New York, plus 07:00–09:30 London for EUR and GBP).
- Risk: 1% per trade, 3% daily loss (realized plus floating, day rolls at 17:00 New York),
  10% drawdown kill switch (manual reset), max 3 open signals, minimum R:R 1.5, max 2 signals
  per currency per direction (XAU and XAG form one METALS cluster), net USD exposure capped the same way.
- Costs: calibration week, then a spread cap of 2.5x the median per symbol and session. Always reject if the
  estimated spread + commission + slippage is more than 0.15R (`maxCostR`, configurable). R:R ≥ 1.5 is measured
  on price distances. Journal gross R and net R for every signal, and count rejections by reason.
- A failing baseline is a result, not a bug. If an M15 strategy fails on costs, try an H1-entry variant
  before concluding anything. Never tune to make a test pass.
- Secrets (client secret, tokens, Telegram token) live only in the encrypted `conf.xml` on the VM,
  never in the repo, docs or chat.
- Sessions use IANA zones: London 08:00–17:00 Europe/London, New York 08:00–17:00
  America/New_York, displayed in EAT. No new entries in the last 2 hours before the Friday close.
- News blackout: ±15 minutes for high-impact events, ±30 minutes for NFP, FOMC, CPI and central-bank
  rate decisions. Applies to the currencies involved (USD for metals and USDX).

## Environment and conventions
- Engine: Java 25 LTS, **Maven 3.9.x via `./mvnw` only** (never system mvn, never Gradle). Every version lives in the root pom `<properties>`.
  `make test` = `./mvnw -B verify` (Surefire unit tests + Failsafe `*IT`). The fat jar `forex-signals.jar` runs as `java -jar forex-signals.jar --mode=live|replay|import|gapreport|spotcheck`. Database: Percona Distribution for PostgreSQL 18, Flyway
  migrations. Dashboard: React, TypeScript, Vite, Node 22.
- Development VM: Parallels Ubuntu, `parallels@10.211.55.5`. Use SSH key login only.
- Store every timestamp in UTC (`timestamptz`, `Instant`). Display EAT (Africa/Nairobi).
- Config: `conf.xml` uses the CLEARTEXT to ENCRYPTED rewrite (AES-256-GCM).
  `conf.example.xml` is the committed template.
- Code style: long, linear, self-contained functions with inline logic and clear comments.
  Split code only at real boundaries: I/O, the `Strategy` interface, the risk gate,
  the feed adapter and the clock.
- Before each phase, summarise the plan and the files to be touched. After each phase,
  summarise the changes, how to run them and what to check. Then stop for review.
- Ask instead of guessing on trading rules, money handling and risk limits.
  Flag anything over-fitted or too good to be true.
