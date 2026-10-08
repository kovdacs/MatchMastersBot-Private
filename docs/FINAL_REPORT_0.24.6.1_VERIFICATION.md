# Final report — 0.24.6.1 verification

Narrow verification round on top of 0.24.6 (`78cc116`). No threshold change. No MoveAnalysis retune. No automatic play and no real touch. Coordinate alignment is not proven.

## Identity

- versionName: `0.24.6.1`
- versionCode: **20** (`app/build.gradle.kts`)
- Branch: `cursor/real-device-roi-overlay-0.24.6-a08e` (PR #5, draft, base `cursor/diagnostics-single-move-0.24.5-9f3b`)
- Behavior commit (install this): `c7d3cf47bba1f47dd3378018e6d2a5310dc7a93f`
- analyzer-ci push run: **37814323126** result **success** (event `push`, head `c7d3cf4`)
- https://github.com/kovdacs/MatchMastersBot-Private/actions/runs/37814323126
- PR check on the same SHA also succeeded (run 37814330890). That run builds the merge commit. It is not the install candidate.
- Local and CI unit tests before this round: **452** passed (0.24.6, `78cc116`).
- Local and CI unit tests after this round: **461** passed, 0 failed. CI log count of `PASSED` lines is 461. `testDebugUnitTest` BUILD SUCCESSFUL.

APK artifact (push run 37814323126, downloaded and hashed locally; matches the log line `APK_SHA256`):

- Name: `Match3Analyzer-0.24.6.1-c7d3cf4.apk`
- Size: 25284247 bytes
- SHA-256: `c478c8cb1b95c457974c2e44af95927aae00c5683326b6775d222a9227479e7d`
- Signing: `SIGNING_MODE=stable-debug-keystore-committed`

A later docs-only commit that records this hash is not the install candidate. `GIT_COMMIT` is `GITHUB_SHA`, so that commit's APK has different bytes. Install `Match3Analyzer-0.24.6.1-c7d3cf4.apk` only.

## What each mandatory item changed

### 1. Diagnostic analysis and export with accessibility off

The loop used to pause on `ACCESSIBILITY: DISCONNECTED` before `analyzeFrame`, so a disconnected service never analyzed or exported. Play dispatch is unchanged: `runCycleIfActive` still pauses when a play cycle is asked to run with accessibility disconnected.

- `DiagnosticAnalysisGate` — capture with a frame and either `analysisOnly` or accessibility off returns analyze-and-export, and `callRunCycle = false`.
- `AutoPlayController.onDiagnosticStart` / `enterAnalysisOnly` — RUNNING with the input switch off. `runCycleIfActive` returns HOLD, gesture `NOT CREATED`, and does not pause, before the accessibility pause.
- `FloatingBubbleService.ensureLoopRunning` — accessibility off calls `enterAnalysisOnly` and continues into capture, analysis, and export. It does not call `runCycleIfActive`.
- `FloatingBubbleService.startLoopFromBubbleAttempt` — play start is tried first. If it refuses, `onDiagnosticStart` runs. Capture wait no longer requires accessibility.
- `MainActivity` — INDÍTÁS no longer aborts the chain or opens accessibility settings when the service is off. The bubble start falls back to diagnostics.

Tests:

- `DiagnosticAnalysisWithoutAccessibilityTest.gate_a11yOff_analyzesAndDoesNotCallThePlayCycle`
- `DiagnosticAnalysisWithoutAccessibilityTest.diagnosticStart_runsAnalysisExport_andNeverDispatches`

`RuntimeDiagnosticsAcceptanceTest` still expects a play cycle with accessibility disconnected to pause and create no gesture.

### 2. Continuous INDÍTÁS requires the self-check

`AutoPlayController.onStartRequested` checks `CoordinateSelfCheck.allowsContinuousStart()` after the accessibility gate and before RUNNING. Missing or refused record stays out of RUNNING and does not enable input. The check is not inside the production start as a hidden grant. Unit tests that expect a successful play start call `PlayPermit.allowContinuousStart()`, which only records an unproven self-check.

Test: `DiagnosticAnalysisWithoutAccessibilityTest.continuousStart_requiresRecordedSelfCheck`

### 3. Observed point different from expected does not unlock EGY LÉPÉS

`CoordinateSelfCheck.record` takes optional `observedX` / `observedY`. When both are present and either differs from the expected point, status is `OBSERVED_MISMATCH`. `allowsSingleMoveArm` and `allowsContinuousStart` stay false. `alignmentProven` stays false. A record with no observed point is still `RECORDED_UNPROVEN` (luma-only is not a measured miss).

Test: `DiagnosticAnalysisWithoutAccessibilityTest.observedMismatch_doesNotUnlockSingleMove`

### 4. Diagnostic export fields

`DiagnosticBundle` and `CaptureOverlayTrace`. The bubble loop writes the trace before `fromObservation`. `analysisOnly` forces `dispatchStatus` `NOT STARTED` and `gestureStatus` `NOT CREATED` even if the caller passed a success string.

New JSON fields:

- `overlayCollapsed`
- `overlayRect`
- `overlayGateResult`
- `skippedFrameCount`
- `collapseWallMs`
- `analyzedFrameTimestampMs`
- `latticeScore`
- `latticeStd`
- `playfieldSnap`
- `latticeRoiUsed`
- `latticeCandidate`
- `roiAspect`
- `roiTopFraction`
- `roiBottomMarginPx`
- `pitchX`
- `pitchY`
- `specialCropSizes`
- `specialRejectedCropCount`
- `specialRejectedCropOrigin`
- `gestureStatus`
- `analysisOnly`

Already present and still written: `frameWidth`, `frameHeight`, `frameAgeMs`, `accessibilityConnected`, `dispatchStatus`.

`BoardFinder.refitGutterLattice` now records score, std, and the candidate when the separator snap is kept (`latticeRoiUsed=no`). It does not change `playfieldSnap` in that case. Skipped frames after collapse increment `CaptureOverlayTrace.skippedAfterCollapse` (`FloatingBubbleService`, when `frameShowsCollapsedOverlay` is false).

Test: `DiagnosticAnalysisWithoutAccessibilityTest.diagnosticStart_runsAnalysisExport_andNeverDispatches` (PvP frame, accessibility false, `analysisOnly` true, dispatch forced to `NOT STARTED`).

### 5. Collapsed chip on the golden and on the device JPEGs

`CollapsedChipCompositeTest` paints the collapsed chip (`OverlayPlacement.collapsedChipPx`, density 2.75, high-luma low-sat) onto a copy of the pixels.

- `pvpBoard_collapsedChip_keepsGolden_andIsNotAColumnRun` — grid confidence within 5e-4 of **0.9872**, validation Pass, `gridRecover` none, `playfieldSnap` `separator_square`, overlay column mask empty.
- `deviceTest0_collapsedChip_doesNotChangeTheBubbleMask` — running_1 and running_2 mask text unchanged, ROI top 1175–1205, bottom 2225–2255, not Pass.

### 6. Caller of the 131×339 crop

The only production caller of `SpecialDetector.detect` is `VisionPipeline.analyzeCell`. It crops `GridGeometry.cellBox(row, col)` with `PixelMath.crop` and passes source `VisionPipeline.analyzeCell r=<row> c=<col>`.

131×339 is one cell whose x boundaries are 131 px apart and whose y boundaries are 339 px apart. `131 * 339 = 44409`. `arrowScore` uses `band = height / 5` as a horizontal inset, so the unguarded top-band x is `midX - band = 65 - 67 = -2`. That is the device string `length=44409; index=-2`. It is not a second detector. The old log did not record row and column.

The clamp in `regionBright` / `pixelOrNull` stays. `SpecialCropAudit.observe` records every crop size and, when the unguarded index is negative or the buffer is short, the source, width, height, and index. `VisionPipeline.analyze` clears the audit at the start of the frame so the export is that frame. Occluded cells still return before `detect`; the device crash means `detect` did run, so that cell was not short-circuited.

Test: `SpecialCropCallerTest.pipelineCell_131x339_recordsCaller_andDoesNotThrow` (injected grid, cell row 2 col 3, non-occluded fill). `SpecialDetectorBoundsTest.length44409_indexMinus2_doesNotThrow` still holds.

### 7. Frame size after PAUSED

`AutoPlaySession.updateDiagnostics` keeps a previous positive `frameWidth` / `frameHeight` when the new value is null or ≤ 0. The post-dispatch missing-frame update no longer writes an explicit 0 (`FloatingBubbleService`, `takeIf { it > 0 }`).

Test: `FrameSizeRetentionTest.pausedZero_doesNotEraseMeasuredSize_resumeKeepsIt` (1080×2400, then 0×0 with phase pause, still 1080×2400, then a real size stays non-zero).

### 8. 29.8 px oracle

`HandMeasuredPvpCenters.TOLERANCE_PX` remains `MIN_COLUMN_PITCH_PX / 5` (`149 / 5`). The observed row-2 error 27.5 is not an input. No touch is enabled. `alignmentProven` is not set.

Test: `HandMeasuredOracleTest.tolerance_doesNotDependOnTheObservedRow2Error` (and the existing `tolerance_isMinColumnPitchOverFive_row6IsMeasured`).

## Forbidden items

Thresholds stay 0.98 / 0.95 / unknown ≤ 1 (`DeviceTest0BoardRoiTest.thresholds_stayAtTheDocumentedGates` and `VisionValidatorTest.integrity_thresholdConstantsUnchanged`). MoveAnalysis was not edited. No gesture or touch feature was added. The self-check cannot set `alignmentProven`.

## Pull requests

Do not merge. No auto-merge.

When the lead approves, merge in this order:

1. **PR #4** `cursor/diagnostics-single-move-0.24.5-9f3b` → `main` (0.24.5). It is open and is the base of this work.
2. **PR #5** `cursor/real-device-roi-overlay-0.24.6-a08e` (0.24.6 plus this 0.24.6.1) after #4 is on `main`. Retarget #5 onto `main` if GitHub does not do that when #4 merges. #5 stays draft until that review.

Both stay open until then.

## Phone

Phone Test 0 for 0.24.6.1 is not part of this change. It is only after this CI is green and the lead reviews. Plan, not a result: accessibility off, diagnostics and export only, at least 2–3 minutes and 20 cycles. HOLD is acceptable. Do not run that pass with accessibility on.

LIVE PHONE: NOT TESTED FOR 0.24.6.1

FIRST REAL AUTOMATIC TOUCH: NOT PROVEN
