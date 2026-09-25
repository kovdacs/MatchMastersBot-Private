# real_frames

Place a real Match Masters PvP board capture here as `pvp_board.jpg` to enable
`RealFrameVisionTest` (REAL_FRAME harness).

**Status:** `REAL_FRAME_AVAILABLE = NO` — `pvp_board.jpg` is not in this repository
(git history search found only launcher mipmaps + REFERENCE_PENDING JSON).
Do not invent a fake frame.

## Harness modes (keep separate)

| Mode | When |
|------|------|
| REAL_FRAME | This folder’s `pvp_board.jpg` — Assume-skip if missing |
| REALISTIC_SYNTHETIC | `RealisticSyntheticFixture` — always runs; **not** a real MM frame |
| SYNTHETIC_UNIT | `SyntheticFrames.letterboxedBoard` — clean unit board |

When present, CI Temurin 17 loads JPEG via `javax.imageio.ImageIO` in
`RealFrameLoader` (no Android Bitmap required for JVM unit tests).
