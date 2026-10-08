# Final report — 0.24.4-production-safety-integration

This document is the evidence an auditor can check without the private repository.
The candidate APK below was built by GitHub Actions from commit
`fb4e74eb0b7faf03c8709ff55fa624895d454c7e`. This markdown file is a later commit
on the same branch and is not inside that APK. `BuildConfig.GIT_COMMIT` is
`GITHUB_SHA` at assemble time, so a later docs-only commit produces a different
APK hash. The designated candidate is the push artifact of `fb4e74e`, not the
pull-request merge artifact and not `app-debug.apk`.

**Date:** 2026-10-08
**Branch:** `cursor/production-safety-0.24.4-8007`
**PR:** https://github.com/kovdacs/MatchMastersBot-Private/pull/3 (open, not merged)
**Package:** `com.match3vision.analyzer`
**Diff base:** `c6094796c6982231113ac6ad8ad1c339388ce653` (merge of 0.24.3, PR #2)

Local `testDebugUnitTest` on this tree before the push: **415 passed, 0 failed, 0 skipped**.
The same count is in the push CI log (`415` lines ending in ` PASSED`).
No `@Ignore`. Vision thresholds were not edited. MoveAnalysis was not retuned.
No physical phone was used. No automatic touch is claimed.

## REQUIRED FINAL REPORT

VERSION:
0.24.4-production-safety-integration

COMMIT:
fb4e74eb0b7faf03c8709ff55fa624895d454c7e

BASE COMMIT:
c6094796c6982231113ac6ad8ad1c339388ce653

CI RUN:
Push (candidate): https://github.com/kovdacs/MatchMastersBot-Private/actions/runs/37781640302
Push run id 37781640302, event `push`, headSha `fb4e74eb0b7faf03c8709ff55fa624895d454c7e`.
PR (merge checkout, not the candidate): https://github.com/kovdacs/MatchMastersBot-Private/actions/runs/37781672778
PR run id 37781672778, event `pull_request`, reported headSha `fb4e74eb0b7faf03c8709ff55fa624895d454c7e`, but `actions/checkout` built merge commit `fe1ec40` (APK name suffix).
Push artifact id 11551928943 (zip 8796330 bytes; the hash below is the APK inside the zip).
PR artifact id 11552477367 (zip 8796225 bytes). Artifact URL pattern: `https://github.com/kovdacs/MatchMastersBot-Private/actions/runs/<run>/artifacts/<id>`.

CI STATUS:
SUCCESS on the candidate commit. Both the push run and the pull-request run completed with conclusion `success`. Unit tests BUILD SUCCESSFUL. assembleDebug BUILD SUCCESSFUL. The failing-test dump step was skipped because tests passed.

CANDIDATE APK:
Match3Analyzer-0.24.4-production-safety-integration-fb4e74e.apk
Size 25136627 bytes. versionCode 16. versionName 0.24.4-production-safety-integration.
`aapt dump badging` on the downloaded push artifact:
`package: name='com.match3vision.analyzer' versionCode='16' versionName='0.24.4-production-safety-integration' compileSdkVersion='35'`
minSdk 29, targetSdk 35.
The other CI artifact is `Match3Analyzer-0.24.4-production-safety-integration-fe1ec40.apk` (25136627 bytes, SHA-256 `7f287a9eae842abdb202e4a1b6ba80ba80cc4550c55a46e8976cb395b1960f44`). Same size and version, different bytes. It is not the candidate.
Gradle’s `app/build/outputs/apk/debug/app-debug.apk` is the pre-rename file and is not uploaded.

APK SHA-256:
2e5574cb9899c68386273f16793354f7f732e24e062303e86e186443533d6661
Recomputed locally with `sha256sum` on the downloaded artifact. It matches the push log line `APK_SHA256`.

SIGNING IDENTITY:
SIGNING_MODE=ephemeral-agp-debug-keystore
SIGNING_FALLBACK=Fixed CI debug keystore secrets are absent.
apksigner verify --print-certs on the candidate:
Signer #1 certificate DN: C=US, O=Android, CN=Android Debug
Signer #1 certificate SHA-256 digest: d7530707ac10271834e2dc09ee7443f86b47cbfe9d1557635a13e7ab4a2022a5
Signer #1 certificate SHA-1 digest: 30204ac423ac2b0a0da6f185aec9836990ce1080
The PR merge APK is a different ephemeral debug certificate:
SHA-256 digest `0f729c3245c6a00d4e78513143510df62020a91b4433504c51197a085a6c43b0`, same DN `C=US, O=Android, CN=Android Debug`.
No keystore and no password were committed.

TESTS BEFORE:
388 (0.24.3 report and that round’s CI `PASSED` count).

TESTS AFTER:
415. Delta +27, −0. CI push log contains 415 ` PASSED` lines and 0 failures. Local XML totals: tests=415 failures=0 errors=0 skipped=0.

P1 FINDINGS:
Independent screen measurement:
Closed in the production path. `ProductionCycleContext.fromLoopObservation` requires a `ScreenMeasurement` and stores `screen.widthPx` / `screen.heightPx`. It refuses `source == "frame"` and does not copy `frameWidth` / `frameHeight` into the screen fields (`ProductionPath.kt:114-155`). The bubble measures with `ProductionLiveReaders.screenSource.measure()` immediately before building the context (`FloatingBubbleService.kt:582-596`). `AndroidScreenMetrics.measure` reads `maximumWindowMetrics`, then `currentWindowMetrics`, then `getRealMetrics`, and selects via `IndependentScreenMetrics.choose` (`ScreenMeasurement.kt:53-144`). `dispatchChecked` re-reads the live screen and refuses an unavailable measurement, a frame-sourced measurement, a size change during analysis, a rotation/axis swap, or a size mismatch (`AccessibilityGestureExecutor.kt:50-87`). Matching sizes continue and set `alignmentProven = false` with reason text containing `UNPROVEN` (`ProductionPath.kt:206-214`). `coordinateAlignmentProven` on the context is hard-coded false (`ProductionPath.kt:152`). JVM tests do not construct `AndroidScreenMetrics`. What remains unverified: that WindowManager on the accessibility service returns the same pixel grid `dispatchGesture` uses (`ScreenMeasurement.kt:95-99`).

Honest verification:
Closed. The bubble no longer passes `newFrameAccepted = true` / `frameFresh = true`. It calls `VerifyObservation.derive` with the pre-dispatch sequence, the post-dispatch sequence, monotonic capture time, the post-dispatch monotonic sample, and `executed.verificationEligible` (`FloatingBubbleService.kt:740-758`). `derive` sets `newFrameAccepted` only when `afterSequence > preDispatchSequence` and both are meaningful, and `frameFresh` only from monotonic age `<= MAX_FRAME_AGE_MS` (3000) (`RuntimeDiagnostics.kt:131-166`, `GestureFailSafe.kt:108`). `timingFailure()` rejects missing, zero, equal, earlier, and backwards samples. No timestamps is `"missing timestamp"`, not success (`RuntimeDiagnostics.kt:96-124`). `frameIsAfterDispatch()` is `timingFailure() == null`. `VerificationPolicy.decide` returns `BOARD CHANGED — MOVE UNCONFIRMED` when every boolean holds, and never `SUCCESS` (`RuntimeDiagnostics.kt:195-205`). `afterBoardChange(true|null)` is the same unconfirmed label; `false` is `FAILED` (`RuntimeDiagnostics.kt:211-215`). A changed hash is not VERIFY SUCCESS. Dispatch completion sets `PENDING` (`RuntimeDiagnostics.kt:187-188`). The bubble shows phase `LÁTÁS OK` only when the label is exactly `SUCCESS` (`FloatingBubbleService.kt:762-766`), which this policy does not emit.

Fixture-to-gesture integration:
Closed as a simulation. `FixtureToGestureEndToEndTest.pvpBoardPixels_visionPass_move_productionDispatch_projectionCenters` loads checked-in `pvp_board.jpg` pixels, runs the real `VisionPipeline`, and only continues if the real gate is PASS and `method == PROJECTION`. It then uses `AutoPlayController` → `InputLoopController` → `AutomaticInputEngine` → `ProductionInstall.accessibilityExecutor` → `AccessibilityGestureExecutor.dispatchChecked` → `CountingChannel`. It does not construct a passing `VisionResult` and does not use `RecordingInputGestureExecutor`. Printed result (also in the test XML `system-out`, and reproduced by the CI run of this commit):

```
E2E_FIXTURE SIMULATION gate=PASS method=PROJECTION grid=0.98717177 board=1.0 unk=0 frame=1080x2400
E2E_FIXTURE SIMULATION move=4,3->5,3 expected=(539.0,1880.5)->(539.0,2021.0) actual=(539.0,1880.5)->(539.0,2021.0) channelCalls=1 alignmentProven=false
```

Expected centres are computed from the detected PROJECTION `xBoundaries` / `yBoundaries` of the selected move, not from EVEN_SPLIT. `channelCalls=1`. `verifyStatus` is `PENDING`, not `SUCCESS`. The test asserts `SimulationMarker.BANNER` contains `SIMULATION`, `LIVE PHONE: NOT TESTED`, and `FIRST REAL AUTOMATIC TOUCH: NOT PROVEN`. Negative tests `visionHold_doesNotReachDispatchChannel` and `invalidScreenMeasurement_doesNotReachDispatchChannel` keep `dispatchGestureCalls == 0`.

DISPATCH SAFETY:
Live stop/input-state recheck:
`FloatingBubbleService.installLiveReaders` (`FloatingBubbleService.kt:399-411`), called from `onCreate`, sets `readStopped` from STOPPED, PAUSED, or `!isLoopActive()`, and the executor is built with `ProductionLiveReaders.probe(enableSwitch)` (`AutoPlaySession`). `dispatchChecked` reads `liveProbe.inputEnabled()` and `liveProbe.stopped()` immediately before the channel (`AccessibilityGestureExecutor.kt:60-68`). Null reads fail closed (`unavailable`). Stop blocks even when the permit says input is enabled. Input disabled blocks even when the permit says enabled. Tests: `ProductionSafety0244Test.liveStop_blocksEvenWhenPermitSaysInputEnabled`, `liveInputDisabled_blocksEvenWhenPermitSaysEnabled`, `liveStateUnavailable_failsClosed`.

Capture-state recheck:
`readCaptureReady` is `manager != null && manager.isCapturing` (`FloatingBubbleService.kt:402-405`). The cycle context uses the same live capturing flag (`FloatingBubbleService.kt:585-586`). `dispatchChecked` refuses a null capture read and a live capture-off even when `permit.captureOn` is true (`AccessibilityGestureExecutor.kt:62-68`, conjunction at `90-93`). Test: `liveCaptureOff_blocksEvenWhenPermitSaysOn`.

Coordinate-space refusal:
`FrameScreenCoordinatePolicy.assess` refuses unknown bounds, rotation/axis swap, and any other size mismatch, and does not scale (`ProductionPath.kt:185-235`). `dispatchChecked` applies that assessment to the live screen (`AccessibilityGestureExecutor.kt:69-87`) and also refuses if the live size differs from the permit (`69-74`). If `alignmentProven` is ever true, dispatch is refused (`85-87`). Tests: `mismatch_blocksThroughProductionContextAndDispatcher`, `rotation_blocksThroughProductionDispatcher`, `missingScreen_blocksDispatch`, `screen_refusesMeasurementLabeledAsTheCaptureFrame`, `matchingDimensions_continueChecks_butDoNotProveAlignment`, plus `ProductionDispatchGuardTest.guard_frameScreenMismatch_realDispatcher_doesNotCallDispatchGesture` and `guard_rotationSwapsAxes_realDispatcher_doesNotCallDispatchGesture`.

Callback behavior:
`GestureCallbackPolicy.decide` maps `awaitCallback == false` to `Kind.SCHEDULED_ONLY` with reason `SCHEDULED_ONLY — dispatch scheduled on main thread; callback not awaited` (`GestureCallbackPolicy.kt:58-62`). `toResult` is `Failed` containing `not eligible for verification` (`29-32`). `eligibleForVerification()` is true only for `COMPLETED` (`45`). `COMPLETED` is `Dispatched(callbackCompleted = true)` and `verifyLabel()` is `PENDING`, not `SUCCESS` (`25-28`, `40-42`). `AutomaticInputEngine` sets `verificationEligible = dispatch.callbackCompleted` (`AutomaticInputEngine.kt:202-210`). `RecordingInputGestureExecutor` still returns `Dispatched` without the flag, so simulation dispatches are not verification-eligible. `AccessibilityGestureExecutor.dispatch()` always returns `UNGUARDED_REFUSAL` and does not call the channel (`108-109`, `144-147`). TESZT ÉRINTÉS uses `dispatchManualTest`, which calls the channel without vision gates (`115`). `AutomaticTouchTest` logs that the manual path is not evidence the vision-gated path works. Test: `scheduledOnly_isNotEligibleForVerification`, `callbackCompleted_isPending_notGameStateSuccess`, `unguardedDispatch_doesNotReachChannel_manualPathIsSeparate`. Verification is not started from the schedule itself: the bubble waits for an executed result whose `verificationEligible` came from `callbackCompleted`, then waits for a newer frame (`FloatingBubbleService.kt:676-748`).

Mutation evidence:
These mutations were applied on the working tree, the named test was run with `./gradlew testDebugUnitTest --tests <name>`, the failure message was taken from the JUnit XML `failure message` attribute, and the source was restored. The committed tree does not contain the mutations. A search for `if (false &&` and `MUTATION` in `app/src/main` is empty.

| Mutation actually applied | Test | Real failure |
|---|---|---|
| M1. Comment-out equivalent: `if (false && !liveInput) return blocked("input disabled (live)")` in `dispatchChecked` | `com.match3vision.analyzer.input.ProductionSafety0244Test.liveInputDisabled_blocksEvenWhenPermitSaysEnabled` | `value of: getReason() expected to contain: input disabled (live) but was: TOCTOU recheck blocked dispatchGesture: input disabled` exit 1. The conjunction `permit.inputEnabled && liveInput` still blocked the channel (calls stayed 0). |
| M1b. M1 plus `inputEnabled = permit.inputEnabled` (live flag dropped from the conjunction) | same test | `value of: getDispatchGestureCalls() expected: 0 but was: 1` exit 1 |
| M2. `if (false && liveStopped) return blocked("STOP — live stop state")` | `ProductionSafety0244Test.liveStop_blocksEvenWhenPermitSaysInputEnabled` | `value of: getDispatchGestureCalls() expected: 0 but was: 1` exit 1 |
| M3. `if (false && !liveCapture) return blocked("CAPTURE: OFF (live)")` | `ProductionSafety0244Test.liveCaptureOff_blocksEvenWhenPermitSaysOn` | `expected to contain: CAPTURE: OFF (live) but was: TOCTOU recheck blocked dispatchGesture: CAPTURE: OFF` exit 1. Conjunction still blocked. |
| M3b. M3 plus `captureOn = permit.captureOn` | same test | `getDispatchGestureCalls() expected: 0 but was: 1` exit 1 |
| M4. Both `if (space.mapping != IDENTITY_FRAME_PIXELS)` checks in `dispatchChecked` and `DispatchRecheck.evaluate` changed to `if (false && ...)` | `ProductionSafety0244Test.rotation_blocksThroughProductionDispatcher` | `getDispatchGestureCalls() expected: 0 but was: 1` exit 1 |
| M5. `fromLoopObservation` stored `screenWidth = frameWidth` and `screenHeight = frameHeight` | `ProductionSafety0244Test.mismatch_blocksThroughProductionContextAndDispatcher` | `value of: getScreenWidth() expected: 1440 but was: 1080` exit 1 |
| M6. Removed the `capturedElapsedMs <= 0 \|\| nowElapsedMs <= 0` fail-closed return in `DispatchRecheck.evaluate` | `ProductionDispatchGuardTest.guard_missingMonotonicClock_failsClosed` | `expected to contain: monotonic frame age unavailable but was: TOCTOU recheck blocked dispatchGesture: stale frame age=3001ms` exit 1. Dispatch stayed blocked because `FrameClock.ageMs` treats a non-positive clock as stale (`STALE_AFTER_MS + 1`). |
| M6b. M6 plus `FrameClock.ageMs` no longer returns stale for invalid clocks | same test | `getDispatchGestureCalls() expected: 0 but was: 1` exit 1 |
| M7. `timingFailure()` returned null when no wall timestamps were supplied (`if (!wallSupplied) return null`) | `ProductionSafety0244Test.verify_equalEarlierMissingZeroAndBackwardsTimestamps_fail` | `value of: timingFailure() expected a string that contains: missing but was: null` exit 1 |
| M8. `VerifyObservation.derive` set `newFrameAccepted = true` | `ProductionSafety0244Test.verify_noNewFrame_andReusedSequence_fail` | `value of: getNewFrameAccepted() expected to be false` exit 1 |
| M9. `Kind.SCHEDULED_ONLY` mapped to `InputDispatchResult.Dispatched(gesture)` | `ProductionSafety0244Test.scheduledOnly_isNotEligibleForVerification` | `expected instance of: InputDispatchResult$Failed but was instance of: InputDispatchResult$Dispatched with value: Dispatched(gesture=GestureSpec(startX=120.0, startY=400.0, endX=200.0, endY=400.0, durationMs=120), callbackCompleted=false)` exit 1 |
| M10. `dispatch()` called `dispatchToChannel(gesture)` | `ProductionSafety0244Test.unguardedDispatch_doesNotReachChannel_manualPathIsSeparate` | `getDispatchGestureCalls() expected: 0 but was: 1` exit 1 |
| M11. Matching-size assessment set `alignmentProven = true` | `ProductionSafety0244Test.matchingDimensions_continueChecks_butDoNotProveAlignment` | `value of: getAlignmentProven() expected to be false` exit 1 |
| M12. `VerificationPolicy.decide` returned `SUCCESS` instead of `BOARD_CHANGED_UNCONFIRMED` | `RuntimeDiagnosticsAcceptanceTest.policy_refusesSuccessUnlessEveryVerifyInputHolds` | `value of: decide(...) expected: BOARD CHANGED — MOVE UNCONFIRMED but was: SUCCESS` exit 1 |
| M13. `afterBoardChange(true)` returned `SUCCESS` | `ProductionSafety0244Test.verify_expectedRegionChange_isUnconfirmed_notSuccess` | `value of: getVerifyStatus() expected: BOARD CHANGED — MOVE UNCONFIRMED but was: SUCCESS` exit 1 |

Note on M12: mutating only `decide()` did not fail `verify_expectedRegionChange_isUnconfirmed_notSuccess` (that run exited 0). The region path uses `VerificationPolicy.afterBoardChange`, not `decide` (`InputLoopController.kt:312`). M13 is the mutation that locks that path. Both functions were restored.

END-TO-END FIXTURE:
Actual Vision result:
PASS, method PROJECTION, frame 1080x2400. Label in the test log: `SIMULATION`. This is not a phone result.

Grid confidence:
0.98717177 (threshold `VisionThresholds.MIN_GRID_CONFIDENCE` remains 0.98f at `VisionModels.kt:314`)

Board confidence:
1.0 (threshold 0.95f at `VisionModels.kt:313`)

Unknown count:
0 (maximum still 1 at `VisionModels.kt:315`)

MoveAnalysis result:
Selected move row,col 4,3 → 5,3 (vertical swap, same column). The test fails the build if `runCycleIfActive` does not return `ExecuteResult.Executed`, so MoveAnalysis produced a legal move and the engine accepted it. The gesture duration is `InputThresholds.SWIPE_DURATION_MS` (asserted, value used by the mapper is 120 ms in the existing coordinate audit).

Expected gesture coordinates:
start (539.0, 1880.5), end (539.0, 2021.0), computed from the actual PROJECTION cell boundaries of cells (4,3) and (5,3).

Actual gesture coordinates:
start (539.0, 1880.5), end (539.0, 2021.0). Equal to the expected centres within the test tolerance of 0.05 px.

Dispatch-channel result:
`CountingChannel.dispatchGestureCalls = 1`. `InputDispatchResult.Dispatched(callbackCompleted = true)` from the test channel. Cycle `verifyStatus` is `PENDING`, not `SUCCESS`. `coordinateAlignmentProven=false`. Screen measurement source in the test is `maximumWindowMetrics` with the same 1080x2400 as the fixture so the identity size check can continue. That match does not prove origin alignment.

DIAGNOSTICS:
Export format and contents:
`DiagnosticBundle.toJson()` is hand-built JSON (no JSON library) (`DiagnosticBundle.kt`). `DiagnosticExport.publish` stores `latestJson` (`DiagnosticBundle.kt:261-268`). The bubble also writes `cacheDir/diagnostic-bundle.json` (`FloatingBubbleService.kt:874-876`). Fields present: appVersion, versionCode, sourceCommit (`BuildConfig.GIT_COMMIT`), diagnosticTimestampMs, failureClass, visionGate, visionReason, gridConfidence, boardConfidence, unknownCount, cellLabels (49 color names or `UNK`; this is the diagnostic crop, not a raw bitmap), boardRoi, frameWidth, frameHeight, screenWidth, screenHeight, screenSource, screenRotation, coordinateAlignmentProven (always false in the bundle, `DiagnosticBundle.kt:215`), coordinateReason, frameSequence, captureTimestampMs, frameAgeMs, frameElapsedMs, cadenceIntervalsMs, accessibilityConnected, gestureCapability, captureOn, hasFrame, moveAnalysis, selectedMove, dispatchStatus, callbackOutcome, verificationStatus, verificationReason, simulated. Tests: `DiagnosticBundleTest.holdBundle_explainsVision_andDoesNotInventAMove`, `captureOff_isNotLabeledAsVisionFailure`. The on-device file write was not executed on a phone.

HOLD diagnostics:
`classify` order (`DiagnosticBundle.kt:116-131`): capture off or no frame → `CAPTURE`; else accessibility disconnected → `ACCESSIBILITY`; else coordinate refusal → `COORDINATE`; else vision not PASS → `VISION`; else dispatch failed → `DISPATCH`; else verification failed → `VERIFICATION`; else `NONE`. On a vision HOLD, `selectedMove` is forced to `none` and the bundle does not invent a move. A capture-off observation is not labeled as a vision failure. The bubble does not dispatch in order to produce a diagnostic.

Frame cadence:
`FrameCadence` keeps up to 32 monotonic emit times and reports intervals (`FrameCadence.kt`). `ScreenCaptureManager` records `cadence.record(elapsed)` when the frame’s elapsed time is `> 0` (`ScreenCaptureManager.kt:210`) and resets cadence on release. The diagnostic bundle includes `cadenceIntervalsMs` when a manager exists. VirtualDisplay cadence on a static phone screen was not measured. The 3000 ms freshness gate was not loosened.

Known limitations:
The bundle’s cell labels are color names, not the captured bitmap. `callbackOutcome` is the same string as `dispatchStatus` at the publish site (`FloatingBubbleService.kt:869`). `GIT_COMMIT` is `unknown` unless `GITHUB_SHA` is set. Alignment is always reported unproven. A HOLD bundle is not a substitute for a gesture log.

ANDROID COMPATIBILITY:
MediaProjection lifecycle:
Static inspection plus unit tests of the pure functions. Not a device run. `CaptureService.startProjection` calls `CaptureConsent.reuseRefusal` before `getMediaProjection` (`CaptureService.kt:82-92`). `reuseRefusal` returns `missing MediaProjection consent token` when result data is absent, and `refusing to reuse MediaProjection consent token while capture is running` when already capturing (`CaptureConsent.kt:29-34`). `ScreenCaptureManager.start` registers `MediaProjection.Callback` (`ScreenCaptureManager.kt:73`). `onStop` calls `stop()` (`51-55`). `stop` / `release` unregister the callback (`81`, `134`). Test: `CaptureSafety0244Test.consentReuse_andForegroundRequirement`.

Foreground-service requirements:
Manifest permissions `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PROJECTION` (`AndroidManifest.xml:5-6`). `CaptureService` has `android:foregroundServiceType="mediaProjection"` (`AndroidManifest.xml:29-32`). `startAsForeground()` uses `ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` on API 29+ and is called before `startProjection` (`CaptureService.kt:59-79`). `MediaProjectionStartup.requiresForegroundServiceFirst(sdkInt)` is true for sdk >= 29 (`CaptureConsent.kt:43`). This ordering was not executed on a phone.

Accessibility configuration:
`res/xml/accessibility_service_config.xml:15-16` sets `android:canPerformGestures="true"` and `android:canRetrieveWindowContent="true"`. The service is not exported and requires `BIND_ACCESSIBILITY_SERVICE` (`AndroidManifest.xml:47-57`). It is not auto-enabled. `MediaProjectionStartup.RESTRICTED_SETTINGS_STEP` (`CaptureConsent.kt:51-53`): Android 13+ sideload requires Settings → Apps → this app → allow restricted settings, then enable the accessibility service. Uninstall can wipe that enablement. `MatchMastersAccessibilityService` still has to be connected at runtime; unit tests substitute `CountingChannel` and do not call `super.dispatchGesture`.

ImageReader row-stride handling:
`RgbaBufferUnpack.unpack` returns null when `pixelStride < 4`, when `rowStride < width * pixelStride`, or when the buffer is shorter than `(height - 1) * rowStride + width * pixelStride` (`RgbaBufferUnpack.kt:21-24`). Pixels are read at `row * rowStride + col * pixelStride` (`29-36`). `ScreenCaptureManager.onImageAvailable` uses that unpack and does not publish a frame when it returns null (`ScreenCaptureManager.kt:175-187`). Test: `CaptureSafety0244Test.rgbaUnpack_skipsRowPadding_andRejectsShortStride`. Not exercised against a device ImageReader.

Install/update compatibility:
versionCode moved from 15 (0.24.3) to 16, so a higher versionCode is present for an update install. The signing identity is not stable. This run’s secrets were absent, so each CI job generated its own AGP debug keystore. The candidate certificate SHA-256 `d7530707ac10271834e2dc09ee7443f86b47cbfe9d1557635a13e7ab4a2022a5` is not the PR merge certificate `0f729c3245c6a00d4e78513143510df62020a91b4433504c51197a085a6c43b0`. An update-install of one CI APK over the other should fail signature mismatch. Uninstalling to replace it wipes accessibility enablement and MediaProjection consent. To get a stable identity the owner adds four GitHub Actions repository secrets (this agent cannot create them):

1. `ANDROID_DEBUG_KEYSTORE_BASE64` — base64 of a debug keystore file (no wrapping). Do not commit the keystore.
2. `ANDROID_DEBUG_KEYSTORE_PASSWORD` — store password.
3. `ANDROID_DEBUG_KEY_ALIAS` — key alias.
4. `ANDROID_DEBUG_KEY_PASSWORD` — key password.

Workflow step `Prepare fixed CI debug keystore when secrets exist` (`.github/workflows/analyzer-ci.yml:89-119`) decodes the keystore to `$RUNNER_TEMP/ci-debug.keystore` only when all four are non-empty, exports `ANDROID_DEBUG_KEYSTORE_PATH` plus the password and alias env vars into `GITHUB_ENV`, and prints `SIGNING_MODE=fixed-ci-debug-keystore` without printing the passwords. If any secret is missing it prints `SIGNING_MODE=ephemeral-agp-debug-keystore` and the four secret names. `app/build.gradle.kts:24-49` creates `signingConfigs.ciDebug` and points the debug build type at it only when all four env vars are set. Otherwise AGP’s default debug keystore is used.

REMAINING BLOCKERS:
Every item below still needs a physical device. None of them was treated as passed.

1. No operator ran the APK. LIVE PHONE: NOT TESTED. FIRST REAL AUTOMATIC TOUCH: NOT PROVEN.
2. `AndroidScreenMetrics` was not constructed on a device. Equal frame and screen sizes do not prove `dispatchGesture` shares the capture origin (cutout, rotation, OEM scale, single-app vs entire-display capture).
3. `super.dispatchGesture` was not called. `CountingChannel` and the manual TESZT ÉRINTÉS path are not that call.
4. Whether a 120 ms swipe is accepted by Match Masters is unknown.
5. Overlay `FLAG_NOT_TOUCHABLE` (`FloatingBubbleService.setBubbleTouchable`) was not observed against a real gesture.
6. Accessibility enablement, restricted settings on Android 13+, and the capture-consent dialog were not exercised. API 34+ entire-display consent is coded (`CaptureConsent.modeForSdk`) and untested on a phone.
7. MediaProjection foreground-service start order, `Callback.onStop`, and ImageReader row stride were inspected and unit-tested as pure logic. They were not run against a device buffer or a system stop.
8. VirtualDisplay frame cadence on a static screen was not measured. The 3000 ms gate was not changed. A phone that stops emitting frames will fail closed at verification.
9. `FrameClock.tryElapsed()` returns 0 when the Android clock stub is missing (`FrameClock.kt:35-38`). The production bubble samples it after dispatch returns (`FloatingBubbleService.kt:681-682` and `:740-741`). On a device that is `SystemClock.elapsedRealtime` after the awaited callback, which is at or after callback completion, not a timestamp taken inside `onCompleted`. On the JVM stub, 0 fails closed. That device sample was not observed.
10. A whole-board hash change and a change in the two swap cells are `BOARD CHANGED — MOVE UNCONFIRMED`. A cascade, refill, UI change, or opponent move can look the same. This build never emits VERIFY SUCCESS from those observations.
11. Update-install over the previous CI APK is not possible with the ephemeral debug certificates above. A stable keystore secret is required before that can be claimed.
12. The diagnostic JSON file under `cacheDir` was not pulled from a phone.

What a verified move would require, and what this build will not say:
A verified move would need a gesture that the game actually applied, confirmed on a later frame as the intended swap rather than some other board change, in the same coordinate space as the capture. This policy does not have that evidence. `SUCCESS` is not returned by `decide` or `afterBoardChange`. Dispatch callback completion is `PENDING`. Hash change plus in-region cell change is `BOARD CHANGED — MOVE UNCONFIRMED` and the loop may CONTINUE, but the bubble phase stays `ELLENŐRZÉS` unless the label is exactly `SUCCESS`.

LIVE PHONE: NOT TESTED
FIRST REAL AUTOMATIC TOUCH: NOT PROVEN

## Key call sites

### Screen measurement into the production context

`ProductionPath.kt` `ProductionCycleContext.fromLoopObservation` (lines 118-155):

```kotlin
fun fromLoopObservation(
    ...
    screen: ScreenMeasurement,
    frameSequence: Long = -1L,
): RuntimeCycleContext {
    check(!hasFrame || (frameWidth > 0 && frameHeight > 0)) {
        "production frame must carry a positive size"
    }
    require(!screen.source.equals(ScreenMeasurement.SOURCE_FRAME, ignoreCase = true)) {
        "refusing screen measurement derived from the capture frame"
    }
    return RuntimeCycleContext(
        ...
        screenWidth = screen.widthPx,
        screenHeight = screen.heightPx,
        frameWidth = frameWidth,
        frameHeight = frameHeight,
        simulated = false,
        screenSource = screen.source,
        screenRotation = screen.rotation,
        coordinateAlignmentProven = false,
        frameSequence = frameSequence,
    )
}
```

Bubble call site `FloatingBubbleService.kt:582-596`:

```kotlin
val screen = ProductionLiveReaders.screenSource.measure()
val cycleContext = ProductionCycleContext.fromLoopObservation(
    a11yConnected = MatchMastersAccessibilityService.isConnected(),
    captureManagerPresent = CaptureService.managerOrNull() != null &&
        manager.isCapturing.value,
    hasFrame = true,
    frameAgeMs = useFrame.ageMs(),
    frameSequenceDecision = seqDecision,
    frameTimestampMs = useFrame.timestampMs,
    frameWidth = useFrame.width,
    frameHeight = useFrame.height,
    capturedElapsedMs = useFrame.elapsedRealtimeMs,
    screen = screen,
    frameSequence = useFrame.sequence,
)
```

Matching sizes stay unproven (`ProductionPath.kt:206-214`):

```kotlin
if (frameWidth == screenWidth && frameHeight == screenHeight) {
    return Assessment(
        mapping = Mapping.IDENTITY_FRAME_PIXELS,
        reason = "sizes match ${frameWidth}x${frameHeight}; coordinate checks continue; " +
            "coordinate origin alignment UNPROVEN " +
            "(matching dimensions are not proof); no dp or status-bar offset in code",
        deviceDependent = true,
        alignmentProven = false,
    )
}
```

### DispatchPermit.from

`DispatchRecheck.kt:34-62`:

```kotlin
fun from(
    context: RuntimeCycleContext,
    vision: VisionResult,
    gesture: GestureSpec,
    inputEnabled: Boolean,
): DispatchPermit {
    val thresholdsPass = vision.validation.isPass &&
        vision.gridConfidence >= VisionThresholds.MIN_GRID_CONFIDENCE &&
        vision.boardConfidence >= VisionThresholds.MIN_BOARD_CONFIDENCE &&
        vision.unknownCount <= VisionThresholds.MAX_UNKNOWN_COUNT
    val seqOk = context.frameSequenceDecision?.allow != false
    return DispatchPermit(
        a11yConnected = context.a11yConnected,
        captureOn = context.captureOn,
        hasFrame = context.hasFrame,
        frameAgeMs = context.frameAgeMs,
        visionPass = thresholdsPass,
        inputEnabled = inputEnabled,
        screenWidth = context.screenWidth,
        screenHeight = context.screenHeight,
        frameWidth = context.frameWidth,
        frameHeight = context.frameHeight,
        gesture = gesture,
        simulated = context.simulated,
        sequenceAllowed = seqOk,
        capturedElapsedMs = context.capturedElapsedMs,
        screenSource = context.screenSource,
    )
}
```

`DispatchRecheck.evaluate` (`DispatchRecheck.kt:74-80`) does not fall back to plan-time `frameAgeMs`:

```kotlin
fun evaluate(permit: DispatchPermit, nowElapsedMs: Long = 0L): Result {
    if (permit.capturedElapsedMs <= 0L || nowElapsedMs <= 0L) {
        return Result(false, "monotonic frame age unavailable (fail closed)")
    }
    if (nowElapsedMs < permit.capturedElapsedMs) {
        return Result(false, "backwards monotonic frame age (fail closed)")
    }
```

### dispatchChecked

`AccessibilityGestureExecutor.kt:50-109`:

```kotlin
fun dispatchChecked(permit: DispatchPermit): InputDispatchResult {
    val screen = readScreen()
    if (screen == null || !screen.valid) {
        return blocked(
            "screen measurement unavailable (${screen?.source ?: ScreenMeasurement.SOURCE_UNAVAILABLE})",
        )
    }
    if (screen.source.equals(ScreenMeasurement.SOURCE_FRAME, ignoreCase = true)) {
        return blocked("screen measurement derived from the capture frame")
    }
    val liveInput = readOrNull { liveProbe.inputEnabled() }
    val liveStopped = readOrNull { liveProbe.stopped() }
    val liveCapture = readOrNull { liveProbe.captureReady() }
    if (liveInput == null) return blocked("live input-enabled state unavailable")
    if (liveStopped == null) return blocked("live stop state unavailable")
    if (liveCapture == null) return blocked("live capture state unavailable")
    if (liveStopped) return blocked("STOP — live stop state")
    if (!liveInput) return blocked("input disabled (live)")
    if (!liveCapture) return blocked("CAPTURE: OFF (live)")
    if (permit.screenWidth != screen.widthPx || permit.screenHeight != screen.heightPx) {
        return blocked(
            "screen changed during analysis plan=${permit.screenWidth}x${permit.screenHeight} " +
                "live=${screen.widthPx}x${screen.heightPx}",
        )
    }
    val space = FrameScreenCoordinatePolicy.assess(
        frameWidth = permit.frameWidth,
        frameHeight = permit.frameHeight,
        screenWidth = screen.widthPx,
        screenHeight = screen.heightPx,
    )
    if (space.mapping != FrameScreenCoordinatePolicy.Mapping.IDENTITY_FRAME_PIXELS) {
        return blocked(space.reason)
    }
    if (space.alignmentProven) {
        return blocked("refusing a claim that coordinate alignment is proven")
    }
    val liveA11y = serviceProvider()?.canDispatchGestures() == true
    val again = DispatchRecheck.evaluate(
        permit.copy(
            a11yConnected = permit.a11yConnected && liveA11y,
            inputEnabled = permit.inputEnabled && liveInput,
            captureOn = permit.captureOn && liveCapture,
            screenWidth = screen.widthPx,
            screenHeight = screen.heightPx,
            screenSource = screen.source,
        ),
        nowElapsedMs = nowElapsedMs(),
    )
    if (!again.allow) return blocked(again.reason)
    return dispatchToChannel(permit.gesture)
}

override fun dispatch(gesture: GestureSpec): InputDispatchResult =
    InputDispatchResult.Failed(UNGUARDED_REFUSAL)
```

`blocked` prefixes every refusal with `TOCTOU recheck blocked dispatchGesture:` (`AccessibilityGestureExecutor.kt:140-141`).

### Verify call site in FloatingBubbleService

`FloatingBubbleService.kt:681-758` (dispatch-completion sample, then the post-frame observation):

```kotlin
val dispatchCompletedElapsedMs =
    com.match3vision.analyzer.input.FrameClock.tryElapsed()
...
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
```

Before `derive`, a missing post-dispatch frame, a rejected sequence, or `afterAge > 3000` already pauses with `VerificationPolicy.FAILED` (`FloatingBubbleService.kt:701-729`). `completeFeedback(null)` fails closed with `required timing information absent` (`InputLoopController.kt:274-277`).

## New and updated tests (27 added)

`ProductionSafety0244Test` (17):

1. `screen_refusesMeasurementLabeledAsTheCaptureFrame`
2. `mismatch_blocksThroughProductionContextAndDispatcher`
3. `rotation_blocksThroughProductionDispatcher`
4. `missingScreen_blocksDispatch`
5. `matchingDimensions_continueChecks_butDoNotProveAlignment`
6. `liveStop_blocksEvenWhenPermitSaysInputEnabled`
7. `liveInputDisabled_blocksEvenWhenPermitSaysEnabled`
8. `liveCaptureOff_blocksEvenWhenPermitSaysOn`
9. `liveStateUnavailable_failsClosed`
10. `unguardedDispatch_doesNotReachChannel_manualPathIsSeparate`
11. `scheduledOnly_isNotEligibleForVerification`
12. `callbackCompleted_isPending_notGameStateSuccess`
13. `verify_noNewFrame_andReusedSequence_fail`
14. `verify_equalEarlierMissingZeroAndBackwardsTimestamps_fail`
15. `verify_staleFrame_andUnchangedBoard_fail`
16. `verify_changeOutsideSwapRegion_isNotSuccess`
17. `verify_expectedRegionChange_isUnconfirmed_notSuccess`

`FixtureToGestureEndToEndTest` (3):

1. `pvpBoardPixels_visionPass_move_productionDispatch_projectionCenters`
2. `visionHold_doesNotReachDispatchChannel`
3. `invalidScreenMeasurement_doesNotReachDispatchChannel`

`DiagnosticBundleTest` (2):

1. `holdBundle_explainsVision_andDoesNotInventAMove`
2. `captureOff_isNotLabeledAsVisionFailure`

`CaptureSafety0244Test` (4):

1. `independentScreen_prefersMaximumWindow_andIgnoresAFrameSizedArgument`
2. `rgbaUnpack_skipsRowPadding_andRejectsShortStride`
3. `cadence_reportsIntervals`
4. `consentReuse_andForegroundRequirement`

`ProductionDispatchGuardTest` added `guard_missingMonotonicClock_failsClosed` (26 tests in that class; was 25).

Existing tests updated so they pass an explicit `ScreenMeasurement`, monotonic clocks, and `gestureEligible`, and so they expect `BOARD CHANGED — MOVE UNCONFIRMED` instead of `SUCCESS`: `ProductionPathIntegrationTest`, `RuntimeDiagnosticsAcceptanceTest` (`policy_refusesSuccessUnlessEveryVerifyInputHolds`, `verify_newFreshChangedBoard_isUnconfirmed_notSuccess`), `ContinuousCycleHarnessTest`, `InputLoopControllerTest`, `AutoPlayControllerTest`, `AutomaticTouchTestTest`. `verify_frameNotLaterThanDispatch_cannotSucceed` now requires the reason to contain `equal to dispatch completion` and `no SUCCESS`.

## CI test names

The 415 names below are the `Class > method PASSED` lines from push run 37781640302, rewritten as `Class.method`.

1. `com.match3vision.analyzer.ai.DecisionEngineTest.passGate_returnsMoves`
2. `com.match3vision.analyzer.ai.DecisionEngineTest.holdGate_blocks`
3. `com.match3vision.analyzer.analytics.AnalyticsCollectorTest.rates_and_export`
4. `com.match3vision.analyzer.board.BoardTest.gameState_defaultsUnknownHud`
5. `com.match3vision.analyzer.board.BoardTest.swapCopy_immutable`
6. `com.match3vision.analyzer.board.BoardTest.fromColors_valid`
7. `com.match3vision.analyzer.board.BoardTest.history_duplicate`
8. `com.match3vision.analyzer.board.BoardTest.contentHash_changes`
9. `com.match3vision.analyzer.booster.BoosterPerkTest.database_unknownId`
10. `com.match3vision.analyzer.booster.BoosterPerkTest.detector_empty_unknown`
11. `com.match3vision.analyzer.booster.BoosterPerkTest.perk_unknown`
12. `com.match3vision.analyzer.booster.BoosterPerkTest.detector_label`
13. `com.match3vision.analyzer.capture.AnalysisFrameGateTest.bubbleLoopRunning_acceptsLiveEvenWhenForeground`
14. `com.match3vision.analyzer.capture.AnalysisFrameGateTest.resume_freezesAgain`
15. `com.match3vision.analyzer.capture.AnalysisFrameGateTest.counters_trackAcceptedAndDiscarded`
16. `com.match3vision.analyzer.capture.AnalysisFrameGateTest.background_acceptsLive`
17. `com.match3vision.analyzer.capture.AnalysisFrameGateTest.default_foreground_rejectsLive`
18. `com.match3vision.analyzer.capture.AnalysisFrameGateTest.bubbleOverlayOnly_zeroAccepted_acceptsLive`
19. `com.match3vision.analyzer.capture.AnalysisFrameGateTest.forceAccept_overridesFreeze`
20. `com.match3vision.analyzer.capture.CaptureConfigTest.clampFps_preservesValidValues`
21. `com.match3vision.analyzer.capture.CaptureConfigTest.clampFps_clampsAboveMaximum`
22. `com.match3vision.analyzer.capture.CaptureConfigTest.withClampedFps_buildsValidConfigFromOutOfRange`
23. `com.match3vision.analyzer.capture.CaptureConfigTest.defaultTargetFps_isFive`
24. `com.match3vision.analyzer.capture.CaptureConfigTest.constructor_rejectsOutOfRangeFps`
25. `com.match3vision.analyzer.capture.CaptureConfigTest.clampFps_clampsBelowMinimum`
26. `com.match3vision.analyzer.capture.CaptureSafety0244Test.cadence_reportsIntervals`
27. `com.match3vision.analyzer.capture.CaptureSafety0244Test.independentScreen_prefersMaximumWindow_andIgnoresAFrameSizedArgument`
28. `com.match3vision.analyzer.capture.CaptureSafety0244Test.rgbaUnpack_skipsRowPadding_andRejectsShortStride`
29. `com.match3vision.analyzer.capture.CaptureSafety0244Test.consentReuse_andForegroundRequirement`
30. `com.match3vision.analyzer.capture.FrameSequenceGateTest.reset_clearsRequireNew`
31. `com.match3vision.analyzer.capture.FrameSequenceGateTest.afterGesture_SAME_forbidden_NEW_allowed`
32. `com.match3vision.analyzer.capture.FrameSequenceGateTest.afterGesture_OLD_forbidden`
33. `com.match3vision.analyzer.capture.FrameSequenceGateTest.afterGesture_missingFrame_forbidden`
34. `com.match3vision.analyzer.capture.LetterboxDetectorTest.noBars_returnsFullFrame`
35. `com.match3vision.analyzer.capture.LetterboxDetectorTest.allFourBars_detected`
36. `com.match3vision.analyzer.capture.LetterboxDetectorTest.topAndBottomLetterbox_detected`
37. `com.match3vision.analyzer.capture.LetterboxDetectorTest.thinDarkStrip_belowMinRatio_notTrimmed`
38. `com.match3vision.analyzer.capture.LetterboxDetectorTest.leftAndRightPillarbox_detected`
39. `com.match3vision.analyzer.capture.LetterboxDetectorTest.nearBlackBars_stillDetected`
40. `com.match3vision.analyzer.capture.LetterboxDetectorTest.luma_blackIsZero_whiteIs255`
41. `com.match3vision.analyzer.evaluation.MoveEvaluatorTest.risk_increasesWithUncertainty`
42. `com.match3vision.analyzer.evaluation.MoveEvaluatorTest.ranking_descendingEv`
43. `com.match3vision.analyzer.evaluation.MoveEvaluatorTest.evaluate_legal_hasReasons`
44. `com.match3vision.analyzer.gamemode.GameModeDetectorTest.tournament`
45. `com.match3vision.analyzer.gamemode.GameModeDetectorTest.empty_unknown`
46. `com.match3vision.analyzer.gamemode.GameModeDetectorTest.doesNotAssumeNormal`
47. `com.match3vision.analyzer.gamemode.GameModeDetectorTest.boosterRelated`
48. `com.match3vision.analyzer.input.AutoPlayControllerTest.bubbleStop_isTerminal_noMoreCycles`
49. `com.match3vision.analyzer.input.AutoPlayControllerTest.bubbleStart_thenCycleExecutes_pauseStopsFurtherInput`
50. `com.match3vision.analyzer.input.AutoPlayControllerTest.resetForNewSession_allowsStartAgain`
51. `com.match3vision.analyzer.input.AutoPlayControllerTest.visionHold_whileRunning_returnsHold_noGesture`
52. `com.match3vision.analyzer.input.AutoPlayControllerTest.default_noCycleBeforeBubbleStart`
53. `com.match3vision.analyzer.input.AutoPlayControllerTest.a11yDisconnected_blocksRunning_evenWithSettingsFlag`
54. `com.match3vision.analyzer.input.AutomaticInputEngineTest.success_thenVerifyNextFrame_boardChanged_allowsNext`
55. `com.match3vision.analyzer.input.AutomaticInputEngineTest.visionHold_blocksInput`
56. `com.match3vision.analyzer.input.AutomaticInputEngineTest.shellInputExecutor_buildsSwipeCommand`
57. `com.match3vision.analyzer.input.AutomaticInputEngineTest.repeatedVerifyFailure_STOP`
58. `com.match3vision.analyzer.input.AutomaticInputEngineTest.uncertainMove_withAdequateConfidence_stillAllowed`
59. `com.match3vision.analyzer.input.AutomaticInputEngineTest.lowMoveConfidence_blocked`
60. `com.match3vision.analyzer.input.AutomaticInputEngineTest.unknownAfterInput_failSafeSTOP`
61. `com.match3vision.analyzer.input.AutomaticInputEngineTest.passGates_allowed_dispatchesSwipeFromCellCenters`
62. `com.match3vision.analyzer.input.AutomaticInputEngineTest.touchCoordinateMapper_usesRecognizedGridCenters_notHardcoded`
63. `com.match3vision.analyzer.input.AutomaticInputEngineTest.inputChannelNotReady_blocked`
64. `com.match3vision.analyzer.input.AutomaticInputEngineTest.unknownCountAboveOne_blocked`
65. `com.match3vision.analyzer.input.AutomaticInputEngineTest.invalidNewFrame_STOP`
66. `com.match3vision.analyzer.input.AutomaticInputEngineTest.lowGridConfidence_blocked`
67. `com.match3vision.analyzer.input.AutomaticInputEngineTest.inputDisabled_blocksEvenWhenVisionPass`
68. `com.match3vision.analyzer.input.AutomaticInputEngineTest.failedVerify_boardUnchanged_STOP_noBlindRetry`
69. `com.match3vision.analyzer.input.AutomaticInputEngineTest.illegalMoveEv_blocked`
70. `com.match3vision.analyzer.input.AutomaticInputEngineTest.noLegalMove_blocked`
71. `com.match3vision.analyzer.input.AutomaticInputEngineTest.lowBoardConfidence_blocked`
72. `com.match3vision.analyzer.input.AutomaticInputEngineTest.inputEnableSwitch_defaultsDisabled`
73. `com.match3vision.analyzer.input.AutomaticTouchTestTest.exampleConstants_matchDocumented1080x2340`
74. `com.match3vision.analyzer.input.AutomaticTouchTestTest.resolveCoords_fixedFormula_for1080x2340`
75. `com.match3vision.analyzer.input.AutomaticTouchTestTest.touchTestHarness_passReportFields`
76. `com.match3vision.analyzer.input.AutomaticTouchTestTest.runOnce_success_logsEnabledCreatedDispatchSuccess`
77. `com.match3vision.analyzer.input.AutomaticTouchTestTest.runOnce_connectedButCannotGesture_failsWithoutDispatch`
78. `com.match3vision.analyzer.input.AutomaticTouchTestTest.runOnce_dispatchFails_logsFail`
79. `com.match3vision.analyzer.input.AutomaticTouchTestTest.accessibilityGestureExecutor_notReady_whenServiceMissing`
80. `com.match3vision.analyzer.input.AutomaticTouchTestTest.runOnce_a11yDisabled_failsWithoutDispatch`
81. `com.match3vision.analyzer.input.BotStateMachineTest.unknown_failSafeStop`
82. `com.match3vision.analyzer.input.BotStateMachineTest.happyPath_cyclesToCaptureAfterVerify`
83. `com.match3vision.analyzer.input.BotStateMachineTest.validationHold_goesToHold`
84. `com.match3vision.analyzer.input.BotStateMachineTest.stopIsTerminal_startCaptureNoOp`
85. `com.match3vision.analyzer.input.ContinuousCycleHarnessTest.continuous_20_moves`
86. `com.match3vision.analyzer.input.ContinuousCycleHarnessTest.holdDoesNotFreezeModeRunning`
87. `com.match3vision.analyzer.input.ContinuousCycleHarnessTest.continuous_10_moves`
88. `com.match3vision.analyzer.input.ContinuousCycleHarnessTest.continuous_5_moves`
89. `com.match3vision.analyzer.input.ContinuousCycleHarnessTest.continuous_1_move`
90. `com.match3vision.analyzer.input.ContinuousCycleHarnessTest.verifyUnchanged_stops_noBlindRetry`
91. `com.match3vision.analyzer.input.DiagnosticBundleTest.holdBundle_explainsVision_andDoesNotInventAMove`
92. `com.match3vision.analyzer.input.DiagnosticBundleTest.captureOff_isNotLabeledAsVisionFailure`
93. `com.match3vision.analyzer.input.FixtureToGestureEndToEndTest.invalidScreenMeasurement_doesNotReachDispatchChannel`
94. `com.match3vision.analyzer.input.FixtureToGestureEndToEndTest.pvpBoardPixels_visionPass_move_productionDispatch_projectionCenters`
95. `com.match3vision.analyzer.input.FixtureToGestureEndToEndTest.visionHold_doesNotReachDispatchChannel`
96. `com.match3vision.analyzer.input.GestureFailSafeTest.unknownCountAboveOne_blocks`
97. `com.match3vision.analyzer.input.GestureFailSafeTest.visionHold_blocks`
98. `com.match3vision.analyzer.input.GestureFailSafeTest.noValidMove_blocks`
99. `com.match3vision.analyzer.input.GestureFailSafeTest.thresholds_notLoosened`
100. `com.match3vision.analyzer.input.GestureFailSafeTest.uncertainLowConfidence_blocks`
101. `com.match3vision.analyzer.input.GestureFailSafeTest.cascadeUncertain_withAdequateConfidence_stillAllowed`
102. `com.match3vision.analyzer.input.GestureFailSafeTest.sameFrameAfterGesture_blocks`
103. `com.match3vision.analyzer.input.GestureFailSafeTest.happyPath_allows`
104. `com.match3vision.analyzer.input.GestureFailSafeTest.staleFrame_blocks`
105. `com.match3vision.analyzer.input.GestureFailSafeTest.captureError_blocks`
106. `com.match3vision.analyzer.input.GestureFailSafeTest.gridConfBelow098_blocks`
107. `com.match3vision.analyzer.input.GestureFailSafeTest.noFreshFrame_blocks`
108. `com.match3vision.analyzer.input.GestureFailSafeTest.inputReadyFalse_blocks`
109. `com.match3vision.analyzer.input.GestureFailSafeTest.boardConfBelow095_blocks`
110. `com.match3vision.analyzer.input.GestureFailSafeTest.a11yNotConnected_blocks`
111. `com.match3vision.analyzer.input.InputLoopControllerTest.enabled_executes_thenFeedbackSuccess`
112. `com.match3vision.analyzer.input.InputLoopControllerTest.visionHold_noInput`
113. `com.match3vision.analyzer.input.InputLoopControllerTest.defaultDisabled_analyzesButDoesNotInput`
114. `com.match3vision.analyzer.input.OneStepSmokeControllerTest.boardUnchanged_STOP_noBlindRetry_noSecondSwipe`
115. `com.match3vision.analyzer.input.OneStepSmokeControllerTest.smokeEnableSwitch_defaultsDisabled`
116. `com.match3vision.analyzer.input.OneStepSmokeControllerTest.channelNotReady_hold`
117. `com.match3vision.analyzer.input.OneStepSmokeControllerTest.oneStep_dispatchesExactlyOneSwipe_fromLiveGridCenters`
118. `com.match3vision.analyzer.input.OneStepSmokeControllerTest.inputDisabled_blocksEvenWhenSmokeEnabled`
119. `com.match3vision.analyzer.input.OneStepSmokeControllerTest.unknownCountAboveOne_blocked`
120. `com.match3vision.analyzer.input.OneStepSmokeControllerTest.visionHold_blocks_noSwipe`
121. `com.match3vision.analyzer.input.OneStepSmokeControllerTest.smokeDisabled_blocksEvenWhenInputEnabledAndVisionPass`
122. `com.match3vision.analyzer.input.OneStepSmokeControllerTest.lowGridConfidence_blocked`
123. `com.match3vision.analyzer.input.OneStepSmokeControllerTest.maxSwipesConstant_isOne`
124. `com.match3vision.analyzer.input.OneStepSmokeControllerTest.feedbackSuccess_readyForNext_secondSwipeBlockedUntilReset`
125. `com.match3vision.analyzer.input.ProductionDispatchGuardTest.guard_staleFrame_realDispatcher_doesNotCallDispatchGesture`
126. `com.match3vision.analyzer.input.ProductionDispatchGuardTest.guard_missingMonotonicClock_failsClosed`
127. `com.match3vision.analyzer.input.ProductionDispatchGuardTest.guard_sequenceRejected_realDispatcher_doesNotCallDispatchGesture`
128. `com.match3vision.analyzer.input.ProductionDispatchGuardTest.guard_inputDisabled_realDispatcher_doesNotCallDispatchGesture`
129. `com.match3vision.analyzer.input.ProductionDispatchGuardTest.guard_offScreen_realDispatcher_doesNotCallDispatchGesture`
130. `com.match3vision.analyzer.input.ProductionDispatchGuardTest.verify_identicalBoard_cannotSucceed`
131. `com.match3vision.analyzer.input.ProductionDispatchGuardTest.frameClock_usesMonotonicAge_threshold3000_backwardsIsStale`
132. `com.match3vision.analyzer.input.ProductionDispatchGuardTest.guard_captureOff_realDispatcher_doesNotCallDispatchGesture`
133. `com.match3vision.analyzer.input.ProductionDispatchGuardTest.engine_staleContext_doesNotReachRealDispatchGesture`
134. `com.match3vision.analyzer.input.ProductionDispatchGuardTest.guard_a11yDisconnected_realDispatcher_doesNotCallDispatchGesture`
135. `com.match3vision.analyzer.input.ProductionDispatchGuardTest.verify_noNewFrame_cannotSucceed`
136. `com.match3vision.analyzer.input.ProductionDispatchGuardTest.bubble_showsHoldReasonAndConfidencesWhenVisionDeclines`
137. `com.match3vision.analyzer.input.ProductionDispatchGuardTest.callback_onCancelled_isFailed_notVerifySuccess`
138. `com.match3vision.analyzer.input.ProductionDispatchGuardTest.guard_missingFrame_realDispatcher_doesNotCallDispatchGesture`
139. `com.match3vision.analyzer.input.ProductionDispatchGuardTest.callback_onCompleted_staysVerifyPending`
140. `com.match3vision.analyzer.input.ProductionDispatchGuardTest.guard_frameAgedDuringAnalysis_realDispatcher_remeasuresBeforeDispatchGesture`
141. `com.match3vision.analyzer.input.ProductionDispatchGuardTest.callback_neverArrives_isTimeout_notVerifySuccess`
142. `com.match3vision.analyzer.input.ProductionDispatchGuardTest.happyPath_realDispatcher_callsDispatchGestureOnce_andIsNotVerifySuccess`
143. `com.match3vision.analyzer.input.ProductionDispatchGuardTest.guard_a11yDroppedAfterPlan_realDispatcher_rereadsChannel`
144. `com.match3vision.analyzer.input.ProductionDispatchGuardTest.guard_visionHold_realDispatcher_doesNotCallDispatchGesture`
145. `com.match3vision.analyzer.input.ProductionDispatchGuardTest.guard_simulatedContext_realDispatcher_doesNotCallDispatchGesture`
146. `com.match3vision.analyzer.input.ProductionDispatchGuardTest.coordinate_independentCenters_nonzeroRoi_noDensity_rotationRefused`
147. `com.match3vision.analyzer.input.ProductionDispatchGuardTest.verify_frameNotLaterThanDispatch_cannotSucceed`
148. `com.match3vision.analyzer.input.ProductionDispatchGuardTest.guard_frameScreenMismatch_realDispatcher_doesNotCallDispatchGesture`
149. `com.match3vision.analyzer.input.ProductionDispatchGuardTest.guard_unknownScreenBounds_realDispatcher_doesNotCallDispatchGesture`
150. `com.match3vision.analyzer.input.ProductionDispatchGuardTest.guard_rotationSwapsAxes_realDispatcher_doesNotCallDispatchGesture`
151. `com.match3vision.analyzer.input.ProductionPathIntegrationTest.productionInstall_isAccessibilityExecutor_notRecording`
152. `com.match3vision.analyzer.input.ProductionPathIntegrationTest.productionExecutor_refusesSimulatedContext_withoutConsultingService`
153. `com.match3vision.analyzer.input.ProductionPathIntegrationTest.coordinate_sizeMismatch_blocksBeforeDispatch_simulationProbe`
154. `com.match3vision.analyzer.input.ProductionPathIntegrationTest.verify_onlyNewFreshChangedBoard_canSucceed_andStaysSimulation`
155. `com.match3vision.analyzer.input.ProductionPathIntegrationTest.forbidden_staleFrame_noDispatch_simulationProbe`
156. `com.match3vision.analyzer.input.ProductionPathIntegrationTest.forbidden_inputDisabled_noDispatch_simulationProbe`
157. `com.match3vision.analyzer.input.ProductionPathIntegrationTest.forbidden_a11yDisconnected_noDispatch_simulationProbe`
158. `com.match3vision.analyzer.input.ProductionPathIntegrationTest.productionCycleContext_keepsIndependentScreen_andIsNeverSimulated`
159. `com.match3vision.analyzer.input.ProductionPathIntegrationTest.callChain_namesTheProductionStepsInOrder`
160. `com.match3vision.analyzer.input.ProductionPathIntegrationTest.forbidden_missingFrame_noDispatch_simulationProbe`
161. `com.match3vision.analyzer.input.ProductionPathIntegrationTest.captureConsent_api34RequestsEntireDisplay_olderSdksUseChooser`
162. `com.match3vision.analyzer.input.ProductionPathIntegrationTest.productionExecutor_nullService_doesNotDispatchEvenIfContextSaysConnected`
163. `com.match3vision.analyzer.input.ProductionPathIntegrationTest.forbidden_visionHold_noDispatch_simulationProbe`
164. `com.match3vision.analyzer.input.ProductionPathIntegrationTest.coordinate_identityWhenSizesMatch_cellCentersUnchanged_mismatchRefused`
165. `com.match3vision.analyzer.input.ProductionPathIntegrationTest.recordingShellAndUninstalled_cannotBeInstalledAsProduction`
166. `com.match3vision.analyzer.input.ProductionPathIntegrationTest.forbidden_offScreenCoordinate_noDispatch_simulationProbe`
167. `com.match3vision.analyzer.input.ProductionPathIntegrationTest.captureSize_prefersMaximumWindowMetrics_sameSpaceAsTouchTest`
168. `com.match3vision.analyzer.input.ProductionPathIntegrationTest.thresholds_unchanged`
169. `com.match3vision.analyzer.input.ProductionPathIntegrationTest.dispatchSuccess_isNotVerifySuccess_staleFrameCannotVerify_simulationProbe`
170. `com.match3vision.analyzer.input.ProductionPathIntegrationTest.defaultEngine_isUninstalled_notTheTestRecorder`
171. `com.match3vision.analyzer.input.ProductionPathIntegrationTest.productionExecutor_refusesNullContext_withoutConsultingService`
172. `com.match3vision.analyzer.input.ProductionSafety0244Test.verify_changeOutsideSwapRegion_isNotSuccess`
173. `com.match3vision.analyzer.input.ProductionSafety0244Test.liveStop_blocksEvenWhenPermitSaysInputEnabled`
174. `com.match3vision.analyzer.input.ProductionSafety0244Test.verify_equalEarlierMissingZeroAndBackwardsTimestamps_fail`
175. `com.match3vision.analyzer.input.ProductionSafety0244Test.unguardedDispatch_doesNotReachChannel_manualPathIsSeparate`
176. `com.match3vision.analyzer.input.ProductionSafety0244Test.matchingDimensions_continueChecks_butDoNotProveAlignment`
177. `com.match3vision.analyzer.input.ProductionSafety0244Test.liveInputDisabled_blocksEvenWhenPermitSaysEnabled`
178. `com.match3vision.analyzer.input.ProductionSafety0244Test.rotation_blocksThroughProductionDispatcher`
179. `com.match3vision.analyzer.input.ProductionSafety0244Test.screen_refusesMeasurementLabeledAsTheCaptureFrame`
180. `com.match3vision.analyzer.input.ProductionSafety0244Test.mismatch_blocksThroughProductionContextAndDispatcher`
181. `com.match3vision.analyzer.input.ProductionSafety0244Test.verify_noNewFrame_andReusedSequence_fail`
182. `com.match3vision.analyzer.input.ProductionSafety0244Test.liveCaptureOff_blocksEvenWhenPermitSaysOn`
183. `com.match3vision.analyzer.input.ProductionSafety0244Test.verify_staleFrame_andUnchangedBoard_fail`
184. `com.match3vision.analyzer.input.ProductionSafety0244Test.liveStateUnavailable_failsClosed`
185. `com.match3vision.analyzer.input.ProductionSafety0244Test.callbackCompleted_isPending_notGameStateSuccess`
186. `com.match3vision.analyzer.input.ProductionSafety0244Test.verify_expectedRegionChange_isUnconfirmed_notSuccess`
187. `com.match3vision.analyzer.input.ProductionSafety0244Test.missingScreen_blocksDispatch`
188. `com.match3vision.analyzer.input.ProductionSafety0244Test.scheduledOnly_isNotEligibleForVerification`
189. `com.match3vision.analyzer.input.RestartCycleTest.a11yDisconnect_blocksStart_reconnectAllowsStart`
190. `com.match3vision.analyzer.input.RestartCycleTest.startStopStart_clearsStaleState`
191. `com.match3vision.analyzer.input.RuntimeDiagnosticsAcceptanceTest.policy_refusesSuccessUnlessEveryVerifyInputHolds`
192. `com.match3vision.analyzer.input.RuntimeDiagnosticsAcceptanceTest.offScreenAndUnknownBounds_blockedBeforeDispatch_noRetry`
193. `com.match3vision.analyzer.input.RuntimeDiagnosticsAcceptanceTest.faultyInputReadiness_blocksDispatchInEveryCase`
194. `com.match3vision.analyzer.input.RuntimeDiagnosticsAcceptanceTest.verify_newFreshChangedBoard_isUnconfirmed_notSuccess`
195. `com.match3vision.analyzer.input.RuntimeDiagnosticsAcceptanceTest.sameFrameAfterGesture_blocksBeforeDispatch`
196. `com.match3vision.analyzer.input.RuntimeDiagnosticsAcceptanceTest.verify_boardUnchanged_failed`
197. `com.match3vision.analyzer.input.RuntimeDiagnosticsAcceptanceTest.verify_boardChangedButNotVerifiable_failed`
198. `com.match3vision.analyzer.input.RuntimeDiagnosticsAcceptanceTest.verify_requiresNewFreshFrame_andDoesNotTreatDispatchAsSuccess`
199. `com.match3vision.analyzer.input.RuntimeDiagnosticsAcceptanceTest.verify_nonNewFrame_failedEvenIfBoardWouldDiffer`
200. `com.match3vision.analyzer.input.RuntimeDiagnosticsAcceptanceTest.bubble_listsRequiredRuntimeFields`
201. `com.match3vision.analyzer.input.RuntimeDiagnosticsAcceptanceTest.accessibilityOrCaptureDrop_pauses_andRestartIsExplicit`
202. `com.match3vision.analyzer.input.RuntimeDiagnosticsAcceptanceTest.staleFrame_notUsedForNewDecision_thenFreshStillWorks`
203. `com.match3vision.analyzer.input.RuntimeDiagnosticsAcceptanceTest.passButNoLegalMove_blocksDispatch`
204. `com.match3vision.analyzer.input.RuntimeDiagnosticsAcceptanceTest.noFrame_blocksDispatch`
205. `com.match3vision.analyzer.input.RuntimeDiagnosticsAcceptanceTest.dispatchSuccess_isNotVerifySuccess`
206. `com.match3vision.analyzer.input.RuntimeDiagnosticsAcceptanceTest.visionHold_neverReachesDispatch`
207. `com.match3vision.analyzer.input.RuntimeDiagnosticsAcceptanceTest.idleAfterRejectedStart_showsConcreteBlock_notPressStart`
208. `com.match3vision.analyzer.input.RuntimeDiagnosticsAcceptanceTest.thresholds_unchanged`
209. `com.match3vision.analyzer.input.RuntimeDiagnosticsAcceptanceTest.dispatchFailure_doesNotRetryAndIsNotVerifySuccess`
210. `com.match3vision.analyzer.input.StartupReadinessGateTest.captureOff_blocksEvenWhenConnected`
211. `com.match3vision.analyzer.input.StartupReadinessGateTest.runtimeConnected_allowsRunning_inputReadyOnlyWhenSwitchOn`
212. `com.match3vision.analyzer.input.StartupReadinessGateTest.runtimeDisconnected_noSettings_blocksClearly`
213. `com.match3vision.analyzer.input.StartupReadinessGateTest.autoPlayController_rejectsStartWhenA11yDisconnected`
214. `com.match3vision.analyzer.input.StartupReadinessGateTest.settingsEnabledAlone_blocksRunningAndInputReady`
215. `com.match3vision.analyzer.input.StartupReadinessGateTest.autoPlayController_allowsStartWhenA11yConnected`
216. `com.match3vision.analyzer.input.TouchCoordinateMapperAuditTest.letterboxedRoi_centersStayInsideBoard`
217. `com.match3vision.analyzer.input.TouchCoordinateMapperAuditTest.nonMonotonicGrid_throws`
218. `com.match3vision.analyzer.input.TouchCoordinateMapperAuditTest.noHardcodedPvpBoardPixels`
219. `com.match3vision.analyzer.input.TouchCoordinateMapperAuditTest.cellCenters_matchEvenSplitRoi`
220. `com.match3vision.analyzer.learning.LearningInfraTest.collect_export_noOnlineLearning`
221. `com.match3vision.analyzer.moves.MoveAnalysisEngineTest.special_create_from_length4`
222. `com.match3vision.analyzer.moves.MoveAnalysisEngineTest.simple_3_match_clearsAtLeastThree`
223. `com.match3vision.analyzer.moves.MoveAnalysisEngineTest.pass_gate_exposes_v1_fields`
224. `com.match3vision.analyzer.moves.MoveAnalysisEngineTest.multi_simultaneous_matches_detected`
225. `com.match3vision.analyzer.moves.MoveAnalysisEngineTest.top5_ordering_by_ev_descending`
226. `com.match3vision.analyzer.moves.MoveAnalysisEngineTest.four_match_scoresHigherSizeBonus_andMayCreateSpecial`
227. `com.match3vision.analyzer.moves.MoveAnalysisEngineTest.unknown_or_hold_gate_blocks_with_no_moves`
228. `com.match3vision.analyzer.moves.MoveAnalysisEngineTest.five_match_createsLightningProxy_andExtraMove`
229. `com.match3vision.analyzer.moves.MoveAnalysisEngineTest.no_valid_move_returnsEmptyTop5`
230. `com.match3vision.analyzer.moves.MoveEngineTest.simulator_clears`
231. `com.match3vision.analyzer.moves.MoveEngineTest.generator_excludesUnknown`
232. `com.match3vision.analyzer.moves.MoveEngineTest.generator_reproducible_and_findsMove`
233. `com.match3vision.analyzer.moves.MoveEngineTest.future_and_validator`
234. `com.match3vision.analyzer.opponent.OpponentModelTest.threat_unknownWithoutHud`
235. `com.match3vision.analyzer.opponent.OpponentModelTest.moveDetector_changeUnknownActor`
236. `com.match3vision.analyzer.opponent.OpponentModelTest.response_unknownPrior`
237. `com.match3vision.analyzer.opponent.OpponentModelTest.tracker_defaultUnknown`
238. `com.match3vision.analyzer.orchestration.AnalysisOrchestratorTest.holdVision_blocksMoveAnalysis`
239. `com.match3vision.analyzer.orchestration.AnalysisOrchestratorTest.passVision_optionalMoveAnalysis_runsReadOnly`
240. `com.match3vision.analyzer.overlay.DiagnosticsStopDisplayTest.explicitStop_winsWhenNotClearing`
241. `com.match3vision.analyzer.overlay.DiagnosticsStopDisplayTest.running_dropsStaleAccessibilityDisconnected`
242. `com.match3vision.analyzer.overlay.DiagnosticsStopDisplayTest.paused_keepsPreviousStopReason`
243. `com.match3vision.analyzer.overlay.DiagnosticsStopDisplayTest.autoPlayTrace_clearLastStop_doesNotWipeRing`
244. `com.match3vision.analyzer.overlay.DiagnosticsStopDisplayTest.successfulStart_clearsLastStopReason`
245. `com.match3vision.analyzer.overlay.DiagnosticsStopDisplayTest.clear_forcesNullEvenWhenTraceHasStop`
246. `com.match3vision.analyzer.overlay.LivePipelineStatusTest.firstBlock_verifyFailed`
247. `com.match3vision.analyzer.overlay.LivePipelineStatusTest.firstBlock_nullWhenClearPath`
248. `com.match3vision.analyzer.overlay.LivePipelineStatusTest.firstBlock_idleBeforeStart`
249. `com.match3vision.analyzer.overlay.LivePipelineStatusTest.firstBlock_a11yBeforeVision`
250. `com.match3vision.analyzer.overlay.LivePipelineStatusTest.gates_neverLoosened`
251. `com.match3vision.analyzer.overlay.LivePipelineStatusTest.firstBlock_visionHoldBeforeMove`
252. `com.match3vision.analyzer.overlay.LivePipelineStatusTest.bubbleLines_containMandatoryP0Fields`
253. `com.match3vision.analyzer.overlay.LivePipelineStatusTest.firstBlock_gridGateNeverLoosened`
254. `com.match3vision.analyzer.recording.RecordersTest.frameAndDecisionExport`
255. `com.match3vision.analyzer.rules.RuleEngineTest.swapValidator_rejectsUnknown`
256. `com.match3vision.analyzer.rules.RuleEngineTest.combo_lightning`
257. `com.match3vision.analyzer.rules.RuleEngineTest.gravity_and_cascade`
258. `com.match3vision.analyzer.rules.RuleEngineTest.matchDetector_findsHorizontal`
259. `com.match3vision.analyzer.rules.RuleEngineTest.refill_unknownWithoutProvider`
260. `com.match3vision.analyzer.rules.RuleEngineTest.specialCreator_len4_and_5`
261. `com.match3vision.analyzer.safety.SafetyGateTest.emergencyStop_blocks`
262. `com.match3vision.analyzer.safety.SafetyGateTest.visionHold_blocks`
263. `com.match3vision.analyzer.safety.SafetyGateTest.duplicateFrame_blocks`
264. `com.match3vision.analyzer.search.BeamSearchTest.search_finite_respectsDepth`
265. `com.match3vision.analyzer.search.BeamSearchTest.search_timeBudget_returns`
266. `com.match3vision.analyzer.ui.AnalyzerViewModelStartupTest.readinessGate_settingsFlagDoesNotEqualConnected`
267. `com.match3vision.analyzer.ui.AnalyzerViewModelStartupTest.applicationOnlyConstructor_existsForAndroidViewModelFactory`
268. `com.match3vision.analyzer.ui.AnalyzerViewModelStartupTest.autoPlayController_defaultsIdleAndDoesNotRunWithoutBubbleStart`
269. `com.match3vision.analyzer.ui.AnalyzerViewModelStartupTest.inputEnableSwitch_defaultsDisabled`
270. `com.match3vision.analyzer.vision.BoardConfidenceTest.evenSplitManyUnknowns_gateHold_despiteAnyBoardConf`
271. `com.match3vision.analyzer.vision.BoardConfidenceTest.idealizedCleanBoard_boardConfidencePassEligible`
272. `com.match3vision.analyzer.vision.BoardConfidenceTest.degradedNoise_mayHold_documentsScores`
273. `com.match3vision.analyzer.vision.BoardFinderRobustnessTest.jpegLikeQuantization_documentsConfidence`
274. `com.match3vision.analyzer.vision.BoardFinderRobustnessTest.cleanFixture_gridConfidenceAtLeastMinGate`
275. `com.match3vision.analyzer.vision.BoardFinderRobustnessTest.mildNoise_onBoard_documentsConfidence`
276. `com.match3vision.analyzer.vision.BoardFinderRobustnessTest.mildShear_documentsConfidenceOrFallback`
277. `com.match3vision.analyzer.vision.BoardFinderRobustnessTest.multiPerturbationMatrix_documentsGridBoardGate`
278. `com.match3vision.analyzer.vision.BoardFinderRobustnessTest.relVarToConfidenceFormula_documents1_5fCalibration`
279. `com.match3vision.analyzer.vision.BoardFinderRobustnessTest.letterboxOffsetChange_keepsValidGrid`
280. `com.match3vision.analyzer.vision.BoardFinderRobustnessTest.gutterJitter_1to2px_stillProjectionOrFallback`
281. `com.match3vision.analyzer.vision.BoardFinderRobustnessTest.scaleBoardSlightly_documentsConfidence`
282. `com.match3vision.analyzer.vision.BoardFinderTest.banner_doesNotShiftGrid`
283. `com.match3vision.analyzer.vision.BoardFinderTest.grid_is7x7_with8x8Boundaries`
284. `com.match3vision.analyzer.vision.BoardFinderTest.projection_isPrimary_whenGuttersPresent`
285. `com.match3vision.analyzer.vision.BoardFinderTest.tallPortrait_snapsSquarePlayfieldBelowDarkSeparator`
286. `com.match3vision.analyzer.vision.BoardFinderTest.letterbox_doesNotShiftCoordinatesIncorrectly`
287. `com.match3vision.analyzer.vision.BoardFinderTest.projectionPeaks_repickOffsetOutlierFarFalsePeak`
288. `com.match3vision.analyzer.vision.BoardFinderTest.projectionPeaks_softOutlierRepickMildOffset`
289. `com.match3vision.analyzer.vision.BoardFinderTest.invalidProjection_fallsBackToEvenSplit`
290. `com.match3vision.analyzer.vision.BoardFinderTest.projectionPeaks_cleanPeriodicGutters_stayOnIdeals`
291. `com.match3vision.analyzer.vision.BoardFinderTest.projectionPeaks_repickEnergyOutlierToolbarSpike`
292. `com.match3vision.analyzer.vision.ColorDetectorTest.mixedTwoHues_documentsUncertainty`
293. `com.match3vision.analyzer.vision.ColorDetectorTest.shadedBlue_stillDetectsB`
294. `com.match3vision.analyzer.vision.ColorDetectorTest.centerWeighted_ignoresBluePlayfieldBleed_aroundRedGem`
295. `com.match3vision.analyzer.vision.ColorDetectorTest.hueBucketBoundaries_documentCurrentMapping`
296. `com.match3vision.analyzer.vision.ColorDetectorTest.solidRed_detectsR`
297. `com.match3vision.analyzer.vision.ColorDetectorTest.solidPurple_detectsP`
298. `com.match3vision.analyzer.vision.ColorDetectorTest.thresholds_unchanged_byVisionStab`
299. `com.match3vision.analyzer.vision.ColorDetectorTest.brightnessBoostedGreen_stillG`
300. `com.match3vision.analyzer.vision.ColorDetectorTest.solidOrange_detectsO`
301. `com.match3vision.analyzer.vision.ColorDetectorTest.solidYellow_detectsY`
302. `com.match3vision.analyzer.vision.ColorDetectorTest.solidBlue_detectsB`
303. `com.match3vision.analyzer.vision.ColorDetectorTest.solidGreen_detectsG`
304. `com.match3vision.analyzer.vision.ColorDetectorTest.jpegQuantizedOrange_stillO`
305. `com.match3vision.analyzer.vision.ColorDetectorTest.antiAliasedEdge_redOnDark_stillR`
306. `com.match3vision.analyzer.vision.ColorDetectorTest.lowSaturationGray_isUnknown`
307. `com.match3vision.analyzer.vision.ColorDetectorTest.centerWeighted_medianHue_orangeNotRed_withSpecular`
308. `com.match3vision.analyzer.vision.ColorShapeReconcilerTest.bothUnknown_staysUnknown`
309. `com.match3vision.analyzer.vision.ColorShapeReconcilerTest.lowShapeConfDisagreement_trustsColorExpectedShape`
310. `com.match3vision.analyzer.vision.ColorShapeReconcilerTest.orangeTriangle_isAcceptableNotUnknown`
311. `com.match3vision.analyzer.vision.ColorShapeReconcilerTest.highConfNearTieContradiction_yieldsUnknown`
312. `com.match3vision.analyzer.vision.ColorShapeReconcilerTest.shapeUnknown_colorKnown_fillsExpectedShape`
313. `com.match3vision.analyzer.vision.ColorShapeReconcilerTest.bothBelowHigh_disagree_stillColorPreferred`
314. `com.match3vision.analyzer.vision.ColorShapeReconcilerTest.consistentPairs_pass`
315. `com.match3vision.analyzer.vision.ColorShapeReconcilerTest.colorUnknown_shapeKnown_fillsExpectedColor`
316. `com.match3vision.analyzer.vision.ColorShapeReconcilerTest.highConfContradiction_specialCleared`
317. `com.match3vision.analyzer.vision.ColorShapeReconcilerTest.highConfDominantShape_trustsShapeExpectedColor`
318. `com.match3vision.analyzer.vision.ColorShapeReconcilerTest.highConfDominantColor_trustsColorExpectedShape`
319. `com.match3vision.analyzer.vision.ColorShapeReconcilerTest.contradiction_doesNotReduceUnknownCount_pipelineLevel`
320. `com.match3vision.analyzer.vision.EvenSplitFailClosedTest.boardFinderFallback_diagMarksEvenSplit`
321. `com.match3vision.analyzer.vision.EvenSplitFailClosedTest.evenSplit072_cannotPassValidator`
322. `com.match3vision.analyzer.vision.GridConfidenceCalibrationTest.noArtificialInflation_beyondFormula`
323. `com.match3vision.analyzer.vision.GridConfidenceCalibrationTest.calibrationTable_documentsRelVarToGate`
324. `com.match3vision.analyzer.vision.GridGeometryTest.cellCenters_lieInsideBoardRoi`
325. `com.match3vision.analyzer.vision.GridGeometryTest.highSpacingVariance_failsSpacingOk`
326. `com.match3vision.analyzer.vision.GridGeometryTest.nonMonotonic_failsValidate`
327. `com.match3vision.analyzer.vision.GridGeometryTest.boundaries_areMonotonic`
328. `com.match3vision.analyzer.vision.GridGeometryTest.evenSplit_has7x7CellsAnd8Boundaries`
329. `com.match3vision.analyzer.vision.GridGeometryTest.cellBox_matchesBoundaries`
330. `com.match3vision.analyzer.vision.LiveBoardFrameRegressionTest.boardConf_allUnknown_penaltyClampsToZero`
331. `com.match3vision.analyzer.vision.LiveBoardFrameRegressionTest.realFrame_statusBarContentRoi_stillPass`
332. `com.match3vision.analyzer.vision.LiveBoardFrameRegressionTest.grayUiOverlayCoveringBoard_suspectFlag_boardConfNearZero`
333. `com.match3vision.analyzer.vision.LiveBoardFrameRegressionTest.realFrame_clearBoard_unknownAtMostOne_includingMushroom`
334. `com.match3vision.analyzer.vision.LiveBoardFrameRegressionTest.realFrame_misSnapRoi_stillUnknownAtMostOne`
335. `com.match3vision.analyzer.vision.LiveBoardFrameRegressionTest.realFrame_leftMisSnapLikeLive_softRecoverClearsMinGrid`
336. `com.match3vision.analyzer.vision.LiveBoardFrameRegressionTest.integrity_gatesUnchanged`
337. `com.match3vision.analyzer.vision.LiveBoardFrameRegressionTest.realFrame_statusBarAndBubbleCoverTopUi_stillPass`
338. `com.match3vision.analyzer.vision.OcclusionAndReconcileTest.colorShapeContradiction_highConfidence_yieldsUnknown`
339. `com.match3vision.analyzer.vision.OcclusionAndReconcileTest.matchingColorShape_notUnknown`
340. `com.match3vision.analyzer.vision.OcclusionAndReconcileTest.blueUiOverlay_60percent_isOccluded`
341. `com.match3vision.analyzer.vision.OcclusionAndReconcileTest.solidRed_stillClear_afterPartialFix`
342. `com.match3vision.analyzer.vision.OcclusionAndReconcileTest.clearColoredCell_notOccluded`
343. `com.match3vision.analyzer.vision.OcclusionAndReconcileTest.occludedDarkCell_isDetected`
344. `com.match3vision.analyzer.vision.OcclusionAndReconcileTest.warmBannerFull_stillOccluded`
345. `com.match3vision.analyzer.vision.OcclusionAndReconcileTest.solidOrangeTile_notOccludedAsBanner`
346. `com.match3vision.analyzer.vision.OcclusionAndReconcileTest.solidRedTile_notOccludedAsBanner`
347. `com.match3vision.analyzer.vision.OcclusionAndReconcileTest.partialDarkOverlay_60percent_isOccluded`
348. `com.match3vision.analyzer.vision.OcclusionAndReconcileTest.whiteUiOverlay_60percent_isOccluded`
349. `com.match3vision.analyzer.vision.OcclusionAndReconcileTest.grayUiOverlay_55percent_isOccluded`
350. `com.match3vision.analyzer.vision.OcclusionAndReconcileTest.special_explicitLowConfidence_mapsToNone`
351. `com.match3vision.analyzer.vision.OcclusionAndReconcileTest.partialDarkOverlay_50percent_isOccluded`
352. `com.match3vision.analyzer.vision.OcclusionAndReconcileTest.texturedBanner_partial_isOccluded`
353. `com.match3vision.analyzer.vision.OcclusionAndReconcileTest.solidOrange_stillClear_afterPartialFix`
354. `com.match3vision.analyzer.vision.OcclusionAndReconcileTest.sparseTriangleOnDarkBoard_notPartialDark`
355. `com.match3vision.analyzer.vision.OcclusionAndReconcileTest.solidRedTileWithDarkBorder_notOccludedAsBanner`
356. `com.match3vision.analyzer.vision.OcclusionAndReconcileTest.colorOnly_fillsExpectedShape`
357. `com.match3vision.analyzer.vision.OcclusionAndReconcileTest.bannerCell_isOccluded`
358. `com.match3vision.analyzer.vision.OcclusionAndReconcileTest.special_belowThreshold_becomesNone`
359. `com.match3vision.analyzer.vision.RealFrameExportTest.realFrame_pvpBoard_androidExport_matchesGoldenAndSchema`
360. `com.match3vision.analyzer.vision.RealFrameExportTest.goldenClasspath_isAndroidExport_notPythonReady`
361. `com.match3vision.analyzer.vision.RealFrameVisionTest.realFrame_pvpBoard_softHumanGtCompare_whenPresent`
362. `com.match3vision.analyzer.vision.RealFrameVisionTest.realFrame_harness_reportsMissingClearly`
363. `com.match3vision.analyzer.vision.RealFrameVisionTest.realFrame_secondaryFrames_softDiagnostics_whenPresent`
364. `com.match3vision.analyzer.vision.RealFrameVisionTest.realFrame_pvpBoard_pipelineDiagnostics_whenPresent`
365. `com.match3vision.analyzer.vision.RealisticSyntheticVisionTest.realisticSynthetic_groundTruth_fromConstructionNotDetector`
366. `com.match3vision.analyzer.vision.RealisticSyntheticVisionTest.realisticSynthetic_failurePath_dumpsUnknownMap`
367. `com.match3vision.analyzer.vision.RealisticSyntheticVisionTest.realisticSynthetic_alwaysRuns_pipelineDiagnostics`
368. `com.match3vision.analyzer.vision.RealisticSyntheticVisionTest.realisticSynthetic_distinctFromCleanLetterboxedBoard`
369. `com.match3vision.analyzer.vision.ShapeDetectorRealisticTest.carvedTriangle_scaledSlightly`
370. `com.match3vision.analyzer.vision.ShapeDetectorRealisticTest.carvedSquare_offsetBlurred`
371. `com.match3vision.analyzer.vision.ShapeDetectorRealisticTest.confidenceNeverExceedsOne_onOffsetSquare`
372. `com.match3vision.analyzer.vision.ShapeDetectorRealisticTest.carvedDiamond_shaded`
373. `com.match3vision.analyzer.vision.ShapeDetectorRealisticTest.solidUnknown_withNoise_staysUnknown`
374. `com.match3vision.analyzer.vision.ShapeDetectorRealisticTest.emptyDark_isUnknown`
375. `com.match3vision.analyzer.vision.ShapeDetectorRealisticTest.carvedCircle_noisyAntiAliased_stillCircleOrUnknownSoft`
376. `com.match3vision.analyzer.vision.ShapeDetectorTest.solidWithDarkGutter_isUnknownNotCircle`
377. `com.match3vision.analyzer.vision.ShapeDetectorTest.solidRedFill_isUnknownNotCircle`
378. `com.match3vision.analyzer.vision.ShapeDetectorTest.carvedCircle_isDetectedAsCircle`
379. `com.match3vision.analyzer.vision.ShapeDetectorTest.solidBlueFill_isUnknownNotCircle`
380. `com.match3vision.analyzer.vision.SoftColorDiagnosticsTest.unknownReason_taxonomy_coversRequiredCategories`
381. `com.match3vision.analyzer.vision.SoftColorDiagnosticsTest.realFrame_softColorMismatch_report_andConfusionMatrix`
382. `com.match3vision.analyzer.vision.SpecialDetectorTest.partialBrightOverlay_staysConservative`
383. `com.match3vision.analyzer.vision.SpecialDetectorTest.arrowLobes_documentsConservative`
384. `com.match3vision.analyzer.vision.SpecialDetectorTest.thresholdConstant_unchanged`
385. `com.match3vision.analyzer.vision.SpecialDetectorTest.normalSolid_isNone`
386. `com.match3vision.analyzer.vision.SpecialDetectorTest.lightningStreak_documentsConservative`
387. `com.match3vision.analyzer.vision.SpecialDetectorTest.uncertainNoise_staysNone`
388. `com.match3vision.analyzer.vision.SpecialDetectorTest.bombLikeDarkCore_documentsConservative`
389. `com.match3vision.analyzer.vision.VisionFrameAnalyzerTest.analyzePixels_producesDebugSummaryAndJson`
390. `com.match3vision.analyzer.vision.VisionFrameAnalyzerTest.holdGate_exposedInDebugSummary`
391. `com.match3vision.analyzer.vision.VisionFrameAnalyzerTest.analyzePixels_neverInventedFrame_usesProvidedBuffer`
392. `com.match3vision.analyzer.vision.VisionPipelineTest.cleanBoard_pipelineProduces7x7AndPreferPassOrHoldMessage`
393. `com.match3vision.analyzer.vision.VisionPipelineTest.manyUnknowns_gateHold`
394. `com.match3vision.analyzer.vision.VisionPipelineTest.occludedCell_inPipeline_isUnknown`
395. `com.match3vision.analyzer.vision.VisionPipelineTest.fallbackEvenSplit_whenNoGutters`
396. `com.match3vision.analyzer.vision.VisionValidatorTest.integrity_unknownCountTwo_holdsEvenIfConfidencesPerfect`
397. `com.match3vision.analyzer.vision.VisionValidatorTest.unknownGreaterThanOne_gateHold`
398. `com.match3vision.analyzer.vision.VisionValidatorTest.integrity_boardJustBelowGate_holds`
399. `com.match3vision.analyzer.vision.VisionValidatorTest.highUnknown_reportedBeforeBoardConf`
400. `com.match3vision.analyzer.vision.VisionValidatorTest.integrity_exactGateBoundaries_pass`
401. `com.match3vision.analyzer.vision.VisionValidatorTest.lowGridConfidence_gateHold`
402. `com.match3vision.analyzer.vision.VisionValidatorTest.threeIndependentHoldModes_noBypass`
403. `com.match3vision.analyzer.vision.VisionValidatorTest.integrity_gridJustBelowGate_holds`
404. `com.match3vision.analyzer.vision.VisionValidatorTest.integrity_thresholdConstantsUnchanged`
405. `com.match3vision.analyzer.vision.VisionValidatorTest.cleanBoard_gatePass`
406. `com.match3vision.analyzer.vision.VisionValidatorTest.lowBoardConfidence_gateHold`
407. `com.match3vision.analyzer.vision.VisionValidatorTest.oneUnknown_stillPass`
408. `com.match3vision.analyzer.vision.VisionValidatorTest.integrity_noForcedPassWhenAnyGateFails`
409. `com.match3vision.analyzer.vision.parity.VisionParityComparatorTest.exporter_emitsFortyNineCells`
410. `com.match3vision.analyzer.vision.parity.VisionParityComparatorTest.exporter_containsRequiredSchemaFields`
411. `com.match3vision.analyzer.vision.parity.VisionParityComparatorTest.pendingReference_returnsReferencePending`
412. `com.match3vision.analyzer.vision.parity.VisionParityComparatorTest.gateMismatch_reportedSeparately`
413. `com.match3vision.analyzer.vision.parity.VisionParityComparatorTest.colorMismatch_lowersOnlyColorMetric`
414. `com.match3vision.analyzer.vision.parity.VisionParityComparatorTest.perfectSyntheticMatch_separateMetricsAllAgree`
415. `com.match3vision.analyzer.vision.parity.VisionParityReferenceScaffoldTest.classpathReference_isReferencePending_notReady`

