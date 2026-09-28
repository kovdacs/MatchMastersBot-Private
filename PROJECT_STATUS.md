# PROJECT STATUS — Match3 Vision Analyzer

**Date:** 2026-09-28 (Europe/Vienna)  
**CI:** analyzer-ci SUCCESS run 36416924508 on `33a876d` (REAL_FRAME export + PASS); calib `e0fc877`/36414665548  
**Root:** `/workspace/MatchMastersBot-Private`  
**Package:** `com.match3vision.analyzer`  
**Repo:** `kovdacs/MatchMastersBot-Private` (private)

## Vision flags (evidence-based)

| Flag | Value |
|------|-------|
| `REAL_FRAME_AVAILABLE` | **YES** |
| `REALISTIC_FIXTURE_AVAILABLE` | **YES** |
| `PYTHON_REFERENCE_AVAILABLE` | **NO** |
| `PARITY_VERIFIED` | **NO** |
| `VISION_REAL_WORLD_VALIDATED` | **NO** (partial: one primary frame + secondaries; no Python parity; possible toolbar crop) |

## Phase status

| Phase | Title | Status |
|------:|-------|--------|
| 1 | Capture | PASS |
| 2 | Vision pipeline | PASS (robustness + REALISTIC_SYNTHETIC + REAL_FRAME harness) |
| 2.1 | Vision parity infra | PASS scaffold (`REFERENCE_PENDING`) |
| 2.2 | Real frame validation | PASS harness; **REAL_FRAME_AVAILABLE=YES** (numerics from CI) |
| 3–16 | Board→Safety | PASS (prior) |
| 17 | Full regression | CI (`analyzer-ci`) — no JDK on box |
| 18 | Final audit | See REAL_FRAME_REPORT.md |

## Vision package (this milestone)

- Real MM JPEGs checked in under `app/src/test/resources/real_frames/`
- Primary `pvp_board.jpg` + showdown/FX/volume secondaries
- HUMAN_VISUAL GT (`RealFrameHumanGroundTruth` / `human_ground_truth.json`)
- REAL_FRAME primary hard-asserts PASS + secondary soft diagnostics (HOLD OK)
- Gates unchanged: MIN_GRID=0.98, MIN_BOARD=0.95, MAX_UNKNOWN=1
- Docs: `REAL_FRAME_REPORT.md`, `docs/VISION_PARITY.md`, `docs/VISION_TEST_MATRIX.md`
- Android REAL_FRAME export golden: `data/vision/real_frames/pvp_board_android_export.json` (`RealFrameExportTest`); `PARITY_VERIFIED` still **NO**

## Build / CI

- Box: **no JDK** — unit tests + `assembleDebug` run on GitHub Actions (`analyzer-ci`, €0 free)
- Analyzer-only: no AccessibilityService / touch injection / auto-play / DecisionEngine

## BLOCKED / PENDING

| Item | Status |
|------|--------|
| Cleaner full 7×7 without system overlays | Pending (primary may crop bottom row) |
| Python V3.1 dump | REFERENCE_PENDING |
| Parity READY comparison | Blocked on real dump |
| Real-world vision validation | NO (partial evidence only) |

## Move Analysis Engine V1

- `MoveAnalysisEngine` — gated TOP-5 (grid≥0.98, board≥0.95, unk≤1); HOLD otherwise
- Reuses `moves` / `rules` / `evaluation` / `simulation`; special leave-behind in `CascadeEngine`
- Orchestrator wires optional read-only call after Vision PASS + SafetyGate
- See `MOVE_ANALYSIS_ENGINE_V1.md`

## Next steps

1. Capture cleaner full 7×7 without Android screenshot toolbar.
2. Obtain matching Python V3.1 dump (do not invent); set reference READY.
3. Run parity comparator on the same frame → only then `PARITY_VERIFIED`.
4. Do **not** add input automation.

## Safety confirmation

**ANALYZER ONLY** — recommendations/display only. Decision AI never executes input.
