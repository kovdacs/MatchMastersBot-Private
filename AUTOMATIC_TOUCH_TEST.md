# AUTOMATIC TOUCH TEST (isolated)

**Date:** 2026-09-28 (Europe/Vienna)  
**Version:** `0.22.0-touch-test`  
**Package:** `com.match3vision.analyzer.input.AutomaticTouchTest`

## Goal

Prove AccessibilityService can inject **one visible touch/swipe** at a **fixed predetermined** screen coordinate.

- **NO Vision** (no board/grid/unk/confidence)
- **NO PASS / HOLD / play loop**
- Bubble button only: **TESZT ÉRINTÉS**

## Fixed coordinates

Formula (predetermined; not from Vision):

| Value | Formula |
|-------|---------|
| startX | `screenWidthPx / 2` |
| startY | `screenHeightPx * 45 / 100` |
| endX | `startX + 120` (capped to width−1; delta also ≤ width/8) |
| endY | `startY` |
| duration | **250 ms** |

**Documented example (1080×2340):** `(540, 1053) → (660, 1053)`

Exact px for the user’s phone are printed in logcat / bubble status on each press.

## Accessibility enable / check

1. User enables **Match3 Vision Elemző** in Android **Settings → Accessibility**.
2. `MatchMastersAccessibilityService.onServiceConnected` sets singleton + logs `AccessibilityService ENABLED=true`.
3. Touch test checks `MatchMastersAccessibilityService.isConnected()` **or** `InputGestureExecutor.isReady()`.
4. Gesture path: `AutomaticTouchTest` → `AccessibilityGestureExecutor` → `MatchMastersAccessibilityService.dispatchGesture(GestureSpec)` → **`super.dispatchGesture(GestureDescription, …)`** (explicit super — a11y wiring fix so the GestureSpec overload never shadows the framework API).

## Gesture dispatch path

```
Bubble «TESZT ÉRINTÉS»
  → FloatingBubbleService.runTouchTestFromBubble()
  → AutoPlaySession.touchTest.runOnce(width, height)
  → AccessibilityGestureExecutor.dispatch(GestureSpec)
  → MatchMastersAccessibilityService.dispatchGesture
  → AccessibilityService.dispatchGesture (framework)
```

Debug log lines (must appear):

1. `AccessibilityService ENABLED=true|false`
2. `gesture created: start=(x,y) end=(x,y) …`
3. `gesture dispatch happening…`
4. `gesture dispatch SUCCESS` **or** `gesture dispatch FAIL: …`

Filter logcat: `TOUCH_TEST` / `TOUCH_A11Y` / `TOUCH_TEST_LOG`.

## User verification (on-device)

1. Install CI APK `Match3Analyzer-0.22.0-touch-test-<sha>.apk`.
2. Open **Match3 Vision Elemző** → **INDÍTÁS** → grant overlay + Accessibility + (optional) capture.
3. Open **Match Masters** (game visible).
4. On the floating bubble tap **TESZT ÉRINTÉS**.
5. You should see a short horizontal swipe near screen center (e.g. ~(540,1053) on 1080-wide).
6. Bubble status: `érintés OK (x,y)→(x,y)` or fail reason.

CI proves the harness with unit tests; **visible on-device swipe is operator-confirmed**.

## Out of scope (unchanged)

Vision algorithms / thresholds, auto-play loop, one-step smoke Vision gates.
