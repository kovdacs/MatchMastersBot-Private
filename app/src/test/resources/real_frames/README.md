# real_frames

Real Match Masters PvP board captures (1080×2400 JPEG) for harness mode **REAL_FRAME**.

**Status:** `REAL_FRAME_AVAILABLE = YES` (checked in 2026-09-28).

## Files

| File | Role | Notes |
|------|------|-------|
| **`pvp_board.jpg`** | **PRIMARY** clean early-match board | Time Left 103, scores 0–0, mushroom +3 at row5 col2 (1-indexed). Android screenshot/edit toolbar at bottom may clip bottom UI / last board row — document honestly. |
| `pvp_board_showdown_overlay.jpg` | Secondary — READY? GO! / SHOWDOWN text overlay | Soft diagnostics only; expect HOLD / high unknowns OK |
| `pvp_board_activate_fx.jpg` | Secondary — mid-match ACTIVATE + particle FX | Hard; soft diagnostics only |
| `pvp_board_mid_volume.jpg` | Secondary — mid-match volume slider on right edge | Soft diagnostics only |

**Not a board (do not use as pvp_board):** PERKS MENU screenshot (archive `01_perks_menu.jpg` / attachment `98dd72…`) — never treat as REAL_FRAME primary.

Archive copies (outside test resources): `/workspace/mm-real-frames/01..05_*.jpg`.

## Human GT

`human_ground_truth.json` — HUMAN_VISUAL provenance for primary `pvp_board.jpg` only.
Row 7 (0-indexed row 6) marked UNVERIFIED where Android toolbar crops the primary frame.
Mushroom +3 is **not** a `SpecialType` enum value (`NONE`/`TWO_WAY_ARROW`/`LIGHTNING`/`BOMB` only) → document as special-unmapped / soft UNKNOWN.

**Never** derive GT from VisionPipeline detector output. Soft color compares only where status=VERIFIED.

## Harness modes (keep separate)

| Mode | When |
|------|------|
| REAL_FRAME | This folder — primary always runs when `pvp_board.jpg` present |
| REALISTIC_SYNTHETIC | `RealisticSyntheticFixture` — always runs; **not** a real MM frame |
| SYNTHETIC_UNIT | `SyntheticFrames.letterboxedBoard` — clean unit board |

CI Temurin 17 loads JPEG via `javax.imageio.ImageIO` in `RealFrameLoader` (no Android Bitmap for JVM unit tests).

## Caveats

- Pipeline expects 7×7; primary may have bottom row partially cropped by system screenshot toolbar.
- Overlays / FX / volume UI → expect HOLD and elevated unknowns; do not loosen PASS/HOLD gates.
- One real frame (plus occluded secondaries) ≠ full real-world validation; Python V3.1 dump still absent → `PARITY_VERIFIED=NO`.
