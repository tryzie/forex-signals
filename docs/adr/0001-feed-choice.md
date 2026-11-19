# ADR 0001: cTrader Open API as primary feed, Deriv as fallback

- Status: Accepted (Phase 0, 2026-11-18). Amended 2026-11-19 with Phase 1 measurements.

## Context
We need real bid/ask quotes and multi-year M1 history for a demo account, on a Linux server, read-only.

## Decision
- Primary: **cTrader Open API** (Pepperstone demo, Protobuf over TLS). It provides spot events with bid
  and ask, trendbars and tick history, and an `accounts` (read-only) OAuth scope.
- Fallback: **Deriv** new API, public WebSocket endpoint, behind the same `FeedAdapter` interface.
- Rejected: MT5 Python package (Windows-only; Wine + RPyC on Linux is fragile).
- Feeds are never pooled: every row carries its feed.

## Consequences (Phase 1 measurements, 2026-11-19)
- Deriv history is quote (mid) only, so Deriv candles use the QUOTE price basis; cTrader uses BID.
- Deriv answers hold at most 1000 items (the newest before `end`), whatever `count` says. M1 depth is
  about one year, and past it Deriv silently returns recent bars. Both are guarded in the adapter and importer.
- cTrader remains "1b: pending" until Spotware approves the app.
