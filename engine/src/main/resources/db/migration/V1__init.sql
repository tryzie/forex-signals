-- Forex Signal Platform: initial schema (PLAN §11).
-- All timestamps are timestamptz in UTC. Feeds are never mixed: every market-data row carries its feed.
-- Prices are double precision; comparisons are done in integer points (price / point size, rounded).

-- ---------------------------------------------------------------------------------------------
-- Partition helper: creates monthly range partitions [month, month+1) for a partitioned table.
-- Idempotent. Called at startup (a few months around now) and by the importer for its range.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION fx_ensure_month_partitions(p_parent text, p_from timestamptz, p_to timestamptz)
RETURNS integer LANGUAGE plpgsql AS $$
DECLARE
    m       timestamptz := date_trunc('month', p_from AT TIME ZONE 'UTC') AT TIME ZONE 'UTC';
    created integer := 0;
    part    text;
BEGIN
    WHILE m < p_to LOOP
        part := format('%s_y%sm%s', p_parent, to_char(m AT TIME ZONE 'UTC', 'YYYY'), to_char(m AT TIME ZONE 'UTC', 'MM'));
        IF to_regclass(part) IS NULL THEN
            EXECUTE format('CREATE TABLE %I PARTITION OF %I FOR VALUES FROM (%L) TO (%L)',
                           part, p_parent, m, m + interval '1 month');
            created := created + 1;
        END IF;
        m := m + interval '1 month';
    END LOOP;
    RETURN created;
END $$;

-- ---------------------------------------------------------------------------------------------
-- Symbols and specs
-- ---------------------------------------------------------------------------------------------
-- One row per logical symbol (feed independent). role: SIGNAL | CONTEXT | DATA_ONLY.
CREATE TABLE symbol (
    code         text PRIMARY KEY,
    role         text NOT NULL CHECK (role IN ('SIGNAL', 'CONTEXT', 'DATA_ONLY')),
    asset_class  text NOT NULL,             -- FX | METAL | INDEX
    base_ccy     text NOT NULL,
    quote_ccy    text NOT NULL,
    cluster      text,                      -- e.g. METALS for XAU and XAG
    verified     boolean NOT NULL DEFAULT false,
    updated_at   timestamptz NOT NULL DEFAULT now()
);

-- Per-feed mapping (a symbol can exist on several feeds under different broker names/ids).
CREATE TABLE symbol_feed (
    feed              text NOT NULL,
    code              text NOT NULL REFERENCES symbol(code),
    broker_symbol     text NOT NULL,
    broker_symbol_id  bigint,
    required          boolean NOT NULL,
    available         boolean NOT NULL,
    checked_at        timestamptz NOT NULL,
    PRIMARY KEY (feed, code)
);

-- Contract specs as reported by the feed. History is kept: a new row whenever anything changes.
CREATE TABLE symbol_spec (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    symbol         text NOT NULL,
    feed           text NOT NULL,
    digits         integer,
    pip_position   integer,
    contract_size  double precision,
    min_volume     double precision,
    volume_step    double precision,
    max_volume     double precision,
    commission     jsonb,
    swap_long      double precision,
    swap_short     double precision,
    trading_hours  jsonb,
    raw            jsonb,
    spec_hash      text NOT NULL,
    captured_at    timestamptz NOT NULL
);
CREATE INDEX symbol_spec_latest ON symbol_spec (feed, symbol, captured_at DESC);

-- ---------------------------------------------------------------------------------------------
-- Market data (partitioned monthly)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE tick (
    feed     text NOT NULL,
    symbol   text NOT NULL,
    ts       timestamptz NOT NULL,          -- broker/feed timestamp
    bid      double precision,              -- null when the feed has no bid
    ask      double precision,              -- null when the feed has no ask
    quote    double precision,              -- the feed's own reference price if it publishes one (Deriv mid)
    recv_ts  timestamptz NOT NULL,          -- when we received it (for lag metrics)
    seq      bigint NOT NULL,               -- per-process arrival order, keeps replay order deterministic
    PRIMARY KEY (feed, symbol, ts, seq)
) PARTITION BY RANGE (ts);

CREATE TABLE candle_m1 (
    feed        text NOT NULL,
    symbol      text NOT NULL,
    open_time   timestamptz NOT NULL,
    open        double precision NOT NULL,
    high        double precision NOT NULL,
    low         double precision NOT NULL,
    close       double precision NOT NULL,
    tick_count  integer,
    spread_min  double precision,
    spread_med  double precision,
    spread_max  double precision,
    source      text NOT NULL CHECK (source IN ('BUILT', 'IMPORT')),
    PRIMARY KEY (feed, symbol, open_time)
) PARTITION BY RANGE (open_time);

-- Higher timeframes, derived from candle_m1 only; can be truncated and rebuilt at any time.
CREATE TABLE candle (
    feed        text NOT NULL,
    symbol      text NOT NULL,
    timeframe   text NOT NULL CHECK (timeframe IN ('M5', 'M15', 'H1', 'H4', 'D1')),
    open_time   timestamptz NOT NULL,
    close_time  timestamptz NOT NULL,
    open        double precision NOT NULL,
    high        double precision NOT NULL,
    low         double precision NOT NULL,
    close       double precision NOT NULL,
    tick_count  integer,
    spread_min  double precision,
    spread_med  double precision,
    spread_max  double precision,
    source      text NOT NULL DEFAULT 'DERIVED' CHECK (source = 'DERIVED'),
    PRIMARY KEY (feed, symbol, timeframe, open_time)
) PARTITION BY RANGE (open_time);

-- Resumable, idempotent M1 import: one row per (feed, symbol, month chunk).
CREATE TABLE import_progress (
    feed         text NOT NULL,
    symbol       text NOT NULL,
    chunk_start  timestamptz NOT NULL,
    chunk_end    timestamptz NOT NULL,
    status       text NOT NULL CHECK (status IN ('PENDING', 'DONE', 'FAILED')),
    rows         integer NOT NULL DEFAULT 0,
    attempts     integer NOT NULL DEFAULT 0,
    last_error   text,
    updated_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (feed, symbol, chunk_start)
);

CREATE TABLE gap_report (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_at      timestamptz NOT NULL,
    feed        text NOT NULL,
    symbol      text NOT NULL,
    gap_start   timestamptz NOT NULL,       -- first missing minute
    gap_end     timestamptz NOT NULL,       -- first minute present again
    minutes     integer NOT NULL,
    expected    boolean NOT NULL,           -- weekend / daily break / holiday per trading hours
    reason      text NOT NULL
);
CREATE INDEX gap_report_lookup ON gap_report (feed, symbol, run_at);

CREATE TABLE spread_stats (
    symbol          text NOT NULL,
    feed            text NOT NULL,
    session_bucket  text NOT NULL,          -- ASIA | LONDON | OVERLAP | NEW_YORK | ROLLOVER
    window_start    timestamptz NOT NULL,
    window_end      timestamptz NOT NULL,
    median          double precision NOT NULL,
    p95             double precision NOT NULL,
    samples         bigint NOT NULL,
    computed_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (feed, symbol, session_bucket, window_start)
);

CREATE TABLE feed_event (
    id      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ts      timestamptz NOT NULL,
    feed    text NOT NULL,
    symbol  text,
    kind    text NOT NULL CHECK (kind IN ('CONNECTED', 'DISCONNECTED', 'STALE', 'RECOVERED', 'SPEC_CHANGED',
                                          'LATE_TICK', 'SYMBOL_MISSING', 'GAP_UNEXPECTED')),
    detail  jsonb
);
CREATE INDEX feed_event_ts ON feed_event (ts);

-- ---------------------------------------------------------------------------------------------
-- Costs, news, strategies, runs (used from Phase 2/3; created now so V1 is the full model)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE cost_profile (
    name                     text PRIMARY KEY,
    commission_per_lot_side  jsonb NOT NULL,   -- {"FX": 3.00, "XAU": 3.50, "XAG": null}
    default_slippage         jsonb NOT NULL,   -- price units per symbol class
    notes                    text
);
INSERT INTO cost_profile (name, commission_per_lot_side, default_slippage, notes) VALUES
 ('pepperstone_razor_ctrader',
  '{"FX": 3.00, "XAU": 3.50, "XAG": null}',
  '{"FX": 0.00002, "FX_JPY": 0.002, "XAU": 0.15, "XAG": 0.02, "multiplierOffSession": 2, "multiplierNews": 3}',
  'Razor on cTrader. FX: published (USD 6 round turn per lot). XAU: probable. XAG: UNKNOWN, must be verified from the cTrader spec and a demo statement before Phase 3.');

CREATE TABLE news_event (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ts         timestamptz NOT NULL,
    currency   text NOT NULL,
    title      text NOT NULL,
    impact     text NOT NULL CHECK (impact IN ('HIGH', 'MEDIUM', 'LOW')),
    tier       text NOT NULL CHECK (tier IN ('STANDARD', 'MAJOR')),
    source     text NOT NULL CHECK (source IN ('FEED', 'CSV')),
    loaded_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (ts, currency, title)
);

CREATE TABLE strategy (
    name              text NOT NULL,
    version           text NOT NULL,
    params            jsonb NOT NULL,
    params_hash       text NOT NULL,
    source            text NOT NULL,
    mode              text NOT NULL DEFAULT 'SHADOW' CHECK (mode IN ('DISABLED', 'SHADOW', 'LIVE')),
    params_frozen_at  timestamptz,
    PRIMARY KEY (name, version)
);

CREATE TABLE run (
    id            uuid PRIMARY KEY,
    kind          text NOT NULL CHECK (kind IN ('LIVE', 'REPLAY', 'BACKTEST', 'IMPORT')),
    feed          text NOT NULL,
    from_ts       timestamptz,
    to_ts         timestamptz,
    cost_profile  text,
    config_hash   text,
    git_sha       text,
    started_at    timestamptz NOT NULL,
    finished_at   timestamptz
);

CREATE TABLE signal (
    id                 uuid PRIMARY KEY,
    run_id             uuid NOT NULL REFERENCES run(id),
    strategy           text NOT NULL,
    strategy_version   text NOT NULL,
    params_hash        text NOT NULL,
    feed               text NOT NULL,
    mode               text NOT NULL CHECK (mode IN ('LIVE', 'SHADOW', 'REPLAY', 'BACKTEST')),
    symbol             text NOT NULL,
    direction          text NOT NULL CHECK (direction IN ('LONG', 'SHORT')),
    created_at         timestamptz NOT NULL,
    candle_close_at    timestamptz NOT NULL,
    valid_until        timestamptz NOT NULL,
    entry              jsonb NOT NULL,
    stop               double precision NOT NULL,
    targets            jsonb NOT NULL,
    exit_plan          jsonb NOT NULL,
    suggested_lots     double precision,
    risk_amount_usd    double precision,
    risk_pct           double precision,
    rr_to_first_target double precision,
    spread_at_signal   double precision,
    reasons            jsonb NOT NULL,
    invalidation       jsonb,
    state              text NOT NULL CHECK (state IN ('PENDING', 'ACTIVE', 'CLOSED', 'EXPIRED', 'INVALIDATED')),
    filled_price       double precision,
    closed_at          timestamptz,
    close_price        double precision,
    close_reason       text CHECK (close_reason IN ('TARGET', 'STOP', 'TIME_STOP', 'EXIT_RULE', 'INVALIDATED',
                                                    'EXPIRED', 'KILL_SWITCH', 'MANUAL')),
    est_cost_r         double precision,
    gross_r            double precision,
    net_r              double precision,
    result_usd         double precision,
    mae_r              double precision,
    mfe_r              double precision,
    flags              text[] NOT NULL DEFAULT '{}'
);
CREATE INDEX signal_run ON signal (run_id, created_at);

CREATE TABLE signal_event (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    signal_id   uuid NOT NULL REFERENCES signal(id),
    ts          timestamptz NOT NULL,
    from_state  text,
    to_state    text NOT NULL,
    price       double precision,
    detail      jsonb
);

CREATE TABLE risk_rejection (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id        uuid NOT NULL REFERENCES run(id),
    ts            timestamptz NOT NULL,
    strategy      text NOT NULL,
    symbol        text NOT NULL,
    direction     text NOT NULL,
    first_reason  text NOT NULL,
    checks        jsonb NOT NULL
);

CREATE TABLE paper_trade (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    signal_id      uuid NOT NULL REFERENCES signal(id),
    entry_ts       timestamptz NOT NULL,
    entry_price    double precision NOT NULL,
    exit_ts        timestamptz,
    exit_price     double precision,
    lots           double precision NOT NULL,
    spread_cost    double precision,
    commission     double precision,
    slippage_cost  double precision,
    swap           double precision,
    pnl_usd        double precision,
    result_r       double precision
);

CREATE TABLE equity_snapshot (
    run_id            uuid NOT NULL REFERENCES run(id),
    ts                timestamptz NOT NULL,
    balance           double precision NOT NULL,
    equity            double precision NOT NULL,
    floating          double precision NOT NULL,
    day_start_equity  double precision NOT NULL,
    peak_equity       double precision NOT NULL,
    PRIMARY KEY (run_id, ts)
);

CREATE TABLE kill_switch_event (
    id      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ts      timestamptz NOT NULL,
    action  text NOT NULL CHECK (action IN ('TRIPPED', 'RESET')),
    equity  double precision NOT NULL,
    peak    double precision NOT NULL,
    actor   text NOT NULL,
    note    text
);

CREATE TABLE validation_result (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    strategy     text NOT NULL,
    version      text NOT NULL,
    params_hash  text NOT NULL,
    run_id       uuid REFERENCES run(id),
    verdict      text NOT NULL CHECK (verdict IN ('PASS', 'FAIL', 'INCONCLUSIVE')),
    checks       jsonb NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);
