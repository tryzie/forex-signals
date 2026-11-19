-- Spread statistics now come from two sources: live recording (LIVE) and sampled cTrader tick-history weeks
-- (TICK_SAMPLE, one week per quarter, Phase 2 spread model). label = sample quarter, e.g. 2024Q1.
ALTER TABLE spread_stats ADD COLUMN source text NOT NULL DEFAULT 'LIVE' CHECK (source IN ('LIVE', 'TICK_SAMPLE'));
ALTER TABLE spread_stats ADD COLUMN label text;
