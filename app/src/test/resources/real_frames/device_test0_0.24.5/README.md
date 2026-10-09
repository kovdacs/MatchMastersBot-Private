# Real-device Test 0 fixture (0.24.5, commit 59e11be)

Captured 2026-10-08 on the owner's phone (1080x2400, rotation 0), Match Masters live board, "Your Turn".
Files are the phone's own native screenshots (JPEG, 1080x2400), not MediaProjection frames, but frame size == screen size on this device (see hold_export_latest.json).

- native_screenshot_running_1.jpg / _2.jpg: loop RUNNING, vision HOLD (unknownCount 19). NOTE: the app's own floating bubble panel (~x 0..500, y 100..1990) is visible and covers the left ~3 board columns. Use for the overlay-occlusion test; for clean-board ROI tests, only columns 3..6 and the bubble-free areas are reliable.
- native_screenshot_paused_hiba.jpg: after the crash "HIBA: length=44409; index=-2" (onFailsafePause at FloatingBubbleService.kt:1048), frame 0x0.
- hold_export_latest.json: the app's HOLD diagnostic export (latest). Pinned-first export was identical except timestamps/sequence (265) and cadence.

Manual measurement (Grok, from the native screenshot): the real 7x7 board spans about y=1190..2240 (row pitch ~150 px) and x=0..1080 (column pitch ~154 px). The app's ROI LTRB(0,1360,1080,2400) misses the top row and treats the bottom booster/"Score legend" toolbar (y~2250..2400) as a board row. These are approximate (+-15 px) and must be re-derived/verified, not trusted blindly.
