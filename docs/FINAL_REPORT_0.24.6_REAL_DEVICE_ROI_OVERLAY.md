# Final report — 0.24.6-real-device-roi-overlay

Evidence for an auditor without repository access. No vision threshold was edited. MoveAnalysis was not retuned. No touch or gesture feature was added. Unit tests are not a phone touch.

**Package:** `com.match3vision.analyzer`
**Date:** 2026-10-08
**PR base:** `cursor/diagnostics-single-move-0.24.5-9f3b` (0.24.5 commit `59e11be`, not merged)
**Branch start:** `fixtures/test0-device-0.24.5` (`622d42e`, fixture on top of `59e11be`)

LIVE PHONE: Test 0 only (no touch). This round did not install the new APK on a phone. The phone evidence is the 0.24.5 Test 0 capture (APK `59e11be`, 1080×2400, rotation 0).

FIRST REAL AUTOMATIC TOUCH: NOT PROVEN

## REQUIRED FINAL REPORT

VERSION:
0.24.6-real-device-roi-overlay (versionCode 19, `app/build.gradle.kts:14-15`). Supersedes 0.24.5-diagnostics-single-move (versionCode 18).

COMMIT:
The git revision that adds the vision, overlay, and crash fixes and this file. That revision is the phone candidate once its push artifact exists. A later commit that only records the artifact hash is not a behavior change except `BuildConfig.GIT_COMMIT`.

BASE COMMIT:
59e11be1fa3c46df349a980e08c580e5b85a09be (0.24.5 corrections). The fixture commit on the start branch is 622d42e. The pull request base is the 0.24.5 branch, so the diff includes the fixture plus this round.

CI PUSH RUN:
PENDING_PUSH_RUN

CI PR RUN:
PENDING_PR_RUN

CI STATUS:
Local `testDebugUnitTest` before the push: 452 passed, 0 failed, 0 skipped (`--rerun-tasks`). analyzer-ci on the candidate commit is the gate. The pull-request job checks out a merge commit, so its APK bytes differ.

CANDIDATE APK:
PENDING_APK_NAME
versionCode 19. versionName 0.24.6-real-device-roi-overlay. Produced by the push job of the candidate commit. `app-debug.apk` is the pre-rename file and is not the candidate.

APK SHA-256:
PENDING_APK_SHA256

`BuildConfig.GIT_COMMIT` is `GITHUB_SHA` at assemble time (`.github/workflows/analyzer-ci.yml` assembleDebug, name `Match3Analyzer-<versionName>-<first 7 of GITHUB_SHA>.apk`). Writing the finished hash back into this file creates another commit and another APK. The four PENDING lines above are filled from the candidate push run. If that fill is a later commit, the named artifact stays the phone candidate. Do not install a different SHA-256 from the documentation commit.

SIGNING IDENTITY:
STABLE DEBUG KEY — NEVER USE FOR RELEASE
Keystore `signing/match3-stable-debug.keystore` (PKCS12), alias `androiddebugkey`, store and key password `android`. Subject `CN=Android Debug, OU=STABLE DEBUG KEY - NEVER USE FOR RELEASE, O=Match3 Analyzer Debug, C=US`. Certificate SHA-256 `3ca07e89cbdedc4bcfc70604b1816743d6895dc4d1f392c45461480bebb3e369`. SHA-1 `2bf3aa3fd47971911aea8c8cf0e43c172b4c09d5`. Debug `signingConfig` points at this file (`app/build.gradle.kts`). The release build type does not. CI prints `SIGNING_MODE=stable-debug-keystore-committed` and does not use GitHub signing secrets. The certificate is unchanged from 0.24.5, so this APK can update-install over that build.

TESTS BEFORE:
439 (0.24.5 local `testDebugUnitTest` on versionCode 18).

TESTS AFTER:
452. Delta +13, −0 failures. Local XML after `--rerun-tasks`: tests=452 failures=0 errors=0 skipped=0.

## What changed

1. Board ROI on a tall phone frame whose square snap is clipped to the screen bottom, locked in the header, or more than 80 px off a 7-cell gutter lattice. Separator luma is not retuned. The PvP golden snap is kept.
2. While the loop is analyzing, the bubble collapses to a top-end chip. A frame captured before that collapse has been on screen is skipped. If the overlay rect still intersects the board ROI, or the overlay cannot be mapped onto the frame, the published result stays HOLD and `runCycleIfActive` is not called.
3. `SpecialDetector` no longer indexes a negative arrow band. The loop catch still pauses and still does not dispatch. The pause and the UI update are each guarded so a second throw cannot escape the catch.

No new gameplay. The diagnostics-only copy says the accessibility service can stay off. The loop still pauses if accessibility disconnects during a run. That gate was not weakened.

## 1. Board ROI

### Before (phone Test 0, APK 59e11be)

From `app/src/test/resources/real_frames/device_test0_0.24.5/hold_export_latest.json` (simulated=false):

- frame 1080×2400, screen 1080×2400, rotation 0, capture ON
- cadence median 634 ms (samples=32; the session also saw medians down to 424 ms)
- accessibility capabilities 0x21
- vision HOLD: gridConfidence 0.98397446, boardConfidence 0.0, unknownCount 19
- boardRoi `LTRB(0,1360,1080,2400)`
- grid y = 1360, 1496, 1663, 1811, 1958, 2115, 2236, 2400
- grid x = 0, 154, 308, 463, 619, 768, 919, 1080
- moveAnalysis `not run — vision HOLD`
- selectedMove `none`
- gesture not created, dispatchStatus `NOT STARTED`
- finalSafetyDecision HOLD VISION

The top gem row is above y=1360. The last grid row is the booster / “Score legend” toolbar at the bottom of the screen. The native screenshots (`native_screenshot_running_1.jpg`, `native_screenshot_running_2.jpg`) are 1080×2400 and still show the expanded bubble over the left columns. `native_screenshot_paused_hiba.jpg` is the frame after the crash pause.

Manual measurement in the fixture README: about LTRB(0,1190,1080,2240), ±15 px, row pitch about 150. That number was re-derived; it was not copied into the detector as a constant.

### How the new bounds were derived

`BoardFinder.refineBoardRoi` still trims dark margins and still calls `snapSquarePlayfield` (dark purple separator, luma cut 22, blueish fraction > 0.75, min run 12, chrome inset = side/14). That snap is unchanged for frames that are not a tall phone, and it is unchanged when it already agrees with the gutter lattice.

On a frame with height ≥ 1600 and width ≥ 700, and content aspect taller than 1.25, `refitGutterLattice` (`BoardFinder.kt`) searches a 7-cell period:

- dark-blue row profile on columns the overlay mask does not cover, step 3: luma < 48 and blue dominant, smoothed ±2
- period from `(width/7)*0.92` through `(width/7)*1.06`
- top from 47% to 54% of frame height
- bottom = top + 7×period
- reject bottom ≥ height−2, and reject bottom within 0.6×period of the screen bottom
- score = boundary dark sum − 0.65×center dark sum, plus 0.015×min center luma std when that std ≥ 45, else −3
- accept only score ≥ 5 and min std ≥ 45
- replace the snap only if the snap bottom is within 8 px of the frame bottom, or the snap top is above 40% of the height, or either edge disagrees by more than 80 px

When the overlay column mask is non-empty, the replacement uses the pre-rim content left/right. The side-rim walk treats the bright bubble as chrome and would otherwise inset the left edge. An empty mask leaves left/right on the snap, and projection does not skip any column.

Projection confidence is unchanged: `(1f - (relVarX + relVarY) * 1.5f).coerceIn(projectionMinConfidence, 0.99f)`. EVEN_SPLIT stays 0.72, which is still below 0.98. `VisionThresholds` stays grid ≥ 0.98, board ≥ 0.95, unknown ≤ 1.

### After (same JPEGs, unit test, not a new phone capture)

`DeviceTest0BoardRoiTest` prints the lattice and does not treat labels under the bubble as ground truth.

| Frame | ROI | Lattice | Overlay columns | unknown | validation |
|---|---|---|---|---|---|
| running_1 | LTRB(0,1184,1080,2227) | 1184,2227,period 149, score 6.53 | 17..452 | 16 | HOLD |
| running_2 | LTRB(0,1185,1080,2228) | 1185,2228,period 149, score 7.06 | 17..452 | 15 | HOLD |

Against the manual 1190 / 2240 window: top is 5–6 px higher, bottom is 12–13 px higher, left 0, right 1080. All inside ±15 px. `yBoundaries[0]` is 1184 or 1185 (below the old wrong top of 1360). `yBoundaries[7]` is 2227 or 2228 (above 2300, so it is not the toolbar and not y=2400). `playfieldSnap` is `gutter_lattice`. validation is not Pass. unknownCount stays above 1 because the JPEG still contains the bubble. Printed labels on running_1 (not asserted as truth): the left three columns are UNK on the upper rows; the right columns read as colors. That is the occlusion, not a forced PASS.

PvP golden `pvp_board.jpg` (same test and `LiveBoardFrameRegressionTest`): overlay mask `none`, `playfieldSnap` stays `separator_square` (not `gutter_lattice`), gridConfidence within 5e-4 of 0.9872, `gridRecover` `none`, validation Pass, boardRoi LTRB(20,1206,1060,2246). The lattice’s best window on that frame is close enough that the 80 px gate does not replace the snap.

Synthetic `tallPortrait_snapsSquarePlayfieldBelowDarkSeparator` is about 540 px tall, under the 1600 px gate, and still reports `separator_square`.

## 2. Bubble / overlay

MediaProjection records the composed screen. On Test 0 the expanded panel covered about x 0..500, y 100..1990 and sat on the left columns (12 of 19 UNK cells were under it in the export).

During ANALYZING (`collapseBubbleForCapture` in `FloatingBubbleService.kt`):

- called when bubble INDÍTÁS succeeds, when EGY LÉPÉS arms, and on each active loop iteration
- saves gravity, x, y, width, height once, then places a TOP|END chip: width 156 dp, height 96 dp, top 28 dp, end margin 8 dp (`OverlayPlacement`)
- hides the expanded buttons and keeps status and STOP
- `collapseWallMs` is wall-clock `System.currentTimeMillis`, the same clock as `CaptureFrame.timestampMs`
- a frame with timestamp &lt; collapseWall + 500 ms is not analyzed and does not call `runCycleIfActive` (`OverlayPlacement.frameShowsCollapsedOverlay`, `MIN_POST_COLLAPSE_MS` = 500)
- SZÜNET and a non-active loop restore the saved layout

After vision, `OverlayBoardGate.evaluate` compares `getLocationOnScreen` with the board ROI:

- overlay null → `HOLD: overlay bounds unknown — no touch`
- non-positive size, rotation ≠ 0 (including unknown −1), or frame size ≠ screen size → `HOLD: overlay and board are not in one pixel grid — no touch`
- interior intersection → `HOLD: overlay intersects board ROI — no touch`
- a shared edge is not an intersection
- equal sizes do not prove a shared origin; a mismatch refuses

If the gate refuses, `holdIfOverlayBlocks` copies the `VisionResult` with `ValidationResult.Hold`. A vision PASS is still published as HOLD, with the overlay reason. An existing HOLD keeps both reasons. `publishOverlayBlocked` sets gesture `NOT CREATED` and dispatch `NOT STARTED`. `runCycleIfActive` is not called. Thresholds are not edited.

`OverlayBoardGateTest` (no dispatch): expanded LTRB(0,100,500,1990) intersects board LTRB(0,1190,1080,2240); the collapsed chip at 1080×2400 density 2.75 does not (chip bottom is above y=1190); size mismatch refuses; rotations 1 and −1 refuse; null overlay refuses; shared edge allows; a frame 200 ms after collapse is not eligible and a frame 500 ms after is eligible.

The historical JPEG cannot show the collapsed chip. The mask test only proves the bright panel is detected (columns 17..452) and excluded from gutter energy. A live capture with the chip off the board is the next Test 0. This round does not claim that capture.

## 3. Crash `length=44409; index=-2`

The loop catch was `FloatingBubbleService.kt` around the old line 1048: `onFailsafePause("HIBA: ${t.message}")`. The phone then showed PAUSED and a later frame size of 0×0. The fail-safe held: no gesture, no dispatch. There is no saved stack trace in the fixture. The message shape is ART `ArrayIndexOutOfBoundsException` (`length=%d; index=%d`).

The throw site is `SpecialDetector.arrowScore`. The top/bottom bright band used `band = height/5` as a horizontal inset around `midX = width/2`. For a cell of 131×339:

- 131 × 339 = 44409
- midX = 65, band = 67, first x of the top region = 65 − 67 = −2
- the first read is y=0, x=−2, index −2

A JVM test prints a different sentence (`Index -2 out of bounds for length 44409`). The regression asserts the unguarded formula, not the ART sentence: `SpecialDetector.unguardedArrowIndex(131, 339) == -2` and `detect(IntArray(44409), 131, 339)` does not throw and returns NONE. A 10-byte buffer with the same declared size returns NONE and does not throw.

`regionBright` clamps the rectangle to `[0,width) × [0,height)` before indexing. `pixelOrNull` refuses a negative or past-the-end index. `streakScore` uses the same guard. `detect` returns NONE when `width * height` is greater than the buffer. The happy-path special tests still pass.

The catch (`FloatingBubbleService.kt`, the `auto-play loop error` handler) still calls `onFailsafePause("HIBA: …")` and still does not dispatch. The pause call and the UI update are in separate try blocks, so a throw inside the handler cannot escape and spin. The 0×0 size was the post-pause symptom (no new frame published; width and height default to 0). This round does not claim a device reproduction of the throw, and it does not invent a 0×0 frame.

## Regression fixtures

Already on the branch (commit `622d42e`), not relabeled:

- `app/src/test/resources/real_frames/device_test0_0.24.5/README.md`
- `native_screenshot_running_1.jpg`
- `native_screenshot_running_2.jpg`
- `native_screenshot_paused_hiba.jpg`
- `hold_export_latest.json`

New tests, no new images, no invented cell labels:

- `DeviceTest0BoardRoiTest` — both running JPEGs, ROI ±15, not PASS, unknown &gt; 1, thresholds unchanged, PvP mask empty and golden 0.9872 unchanged
- `SpecialDetectorBoundsTest` — length 44409, index −2
- `OverlayBoardGateTest` — intersection, chip, unmapped size, rotation, unknown overlay, collapse delay

## Components

- `app/src/main/java/com/match3vision/analyzer/vision/BoardFinder.kt` — lattice refit and overlay-column skip in projection
- `app/src/main/java/com/match3vision/analyzer/vision/OverlayColumnMask.kt` — bright low-saturation column runs
- `app/src/main/java/com/match3vision/analyzer/vision/SpecialDetector.kt` — bounds
- `app/src/main/java/com/match3vision/analyzer/overlay/OverlayPlacement.kt` — chip rect and board gate
- `app/src/main/java/com/match3vision/analyzer/overlay/FloatingBubbleService.kt` — collapse, skip, HOLD, safer catch
- `AnalyzerScreen.kt`, `AnalyzerViewModel.kt` subtitle, `strings.xml` `autoplay_steps` — diagnostics-only copy that accessibility may stay off
- `app/build.gradle.kts` versionCode 19 / versionName `0.24.6-real-device-roi-overlay`

Not edited: `VisionThresholds`, `MoveAnalysisEngine`, accessibility disconnect handling, gesture dispatch path.

## Remaining risks

- The new APK has not been on a phone. The next step is the same controlled Test 0: capture on, no automatic touch, confirm the chip is off the board, the ROI covers the seven gem rows and not the toolbar, and a bad cell does not pause the loop with `index=-2`. FIRST REAL AUTOMATIC TOUCH remains NOT PROVEN until a later round actually dispatches on a phone and shows it.
- The JPEGs still contain the expanded bubble, so unknownCount 15–16 on those files is expected. Collapse is what should clear those columns on a new capture. This report does not predict PASS. Board confidence was 0.0 on the phone because of the unknowns; the penalty formula was not changed.
- Lattice replacement runs only when the existing snap is clearly wrong. A future frame whose snap is wrong by less than 80 px and is not header-locked or bottom-clipped will keep the snap. A frame under 1600 px tall never uses the lattice.
- If the overlay mask misses the bubble, the side rim can inset the left edge on a replacement ROI. On these two JPEGs the mask was 17..452.
- The intersection gate uses the view’s current screen rect after layout. A drag of the chip onto the board HOLDs and does not dispatch. Rotation other than 0 HOLDs. Matching width and height still do not prove a shared origin.
- The 500 ms post-collapse wait skips in-flight frames. Phone cadence was about 424–634 ms, so about one frame is skipped. That skip does not dispatch.
- The crash site is identified by the index formula matching `length=44409; index=-2`. Another exception still pauses via the same catch and still does not dispatch.
- Unit tests and the lattice on a JPEG are not a touch and are not a live MediaProjection frame.

## Next step

Repeat the same controlled Test 0 with the candidate APK named above. Capture on. Do not start a touch. Confirm ROI, bubble position, and that the loop does not pause with `index=-2`.
