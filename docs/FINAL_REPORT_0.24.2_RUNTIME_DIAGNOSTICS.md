# Final report — 0.24.2-runtime-diagnostics

**Date:** 2026-10-08  
**Base:** `0.24.1-live-pipeline` (`c085dac`)  
**Version name:** `0.24.2-runtime-diagnostics` (`versionCode` 14)

## What this pack is

Runtime diagnostics and a readiness gate. The bubble names the first thing that
actually stops an automatic move. It does not prove a live phone touch.

Vision gates are unchanged: `MIN_GRID_CONFIDENCE=0.98`, `MIN_BOARD_CONFIDENCE=0.95`,
`MAX_UNKNOWN_COUNT=1`. MoveAnalysis was not retuned. Fail-safes stay fail-closed.
`dispatchGesture` success is not `VERIFY SUCCESS`.

## Explicit live-phone answer

**Tier E not proven.** LIVE PHONE: NOT TESTED. FIRST REAL AUTOMATIC TOUCH: NOT PROVEN.

## Why a phone can open the app and never play (code audit)

These are plausible blockers read from the startup chain. None of them was
confirmed on the owner's device (no screenshot, no logcat).

1. **Cold open is IDLE.** `InputEnableSwitch` stays disabled until INDÍTÁS.
   Opening the activity does not start the loop.
2. **Settings listing is not a connection.** `MainActivity.finishStartChainAfterCaptureAndBubble`
   arms RUNNING only when `MatchMastersAccessibilityService.isConnected()`
   (live instance). Settings-on + service-not-bound toasts
   `ACCESSIBILITY: DISCONNECTED` and `moveTaskToBack`. The UI is gone and nothing plays.
3. **CaptureService comes up after `startForegroundService`.** The old start path
   published `captureReady=true` immediately and, 350 ms later, treated a null
   manager as a hard failure. Mode stayed IDLE, and the first block said
   "press INDÍTÁS" even though the user had just pressed it. The start path now
   retries ~2 s and, if capture never appears, the first block is `CAPTURE: OFF`.
4. **Projection death used to spin.** If the service instance disappeared, the
   loop waited on "no frame" while `captureReady` stayed true. A null manager or
   a projection that stops after a frame now pauses with `CAPTURE: OFF`.
5. **Vision HOLD never dispatches** (thresholds not loosened). On a real Match
   Masters board this is still the most likely reason a *running* loop never
   touches. The bubble shows `VISION: HOLD` plus the reason as `BLOKK`.
6. **The play loop did not make the bubble non-touchable.** TESZT ÉRINTÉS already
   set `FLAG_NOT_TOUCHABLE` because an overlay cancels `dispatchGesture`.
   Auto-play did not. The loop now clears touches for the dispatch call.
   A failed dispatch pauses once. It does not retry without a new INDÍTÁS.
7. **Stale `latestFrame` could be decided again.** `tryExecute` defaulted
   `frameAgeMs=0`, `hasFrame=true`, `a11yConnected=true`, so the age / sequence /
   a11y arguments on `GestureFailSafe` were not the live observation.
   The loop now passes a `RuntimeCycleContext` measured from the capture frame
   and `isConnected()`. Age above 3000 ms does not select a move.
8. **Process restart resets the session.** `FloatingBubbleService.onCreate`
   calls `beginNewSession()` (IDLE, input off). `START_STICKY` does not replay
   `ACTION_START_LOOP`. After a kill the user must press INDÍTÁS again.

## RUNTIME STATUS AUDIT

**From real observations**

- Autoplay mode: `AutoPlayController.mode`
- Accessibility: service instance connected, not the settings flag
- Capture on/off: `CaptureService.managerOrNull()` and `isCapturing`
- Frame time, size, age, freshness: `CaptureFrame`
- Vision PASS/HOLD and the reason: `VisionResult.validation` and confidences
- Move candidates and the selected swap: `MoveAnalysisEngine` top list
- Gesture created: only after an in-bounds `GestureSpec`
- Dispatch: executor result (`NOT STARTED` / `SUCCESS` / `FAILED`)
- Verification: `InputFeedbackVerifier` on a new fresh frame, else `PENDING` or `FAILED`

**Were simulated, sticky, or missing**

- Engine defaults pretended the frame was fresh and accessibility was connected
- `captureReady=true` was published before the service existed
- A previous `VERIFY SUCCESS` could stay on screen across the next dispatch
- Bubble mapped `BotLoopOutcome.CONTINUE` to verify success
- No gesture line; dispatch `NONE` instead of `NOT STARTED`; input `YES`/`NO` without a block reason
- `moveCount` was executed moves, not candidate count
- Dispatch failure was `HOLD`, and the bubble retried every 280 ms
- 1/5/10/20 harness uses `RecordingInputGestureExecutor` (labeled `SIMULATION`)

## FIXED

- `input/RuntimeDiagnostics.kt` — snapshot labels, `VerificationPolicy`, `CoordinateBounds`, `SimulationMarker`
- `input/AutomaticInputEngine.tryExecute` — live context, off-screen block before `dispatch`, one-shot stop on dispatch/gesture failure
- `input/InputLoopController` — stale/missing/non-new frames are not a new decision; dispatch sets `VERIFY PENDING`
- `input/AutoPlayController` — mid-run a11y/capture loss pauses; `completeFeedback` takes a `VerifyObservation`
- `overlay/LivePipelineStatus` — required bubble lines, first block includes a rejected start
- `overlay/AutoPlaySession` — diagnostics fields from the cycle, not only string sniffing
- `overlay/FloatingBubbleService` — capture retry, capture-loss pause, frame facts, non-touchable dispatch, verify from policy
- `MainActivity` — does not claim capture is on before `CaptureService` exists

## TEST EVIDENCE

`RuntimeDiagnosticsAcceptanceTest` plus the existing suite:

- Full `testDebugUnitTest`: **342 tests, 0 failures**
- `assembleDebug`: **BUILD SUCCESSFUL** (APK ~25 021 931 bytes before CI rename)
- Faulty input readiness (switch off, channel not ready, a11y down, capture off, settings-only) does not dispatch
- Vision HOLD does not dispatch
- Dispatch success leaves `VERIFY PENDING`, not `SUCCESS`
- Verification SUCCESS only for a new, fresh, verifiable, changed board
- Stale or non-new frames cannot decide, even if the board hash would differ
- Off-screen and unknown bounds are blocked before dispatch and are not retried
- One failed dispatch is not retried (`failNext` would have succeeded on a second call)
- 1/5/10/20 simulations still pass and are labeled `SIMULATION`

## SIMULATION

1 / 5 / 10 / 20 moves: **PASS** in `ContinuousCycleHarnessTest` (Tier B).
Banner: `SIMULATION — not live phone`. Not a live-phone proof.

## REMAINING BLOCKERS

1. No operator Tier E evidence (bubble PASS → DISPATCH → VERIFY SUCCESS on live Match Masters).
2. Live vision may still HOLD. Gates were not loosened.
3. Mushroom/+3 still needs labeled crops.
4. Gesture coordinates are capture-bitmap pixels. A device where that space is not the touch space would show `COORD` / verify FAILED, not a silent success. Not measured here.
5. After a process kill the session returns to IDLE until INDÍTÁS.

## Report block

```
VERSION:
0.24.2-runtime-diagnostics

COMMIT:
(filled after push)

CI RUN:
(filled when Actions finishes)

CI STATUS:
(filled when Actions finishes)

APK ARTIFACT:
Match3Analyzer-0.24.2-runtime-diagnostics-<shortsha>.apk

TESTS BEFORE:
0.24.1 CI testDebugUnitTest was green (run 36468136011). Not re-executed on c085dac in this session.

TESTS AFTER:
testDebugUnitTest: 342 tests, 0 failures, 0 errors. assembleDebug: BUILD SUCCESSFUL.

RUNTIME STATUS AUDIT:
- Real: mode, a11y instance, CaptureService alive/capturing, frame timestamp/size/age, VisionResult gate, MoveAnalysis candidates, gesture build, executor dispatch, verifier on a new frame.
- Were simulated or incomplete: tryExecute defaults (fresh frame, a11y true), sticky captureReady, sticky VERIFY SUCCESS, CONTINUE mapped to verify success, missing GESTURE / NOT STARTED / BLOCKED reason, dispatch HOLD retried without limit, 1/5/10/20 harness is RecordingInputGestureExecutor.

FIXED:
- RuntimeDiagnostics.kt (VerificationPolicy, CoordinateBounds, SimulationMarker)
- AutomaticInputEngine.tryExecute
- InputLoopController.runAnalyzeAndMaybeInput / completeFeedback
- AutoPlayController.runCycleIfActive / onCaptureLost / completeFeedback
- LivePipelineStatus.firstBlockingReason / bubbleLines
- AutoPlaySession diagnostics
- FloatingBubbleService loop + start retry
- MainActivity captureReady

TEST EVIDENCE:
- Faulty input readiness blocks dispatch: PASS
- Vision HOLD never dispatches: PASS
- Dispatch success is not VERIFY SUCCESS: PASS
- Verify only on a new fresh verifiable frame: PASS
- Stale frame is not a new decision: PASS
- Bad coordinates blocked before dispatch: PASS
- Failed dispatch does not retry: PASS
- 1/5/10/20 simulations labeled SIMULATION: PASS
- Thresholds 0.98 / 0.95 / unk<=1 unchanged: PASS

SIMULATION:
- 1 / 5 / 10 / 20: PASS (Tier B, explicitly SIMULATION)

REMAINING BLOCKERS:
- No live-phone Tier E evidence
- Real boards may still VISION HOLD
- Mushroom/+3 crops still required
- Touch-space vs bitmap-space not measured on a device
- Process death returns to IDLE until INDÍTÁS

LIVE PHONE:
NOT TESTED

FIRST REAL AUTOMATIC TOUCH:
NOT PROVEN
```
