# AUTOMATIC INPUT ENGINE V1 + FEEDBACK LOOP

**Date:** 2026-09-28 (Europe/Vienna)  
**Repo:** `kovdacs/MatchMastersBot-Private`  
**Package:** `com.match3vision.analyzer.input`

## Safety first

| Gate | Default |
|------|---------|
| `InputEnableSwitch` | **DISABLED** (never auto-on after install) |
| System AccessibilityService | **OFF** until user enables in Android settings |
| DecisionEngine / MoveAnalysisEngine | Read-only; do **not** auto-play |

Both the system a11y toggle **and** the in-app enable switch must be on before any gesture. Failed validation → **HOLD**, no input. Unknown/uncertain → **FAIL-SAFE STOP**.

## Android input mechanism

**Preferred:** `MatchMastersAccessibilityService` → `AccessibilityService.dispatchGesture(GestureDescription)` via `AccessibilityGestureExecutor`.

**Documented alternative:** `ShellInputGestureExecutor` runs `input swipe x1 y1 x2 y2 duration` (Instrumentation / `adb shell`). Same enable gates apply.

`InputGestureExecutor` abstracts the channel so JVM unit tests use `RecordingInputGestureExecutor` (no OS input).

### Enable conditions (all required)

1. `InputEnableSwitch.isEnabled() == true` (explicit; default false)
2. Executor `isReady()` (a11y service connected **or** shell ready)
3. VisionValidator **PASS**
4. `gridConfidence ≥ 0.98` (`VisionThresholds.MIN_GRID_CONFIDENCE`)
5. `boardConfidence ≥ 0.95` (`VisionThresholds.MIN_BOARD_CONFIDENCE`)
6. `unknownCount ≤ 1`
7. Valid `MoveEvaluation` present (finite EV, not uncertain)
8. `move.confidence ≥ InputThresholds.MIN_MOVE_CONFIDENCE` (0.70)

Else → **HOLD — Decision AI blocked / input disabled**, no gesture.

## Swipe coordinate math

```
Move (r1,c1)↔(r2,c2)
  + GridGeometry (recognized cell boundaries from Vision)
  → TouchCoordinateMapper
  → GestureSpec(
        start = cellBox(r1,c1).center,
        end   = cellBox(r2,c2).center,
        durationMs = SWIPE_DURATION_MS
     )
```

Centers come from **live** `GridGeometry` / `CellGeometry.centerX/Y`.  
**No hardcoded pvp_board / device pixel constants.**

## State machine

```
IDLE → CAPTURE → VALIDATE → ANALYZE → SELECT_MOVE → EXECUTE_INPUT
     → WAIT_FOR_BOARD → VERIFY_RESULT → CAPTURE …
errors → HOLD (soft) or STOP (fail-safe)
```

## Post-input verification (feedback loop)

1. After `EXECUTE_INPUT`, enter `WAIT_FOR_BOARD` (caller waits `ANIMATION_WAIT_MS` ≈ 650ms).
2. Capture new screenshot/frame; re-run Vision.
3. `InputFeedbackVerifier`:
   - Invalid new frame / low conf / structure bad → **STOP**
   - `unknownCount > 1` / uncertain → **FAIL-SAFE STOP**
   - Board `contentHash` unchanged → **STOP** (no blind retry)
   - Board changed → **Success** → back to `CAPTURE` (next move allowed)

Repeated verify failures also **STOP**.

## Components

| Type | Role |
|------|------|
| `AutomaticInputEngine` | Gate + execute + verify API |
| `InputEnableSwitch` | Default-disabled safety switch |
| `TouchCoordinateMapper` | Move + grid → `GestureSpec` |
| `InputGestureExecutor` | Real / fake / shell dispatch |
| `MatchMastersAccessibilityService` | Real a11y gestures |
| `InputFeedbackVerifier` | Post-input board-change check |
| `BotStateMachine` | Loop states HOLD/STOP |
| `InputLoopController` | Optional wire: MoveAnalysis → input → feedback |

Vision / Move Analysis V1 / REAL_FRAME PASS paths are unchanged. Orchestrator remains display-oriented; input is opt-in via `InputLoopController` + enable switch.

## Tests

`AutomaticInputEngineTest`, `BotStateMachineTest`, `InputLoopControllerTest`:

- PASS allowed; HOLD blocked; low grid/board conf; unk>1; no legal move; low move conf
- Coord conversion from grid centers; input disabled blocked
- Success → verify next frame; failed verify STOP; repeated failure STOP; unknown fail-safe STOP
