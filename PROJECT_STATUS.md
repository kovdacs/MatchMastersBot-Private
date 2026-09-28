# PROJECT STATUS — Match3 Vision Analyzer

**Date:** 2026-09-28 (Europe/Vienna)  
**Version:** `0.21.2-live-cells`  
**CI:** analyzer-ci on GitHub Actions (no JDK on box)  
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
| `VISION_REAL_WORLD_VALIDATED` | **NO** (partial) |

## Auto-play bubble UX + live grid/cells (0.21.2)

- Big **INDÍTÁS** → minimal permission prompts (overlay + a11y + MediaProjection)
- Floating movable bubble: **INDÍTÁS / SZÜNET / STOP**
- Continuous loop only while bubble INDÍTÁS active; reuses Vision + Move Analysis + AutomaticInputEngine
- Does **not** auto-start before bubble INDÍTÁS
- **0.21.1:** live BoardFinder soft gutter recovery (gridConf≥0.98)
- **0.21.2:** live cell dominance reconcile (unk≤1 with mushrooms/+3); bubble FUT accepts live frames; hide large analyzer UI during FUT
- CI APK: `Match3Analyzer-<versionName>-<shortsha>.apk`
- See `AUTO_PLAY_BUBBLE_V1.md` / `docs/LIVE_BOARD_CAPTURE.md`

## Safety confirmation

**Input default DISABLED** until bubble INDÍTÁS.  
PASS/HOLD gates unchanged. Fail-closed verify → pause.  
STOP removes bubble + stops capture/input.
