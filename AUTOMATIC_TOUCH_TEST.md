# AUTOMATIC TOUCH TEST (isolated)

**Date:** 2026-09-28 (Europe/Vienna)  
**Version:** `0.22.1-touch-fix`  
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
| endX | `startX + 200` (capped to width−1; delta also ≤ width/5, ≥ 80) |
| endY | `startY` |
| duration | **400 ms** |

**Documented example (1080×2340):** `(540, 1053) → (740, 1053)`

Exact px for the user’s phone are printed in Toast / bubble status / logcat on each press.

## Accessibility enable / check

1. User enables **Match3 Vision Elemző** in Android **Settings → Accessibility**.
2. `MatchMastersAccessibilityService.onServiceConnected` sets singleton, re-applies serviceInfo flags, logs `canPerformGestures`.
3. XML: `canPerformGestures=true` + `canRetrieveWindowContent=true` (no touch-exploration mode).
4. Touch test requires **connected AND** `canDispatchGestures()` (CAPABILITY_CAN_PERFORM_GESTURES).
5. Gesture path awaits `GestureResultCallback` off the main thread → SUCCESS = **onCompleted**, FAIL = onCancelled / timeout / false.

## Android fixes in 0.22.1-touch-fix

| Issue | Fix |
|-------|-----|
| Overlay button MotionEvent cancels injected gesture | **400 ms delay** after TESZT ÉRINTÉS before dispatch |
| Overlay steals injected touches | **FLAG_NOT_TOUCHABLE** on bubble during gesture |
| Main-thread await deadlocks callback | Dispatch/await on **Dispatchers.Default** |
| SUCCESS meant only “scheduled” | Await **onCompleted / onCancelled** |
| Weak capability check | Runtime `CAPABILITY_CAN_PERFORM_GESTURES` |
| Silent failure | Hungarian **Toast + bubble**: a11y igen/nem, coords, OK/FAIL reason |

## Gesture dispatch path

```
Bubble «TESZT ÉRINTÉS»
  → Toast a11y=IGEN/NEM + screen WxH
  → FLAG_NOT_TOUCHABLE + delay 400ms
  → FloatingBubbleService.runTouchTestFromBubble() (background)
  → AutomaticTouchTest.runOnce(width, height)
  → AccessibilityGestureExecutor.dispatch(GestureSpec) [await callback]
  → MatchMastersAccessibilityService.dispatchGesture
  → AccessibilityService.dispatchGesture (framework)
  → Toast/status: a11y=… koordináták=(x,y)→(x,y) OK|FAIL
```

Required log lines (must appear):

1. `AccessibilityService ENABLED=true|false`
2. `gesture created: start=(x,y) end=(x,y) …`
3. `gesture dispatch happening…`
4. `gesture dispatch SUCCESS` **or** `gesture dispatch FAIL: …`
5. `TOUCH_A11Y: … onCompleted` **or** `onCancelled`

Filter logcat: `TOUCH_TEST` / `TOUCH_A11Y` / `TOUCH_TEST_LOG`.

## User verification (on-device)

1. Install CI APK `Match3Analyzer-0.22.1-touch-fix-<sha>.apk`.
2. Open **Match3 Vision Elemző** → **INDÍTÁS** → grant overlay + Accessibility + (optional) capture.
3. Open **Match Masters** (game visible).
4. On the floating bubble tap **TESZT ÉRINTÉS**.
5. Toast: `a11y=IGEN képernyő=…` then after ~0.4s a short horizontal swipe near center; final Toast `… OK (onCompleted)`.
6. If fail: Toast shows reason (`a11y=NEM`, `onCancelled`, `canPerformGestures=false`, …).

CI proves the harness with unit tests; **visible on-device swipe is operator-confirmed**.

## Out of scope (unchanged)

Vision algorithms / thresholds, auto-play loop Vision gates.
