# Final report — 0.24.5-diagnostics-single-move

Evidence for an auditor without repository access. This file is the final commit on the branch. `BuildConfig.GIT_COMMIT` is `GITHUB_SHA` at assemble time, so the candidate APK is the push artifact of this commit, not an earlier push and not the pull-request merge checkout. Writing the finished run id back into this file would create another commit and another APK. The pull-request body is updated from that push run after `analyzer-ci` is green.

**PR:** https://github.com/kovdacs/MatchMastersBot-Private/pull/4 (open, not merged)
**Package:** `com.match3vision.analyzer`
**Date:** 2026-10-08

No vision threshold was edited. MoveAnalysis was not retuned. No new automatic gameplay beyond one production dispatch per explicit arm, and a cap on consecutive unconfirmed results. No physical phone was used. No automatic touch is claimed.

## REQUIRED FINAL REPORT

VERSION:
0.24.5-diagnostics-single-move (versionCode 17, `app/build.gradle.kts:14-15`)

COMMIT:
The git revision that adds this file. That revision is the candidate. The earlier implementation commit `9e4704f236f196279e60cb9cf0a1d63f3833f0e8` is not the candidate once this file exists.

BASE COMMIT:
154a2634985f014b4a0d16c397bf78cca86c8453 (merge of 0.24.4, PR #3)

CI RUN:
The push workflow of this commit is the candidate. Its URL, run id, APK name, and SHA-256 are copied into the PR body after that run finishes. Do not use push run 37789741931 (commit `9e4704f`, APK `Match3Analyzer-0.24.5-diagnostics-single-move-9e4704f.apk`, SHA-256 `611f49608303a83ce230e0e2fcaea98382175df1128592b2c4163cceaf9443cc`). That run was green and used the same certificate, and it is superseded by this commit.

CI STATUS:
Filled from the push run of this commit in the PR body. Local `testDebugUnitTest` before the push: 424 passed, 0 failed, 0 skipped.

CANDIDATE APK:
`Match3Analyzer-0.24.5-diagnostics-single-move-<first 7 hex of this commit>.apk`, produced by the push job of this commit. versionCode 17. versionName 0.24.5-diagnostics-single-move. The pull-request job checks out a merge commit, so its APK bytes differ. It is not the candidate. `app-debug.apk` is the pre-rename file and is not uploaded.

APK SHA-256:
Taken from the push log line `APK_SHA256` of this commit and recomputed on the downloaded artifact. Recorded in the PR body. The earlier commit’s hash above is not this APK.

SIGNING IDENTITY:
STABLE DEBUG KEY — NEVER USE FOR RELEASE
Keystore `signing/match3-stable-debug.keystore` (PKCS12), alias `androiddebugkey`, store and key password `android` (standard debug password). Subject `CN=Android Debug, OU=STABLE DEBUG KEY - NEVER USE FOR RELEASE, O=Match3 Analyzer Debug, C=US`. Certificate SHA-256 `3ca07e89cbdedc4bcfc70604b1816743d6895dc4d1f392c45461480bebb3e369`. SHA-1 `2bf3aa3fd47971911aea8c8cf0e43c172b4c09d5`. Valid 2026-10-08 to 2054-02-23. SHA384withRSA, 2048-bit. Debug `signingConfig` points at this file (`app/build.gradle.kts:24-39`). The release build type does not. CI prints `SIGNING_MODE=stable-debug-keystore-committed` and does not use GitHub signing secrets (`.github/workflows/analyzer-ci.yml:89-97`). On the superseded commit, push run 37789741931 and PR run 37789764897 both printed this same certificate SHA-256. The push and PR APK hashes differed (`611f4960…` vs `2527b992…`, both 25202283 bytes) because the PR built merge commit `8a74110`. The certificate did not differ. The same check is repeated on this commit’s push and PR artifacts in the PR body.

TESTS BEFORE:
415 (0.24.4 report; that round’s CI `PASSED` count).

TESTS AFTER:
424. Delta +9, −0 failures. Removed `ContinuousCycleHarnessTest.continuous_5_moves`, `continuous_10_moves`, `continuous_20_moves` (those required unbounded MOVE UNCONFIRMED continuation). Added the twelve tests named in the sections below. Local XML: tests=424 failures=0 errors=0 skipped=0. CI of `9e4704f` logged the same 424 `PASSED` lines. This commit does not change tests.

LIVE PHONE: NOT TESTED

FIRST REAL AUTOMATIC TOUCH: NOT PROVEN

## 1. HOLD diagnostics

The single overwritten `cacheDir/diagnostic-bundle.json` is replaced by a ring of the last 8 bundles plus a separate pin of the first HOLD.

`DiagnosticHistory` (`DiagnosticHistory.kt:12-64`) keeps `ArrayDeque` capacity `DEFAULT_CAPACITY = 8` (`:64`). `record` (`:32-45`) pins only when `isHoldEvidence()` and `pinnedJson == null`. Later HOLDs rotate the ring and do not replace the pin. `adoptPinnedJson` (`:48-50`) reloads a disk pin at process start so a restart cannot overwrite it. `clear` (`:53-57`) is the only drop. `DiagnosticFiles.write` writes `pinned-first-hold.json` and the overlay beside `latest.json` (`DiagnosticHistory.kt:88-94`).

Share and copy do not use ADB. Bubble buttons `DIAG MEGOSZT`, `DIAG MÁSOL`, `DIAG TÖRLÉS` (`FloatingBubbleService.kt:168-185`) call `shareDiagnosticsFromBubble` (`:362-371`), `copyDiagnosticsFromBubble` (`:373-381`), and `clearDiagnosticsFromBubble` (`:383-387`). The main screen section `DIAGNOSZTIKA — HOLD` (`AnalyzerScreen.kt:121-140`) calls `AnalyzerViewModel.shareDiagnostics` / `copyDiagnostics` / `clearDiagnostics` (`AnalyzerViewModel.kt:457-473`). `DiagnosticShare` (`DiagnosticShare.kt:14-62`) copies via `ClipboardManager` and shares via `FileProvider` `ACTION_SEND` / `ACTION_SEND_MULTIPLE` with `FLAG_GRANT_READ_URI_PERMISSION` and `FLAG_ACTIVITY_NEW_TASK`. Authority `${applicationId}.fileprovider` (`AndroidManifest.xml:29-33`). Files live under `filesDir/diagnostics`.

Each bundle’s JSON includes timestamp, failure class, screen size, capture size, ROI, grid method, relVarX, relVarY, projection peak counts, mean luminance, black-frame text, grid and board confidence, unknown count, vision decision string, move analysis, verification, dispatch decision, and final safety decision (`DiagnosticBundle.kt` fields and `toJson`). Peak counts are diagnostic only. Projection confidence is unchanged: `(1f - (relVarX + relVarY) * 1.5f).coerceIn(projectionMinConfidence, 0.99f)` (`BoardFinder.kt:696`).

`fromObservation` rewrites a verification status of `SUCCESS` to `REFUSED — diagnostic does not record VERIFY SUCCESS` (`DiagnosticBundle.kt:249-253`). `diagnosticInfluencesGate` is hard-coded false (`:312`). A vision HOLD forces `selectedMove` to `none` and `dispatchStatus` to `NOT STARTED` (`:231`, `:254`). `callbackOutcome` is no longer a copy of `dispatchStatus` (`InputLoopController.kt:195-199`).

The overlay is drawn on the supplied pixels, max edge 480, magenta `0xFFFF00FF` (`DiagnosticFrame.kt`). If pixels are null, recycled, or `getPixels` throws, status is `NOT EXPORTED` and no PNG is invented (`DiagnosticFrame.kt:45-49`, `FloatingBubbleService.kt:1033-1061`). `DiagnosticLuminance` (`BLACK_MEAN = 8.0`) runs only on a real copied buffer. A missing buffer is `not measured`, not `BLACK FRAME` (`DiagnosticFrame.kt:124-129`). A failed copy still passes the zero buffer into vision, which is the previous analyze-on-copy-failure behavior; the diagnostic does not call that a captured black frame.

### Example exported HOLD bundle

From `DiagnosticHistoryTest.recordingAHold_doesNotChangeTheVisionGate`. The caller passed `dispatchStatus=SUCCESS`, `verificationStatus=SUCCESS`, and a selected move. The export refused all three. `simulated` is true because this is a unit fixture, not a phone capture. `frameExportStatus` is `NOT EXPORTED` because no pixels were supplied.

```json
{
  "appVersion": "0.24.5-diagnostics-single-move",
  "versionCode": 17,
  "sourceCommit": "test",
  "diagnosticTimestampMs": 50,
  "failureClass": "VISION",
  "visionGate": "HOLD",
  "visionReason": "HOLD: grid confidence 0.970 < 0.980",
  "gridConfidence": 0.97,
  "boardConfidence": 0.9,
  "unknownCount": 4,
  "cellLabels": ["B","B","B","B","B","B","B","B","B","B","B","B","B","B","B","B","B","B","B","B","B","B","B","B","B","B","B","B","B","B","B","B","B","B","B","B","B","B","B","B","B","B","B","B","B","B","B","B","B"],
  "boardRoi": "LTRB(0,0,70,70)",
  "frameWidth": 8,
  "frameHeight": 8,
  "screenWidth": 1080,
  "screenHeight": 2400,
  "screenSource": "maximumWindowMetrics",
  "screenRotation": -1,
  "coordinateAlignmentProven": false,
  "coordinateReason": "coordinate origin alignment UNPROVEN",
  "frameSequence": 1,
  "captureTimestampMs": 40,
  "frameAgeMs": 10,
  "frameElapsedMs": 10,
  "cadence": "not measured",
  "accessibilityConnected": true,
  "gestureCapability": "test",
  "captureState": "ON",
  "moveAnalysis": "not run — vision HOLD",
  "selectedMove": "none",
  "dispatchStatus": "NOT STARTED",
  "callbackOutcome": "not dispatched",
  "verificationStatus": "REFUSED — diagnostic does not record VERIFY SUCCESS",
  "verificationReason": "",
  "gridMethod": "PROJECTION",
  "relVarX": "0.0100",
  "relVarY": "0.0120",
  "projectionPeakCountX": "6",
  "projectionPeakCountY": "6",
  "meanLuminance": "not measured",
  "blackFrame": "not measured — frame pixels were not supplied",
  "visionDecisions": "method=PROJECTION; validation=HOLD: grid confidence 0.970 < 0.980; gridRecover=not measured; fallback=none; projectionRejected=none",
  "dispatchDecision": "NOT STARTED",
  "finalSafetyDecision": "HOLD VISION: HOLD: grid confidence 0.970 < 0.980",
  "frameExportStatus": "NOT EXPORTED",
  "frameExportReason": "frame pixels were not supplied",
  "gridBoundaries": "x=0.0,10.0,20.0,30.0,40.0,50.0,60.0,70.0;y=0.0,10.0,20.0,30.0,40.0,50.0,60.0,70.0",
  "frameTimestampMeaning": "wall-clock receipt time (System.currentTimeMillis at ImageReader delivery), not image content time",
  "diagnosticInfluencesGate": false,
  "callbackOutcomeIsDispatchCopy": false,
  "simulated": true
}
```

New tests: `DiagnosticHistoryTest.ring_keepsLastN_andDoesNotDropPinnedFirstHold`, `explicitClear_dropsPinnedFirstHold`, `missingPixels_sayNotExported_andDoNotInventAFrame`, `overlay_isDrawnOnTheSuppliedFrame_notAPlaceholder`, `recordingAHold_doesNotChangeTheVisionGate`.

## 2. Controlled single-move

UI: bubble `EGY LÉPÉS` (`FloatingBubbleService.kt:162-167`) calls `armSingleMove` (`AutoPlayController.kt:145-187`), which uses the same `StartupReadinessGate` as continuous start. Phases printed by `label()`: ARMED, ANALYZING, ONE MOVE, VERIFYING, PAUSED, STOPPED. The bubble status line appends `EGY LÉPÉS: ${label}` (`FloatingBubbleService.kt:1069-1070`).

One arm sets `productionDispatches = 0` and phase ARMED. `tryProductionDispatch` records exactly one dispatch and returns false afterwards (`SingleMoveMachine.kt:61-66`). HOLD does not call it. After `Executed`, the controller disables the input switch and sets mode PAUSED before returning (`AutoPlayController.kt:295-309`), so the same bubble iteration can still verify, then `finishSingleMoveAfterExport` (`:372-378`, called at `FloatingBubbleService.kt:913-915`) sets STOPPED. A second `runCycleIfActive` returns null. Continuous INDÍTÁS is refused while a consumed single-move protocol is in progress (`AutoPlayController.kt:103-110`).

`TESZT ÉRINTÉS` remains `runTouchTestFromBubble` → `dispatchManualTest` (`FloatingBubbleService.kt:419-483`). It does not increment `productionDispatches`. Unguarded `dispatch()` still fails. The screen text says TESZT ÉRINTÉS is separate from EGY LÉPÉS (`AnalyzerScreen.kt:123-125`).

Continuous mode caps consecutive `BOARD CHANGED — MOVE UNCONFIRMED` at 2 (`AutoPlayController.kt:347-360`, constant `:397`). The second unconfirmed calls `onFailsafePause` and disables input. A single unconfirmed leaves continuous mode RUNNING, so existing one-cycle tests stay valid. `InputLoopController.completeFeedback` itself still returns CONTINUE + UNCONFIRMED; the cap lives in the controller. `VerificationPolicy` still never returns SUCCESS.

### Single-move state machine (complete)

```kotlin
class SingleMoveMachine {
    enum class Phase {
        IDLE,
        ARMED,
        ANALYZING,
        ONE_MOVE,
        VERIFYING,
        PAUSED,
        STOPPED,
    }

    @Volatile
    var phase: Phase = Phase.IDLE
        private set

    @Volatile
    var productionDispatches: Int = 0
        private set

    fun label(): String = when (phase) {
        Phase.ONE_MOVE -> "ONE MOVE"
        else -> phase.name
    }

    fun arm(): Boolean {
        if (phase != Phase.IDLE && phase != Phase.STOPPED && phase != Phase.PAUSED) {
            return false
        }
        phase = Phase.ARMED
        productionDispatches = 0
        return true
    }

    fun onObserve() {
        if (phase == Phase.ARMED) phase = Phase.ANALYZING
    }

    fun allowsProductionDispatch(): Boolean =
        productionDispatches == 0 && (phase == Phase.ARMED || phase == Phase.ANALYZING)

    fun tryProductionDispatch(): Boolean {
        if (!allowsProductionDispatch()) return false
        productionDispatches = 1
        phase = Phase.ONE_MOVE
        return true
    }

    fun beginVerify() {
        if (phase == Phase.ONE_MOVE) phase = Phase.VERIFYING
    }

    fun onVerified() {
        if (phase == Phase.VERIFYING) phase = Phase.PAUSED
    }

    fun finishStopped() {
        if (phase == Phase.IDLE) return
        phase = Phase.STOPPED
    }

    fun resetIdle() {
        phase = Phase.IDLE
        productionDispatches = 0
    }
}
```

Source: `app/src/main/java/com/match3vision/analyzer/input/SingleMoveMachine.kt:16-86`.

New tests: `SingleMoveModeTest.stateMachine_oneArm_thenPauseVerifyStop_secondDispatchRefused`, `oneArm_oneProductionDispatch_holdDoesNotConsumeIt_unconfirmedDoesNotRepeat`, `manualTouchPath_doesNotCountAsProductionDispatch`, `productionChannel_singleMove_callsDispatchCheckedOnce`, `ContinuousCycleHarnessTest.continuous_unconfirmedCap_allowsTwoThenPauses_noThirdDispatch`.

## 3. Independent tile-centre oracle

Expected centres are chroma-blob centroids of `real_frames/pvp_board.jpg` (1080×2400), not `BoardFinder` boundaries. Columns: 89, 239, 389, 539, 690, 839, 989. Rows: 1267, 1411, 1563, 1719, 1865, 2015, and row 6 at 2165. Row 3 is the median Y of the five saturated blobs in that band (1714.5, 1714.5, 1719.0, 1727.8, 1727.8). Columns 3 and 5 of that row are low-chroma and were not used. 1719 is not the detector centre 1740.5. Row 6 is one median pitch (~149.6 px) below row 5 because the bottom band has no stable blob. Tolerance is 28 px. Half the tile pitch is about 75 px, so a neighbouring tile fails (`HandMeasuredPvpCenters.kt:19-22`).

`FixtureToGestureEndToEndTest.pvpBoardPixels_visionPass_move_productionDispatch_projectionCenters` compares all 49 cell centres and the dispatched gesture to those constants. Printed result:

`move=4,3->5,3 hand=(539.0,1865.0)->(539.0,2015.0) actual=(539.0,1880.5)->(539.0,2021.0) tolerancePx=28.0 maxAbsDx=5.5 maxAbsDy=27.5 channelCalls=1 alignmentProven=false`

Gate PASS, method PROJECTION, grid 0.98717177, board 1.0, unk 0. `verifyStatus` is PENDING, not SUCCESS.

| index | hand X | detected X | abs dx | hand Y | detected Y | abs dy |
| --- | --- | --- | --- | --- | --- | --- |
| 0 | 89 | 89.5 | 0.5 | 1267 | 1276.0 | 9.0 |
| 1 | 239 | 239.0 | 0.0 | 1411 | 1430.5 | 19.5 |
| 2 | 389 | 389.0 | 0.0 | 1563 | 1590.5 | 27.5 |
| 3 | 539 | 539.0 | 0.0 | 1719 | 1740.5 | 21.5 |
| 4 | 690 | 689.0 | 1.0 | 1865 | 1880.5 | 15.5 |
| 5 | 839 | 833.5 | 5.5 | 2015 | 2021.0 | 6.0 |
| 6 | 989 | 984.0 | 5.0 | 2165 | 2171.0 | 6.0 |

Detected boundaries (projection, not the oracle): x = 20, 159, 319, 459, 619, 759, 908, 1060; y = 1206, 1346, 1515, 1666, 1815, 1946, 2096, 2246. Centres are midpoints of those boundaries. Thresholds were not changed to make 27.5 px fit. The largest miss is row 2, 27.5 px, inside 28 and well under a one-tile error.

## 4. RGBA / live-capture parity

Path in `RgbaCaptureParityTest`: JPEG pixels from `pvp_board.jpg` → packed RGBA_8888 with 64 bytes of row padding → `RgbaBufferUnpack.unpack` (the production `ImageReader` plane path, `ScreenCaptureManager.kt:173-183`) → `VisionPipeline`.

`paddedRgba_matchesJpegFixture_geometryGateAndChannels` printed:

`RGBA_PARITY gate=PASS method=PROJECTION grid=0.98717177 roi=ContentRoi(left=20, top=1206, right=1060, bottom=2246) redPixel=(34,112) R=201 G=137 B=125 rowStride=4384`

The unpacked pixel equals the JPEG pixel. R, G, and B of that red-dominant pixel match. Width 1080, height 2400. Row stride is `1080*4+64 = 4384`, so the padding is skipped. ROI, grid confidence, board confidence, unknown count, and gate match the JPEG pipeline result.

`rbSwap_changesChannelOrder_andBoardInterpretation` packs the same JPEG with R and B exchanged and prints:

`RGBA_RB_SWAP redPixel=(34,112) jpegR=201 jpegB=125 unpackedR=125 unpackedB=201 correctGate=PASS swappedGate=HOLD labelsDiffer=true`

The swapped buffer is not the same board. This is not a length check.

## 5. Verify call site, screen metrics, timing

Frame time is receipt time. `ScreenCaptureManager` sets `timestampMs = System.currentTimeMillis()` and `elapsedRealtimeMs = FrameClock.tryElapsed()` (`SystemClock.elapsedRealtime`) when `ImageReader` delivers the buffer (`ScreenCaptureManager.kt:165-208`). `Image.getTimestamp()` is not read. Neither value is exposure time.

Swipe duration is `InputThresholds.SWIPE_DURATION_MS = 120` (`InputThresholds.kt:20`). The accessibility callback is awaited inside `dispatchGesture`. Verification does not start at schedule time. The bubble samples `dispatchCompletedElapsedMs` when `dispatchGesture` returns, then waits `ANIMATION_WAIT_MS` (650). There is no extra settle delay. It then polls up to 2500 ms in 100 ms steps for a `FrameSequenceGate`-accepted newer frame. `VerifyObservation.derive` requires that frame’s monotonic receipt time to be strictly later than the sample taken when dispatch returned, and fresh under `GestureFailSafe.MAX_FRAME_AGE_MS` (3000). Names of those existing waits are `VerifyTiming` (`VerifyTiming.kt:34-38`); the bubble `check`s they still equal `InputThresholds` (`FloatingBubbleService.kt:780-781`).

Board change is the post-dispatch `VisionResult` content hash versus the pre-dispatch hash, plus `SwapRegionCheck` of the intended swap cells (`InputLoopController.kt:312-347`, `SwapRegionCheck.kt:14-29`). A whole-board change is `BOARD CHANGED — MOVE UNCONFIRMED`, never VERIFY SUCCESS.

Production screen context is `FloatingBubbleService.this`, installed as `AndroidScreenMetrics(this)` (`FloatingBubbleService.kt:486-488`). That is `WINDOW_SERVICE` plus `resources.displayMetrics.densityDpi` as a density fallback. It is not MainActivity and not the bitmap. `dispatchChecked` re-reads `liveProbe.screen()` (`AccessibilityGestureExecutor.kt:50-59`), which is that same source, and refuses source `frame`. Matching sizes set `alignmentProven = false` and do not prove a shared origin.

### Verify call site (complete, not elided)

`FloatingBubbleService.kt:767-915`, inside the production loop after `runCycleIfActive`:

```kotlin
                    when (cycle.outcome) {
                        BotLoopOutcome.CONTINUE -> {
                            val executed = cycle.executed
                            if (executed is AutomaticInputEngine.ExecuteResult.Executed) {
                                seqGate.markGestureDispatched(frameId)
                                statusView?.text = "GESZTUS #${ctrl.moveCount} ${ctrl.singleMove.label()}"
                                // Sampled when dispatchGesture returns, before the 650 ms wait.
                                // This is receipt-side elapsedRealtime, not image content time.
                                val dispatchCompletedElapsedMs =
                                    com.match3vision.analyzer.input.FrameClock.tryElapsed()
                                val waitMs = cycle.animationWaitMs.coerceAtLeast(
                                    VerifyTiming.POST_DISPATCH_WAIT_MS,
                                )
                                check(VerifyTiming.POST_DISPATCH_WAIT_MS == InputThresholds.ANIMATION_WAIT_MS)
                                check(VerifyTiming.SWIPE_DURATION_MS == InputThresholds.SWIPE_DURATION_MS)
                                delay(waitMs)
                                var waited = 0L
                                var after = CaptureService.managerOrNull()?.latestFrame?.value
                                var afterDecision = seqGate.evaluate(after?.toSequenceId())
                                while (isActive && waited < VerifyTiming.NEW_FRAME_POLL_BUDGET_MS &&
                                    (after == null || !afterDecision.allow)
                                ) {
                                    delay(VerifyTiming.NEW_FRAME_POLL_STEP_MS)
                                    waited += VerifyTiming.NEW_FRAME_POLL_STEP_MS
                                    after = CaptureService.managerOrNull()?.latestFrame?.value
                                    afterDecision = seqGate.evaluate(after?.toSequenceId())
                                }
                                val afterFrame = after
                                val afterAge = afterFrame?.ageMs() ?: -1L
                                val afterFresh = afterFrame != null &&
                                    afterAge <= GestureFailSafe.MAX_FRAME_AGE_MS
                                if (afterFrame == null || !afterDecision.allow || !afterFresh) {
                                    val reason = when {
                                        afterFrame == null ->
                                            afterDecision.reason.ifBlank { "nincs húzás utáni ÚJ képkocka" }
                                        !afterDecision.allow -> afterDecision.reason
                                        else ->
                                            "VERIFY FAILED — stale frame age=${afterAge}ms (not used)"
                                    }
                                    ctrl.onFailsafePause(reason)
                                    publishSafetyDiagnostics(
                                        vision = vision,
                                        screen = screen,
                                        frame = useFrame,
                                        pixels = analyzed.pixels,
                                        pixelNote = analyzed.pixelNote,
                                        moveText = executed.move.move.toString(),
                                        coordinateRefused = false,
                                        coordinateReason = "",
                                        dispatchStatus = StartupReadinessGate.LastDispatch.SUCCESS.name,
                                        callbackOutcome = cycle.callbackOutcome,
                                        verificationStatus = VerificationPolicy.FAILED,
                                        verificationReason = reason,
                                    )
                                    AutoPlaySession.updateDiagnostics(
                                        phase = "ELLENŐRZÉS",
                                        frameSequence = afterDecision.verdict.name,
                                        captureStatus = if (afterFrame == null) "MISSING" else "ON",
                                        verifyStatus = VerificationPolicy.FAILED,
                                        lastDispatch = StartupReadinessGate.LastDispatch.SUCCESS,
                                        gestureStatus = "CREATED",
                                        stopReason = reason,
                                        cycleReason = reason,
                                        frameAgeMs = afterAge,
                                        frameFreshness = if (afterFrame == null) "NONE" else "STALE",
                                        frameTimestampMs = afterFrame?.timestampMs ?: -1L,
                                        frameWidth = afterFrame?.width ?: 0,
                                        frameHeight = afterFrame?.height ?: 0,
                                    )
                                    AutoPlaySession.refreshFromController()
                                    refreshBubbleUi()
                                    continue
                                }
                                val afterAnalyzed = withContext(Dispatchers.Default) {
                                    analyzeFrame(afterFrame)
                                }
                                val afterVision = afterAnalyzed.vision
                                AutoPlaySession.updateDiagnostics(
                                    phase = "ELLENŐRZÉS",
                                    verifyStatus = VerificationPolicy.PENDING,
                                    gestureStatus = "CREATED",
                                    lastDispatch = StartupReadinessGate.LastDispatch.SUCCESS,
                                )
                                val nowElapsed =
                                    com.match3vision.analyzer.input.FrameClock.tryElapsed()
                                val observation = VerifyObservation.derive(
                                    preDispatchSequence = useFrame.sequence,
                                    afterSequence = afterFrame.sequence,
                                    afterElapsedMs = afterFrame.elapsedRealtimeMs,
                                    dispatchCompletedElapsedMs = dispatchCompletedElapsedMs,
                                    nowElapsedMs = nowElapsed,
                                    gestureEligible = executed.verificationEligible,
                                )
                                val fb = withContext(Dispatchers.Default) {
                                    ctrl.completeFeedback(
                                        beforeBoardHash = executed.beforeBoardHash,
                                        afterVision = afterVision,
                                        verify = observation,
                                        beforeVision = vision,
                                        attemptedMove = executed.move.move,
                                    )
                                }
                                // Policy label only — CONTINUE is not itself VERIFY SUCCESS.
                                val verifyLabel = fb?.verifyStatus ?: VerificationPolicy.FAILED
                                AutoPlaySession.updateDiagnostics(
                                    phase = if (verifyLabel == VerificationPolicy.SUCCESS) {
                                        "LÁTÁS OK"
                                    } else {
                                        "ELLENŐRZÉS"
                                    },
                                    verifyStatus = verifyLabel,
                                    lastDispatch = StartupReadinessGate.LastDispatch.SUCCESS,
                                    gestureStatus = "CREATED",
                                    stopReason = if (verifyLabel == VerificationPolicy.FAILED) {
                                        fb?.reason
                                    } else {
                                        null
                                    },
                                    heartbeatMs = System.currentTimeMillis(),
                                    cycleReason = fb?.reason,
                                    unknownCount = afterVision.unknownCount,
                                    gridConfidence = afterVision.gridConfidence,
                                    boardConfidence = afterVision.boardConfidence,
                                    boardRoi = afterVision.diagnostics["boardRoi"] ?: boardRoiStr,
                                    frameTimestampMs = afterFrame.timestampMs,
                                    frameWidth = afterFrame.width,
                                    frameHeight = afterFrame.height,
                                    frameAgeMs = afterAge,
                                    frameFreshness = RuntimeLabels.freshness(true, afterAge),
                                    hasFrameFlag = true,
                                    visionPassFlag = afterVision.validation.isPass,
                                )
                                publishSafetyDiagnostics(
                                    vision = afterVision,
                                    screen = ProductionLiveReaders.screenSource.measure(),
                                    frame = afterFrame,
                                    pixels = afterAnalyzed.pixels,
                                    pixelNote = afterAnalyzed.pixelNote,
                                    moveText = executed.move.move.toString(),
                                    coordinateRefused = false,
                                    coordinateReason = "",
                                    dispatchStatus = StartupReadinessGate.LastDispatch.SUCCESS.name,
                                    callbackOutcome = cycle.callbackOutcome,
                                    verificationStatus = verifyLabel,
                                    verificationReason = fb?.reason ?: "",
                                )
                                if (ctrl.runStyle == AutoPlayController.RunStyle.SINGLE_MOVE) {
                                    ctrl.finishSingleMoveAfterExport()
                                }
```

### IndependentScreenMetrics.choose (complete)

`ScreenMeasurement.kt:54-93`. The captured bitmap is not an argument. Order is unchanged from 0.24.4.

```kotlin
    fun choose(
        maximumWindowWidth: Int,
        maximumWindowHeight: Int,
        currentWindowWidth: Int,
        currentWindowHeight: Int,
        realWidth: Int,
        realHeight: Int,
        densityDpi: Int,
        rotation: Int,
    ): ScreenMeasurement {
        if (maximumWindowWidth > 0 && maximumWindowHeight > 0) {
            return ScreenMeasurement(
                widthPx = maximumWindowWidth,
                heightPx = maximumWindowHeight,
                densityDpi = densityDpi,
                rotation = rotation,
                source = ScreenMeasurement.SOURCE_MAXIMUM_WINDOW,
            )
        }
        if (currentWindowWidth > 0 && currentWindowHeight > 0) {
            return ScreenMeasurement(
                widthPx = currentWindowWidth,
                heightPx = currentWindowHeight,
                densityDpi = densityDpi,
                rotation = rotation,
                source = ScreenMeasurement.SOURCE_CURRENT_WINDOW,
            )
        }
        if (realWidth > 0 && realHeight > 0) {
            return ScreenMeasurement(
                widthPx = realWidth,
                heightPx = realHeight,
                densityDpi = densityDpi,
                rotation = rotation,
                source = ScreenMeasurement.SOURCE_REAL_METRICS,
            )
        }
        return ScreenMeasurement.unavailable()
    }
```

### AndroidScreenMetrics (complete)

`ScreenMeasurement.kt:111-154`. Production constructor argument is `FloatingBubbleService` (`FloatingBubbleService.kt:487`).

```kotlin
class AndroidScreenMetrics(
    private val windowManager: WindowManager?,
    private val fallbackDensityDpi: Int = 0,
) : ScreenMetricsSource {
    constructor(context: Context) : this(
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager,
        fallbackDensityDpi = context.resources.displayMetrics.densityDpi,
    )

    override fun measure(): ScreenMeasurement {
        val wm = windowManager ?: return ScreenMeasurement.unavailable("window-manager-missing")
        var maxW = 0
        var maxH = 0
        var curW = 0
        var curH = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val max = wm.maximumWindowMetrics.bounds
            maxW = max.width()
            maxH = max.height()
            val cur = wm.currentWindowMetrics.bounds
            curW = cur.width()
            curH = cur.height()
        }
        val real = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(real)
        @Suppress("DEPRECATION")
        val rotation = wm.defaultDisplay.rotation
        val density = when {
            real.densityDpi > 0 -> real.densityDpi
            fallbackDensityDpi > 0 -> fallbackDensityDpi
            else -> 0
        }
        return IndependentScreenMetrics.choose(
            maximumWindowWidth = maxW,
            maximumWindowHeight = maxH,
            currentWindowWidth = curW,
            currentWindowHeight = curH,
            realWidth = real.widthPixels,
            realHeight = real.heightPixels,
            densityDpi = density,
            rotation = rotation,
        )
    }
}
```

Dispatch-side selection (`AccessibilityGestureExecutor.kt:50-59`): `dispatchChecked` calls `readScreen()` immediately before `dispatchGesture` and returns without calling the channel when the measurement is missing or `source` is `frame`.

## 6. Stable debug signing

See SIGNING IDENTITY above. `signing/README.md` states STABLE DEBUG KEY — NEVER USE FOR RELEASE. The key has no production security value. It is not used for the release build type.

## Thresholds and MoveAnalysis

Unchanged: `VisionThresholds.MIN_GRID_CONFIDENCE = 0.98f`, `MIN_BOARD_CONFIDENCE = 0.95f`, `MAX_UNKNOWN_COUNT = 1` (`VisionModels.kt:313-315`). `InputThresholds` still aliases those constants (`InputThresholds.kt:22-24`). `SWIPE_DURATION_MS` remains 120 and `ANIMATION_WAIT_MS` remains 650. No MoveAnalysis scoring file was edited.

## REMAINING BLOCKERS

- No device was used. Share-sheet delivery, MediaProjection consent, and accessibility enablement were not exercised on a phone.
- Coordinate origin alignment is still unproven. Equal frame and window sizes do not show that `dispatchGesture` uses the same origin as the capture (`ScreenMeasurement.kt:108-109`).
- Row 6 of the oracle is a pitch extrapolation, not a blob centroid. Row 2’s detected centre is 27.5 px from the hand centroid, inside the 28 px tolerance and short of a one-tile miss (~75 px).
- A failed `getPixels` still analyzes a zero buffer. The diagnostic says NOT EXPORTED and does not call it a captured black frame. That is not a phone measurement.
- The pull-request APK is a merge-commit build. Same certificate, different bytes. It is not the candidate.
- This round does not make the app ready for a phone test. A later grid of 0.97 must stay HOLD. The gate was not lowered.

LIVE PHONE: NOT TESTED

FIRST REAL AUTOMATIC TOUCH: NOT PROVEN
