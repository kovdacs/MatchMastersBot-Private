# ARCHITECTURE FINAL — Match3 Vision Analyzer

**Date:** 2026-09-21 (Europe/Vienna)

## Mode

**ANALYZER ONLY** — MediaProjection capture → vision → board → rules → simulation → evaluation → search → opponent/boosters/mode → decision **display**.  
No AccessibilityService, no touch/swipe injection, no auto-click, no live game control.

## Pipeline

```
CaptureFrame (MediaProjection)
  → Letterbox / ContentRoi
  → VisionPipeline / VisionFrameAnalyzer
       BoardFinder → Grid → 49× (occlusion→color→shape→special→reconcile)
       → VisionValidator (PASS|HOLD)
  → Board / GameState
  → SafetyGate (fail-closed)
  → LegalMoveGenerator → MoveSimulator → MoveEvaluator
  → BeamSearch (bounded depth/beam/time)
  → Opponent* / Booster* / Perk* / GameModeDetector (UNKNOWN if not observable)
  → DecisionEngine → TOP-N MoveEvaluation + WhyEngine
  → Compose UI (HOLD blocks actionable best-move presentation)
  → Recorders / Analytics / Learning export (optional)
```

## Packages

| Package | Role |
|---------|------|
| `capture` | MediaProjection, letterbox ROI |
| `vision` (+ `parity`) | Board/grid/cells, gate, parity export |
| `board` | Tile, Board, GameState, history |
| `rules` | Match/swap/specials/gravity/cascade/refill/resolve |
| `moves` / `simulation` | Legal moves, simulate future boards |
| `evaluation` | Scores, risk, EV, why |
| `search` | Beam look-ahead |
| `opponent` | Observable-only opponent model |
| `booster` | Booster/perk stubs |
| `gamemode` | Mode detection |
| `ai` | DecisionEngine / strategies / ranker |
| `safety` | Fail-closed gate |
| `orchestration` | AnalysisOrchestrator |
| `ui` | Compose analyzer |
| `recording` / `analytics` / `learning` | Export-only infra |

## Gates

- Vision HOLD or Safety deny → Decision AI blocked; UI shows HOLD.
- UNKNOWN cells: excluded from legal swaps / simulation marked uncertain / confidence penalty.
- Never invent refill colors without provider.

## Assumptions (not game facts)

Documented in `rules/SpecialCreator`, `SpecialCombinationResolver`, refill policy: generic match-3 patterns only.

## Parity

See `docs/VISION_PARITY.md`. Python V3.1 dumps: **REFERENCE_PENDING**.
