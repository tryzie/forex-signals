-- Phase 2: parameters are frozen BEFORE any out-of-sample test; every run stores the params hash per strategy, and the
-- report refuses to show out-of-sample numbers unless the run's hash equals the frozen hash and the freeze predates the run.
CREATE TABLE frozen_params (
    strategy_id  text NOT NULL,              -- name@version
    params_hash  text NOT NULL,
    params       jsonb NOT NULL,
    frozen_at    timestamptz NOT NULL,
    note         text,
    PRIMARY KEY (strategy_id, params_hash)
);

CREATE TABLE run_strategy (
    run_id       uuid NOT NULL REFERENCES run(id),
    strategy_id  text NOT NULL,
    params_hash  text NOT NULL,
    role         text NOT NULL CHECK (role IN ('PARENT', 'CONTROL', 'STRESS', 'CALIBRATION')),
    PRIMARY KEY (run_id, strategy_id)
);
