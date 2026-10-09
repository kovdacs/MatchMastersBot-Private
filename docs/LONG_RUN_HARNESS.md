# Long-run harness (1 / 5 / 10 / 20 moves)

**Tier:** B (JVM unit) — simulated Vision PASS boards + `RecordingInputGestureExecutor`.  
**Not** Tier E live phone.

## Test

`ContinuousCycleHarnessTest`:

| Test | Moves | Asserts |
|------|-------|---------|
| `continuous_1_move` | 1 | dispatch + MOVE UNCONFIRMED + still RUNNING |
| `continuous_unconfirmedCap_allowsTwoThenPauses_noThirdDispatch` | 2 | third dispatch does not happen; PAUSE |
| `verifyUnchanged_stops_noBlindRetry` | 1 | FAILED → PAUSE, no 2nd dispatch |
| `holdDoesNotFreezeModeRunning` | — | soft HOLD then PASS continues |

Continuous mode pauses after 2 consecutive `BOARD CHANGED — MOVE UNCONFIRMED` results. That label is not VERIFY SUCCESS.

## Live operator (when phone available)

Document observed move counts in bubble (`MOVE COUNT`) for 1/5/10/20+ after Tier E proof.  
Until then mark **5/10/20+** as **FAIL** (not proven on device).
