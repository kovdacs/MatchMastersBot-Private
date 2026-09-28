# PROJECT STATUS — Match3 Vision Analyzer

**Date:** 2026-09-28 (Europe/Vienna)  
**Version:** `0.21.1-live-grid`  
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

## Auto-play bubble UX (0.21.0) + live grid (0.21.1)

- Big **INDÍTÁS** → minimal permission prompts (overlay + a11y + MediaProjection)
- Floating movable bubble: **INDÍTÁS / SZÜNET / STOP**
- Continuous loop only while bubble INDÍTÁS active; reuses Vision + Move Analysis + AutomaticInputEngine
- Does **not** auto-start before bubble INDÍTÁS
- **0.21.1:** live BoardFinder soft gutter recovery so clear PvP boards clear gridConf≥0.98 (REAL_FRAME golden unchanged)
- CI APK: `Match3Analyzer-<versionName>-<shortsha>.apk`
- See `AUTO_PLAY_BUBBLE_V1.md` / `docs/LIVE_BOARD_CAPTURE.md`

## Safety confirmation

**Input default DISABLED** until bubble INDÍTÁS.  
PASS/HOLD gates unchanged. Fail-closed verify → pause.  
STOP removes bubble + stops capture/input.
