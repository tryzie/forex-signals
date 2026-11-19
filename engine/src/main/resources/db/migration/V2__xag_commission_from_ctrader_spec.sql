-- XAG commission from the cTrader symbol spec of the Pepperstone demo account 5376549 (read 2026-11-19):
-- commissionType USD_PER_MILLION_USD, preciseTradingCommissionRate 0  ->  0 USD per side per lot.
-- (Same read: FX USD_PER_LOT 3.00 and XAU USD_PER_LOT 3.50, matching the configured values.)
-- Still to confirm with a 0.01-lot demo trade statement (Phase 3 checklist); the statement wins.
UPDATE cost_profile
SET commission_per_lot_side = jsonb_set(commission_per_lot_side, '{XAG}', '0.00'::jsonb),
    notes = 'Razor on cTrader. FX 3.00 and XAU 3.50 per side per lot: published and confirmed by the cTrader spec (2026-11-19). '
         || 'XAG 0.00: from the cTrader spec (USD_PER_MILLION_USD, rate 0), not yet confirmed by a demo statement.'
WHERE name = 'pepperstone_razor_ctrader';
