# How to prove live Match Masters touch (Tier E)

**Honest status:** This repository **cannot** operate your Android phone.  
Until **you** capture evidence below, mark **LIVE PHONE / FIRST TOUCH / VERIFY** as **FAIL**.

## Prerequisites

1. Install CI APK: `Match3Analyzer-0.24.1-live-pipeline-<sha>.apk` (unique name from Actions artifact).
2. Enable **Kisegítő lehetőségek** → Match3 Analyzer AccessibilityService.
3. Grant overlay + MediaProjection when prompted.
4. Open **Match Masters** PvP board (playfield fully visible).
5. Analyzer bubble top-end; main Activity backgrounded after INDÍTÁS.

## A) Isolated touch (no Vision) — TESZT ÉRINTÉS

1. Tap bubble **TESZT ÉRINTÉS**.
2. Expect Hungarian Toast: `a11y=IGEN` + coords + dispatch ok/fail.
3. **PASS evidence:** visible swipe on screen **or** logcat `TOUCH_A11Y: gesture dispatch SUCCESS (onCompleted)`.
4. This proves a11y→`dispatchGesture` path only — **not** Match Masters board change.

## B) Full auto-play chain (Vision→Move→gesture→verify)

1. Tap **INDÍTÁS** (main or bubble).
2. Watch bubble compact status (P0 fields):
   - `FRAME`, `BOARD ROI`, `GRID/BOARD CONF`, `UNKNOWN`, `PASS/HOLD`
   - `MOVE COUNT`, `SELECTED MOVE`, `A11Y`, `INPUT READY`, `LAST DISPATCH`, `VERIFY`
   - `FIRST BLOCK` / `BLOKK` = first reason if stuck
3. When Vision **PASS** and `INPUT READY: YES`, expect `LAST DISPATCH: SUCCESS` then `VERIFY: SUCCESS` (board changed) or `FAILED` (no blind retry → PAUSE).
4. Continuous cycle: FRAME→ANALYZE→PASS/HOLD→MOVE→INPUT→VERIFY→WAIT NEW FRAME…  
   Heartbeat / phase must keep updating (no silent IDLE freeze).

### Evidence pack (required for Tier E PASS)

- Screenshot of bubble showing `PASS`, move selected, `DISPATCH SUCCESS`, `VERIFY SUCCESS`.
- logcat excerpt: `VISION PASS` → `MOVE SELECTED` → `DISPATCH` → `VERIFY SUCCESS`.
- Optional: before/after board screenshots proving gems moved.

### Instrumented APK steps (optional)

```bash
adb install -r Match3Analyzer-0.24.1-live-pipeline-<sha>.apk
adb shell am start -n com.match3vision.analyzer/.MainActivity
adb logcat -s AutoPlayTrace:I TOUCH_A11Y:I TOUCH_TEST:I FloatingBubbleService:I
```

No Espresso/UI Automator harness is shipped for Match Masters (third-party game).  
Unit harness `ContinuousCycleHarnessTest` simulates 1/5/10/20 moves (Tier B only).

## What is NOT proof

- Vision HOLD (`unknownCount` / gridConf) screenshots
- Synthetic / `pvp_board.jpg` CI PASS (Tier C/D)
- TESZT ÉRINTÉS alone (proves gesture channel, not MM board)
