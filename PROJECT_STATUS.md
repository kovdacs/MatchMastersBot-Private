# PROJECT STATUS — Match3 Vision Analyzer

**Date:** 2026-09-21 (Europe/Vienna)  
**Root:** `/workspace/match3-vision-ai/android/`  
**Package:** `com.match3vision.analyzer`

## Phase status

| Phase | Title | Status |
|------:|-------|--------|
| 1 | Capture | PASS |
| 2 | Vision pipeline | PASS |
| 2.1 | Vision parity infra | PASS (`REFERENCE_PENDING` for Python dumps) |
| 2.2 | Real frame validation | PASS |
| 3 | Board model | PASS |
| 4 | Rule engine | PASS (generic assumptions documented) |
| 5 | Move engine | PASS |
| 6 | Evaluation | PASS |
| 7 | Look-ahead | PASS |
| 8 | Opponent model | PASS (observable-only) |
| 9 | Booster/Perk | PASS (stubs; unknown→UNKNOWN) |
| 10 | Game mode | PASS |
| 11 | Decision AI | PASS (display-only TOP-N) |
| 12 | Analyzer UI | PASS |
| 13 | Recording/Replay | PASS |
| 14 | Analytics | PASS |
| 15 | Learning infra | PASS (export only; no online learning) |
| 16 | Safety | PASS (fail-closed) |
| 17 | Full regression | BLOCKED (ENVIRONMENT_BLOCKED — no JDK) |
| 18 | Final audit | PASS (this document + ARCHITECTURE_FINAL.md) |

## Test counts

- Authored `@Test` methods (approx): **90+** (Phase 1–2 prior ~41 + new phases)
- Gradle execution: **ENVIRONMENT_BLOCKED** (JDK/SDK missing on box)

## Build

- `./gradlew :app:testDebugUnitTest` — **not run** (no `java` / Android SDK)
- Sources target JDK 17 / compileSdk 35

## BLOCKED / REFERENCE_PENDING

| Item | Status |
|------|--------|
| JDK 17 + Android SDK on CI/box | ENVIRONMENT_BLOCKED |
| Python V3.1 parity fixtures | REFERENCE_PENDING (`data/vision/parity/pvp_board_reference.json`) |
| Real-device vision calibration | Deferred |
| OpenCV | Intentionally omitted (pure Kotlin) |

## Known gaps

- Shape/special detectors are conservative heuristics (synthetic-friendly).
- Special combo / refill rules are **documented generic assumptions**, not Match Masters facts.
- Booster/perk catalogs are data-driven stubs; screen detectors return UNKNOWN without labels.
- Opponent actor identity after board change remains UNKNOWN without HUD turn markers.
- UI Compose sections implemented; instrumented UI tests not included.
- No AccessibilityService / GestureDescription / input injection anywhere.

## Next steps (human / CI)

1. Install JDK 17 + Android SDK; set `local.properties` `sdk.dir`.
2. Run `./gradlew :app:testDebugUnitTest` and fix any compile deltas.
3. Drop real Python V3.1 dump into parity JSON (`status: READY`) and re-run comparator.
4. Device calibration of letterbox/board/color thresholds.
5. Do **not** add input automation.

## Safety confirmation

**ANALYZER ONLY** — recommendations/display only. Decision AI never executes input.
