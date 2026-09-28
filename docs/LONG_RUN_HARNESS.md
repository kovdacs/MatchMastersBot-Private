# Long-run harness (1 / 5 / 10 / 20 moves)

**Tier:** B (JVM unit) — simulated Vision PASS boards + `RecordingInputGestureExecutor`.  
**Not** Tier E live phone.

## Test

`ContinuousCycleHarnessTest`:

| Test | Moves | Asserts |
|------|-------|---------|
| `continuous_1_move` | 1 | dispatch + VERIFY SUCCESS + still RUNNING |
| `continuous_5_moves` | 5 | 5 dispatches, no freeze |
| `continuous_10_moves` | 10 | same |
| `continuous_20_moves` | 20 | same |
| `verifyUnchanged_stops_noBlindRetry` | 1 | FAILED → PAUSE, no 2nd dispatch |
| `holdDoesNotFreezeModeRunning` | — | soft HOLD then PASS continues |

## Live operator (when phone available)

Document observed move counts in bubble (`MOVE COUNT`) for 1/5/10/20+ after Tier E proof.  
Until then mark **5/10/20+** as **FAIL** (not proven on device).
