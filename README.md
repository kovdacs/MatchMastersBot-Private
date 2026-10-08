# Match Masters Bot — Match3 Vision Analyzer

**Version:** `0.24.7.5` (versionCode 26)  
**Package:** `com.match3vision.analyzer`  
**Repo:** `kovdacs/MatchMastersBot-Private`

Hungarian UI research prototype: **Capture → Vision → Validation → MoveAnalysis → AutoPlayController → InputLoop → AccessibilityService**.

## Gates (never loosen)

- `MIN_GRID_CONFIDENCE = 0.98`
- `MIN_BOARD_CONFIDENCE = 0.95`
- `MAX_UNKNOWN_COUNT = 1`

## Build / test

No JDK/SDK on the agent box — use GitHub Actions `analyzer-ci`:

- `./gradlew testDebugUnitTest`
- `./gradlew assembleDebug`
- APK artifact: `Match3Analyzer-<version>-<sha>.apk`

## Status & evidence

- `PROJECT_STATUS.md` — current pack
- `docs/EVIDENCE_TIERS.md` — A–E (do not mix)
- `docs/LIVE_PHONE_TOUCH_PROOF.md` — how **you** prove Tier E on a real phone
- `docs/AUDIT_CHAIN_GATES.md` — fail-closed chain
- `docs/LONG_RUN_HARNESS.md` — simulated 1/5/10/20 moves

**Tier E (live phone) is NOT proven in CI.** Do not claim LIVE PHONE PASS without operator evidence.

## Architecture (kept)

Capture → Vision → Validation → MoveAnalysis → AutoPlayController → InputLoop → AccessibilityService  
Input default **OFF** until INDÍTÁS. Bubble: INDÍTÁS / SZÜNET / TESZT ÉRINTÉS / 5 LÉPÉS TESZT / STOP.  
`5 LÉPÉS TESZT` is a bounded phone session (at most 5 gestures, 60 s total) and does not loosen the continuous INDÍTÁS gate.

## Ethics

Research prototype. Respect game ToS. Accessibility gestures only after explicit user enable + INDÍTÁS.
