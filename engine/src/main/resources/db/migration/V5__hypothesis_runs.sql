-- Pre-registered hypotheses: each stage run is recorded. The HOLDOUT of a hypothesis may run exactly once: the row is
-- written BEFORE the run starts (a crashed attempt still counts), and a unique index refuses a second one.
CREATE TABLE hypothesis_run (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hypothesis   text NOT NULL,
    stage        text NOT NULL CHECK (stage IN ('DEVELOPMENT', 'HOLDOUT')),
    started_at   timestamptz NOT NULL,
    finished_at  timestamptz,
    report_path  text,
    summary      jsonb
);
CREATE UNIQUE INDEX hypothesis_holdout_once ON hypothesis_run (hypothesis) WHERE stage = 'HOLDOUT';
