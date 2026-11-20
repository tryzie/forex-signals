# USDX verification (pre-registered criteria V1-V4)

Run at 2026-11-20T10:18:51Z UTC.

| Criterion | Observed | Rule | Pass |
|---|---|---|---|
| V1 spec | Spec[digits=3, contractSize=100.0, minVolume=1.0, volumeStep=1.0, hours=[64800-147540, 158400-233940, 244800-320340, 331200-406740, 417600-492900], specChanges=0] | digits 3, lot 100, min 1, step 1, reviewed schedule, no SPEC_CHANGED | true |
| V2 coverage | 10748 of 10808 in-session EURUSD hours (99.44%) | >= 98% | true |
| V3 consistency | corr(USDX, USDX_SYN) 0.998, corr(USDX, EURUSD) -0.967 over 15596 H1 returns | >= 0.90 and <= -0.80 | true |
| V4 artificial jumps | 0 | none | true |

**Verified clean: YES**
