# Forex Signal Platform

Real-time forex **signal** platform for demo-account testing. It tells you when to enter, why, and when
to exit. **It never places, modifies or closes orders** (see `CLAUDE.md`). Plan: `docs/PLAN.md`.

Status: **Phase 1 (foundation)**. Market data, candles, recording, history import and checks are in
place. There are no strategies or signals yet. cTrader is "1b: pending" until Spotware approves the
Open API app; Deriv and a synthetic feed stand in until then.

## Requirements (development VM)

- Ubuntu 26.04 aarch64, Temurin **JDK 25**, Docker Engine + compose plugin, Node 22 (dashboard, later).
- **No system Maven.** Always use `./mvnw` (pinned Maven 3.9.16; the enforcer fails the build otherwise).
- Your user must be in the `docker` group (`sudo usermod -aG docker $USER`, then log out and in).

## Quick start

```bash
cp .env.example .env            # set PG_PASSWORD (any long random string)
make up                         # Percona PostgreSQL 18.7.1-1, waits until healthy
make test                       # ./mvnw -B verify: unit tests + integration tests (Testcontainers)
make jar                        # builds engine/target/forex-signals.jar
```

Then create your config (next section) and run, for example:

```bash
java -jar engine/target/forex-signals.jar --mode=live --feed=deriv
```

## Configuration: `~/.forexsignals/conf.xml`

Create it yourself on the VM. **Never paste secrets into chat, docs or git.**

```bash
mkdir -p ~/.forexsignals && chmod 700 ~/.forexsignals
cp conf.example.xml ~/.forexsignals/conf.xml && chmod 600 ~/.forexsignals/conf.xml
nano ~/.forexsignals/conf.xml
```

- `<database><password TYPE="CLEARTEXT">`: the same value as `PG_PASSWORD` in `.env`.
- On first start, every non-empty `TYPE="CLEARTEXT"` value is encrypted (AES-256-GCM) and the file is
  rewritten with `TYPE="ENCRYPTED"`. The key is created at `~/.forexsignals/master.key` (chmod 600).
  The app refuses to start unless `master.key` has mode 600 (or 400) and is owned by the user running the app. If you lose the key, re-enter
  the secrets as CLEARTEXT.
- Override paths with `--config=<path>` (or `$FOREXSIGNALS_CONF`) and `$FOREXSIGNALS_KEY_FILE`.
- The symbol catalogue (roles, broker names, required/optional per feed) is `config/symbols.xml`,
  bundled in the jar. Set `<symbols file="..."/>` in conf.xml to use another copy.

### Feed credentials

| Feed | What it needs | How to get it |
|---|---|---|
| **Deriv** (fallback, works now) | **Nothing** for market data: it uses the public endpoint `wss://api.derivws.com/trading/v1/options/ws/public` with no token. `appId` is optional. | If you want an App ID anyway: log in at developers.deriv.com → Dashboard → register an application → copy the new App ID into `<deriv appId="...">`. Legacy App IDs (e.g. 1089) do not work with the new API. An App ID is not a secret. |
| **cTrader** (primary, 1b pending) | `clientId`, `clientSecret` (from your approved Open API app), `accessToken` + `refreshToken` with the **`accounts`** (read-only) scope, and `accountNumber` (your demo account number). | `docs/SETUP-ACCOUNTS.md` §3. Never choose the `trading` scope: the adapter refuses trading-scope tokens and live accounts. |
| Telegram (Phase 4) | `botToken`, `chatId` | `docs/SETUP-ACCOUNTS.md` §4 |

## The jar: `engine/target/forex-signals.jar`

```
java -jar forex-signals.jar --mode=<live|replay|import|gapreport|spotcheck> [options]
```

Every option is `--key=value` (a bare `--flag` means `true`). Each mode loads conf.xml, connects to
Postgres and applies Flyway migrations (bundled in the jar), then runs.
Exit codes: `0` ok, `1` failure, `2` usage error, `3` replay check found unexplained mismatches.
Unknown options (for example `--compare_m5`) are rejected with exit code `2`.
Logs are JSON lines on stdout (UTC timestamps). Reports go to `data/reports/` unless `--report=` is given.
`data/reports` is relative to the working directory: run the jar from the repo root so it lands in the
gitignored `data/` folder.

| Option | Modes | Meaning |
|---|---|---|
| `--config=<path>` | all | conf.xml (default `~/.forexsignals/conf.xml`) |
| `--feed=<deriv\|ctrader\|synthetic>` | all | which feed; default `<feed primary=...>` in conf.xml. Feeds are never mixed |
| `--symbols=A,B` | all | limit to these catalogue codes (default: every symbol mapped on the feed) |
| `--duration=PT10M` | live | stop after an ISO-8601 duration (default: run until stopped) |
| `--seed=<n>` | live (synthetic), spotcheck | random seed (default 1) |
| `--from=<ISO>` `--to=<ISO>` | replay (required), import | UTC instants, e.g. `2026-11-09T00:00:00Z`. Import: `--to` defaults to today 00:00 UTC, `--from` to `--to` minus `--years`; `--from` overrides `--years` |
| `--source=<db\|feed>` | replay | `db`: ticks recorded by live mode (default). `feed`: the feed's tick history |
| `--compare-m5` | replay | compare built M5 candles with the feed's own M5 candles; writes `m5check-*.md` |
| `--write-candles` | replay | also store the rebuilt candles |
| `--years=<n>` | import | history depth (default `<import years>` = 3) |
| `--report=<dir>` | replay, gapreport, spotcheck | report directory (default `data/reports`) |
| `--list-min=<n>` | gapreport | list unexpected gaps with at least n open-market minutes (default 5) |
| `--per-tf=<n>` | spotcheck | random bars per timeframe per symbol (default 20) |

### Modes

- **live**: checks symbols and specs (a missing *required* symbol stops startup), streams ticks, records
  every tick (bid/ask) for spread calibration, builds M1 → M5/M15/H1/H4/D1, writes candles, and tracks
  feed health (stale symbols, disconnects: logged and stored in `feed_event`). Bars that were forming
  across a connect or disconnect are incomplete and are not stored.
- **replay**: runs ticks for a window through the *same* pipeline with a simulated clock.
  `--compare-m5` is the Phase 1 candle check. On cTrader it compares against trendbars; on Deriv,
  against Deriv's own candles (stand-in until 1b).
- **import**: resumable, idempotent M1 history import in month chunks, paced (cTrader ≤ 4 req/s,
  Deriv 2 req/s), with backoff on rate limits. Safe to kill at any point: rerun the same command.
  Higher timeframes are rebuilt from M1 afterwards. Deriv only has about one year of M1.
- **gapreport**: missing minutes per symbol in `candle_m1`. Expected gaps (weekend, metals' daily break,
  rollover) are summarised; unexpected ones are listed with UTC, EAT and broker server time.
- **spotcheck**: 20 random M15/H1/H4 bars per symbol (seeded) as a Markdown + CSV table with UTC, EAT and
  server time, for you to compare with the cTrader chart.

### Makefile

| Target | Does |
|---|---|
| `make up` / `make down` | start / stop the database (docker compose) |
| `make test` | `./mvnw -B verify`: Surefire unit tests + Failsafe `*IT` against Testcontainers Percona |
| `make unit` | unit tests only |
| `make jar` | `./mvnw -B -DskipTests package` |
| `make run-live` | live mode (`FEED=deriv` by default) |
| `make run-replay FROM=... TO=...` | replay with `--compare-m5` |

`CONF=<path>` sets the config for `run-live` and `run-replay` (default `~/.forexsignals/conf.xml`).
`FEED=<deriv|ctrader|synthetic>` applies to `run-live` and `run-replay` (default `deriv`).

Network feed tests are tagged `needs-feed` and are reported as skipped unless enabled:
`FOREXSIGNALS_FEED_IT=1 make test` (Deriv) or cTrader credentials in `~/.forexsignals/conf.xml`.

## Layout

```
pom.xml, mvnw, .mvn/        Maven (all versions in root <properties>)
engine/                     the Java 25 engine (fat jar)
  src/main/proto/ctrader/   vendored cTrader protos (MIT), order requests removed
  src/main/resources/db/migration/  Flyway SQL
config/symbols.xml          symbol catalogue
conf.example.xml            config template (the real conf.xml is never committed)
docker-compose.yml, .env.example, Makefile
docs/                       PLAN.md, SETUP-ACCOUNTS.md, adr/
data/                       (gitignored) reports and dumps
```
