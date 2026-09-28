# CONTROLLED ON-DEVICE SMOKE TEST

**Date:** 2026-09-28 (Europe/Vienna)  
**Repo:** `kovdacs/MatchMastersBot-Private`  
**Package:** `com.match3vision.analyzer.input`

## Goal

Prove **one** automatic swipe end-to-end on a real Android phone:

`CAPTURE → VALIDATE → ANALYZE → SELECT → log → ONE swipe → WAIT → new frame → Vision → verify board changed → SUCCESS READY FOR NEXT | STOP/HOLD`

- **Max 1 auto swipe** per smoke session.
- **No continuous / unattended loop.**
- Second swipe **blocked until Reset**.
- All existing Input Engine PASS gates still required.
- Coordinates from **live `GridGeometry`** only (no hardcoded pvp_board pixels).
- Input remains **DEFAULT DISABLED** at app start.

## Safety switches (both required)

| Switch | Default | Role |
|--------|---------|------|
| `InputEnableSwitch` | DISABLED | Layered safety for any gesture |
| `SmokeEnableSwitch` | DISABLED | Arms one-step smoke only |
| System AccessibilityService | OFF | Must be enabled in Android Settings |

App start never auto-enables any of these.

## CI / device reality

The Grok build box has **no Android device**. CI:

1. Runs full unit tests (including one-step limits + gates).
2. Assembles `app-debug.apk`.
3. Uploads artifact `app-debug-apk`.

**True on-device BOARD_CHANGED cannot be faked from CI.**

Report field: `DEVICE_SMOKE_TEST=HARNESS_READY` when APK + harness ship; phone run is operator-driven.

## Install APK on phone

1. Open the GitHub Actions run for the smoke commit → Artifacts → **app-debug-apk**.
2. Download `app-debug.apk` to the phone (or `adb install -r app-debug.apk`).
3. Allow install from the browser/file manager if prompted.

## Enable AccessibilityService

1. Open Android **Settings → Accessibility**.
2. Find **Match3 Vision Analyzer** / Match Masters accessibility service.
3. Turn it **ON** and confirm the system dialog.
4. Leave Match Masters (or the target match-3 game) installed and playable.

Without this, the smoke run HOLDs with `input channel not ready`.

## Run one-step smoke on phone

1. Open **Match3 Vision Analyzer**.
2. Confirm UI shows Input/Smoke **off** (default).
3. Tap **Start** → grant notification (if asked) → grant **screen capture**.
4. Switch to Match Masters / open a **live PvP board** (stable, fully visible).
5. Return to the analyzer (picture-in-picture / recents) so capture still sees the board  
   **or** use split-screen so the board is visible under the MediaProjection virtual display.
6. In **CONTROLLED ONE-STEP SMOKE**:
   - Toggle **Enable Input (safety)** ON.
   - Toggle **Enable One-Step Smoke** ON.
7. Tap **Run One-Step Smoke** **once**.
8. Watch **SMOKE LOG**:
   - SELECT line with source/target cells + centers + score/EV/conf
   - INPUT_DISPATCHED
   - WAIT
   - BOARD_CHANGED **or** STOP/HOLD
9. On success: status **SUCCESS READY FOR NEXT**.  
   Tap **Reset smoke session** before another one-step run.
10. Toggle both switches **OFF** when finished.

Detailed log file on device: `filesDir/smoke_test.log` (also shown in the SMOKE LOG card).

## What success looks like

| Field | Expected on real phone success |
|-------|--------------------------------|
| VISION_PASS | yes (grid/board conf gates) |
| MOVE_SELECTED | yes (logged cells + centers) |
| INPUT_DISPATCHED | yes (exactly one gesture) |
| BOARD_CHANGED | yes (post-frame contentHash differs) |
| POST_VERIFY | SUCCESS READY FOR NEXT |
| FAILSAFE | not triggered |

If the board does not change, harness **STOP**s (no blind retry) — do not invent BOARD_CHANGED.

## Components

| Type | Role |
|------|------|
| `SmokeEnableSwitch` | Explicit smoke arm (default off) |
| `OneStepSmokeController` | One-swipe session + feedback |
| `SmokeTestLogger` | File + UI log |
| `AutomaticInputEngine` | Unchanged gates / dispatch / verify |
| Analyzer UI | Switches + Run + Reset + log |

Vision algorithms and Move Analysis V1 scoring are **not** modified by this feature.
