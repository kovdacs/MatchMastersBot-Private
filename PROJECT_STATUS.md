# PROJECT STATUS — Match3 Vision Analyzer

**Date:** 2026-09-25 (Europe/Vienna)  
**Root:** `/workspace/MatchMastersBot-Private`  
**Package:** `com.match3vision.analyzer`  
**Repo:** `kovdacs/MatchMastersBot-Private` (private)

## Vision flags (evidence-based)

| Flag | Value |
|------|-------|
| `REAL_FRAME_AVAILABLE` | **NO** |
| `REALISTIC_FIXTURE_AVAILABLE` | **YES** |
| `PYTHON_REFERENCE_AVAILABLE` | **NO** |
| `PARITY_VERIFIED` | **NO** |
| `VISION_REAL_WORLD_VALIDATED` | **NO** |

## Phase status

| Phase | Title | Status |
|------:|-------|--------|
| 1 | Capture | PASS |
| 2 | Vision pipeline | PASS (robustness + REALISTIC_SYNTHETIC harness) |
| 2.1 | Vision parity infra | PASS scaffold (`REFERENCE_PENDING`) |
| 2.2 | Real frame validation | PASS harness; **REAL_FRAME_MISSING** |
| 3–16 | Board→Safety | PASS (prior) |
| 17 | Full regression | CI (`analyzer-ci`) — no JDK on box |
| 18 | Final audit | See REAL_FRAME_REPORT.md |

## Vision package (this milestone)

- Reference recovery documented: no `pvp_board.jpg` in git history
- `RealisticSyntheticFixture` + authored `RealisticSyntheticGroundTruth`
- Harness modes separated: REAL_FRAME / REALISTIC_SYNTHETIC / SYNTHETIC_UNIT
- Robustness audits: BoardFinder matrix, GridConfidence calibration, occlusion,
  color/shape/reconciler/special, validator three HOLD modes
- Docs: `REAL_FRAME_REPORT.md`, `docs/VISION_PARITY.md`, `docs/VISION_TEST_MATRIX.md`

## Build / CI

- Box: **no JDK** — unit tests + `assembleDebug` run on GitHub Actions (`analyzer-ci`, €0 free)
- Gates unchanged: MIN_GRID=0.98, MIN_BOARD=0.95, MAX_UNKNOWN=1
- Analyzer-only: no AccessibilityService / touch injection / auto-play / DecisionEngine

## BLOCKED / PENDING

| Item | Status |
|------|--------|
| Real `pvp_board.jpg` | REAL_FRAME_MISSING |
| Python V3.1 dump | REFERENCE_PENDING |
| Parity READY comparison | Blocked on real dump + frame |
| Real-world vision validation | NO |

## Next steps

1. Drop real `pvp_board.jpg` + V3.1 dump; set reference READY.
2. Run REAL_FRAME asserts + parity comparator on the same frame.
3. Device calibration of letterbox/board/color thresholds.
4. Do **not** add input automation.

## Safety confirmation

**ANALYZER ONLY** — recommendations/display only. Decision AI never executes input.
