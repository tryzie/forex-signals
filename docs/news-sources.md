# News calendar sources (`config/news.csv`)

`config/news.csv` covers **only MAJOR macro events**: FOMC rate decisions, US Nonfarm Payrolls
(Employment Situation), US CPI, ECB monetary policy decisions, Bank of England MPC decisions,
Bank of Japan Monetary Policy Meeting decisions and RBA cash rate decisions, from 2023-11-01 to
2026-11-30 (UTC). Other standard high-impact releases (e.g. retail sales, PMIs, GDP, PPI, jobless
claims, PCE, central-bank speeches) are **not** covered. Any blackout logic that needs them must
keep using the fail-safe fallback windows.

Compiled on 2026-11-19 (UTC) from the official publishers only. No aggregator (ForexFactory or
similar) was used. Total: 195 rows, all `tier=MAJOR`.

Columns: `ts_utc,currency,title,tier,source_url,time_basis,notes`.
`time_basis=OFFICIAL_TIME` means the publisher's stated release time, converted to UTC with the
DST rules for that date (Python `zoneinfo`, IANA tz database). `APPROX_TIME` is used only for the BoJ.

## Publishers, pages and time derivation

### Federal Reserve (USD, FOMC)
- Page: https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm (meeting dates 2023-2027).
- Event date = last day of each meeting. Time = statement at 14:00 America/New_York
  (EDT = 18:00Z, EST = 19:00Z).
- Excluded: the 22 Aug 2025 notation vote (Statement on Longer-Run Goals and Monetary Policy
  Strategy). It was not a rate decision. No unscheduled rate meetings are listed in the range.

### Bureau of Labor Statistics (USD, NFP and CPI)
- Archive lists: https://www.bls.gov/bls/news-release/empsit.htm and
  https://www.bls.gov/bls/news-release/cpi.htm. Each release date was read from the archive file
  name (`empsit_MMDDYYYY.htm`, `cpi_MMDDYYYY.htm`), and each row's `source_url` is that release.
- Schedules (future): https://www.bls.gov/schedule/news_release/empsit.htm and
  https://www.bls.gov/schedule/news_release/cpi.htm (all 08:30).
- Time = 08:30 America/New_York (EDT = 12:30Z, EST = 13:30Z). For the irregular releases
  (20 Nov 2025, 16 Dec 2025, 11 Feb 2026 NFP; 24 Oct 2025, 18 Dec 2025, 13 Feb 2026 CPI) the
  "embargoed until 8:30 a.m. (ET)" line was checked in the release itself.

### European Central Bank (EUR)
- Calendar: https://www.ecb.europa.eu/press/calendars/mgcgc/html/index.en.html (only lists
  meetings from 25 Nov 2026 onward). Rate history: https://www.ecb.europa.eu/stats/policy_and_exchange_rates/key_ecb_interest_rates/html/index.en.html.
- Per-meeting pages (decision press release where found, otherwise the monetary policy statement,
  the combined decisions-and-statement PDF or the meeting account) are in `source_url`.
- Time = decision press release at 14:15 Europe/Berlin (CET = 13:15Z, CEST = 12:15Z), as stated
  on https://www.ecb.europa.eu/press/govcdec/mopo/html/index.en.html.

### Bank of England (GBP)
- 2023: https://www.bankofengland.co.uk/monetary-policy-summary-and-minutes/2023/november-2023 and `/december-2023`.
- 2024: https://www.bankofengland.co.uk/news/2023/september/mpc-confirmed-dates-for-2024
- 2025: https://www.bankofengland.co.uk/news/2024/september/monetary-policy-committee-dates-for-2025
- 2026: https://www.bankofengland.co.uk/monetary-policy/upcoming-mpc-dates
- Time = 12:00 Europe/London (GMT = 12:00Z, BST = 11:00Z). Exception: 8 May 2025 was published
  at 12:02 BST (11:02Z) for the VE Day two minutes' silence:
  https://www.bankofengland.co.uk/news/2025/may/statement-on-the-timing-of-the-mpr-and-mpc-minutes

### Bank of Japan (JPY)
- 2023-2025: https://www.boj.or.jp/en/mopo/mpmsche_minu/past.htm
- 2026: https://www.boj.or.jp/en/mopo/mpmsche_minu/index.htm
- The BoJ has no fixed announcement time. Rows use the meeting's final day at 03:00 UTC with
  `time_basis=APPROX_TIME`.

### Reserve Bank of Australia (AUD)
- Decision lists: https://www.rba.gov.au/monetary-policy/int-rate-decisions/ (2026) and
  `/int-rate-decisions/2023/`, `/2024/`, `/2025/`. Each row links the decision media release.
- 2026 schedule: https://www.rba.gov.au/schedules-events/board-meeting-schedules.html
- Time = 14:30 Australia/Sydney, as stated on the decisions page ("at 2.30 pm after each Board
  meeting"). AEDT (Oct to Apr) = 03:30Z, AEST = 04:30Z.

## Access notes
- ECB: the index lists (decisions archive, press conference archive, accounts archive) load their
  entries from `*_content.en.html` includes that are `Disallow`ed in ecb.europa.eu/robots.txt, so
  they were not fetched. Per-meeting pages were located by web search on ecb.europa.eu instead.
  Some `source_url`s are therefore the statement, PDF or account page rather than the decision
  release, and the 12 Sep 2024 link is the Dutch-language version of the release.
- BLS: plain `curl` gets HTTP 403. That was not worked around. The pages were read through the
  WebFetch tool, which BLS served normally.
- BoE `news/upcoming` returned an empty calendar. The dedicated dates pages above were used instead.
- No other page refused access.

## Gaps and uncertainties
- **No row for the October 2025 Employment Situation or October 2025 CPI.** BLS states both were
  "Not published because of 2025 lapse in federal government appropriations" (1 Oct to 12 Nov 2025).
  October payroll (establishment) data came out with the November report on 16 Dec 2025.
- **Shutdown-shifted US releases** (these dates are confirmed in the releases themselves):
  September 2025 NFP on 20 Nov 2025 and CPI on 24 Oct 2025; November 2025 NFP on 16 Dec 2025
  and CPI on 18 Dec 2025. The January 2026 NFP (Wed 11 Feb 2026) and CPI (Fri 13 Feb 2026) were
  also off the usual pattern. The source pages give no reason for those two dates. No date in the
  file is marked UNCONFIRMED.
- **BoJ times are approximate** (03:00 UTC on the final meeting day, all 24 rows). The real
  release time varies with the length of the meeting. Do not treat it as exact for a blackout
  window. Use a wide window or the fallback rules.
- **BLS release dates come from file names.** Only the six irregular releases had their date and
  time checked inside the release. The rest assume the standard 08:30 ET time shown on the BLS
  schedules.
- **Some ECB rows use secondary ECB pages.** Twelve ECB rows take their date from a statement,
  combined PDF or account page, not the decision press release. In every case that is the same
  meeting's own document on ecb.europa.eu.
- **Future events.** As of 2026-11-19, no event in scope falls between 2026-11-20 and
  2026-11-30. The next ones are RBA 8 Dec, NFP 4 Dec, FOMC 9 Dec, CPI 10 Dec, ECB 17 Dec,
  BoE 17 Dec and BoJ 17-18 Dec 2026, all outside the range. So there are no SCHEDULED rows.
- No unscheduled or emergency rate moves appear on any official list for the range, so none are included.
- Rows before the first in-range date are omitted. The FOMC meeting of 31 Oct to 1 Nov 2023 is
  included. The BoJ meeting of 30-31 Oct 2023 is not.
