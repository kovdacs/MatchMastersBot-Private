# real_frames

Place a real Match Masters PvP board capture here as `pvp_board.jpg` to enable
`RealFrameVisionTest` (REAL_FRAME / REAL_FIXTURE harness).

**Status:** `REAL_FRAME_MISSING` — `pvp_board.jpg` is not in this repository.
Searches of the private repo, match3-vision-ai trees, Google Drive, and Gmail
found no matching capture. Do not invent a fake frame.

When present, CI Temurin 17 loads it via `javax.imageio.ImageIO` in
`RealFrameLoader` (no Android Bitmap required for JVM unit tests).
