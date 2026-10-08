# Final report — 0.24.3-production-path-audit

This document is the evidence an auditor can check without the private repo.
The APK identity below is the GitHub Actions artifact built from commit
`eadf97cc3ca185defbc8f44ce19dc80bf1027582`. This markdown file is a later commit
on the same branch and is not inside that APK.

**Date:** 2026-10-08  
**Branch:** `cursor/production-path-audit-0.24.3-7ea6`  
**Diff base:** `22eca0a01725518ee504ce13b35c9610aa34bef3` (`origin/main`, merge of 0.24.2)  
**Code commit (APK):** `eadf97cc3ca185defbc8f44ce19dc80bf1027582`  
**Previous code commit on this branch:** `a3f642d5f4bdbae9118158c4beff62675960a5f1` (also green; superseded)  
**Version name:** `0.24.3-production-path-audit`  
**versionCode:** `15` (was 14)  
**Package:** `com.match3vision.analyzer`  
**PR:** https://github.com/kovdacs/MatchMastersBot-Private/pull/2 (open, not merged)

## Identity and traceability

| Item | Value |
|---|---|
| Push CI | run **37773265584**, SUCCESS, built SHA `eadf97cc3ca185defbc8f44ce19dc80bf1027582` |
| Push CI URL | https://github.com/kovdacs/MatchMastersBot-Private/actions/runs/37773265584 |
| Push log | `388` lines ending in ` PASSED`, `testDebugUnitTest` BUILD SUCCESSFUL, `assembleDebug` BUILD SUCCESSFUL |
| Push APK filename | `Match3Analyzer-0.24.3-production-path-audit-eadf97c.apk` |
| Push APK size | **25071083** bytes (artifact zip is 8770658 bytes; the hash below is the APK inside the zip) |
| Push APK SHA-256 | `6f5b7577c610872abbee61b89658fa4c051075bb0592ea313066911041871e3c` |
| Push artifact id | 11548449616 |
| Push artifact URL | https://github.com/kovdacs/MatchMastersBot-Private/actions/runs/37773265584/artifacts/11548449616 |
| `aapt dump badging` | `versionCode='15' versionName='0.24.3-production-path-audit' compileSdkVersion='35'` |
| PR CI | run **37773270896**, SUCCESS, PR head `eadf97cc3ca185defbc8f44ce19dc80bf1027582` |
| PR CI URL | https://github.com/kovdacs/MatchMastersBot-Private/actions/runs/37773270896 |
| PR checkout | merge commit `01a59ea85cdffdeea6e35748dfd5d1a7cffe69e2` (`eadf97c` into `22eca0a`) |
| PR log | `388` ` PASSED`, both Gradle tasks BUILD SUCCESSFUL |
| PR APK filename | `Match3Analyzer-0.24.3-production-path-audit-01a59ea.apk` |
| PR APK size | **25071083** bytes (zip 8770663) |
| PR APK SHA-256 | `a523228045752c8c28c3869617d0ac991baf061ee1f9ab83f998fd9ada052627` |
| PR artifact id | 11549091317 |
| PR artifact URL | https://github.com/kovdacs/MatchMastersBot-Private/actions/runs/37773270896/artifacts/11549091317 |

The two APKs are the same size and the same `versionCode` / `versionName`. Their SHA-256 values differ. Debug APK zip timestamps and signing metadata differ between the branch-tip build and the pull-request merge build. Do not treat either hash as a phone-touch proof.

Local `testDebugUnitTest` on this tree before the push: **388 passed, 0 failed, 0 skipped**. Local `assembleDebug`: BUILD SUCCESSFUL. A local debug APK hash is not the CI hash and is not cited.

**Tests before:** 342 `@Test` methods on `22eca0a` / 0.24.2 (that round's report and the suite count agreed).  
**Tests after:** 388. Delta **+46, −0**. No `@Ignore` under `app/src/test`. No vision-threshold edit. No MoveAnalysis retune.

### Added tests (46)

`ProductionPathIntegrationTest` (21):

1. `thresholds_unchanged`
2. `callChain_namesTheProductionStepsInOrder`
3. `defaultEngine_isUninstalled_notTheTestRecorder`
4. `recordingShellAndUninstalled_cannotBeInstalledAsProduction`
5. `productionInstall_isAccessibilityExecutor_notRecording`
6. `productionExecutor_refusesNullContext_withoutConsultingService`
7. `productionExecutor_refusesSimulatedContext_withoutConsultingService`
8. `productionExecutor_nullService_doesNotDispatchEvenIfContextSaysConnected`
9. `productionCycleContext_copiesMeasurements_andIsNeverSimulated`
10. `captureConsent_api34RequestsEntireDisplay_olderSdksUseChooser`
11. `captureSize_prefersMaximumWindowMetrics_sameSpaceAsTouchTest`
12. `coordinate_identityWhenSizesMatch_cellCentersUnchanged_mismatchRefused`
13. `coordinate_sizeMismatch_blocksBeforeDispatch_simulationProbe`
14. `forbidden_missingFrame_noDispatch_simulationProbe`
15. `forbidden_staleFrame_noDispatch_simulationProbe`
16. `forbidden_visionHold_noDispatch_simulationProbe`
17. `forbidden_inputDisabled_noDispatch_simulationProbe`
18. `forbidden_a11yDisconnected_noDispatch_simulationProbe`
19. `forbidden_offScreenCoordinate_noDispatch_simulationProbe`
20. `dispatchSuccess_isNotVerifySuccess_staleFrameCannotVerify_simulationProbe`
21. `verify_onlyNewFreshChangedBoard_canSucceed_andStaysSimulation`

`ProductionDispatchGuardTest` (25):

1. `guard_staleFrame_realDispatcher_doesNotCallDispatchGesture`
2. `guard_missingFrame_realDispatcher_doesNotCallDispatchGesture`
3. `guard_sequenceRejected_realDispatcher_doesNotCallDispatchGesture`
4. `guard_visionHold_realDispatcher_doesNotCallDispatchGesture`
5. `guard_inputDisabled_realDispatcher_doesNotCallDispatchGesture`
6. `guard_a11yDisconnected_realDispatcher_doesNotCallDispatchGesture`
7. `guard_a11yDroppedAfterPlan_realDispatcher_rereadsChannel`
8. `guard_captureOff_realDispatcher_doesNotCallDispatchGesture`
9. `guard_offScreen_realDispatcher_doesNotCallDispatchGesture`
10. `guard_frameScreenMismatch_realDispatcher_doesNotCallDispatchGesture`
11. `guard_rotationSwapsAxes_realDispatcher_doesNotCallDispatchGesture`
12. `guard_frameAgedDuringAnalysis_realDispatcher_remeasuresBeforeDispatchGesture`
13. `guard_simulatedContext_realDispatcher_doesNotCallDispatchGesture`
14. `guard_unknownScreenBounds_realDispatcher_doesNotCallDispatchGesture`
15. `happyPath_realDispatcher_callsDispatchGestureOnce_andIsNotVerifySuccess`
16. `callback_onCancelled_isFailed_notVerifySuccess`
17. `callback_neverArrives_isTimeout_notVerifySuccess`
18. `callback_onCompleted_staysVerifyPending`
19. `frameClock_usesMonotonicAge_threshold3000_backwardsIsStale`
20. `coordinate_independentCenters_nonzeroRoi_noDensity_rotationRefused`
21. `verify_frameNotLaterThanDispatch_cannotSucceed`
22. `verify_noNewFrame_cannotSucceed`
23. `verify_identicalBoard_cannotSucceed`
24. `bubble_showsHoldReasonAndConfidencesWhenVisionDeclines`
25. `engine_staleContext_doesNotReachRealDispatchGesture`

## Production call chain (file:line)

Auto-play, after the user presses INDÍTÁS and the accessibility service is connected:

1. `MainActivity.beginAutoPlaySetup` — `MainActivity.kt:168`. Projection consent is `launchProjectionPermission` — `MainActivity.kt:285`.
2. On SDK ≥ 34 the consent intent is the entire default display: `MediaProjectionConfig.createConfigForDefaultDisplay()` — `MainActivity.kt:294`. Older SDKs use `createScreenCaptureIntent()` with no config — `MainActivity.kt:297`. Selector: `CaptureConsent.modeForSdk` — `CaptureConsent.kt:21`.
3. `ScreenCaptureManager.start` — `ScreenCaptureManager.kt:66`. Pixel size is `capturePixelSize()` — `ScreenCaptureManager.kt:74` and `:234`. Width or height ≤ 0 stops the projection and does not create a virtual display — `ScreenCaptureManager.kt:78-83`.
4. Each image becomes a `CaptureFrame` with wall `timestampMs` and monotonic `elapsedRealtimeMs` — `ScreenCaptureManager.kt:202-210`. Age prefers the monotonic clock — `CaptureFrame.kt:46-54`.
5. `FloatingBubbleService.ensureLoopRunning` — `FloatingBubbleService.kt:422`. The loop reads `MatchMastersAccessibilityService.isConnected()` — `:439`. A null capture manager pauses `CAPTURE: OFF` — `:454-472`. `isCapturing == false` pauses `CAPTURE: OFF` — `:500-521`.
6. Vision: `analyzeFrame` — `FloatingBubbleService.kt:785` → `VisionFrameAnalyzer.analyzePixels`. PASS/HOLD is `VisionResult.validation` against `VisionThresholds` (`VisionModels.kt:312-315`): grid ≥ 0.98, board ≥ 0.95, unknown ≤ 1. Unchanged.
7. Context: `ProductionCycleContext.fromLoopObservation` — `FloatingBubbleService.kt:560-570`, factory `ProductionPath.kt:108-138`. `simulated` is hard-coded `false` (`ProductionPath.kt:136`). `screenWidth/Height` are copies of the frame size (`ProductionPath.kt:131-132`). `capturedElapsedMs` is `useFrame.elapsedRealtimeMs` (`FloatingBubbleService.kt:569`).
8. `AutoPlayController.runCycleIfActive` — `AutoPlayController.kt:164`. Not RUNNING returns null. Disconnected a11y pauses — `:169`. Capture off pauses — `:180`. Input switch off returns null — `:191`.
9. `InputLoopController.runAnalyzeAndMaybeInput` — `InputLoopController.kt:53`. Pre-decision block (capture, frame, sequence, age > 3000) — `:246-255`. Vision HOLD returns before MoveAnalysis — `:92-104`. Otherwise `MoveAnalysisEngine.analyze` — `:109`.
10. `AutomaticInputEngine.tryExecute` — `AutomaticInputEngine.kt:93`. Production executor refuses null or simulated context — `:100-106`. Gesture from `TouchCoordinateMapper.toGesture` — `:126`. Frame/screen policy — `:135-146`. `CoordinateBounds` — `:147-153`. Then `dispatchChecked` — `:182-189`.
11. `AccessibilityGestureExecutor.dispatchChecked` — `AccessibilityGestureExecutor.kt:46-58`. Re-reads `canDispatchGestures()` — `:48`. Re-runs `DispatchRecheck.evaluate` with `nowElapsedMs()` — `:49-52`. Failure returns before `dispatch` — `:53-56`.
12. `dispatch` — `AccessibilityGestureExecutor.kt:61-69` → `MatchMastersAccessibilityService.dispatchGesture` — `MatchMastersAccessibilityService.kt:110`. Framework call is `super.dispatchGesture` — `:168`. Callback mapping is `GestureCallbackPolicy.decide` — `:194` and `:214`.
13. After `Executed`, the bubble stores wall and monotonic completion — `FloatingBubbleService.kt:644-646`, waits for a new frame, then `completeFeedback` with those times — `:705-716`. `InputLoopController.completeFeedback` — `InputLoopController.kt:266`. SUCCESS only from `VerificationPolicy.decide` when the frame is new, fresh, strictly later than dispatch, and the board hash changed.

Session wiring that installs the executor: `AutoPlaySession` — `AutoPlaySession.kt:134-136` calls `ProductionInstall.accessibilityExecutor()` — `ProductionPath.kt:93-99`, which calls `ProductionPath.requireProductionExecutor` — `ProductionPath.kt:66-70`.

### Where a substitute can enter, and where it cannot

| Slot | Production wiring | What a test can substitute |
|---|---|---|
| Executor on the live session | `ProductionInstall.accessibilityExecutor()` with the default provider `MatchMastersAccessibilityService.instanceOrNull()` | A test may pass a `() -> AccessibilityGestureChannel`. `requireProductionExecutor` still rejects `RecordingInputGestureExecutor`, `ShellInputGestureExecutor`, and `UninstalledGestureExecutor`. |
| Engine default if nobody installs | `UninstalledGestureExecutor` (`AutomaticInputEngine.kt:20`). `isReady()` is false. `dispatch` returns Failed. | Tests that want a recorder must pass `RecordingInputGestureExecutor` themselves. That object is not the production install. |
| Clock | `nowElapsedMs` defaults to `FrameClock.tryElapsed()` → `SystemClock.elapsedRealtime` (`FrameClock.kt:35-38`). On a JVM stub the catch returns 0, and age then uses the permit snapshot. | `guard_frameAgedDuringAnalysis_...` injects `nowElapsedMs`. Production code does not take a fake clock. |
| Dispatch channel | The service instance | `CountingChannel` implements `AccessibilityGestureChannel` and counts `dispatchGesture`. It replaces only that call. `AccessibilityGestureExecutor` and `DispatchRecheck` are the production classes. `super.dispatchGesture` is not executed in unit tests. |
| Readiness defaults | `evaluateGate` without a context still assumes a fresh connected frame. The production executor refuses `context == null` before that (`ProductionPath.kt:78-85`, `AutomaticInputEngine.kt:100-106`). | A recorder passed in by a test can still take the null-context path. The live session does not. |
| `simulated = true` | `ProductionCycleContext` writes `false`. `DispatchRecheck` rejects `simulated` (`DispatchRecheck.kt:77-78`). | JVM harnesses build their own `RuntimeCycleContext(simulated = true)`. |

`RecordingInputGestureExecutor` records a list and returns `Dispatched`. It never calls the OS (`InputGestureExecutor.kt:36-59`). `ShellInputGestureExecutor` is the adb `input swipe` path and is refused by `requireProductionExecutor`.

Two other production-typed call sites do **not** run the auto-play gate chain:

- `AutomaticTouchTest` (`AutomaticTouchTest.kt:16` and `:136`) is the bubble button TESZT ÉRINTÉS. It calls `executor.dispatch(gesture)` directly, with fixed screen-fraction coordinates and no vision. It does not call `dispatchChecked`.
- `AnalyzerViewModel` one-step smoke (`AnalyzerViewModel.kt:102-107`) uses `ProductionInstall.accessibilityExecutor()`, but `OneStepSmokeController` calls `tryExecute(vision, top)` with no `RuntimeCycleContext` (`OneStepSmokeController.kt:170`). The production executor then HOLDs (`productionReadinessRefusal`). That smoke button does not dispatch.

## Dispatch guards

Plan-time checks can go stale during vision analysis. `dispatchChecked` is the last check before the channel's `dispatchGesture`.

| Condition | Plan-time check | Re-checked immediately before `dispatchGesture` |
|---|---|---|
| Missing frame | `InputLoopController.preDecisionBlock` `InputLoopController.kt:248` | `DispatchRecheck.kt:81` on the permit. The bitmap is not re-read inside the service. |
| Frame sequence rejected | `InputLoopController.kt:249-250` | `DispatchRecheck.kt:82-83` |
| Stale frame (age > 3000) | `InputLoopController.kt:251-253` using `frame.ageMs()` | `DispatchRecheck.kt:72-86`. If `capturedElapsedMs > 0` and `nowElapsedMs > 0`, age is recomputed with `FrameClock.ageMs`. Otherwise the permit's `frameAgeMs` is used. |
| Vision HOLD | `InputLoopController.kt:92-104`; `DispatchPermit.from` re-applies the three thresholds (`DispatchRecheck.kt:38-41`) | `DispatchRecheck.kt:88` on `visionPass`. This is the permit flag, not a second bitmap. |
| Input disabled | `AutoPlayController.kt:191` and the engine gate | `DispatchRecheck.kt:89` (`inputEnabled` copied when the permit is built) |
| Accessibility disconnected | Bubble loop `FloatingBubbleService.kt:439`; `AutoPlayController.kt:169` | `dispatchChecked` re-reads `canDispatchGestures()` and ANDs it in (`AccessibilityGestureExecutor.kt:48-50`). This live read is real. |
| Capture off | Bubble `FloatingBubbleService.kt:454` and `:500`; `AutoPlayController.kt:180` | `DispatchRecheck.kt:80` on the permit flag. The capture manager is not re-queried inside `dispatchChecked`. |
| Off-screen or non-finite coordinate | `CoordinateBounds` in `tryExecute` (`AutomaticInputEngine.kt:147`) | `DispatchRecheck.kt:100` |
| Unknown screen bounds | same | `CoordinateBounds.check` via `DispatchRecheck.kt:100` (`screenWidth <= 0`) |
| Frame/screen size mismatch, including a 1080×2400 vs 2400×1080 rotation swap | `FrameScreenCoordinatePolicy` in `tryExecute` (`AutomaticInputEngine.kt:135`) | `DispatchRecheck.kt:91-98` |
| Simulated context | `productionReadinessRefusal` (`AutomaticInputEngine.kt:100`) | `DispatchRecheck.kt:77` |
| Null context | `productionReadinessRefusal` returns before a gesture is built | No permit is built, so `dispatchGesture` is not called |

Age equal to 3000 is still fresh (`>`). Missing or backwards monotonic samples return `3001` (`FrameClock.kt:23-28`).

### One test per guard through the real dispatcher

Each `guard_*` test builds `AccessibilityGestureExecutor(serviceProvider = { channel })` and calls `dispatchChecked`. `CountingChannel.dispatchGesture` increments. Every guard asserts `dispatchGestureCalls == 0` and a `Failed` reason containing `TOCTOU` and `dispatchGesture`.

`happyPath_realDispatcher_callsDispatchGestureOnce_andIsNotVerifySuccess` uses `ProductionInstall.accessibilityExecutor { channel }`, a passing vision result, and a measured non-simulated context, and asserts the channel is called **exactly once**. `VerificationPolicy.afterDispatch(true)` is `PENDING`, not `SUCCESS`. The "not called" assertions are not vacuous: deleting `if (!again.allow) return` in `dispatchChecked` (`AccessibilityGestureExecutor.kt:53`) makes `CountingChannel` increment and fails every `guard_*` test.

`engine_staleContext_doesNotReachRealDispatchGesture` goes through `AutomaticInputEngine.tryExecute` with the production executor and a 9000 ms age, and asserts the channel stays at 0.

The `forbidden_*` and `verify_*_simulationProbe` tests in `ProductionPathIntegrationTest` use `RecordingInputGestureExecutor`. They assert `ProductionPath.isSimulationExecutor`. They prove the controller blocks the recorder. They do not call `AccessibilityGestureChannel.dispatchGesture`. The matching `guard_*` tests are the ones that do.

### Callbacks

`MatchMastersAccessibilityService.dispatchGesture` calls `super.dispatchGesture` (`MatchMastersAccessibilityService.kt:168`) and then `GestureCallbackPolicy.decide` (`:214`).

| Callback | Policy | Dispatch result | Verify label | Test |
|---|---|---|---|---|
| `onCancelled` | `Kind.CANCELLED` (`GestureCallbackPolicy.kt:58-61`) | `Failed` | `FAILED` | `callback_onCancelled_isFailed_notVerifySuccess` |
| Latch times out, callback never arrives | `Kind.TIMED_OUT` (`GestureCallbackPolicy.kt:52-56`), timeout `GESTURE_CALLBACK_TIMEOUT_MS` = 3000 | `Failed` | `FAILED` | `callback_neverArrives_isTimeout_notVerifySuccess` |
| `onCompleted` | `Kind.COMPLETED` | `Dispatched` | `PENDING` | `callback_onCompleted_staysVerifyPending` |
| Scheduled on the main thread (cannot await; deadlock) | `Kind.SCHEDULED_ONLY` (`GestureCallbackPolicy.kt:46-50`) | `Dispatched` | `PENDING` | covered by `verifyLabel()` in `GestureCallbackPolicy.kt:30-32`; no separate named test |
| `dispatchGesture` returns false | `Kind.NOT_SCHEDULED` | `Failed` | `FAILED` | policy branch `GestureCallbackPolicy.kt:43-44`; service returns it at `MatchMastersAccessibilityService.kt:194` |

These tests call `GestureCallbackPolicy.decide`. They do not fire a framework `GestureResultCallback`. The service is the production caller of `decide` (`MatchMastersAccessibilityService.kt:214-220`).

A cancelled or failed dispatch STOPs. The bubble does not retry that gesture (`InputLoopController` maps dispatch failure to STOP). That is existing 0.24.2 behavior.

## Runtime state vs static values

| Field | Source |
|---|---|
| Autoplay mode | `AutoPlayController.mode`. Cold start is IDLE until INDÍTÁS. |
| Accessibility | `MatchMastersAccessibilityService.isConnected()` (live instance), not the settings flag. Re-read at dispatch via `canDispatchGestures()`. |
| Capture | `CaptureService.managerOrNull()` and `isCapturing`. |
| Frame time | Wall `CaptureFrame.timestampMs` is display-only. Ordering and age use `elapsedRealtimeMs`. |
| Frame age | `CaptureFrame.ageMs` → `FrameClock.ageMs`. Threshold `GestureFailSafe.MAX_FRAME_AGE_MS` = 3000 (`GestureFailSafe.kt:108`). |
| Vision | `VisionResult.validation`, `gridConfidence`, `boardConfidence`, `unknownCount`. |
| Move | `MoveAnalysisEngine.analyze` only after the pre-decision block and a vision PASS. |
| Dispatch | Executor result. `VerificationPolicy.afterDispatch(true)` is `PENDING` (`RuntimeDiagnostics.kt:83-84`). |
| Verify | `VerificationPolicy.decide` (`RuntimeDiagnostics.kt:90-99`). |

**Clock.** `FrameClock.NAME` is `SystemClock.elapsedRealtime` (`FrameClock.kt:20`). Test `frameClock_usesMonotonicAge_threshold3000_backwardsIsStale`: age of 400 ms from samples `(10000, 10400)` is 400; `isStale(3000)` is false; `isStale(3001)` is true; a backwards pair and a missing capture sample (`0`) are both stale.

**MediaProjection stops.** Three behaviors, all fail closed:

- Service instance gone (`managerOrNull() == null`): pause `CAPTURE: OFF (CaptureService stopped mid-run)` — `FloatingBubbleService.kt:454-472`.
- Projection stopped while a frame object still exists (`isCapturing == false`): pause `CAPTURE: OFF (projection stopped mid-run)` — `:500-521`.
- Capturing stays true but new images stop: `CaptureFrame.ageMs()` grows. Age > 3000 is a HOLD at `preDecisionBlock` and again inside `dispatchChecked` when monotonic timestamps exist. No gesture is built from that stale frame.

**State machine.** `dispatchGesture` accepted (`Dispatched`) or `onCompleted` never maps to `VERIFY SUCCESS`. `afterDispatch` returns `PENDING` or, on failure, `FAILED`. `completeFeedback` returns `FAILED` when the observation is not a new frame, not fresh, not strictly later than dispatch completion, or the board is unchanged (`InputLoopController.kt:271-303`). Only `VerificationPolicy.decide` with all four inputs true returns `SUCCESS`.

## Coordinate mapping (frame → screen)

There is no scale, density, rotation, status-bar, or cutout transform in the gesture path.

1. Virtual-display size: `CaptureDisplaySize.choose` prefers `maximumWindowMetrics` (same bounds TESZT ÉRINTÉS treats as physical pixels) and falls back to real display metrics (`CaptureDisplaySize.kt:22-45`, `ScreenCaptureManager.kt:234-261`). Density DPI is stored on the capture and passed to `createVirtualDisplay`. It is **not** multiplied into gesture coordinates.
2. The bitmap is that size. `LetterboxDetector` produces an optional `ContentRoi` (`ScreenCaptureManager.kt:195-199`).
3. `BoardFinder` fits a 7×7 grid in **frame pixels**. Projection confidence is `(1f - (relVarX + relVarY) * 1.5f).coerceIn(projectionMinConfidence, 0.99f)` with `projectionMinConfidence` default 0.85 (`BoardFinder.kt:686`). EVEN_SPLIT fallback confidence is **0.72** (`BoardFinder.kt:56`), which is below 0.98, so that fallback is always HOLD.
4. `TouchCoordinateMapper.toGesture` (`TouchCoordinateMapper.kt:13-23`) takes `GridGeometry.cellBox` centers: `centerX = (left + right) * 0.5f`, `centerY = (top + bottom) * 0.5f` (`VisionModels.kt:39-40`). EVEN_SPLIT boundaries are `left + i * width / 7` (`VisionModels.kt:154-161`). No integer rounding. Duration is the mapper's swipe duration (the independent test passes 120 ms).
5. `GestureSpec` floats are copied into `Path.moveTo` / `lineTo` (`MatchMastersAccessibilityService.kt:136-143`). Units are pixels, not dp.
6. On the live loop, `ProductionCycleContext` sets `screenWidth = frameWidth` and `screenHeight = frameHeight` (`ProductionPath.kt:128-134`). `FrameScreenCoordinatePolicy.assess` therefore sees an identity pair whenever the frame size is positive. A mismatch is refused (`ProductionPath.kt:192-196`), but the live loop does not measure a second screen size at dispatch time. The mismatch tests pass a permit whose sizes differ; they are not a path the bubble currently builds.
7. Identity is also allowed when a separate frame size was not supplied (`ProductionPath.kt:176-182`). That branch is device-dependent and is not the live loop (the live loop always passes the frame size).

### Independent expected values

ROI `LTRB(37, 511, 1043, 2018)`, move row 4 col 2 → row 4 col 3. Computed in double, without calling `cellBox`:

- `cellW = (1043 - 37) / 7.0 = 1006 / 7 = 143.7142857143`
- `cellH = (2018 - 511) / 7.0 = 1507 / 7 = 215.2857142857`
- `startX = 37 + (2 + 0.5) * cellW = 396.2857142857`
- `startY = 511 + (4 + 0.5) * cellH = 1479.7857142857`
- `endX = 37 + (3 + 0.5) * cellW = 540.0`
- `endY = startY`

`coordinate_independentCenters_nonzeroRoi_noDensity_rotationRefused` asserts the mapper output is within **0.05** of those doubles. The float implementation (`(left+right)*0.5f` on EVEN_SPLIT float boundaries) lands at about `(396.285706, 1479.785645)`, a delta under `0.00007`, inside the tolerance. The test also asserts:

- start is greater than the ROI origin (the letterbox offset is included, not stripped)
- `startX` is not an integer (no px rounding)
- `startX` is not within 1.0 of `396.2857142857 * 2.75` (density is not applied; that product is about 1089.79)
- `assess(1080, 2400, 2400, 1080)` is `REFUSED` and `deviceDependent`

`guard_rotationSwapsAxes_realDispatcher_doesNotCallDispatchGesture` and `guard_frameScreenMismatch_realDispatcher_doesNotCallDispatchGesture` assert the channel is not called for those sizes.

### Unproven without a device

- Whether capture-bitmap pixels equal the accessibility coordinate space (cutout, OEM scale, rotation after the virtual display is created, single-app capture vs entire display).
- Status bar, nav bar, and cutout origin versus bitmap origin. The code applies no inset.
- The live loop never compares frame size to a separately measured `maximumWindowMetrics` at dispatch time. Capture size is chosen once in `start`. A later rotation is not re-measured here.
- `FLAG_NOT_TOUCHABLE` is set around `runCycleIfActive` (`FloatingBubbleService.kt:572-578`), which awaits the callback off the main thread, then the flag is restored. TESZT ÉRINTÉS uses an extra delay that this loop does not copy. Whether the overlay still cancels a gesture on a given phone is unproven.
- `super.dispatchGesture` is not invoked by the unit tests.
- User consent is still required. `createConfigForDefaultDisplay` does not grant the projection by itself.
- Whether a live Match Masters frame clears grid ≥ 0.98. See the HOLD section.

## Verification honesty

`VerifyObservation.frameIsAfterDispatch` (`RuntimeDiagnostics.kt:63-70`):

- If either monotonic sample is set, the frame's `elapsedRealtime` must be **strictly greater** than the dispatch-completion sample, and the completion sample must be > 0.
- Otherwise, if wall times were supplied, `frameTimestampMs` must be strictly greater than `dispatchCompletedAtMs`.
- If the caller supplied no times, the method returns true. That is the legacy unit-test hole. The bubble does not use it: `FloatingBubbleService.kt:708-715` always passes both wall and monotonic times.

`completeFeedback` (`InputLoopController.kt:271-273`) fails closed when `newFrameAccepted` is false, `frameFresh` is false, `frameIsAfterDispatch()` is false, or `boardUnchanged` is true. It does that **before** reading the board.

Identical boards are also rejected by hash when the frame is allowed through: `InputFeedbackVerifier.verify` returns Stop `"board unchanged after input — no blind retry"` when `contentHash` matches (`InputFeedbackVerifier.kt:57-58`). The bubble does not set `boardUnchanged`; it relies on that hash. The flag is an extra fail-closed for callers that set it.

| Test | What it asserts | Executor |
|---|---|---|
| `verify_frameNotLaterThanDispatch_cannotSucceed` | equal wall times 8000/8000 → `FAILED`, reason contains `not later than dispatch` | `RecordingInputGestureExecutor` (simulation). The decision function is production `completeFeedback`. |
| `verify_noNewFrame_cannotSucceed` | `newFrameAccepted = false` → `FAILED`, not `SUCCESS` | same simulation probe |
| `verify_identicalBoard_cannotSucceed` | same vision object → reason contains `unchanged`; a different vision with `boardUnchanged = true` → `FAILED` | same simulation probe |
| `dispatchSuccess_isNotVerifySuccess_staleFrameCannotVerify_simulationProbe` | dispatch leaves `PENDING`; a non-new frame cannot become `SUCCESS` | simulation, banner asserts `LIVE PHONE: NOT TESTED` and `FIRST REAL AUTOMATIC TOUCH: NOT PROVEN` |
| `verify_onlyNewFreshChangedBoard_canSucceed_andStaysSimulation` | a changed board with `newFrameAccepted` and `frameFresh`, and **no timestamps**, can be `SUCCESS`, and the executor role stays `SIMULATION_RECORDING` | simulation only. This is not a phone proof. The production bubble always passes timestamps, so this legacy hole is not the device path. |

## Test evidence quality

No `@Ignore`, no loosened threshold, no assertion was weakened to make a test pass. `SimulationMarker.BANNER` is the string `"SIMULATION — not live phone (Tier B). LIVE PHONE: NOT TESTED. FIRST REAL AUTOMATIC TOUCH: NOT PROVEN."` (`RuntimeDiagnostics.kt:252-254`).

**Mutation.** Removing the early return in `dispatchChecked` when `again.allow` is false calls `CountingChannel.dispatchGesture`. Every `guard_*` test then fails its `dispatchGestureCalls == 0` assertion. `happyPath_...` fails if the channel is not called exactly once, so a test that never reaches the channel cannot hide behind a zero.

### Mock versus real, per new test

`CountingChannel` is the only substitute in the `realDispatcher` tests. It replaces `MatchMastersAccessibilityService` / `super.dispatchGesture`. `AccessibilityGestureExecutor`, `DispatchRecheck`, `ProductionInstall`, `ProductionCycleContext`, `FrameScreenCoordinatePolicy`, `CoordinateBounds`, `FrameClock`, `GestureCallbackPolicy`, and `VerificationPolicy` in those tests are the production classes.

| Test | Production classes exercised | What is substituted |
|---|---|---|
| `thresholds_unchanged` | `VisionThresholds` constants | nothing |
| `callChain_namesTheProductionStepsInOrder` | `ProductionPath.CALL_CHAIN` string | nothing (string lock, not a runtime trace) |
| `defaultEngine_isUninstalled_notTheTestRecorder` | `AutomaticInputEngine` default ctor | nothing |
| `recordingShellAndUninstalled_cannotBeInstalledAsProduction` | `ProductionPath.requireProductionExecutor` | constructs the three refused types |
| `productionInstall_isAccessibilityExecutor_notRecording` | `ProductionInstall` | channel lambda |
| `productionExecutor_refusesNullContext_withoutConsultingService` | engine + production executor | channel; asserts the channel is not consulted |
| `productionExecutor_refusesSimulatedContext_withoutConsultingService` | same | channel + `simulated = true` context |
| `productionExecutor_nullService_doesNotDispatchEvenIfContextSaysConnected` | production executor | `serviceProvider` returns null |
| `productionCycleContext_copiesMeasurements_andIsNeverSimulated` | `ProductionCycleContext` | nothing |
| `captureConsent_api34RequestsEntireDisplay_olderSdksUseChooser` | `CaptureConsent.modeForSdk` | nothing (no real MediaProjection) |
| `captureSize_prefersMaximumWindowMetrics_sameSpaceAsTouchTest` | `CaptureDisplaySize.choose` | numeric inputs, no `WindowManager` |
| `coordinate_identityWhenSizesMatch_cellCentersUnchanged_mismatchRefused` | mapper + `FrameScreenCoordinatePolicy` | nothing |
| `coordinate_sizeMismatch_blocksBeforeDispatch_simulationProbe` | controller, policy, `coordinateBlocked` | `RecordingInputGestureExecutor` |
| `forbidden_missingFrame_noDispatch_simulationProbe` | `InputLoopController.preDecisionBlock` | recorder |
| `forbidden_staleFrame_noDispatch_simulationProbe` | same | recorder |
| `forbidden_visionHold_noDispatch_simulationProbe` | vision HOLD branch | recorder |
| `forbidden_inputDisabled_noDispatch_simulationProbe` | `AutoPlayController` switch | recorder |
| `forbidden_a11yDisconnected_noDispatch_simulationProbe` | controller pause | recorder |
| `forbidden_offScreenCoordinate_noDispatch_simulationProbe` | `CoordinateBounds` | recorder |
| `dispatchSuccess_isNotVerifySuccess_staleFrameCannotVerify_simulationProbe` | `VerificationPolicy`, `completeFeedback` | recorder |
| `verify_onlyNewFreshChangedBoard_canSucceed_andStaysSimulation` | verifier hash path | recorder; labeled SIMULATION |
| `guard_staleFrame_...` through `guard_unknownScreenBounds_...` except the age-during-analysis test | `dispatchChecked` + `DispatchRecheck` | `CountingChannel` only |
| `guard_a11yDroppedAfterPlan_realDispatcher_rereadsChannel` | live `canDispatchGestures` re-read | channel with `ready = false` while the permit says connected |
| `guard_frameAgedDuringAnalysis_realDispatcher_remeasuresBeforeDispatchGesture` | age recompute | channel + injected `nowElapsedMs = { 5000 }` against `capturedElapsedMs = 1000`, plan age 10 |
| `happyPath_realDispatcher_callsDispatchGestureOnce_andIsNotVerifySuccess` | `ProductionInstall`, engine, `tryExecute`, `dispatchChecked` | `CountingChannel` |
| `callback_onCancelled_...`, `callback_neverArrives_...`, `callback_onCompleted_...` | `GestureCallbackPolicy` (the function the service calls) | no `GestureResultCallback`, no service instance |
| `frameClock_usesMonotonicAge_threshold3000_backwardsIsStale` | `FrameClock`, `GestureFailSafe.MAX_FRAME_AGE_MS` | numeric samples, not `SystemClock` |
| `coordinate_independentCenters_nonzeroRoi_noDensity_rotationRefused` | `TouchCoordinateMapper`, `GridGeometry.evenSplit`, policy | expected values computed independently |
| `verify_frameNotLaterThanDispatch_cannotSucceed`, `verify_noNewFrame_cannotSucceed`, `verify_identicalBoard_cannotSucceed` | `completeFeedback`, `InputFeedbackVerifier`, `VerifyObservation` | recorder for the earlier dispatch |
| `bubble_showsHoldReasonAndConfidencesWhenVisionDeclines` | `LivePipelineStatus.bubbleLines` → `RuntimeSnapshot.lines` | constructed status object, not a device overlay |
| `engine_staleContext_doesNotReachRealDispatchGesture` | `tryExecute` + production executor | `CountingChannel` |

## Device-readiness diagnostics (HOLD visibility)

Thresholds were **not** changed: `MIN_GRID_CONFIDENCE = 0.98f`, `MIN_BOARD_CONFIDENCE = 0.95f`, `MAX_UNKNOWN_COUNT = 1` (`VisionModels.kt:312-315`). Test `thresholds_unchanged` locks those values.

The auditor's hypothesis is plausible and **not proven**. Projection grid confidence is capped at 0.99 and floored at 0.85, and the pass line is 0.98, so only a narrow band passes (`BoardFinder.kt:686`). The EVEN_SPLIT fallback is 0.72 and always HOLDs (`BoardFinder.kt:54-59`). The checked-in `pvp_board.jpg` fixture passes at `gridConfidence` **0.9872** (`RealFrameExportTest` constant `EXPECTED_GRID_CONF`, and the exporter string `"gridConfidence": 0.9872`). A different live frame can miss 0.98 and the loop will not dispatch. That is a leading suspect for "the app opens and does not play". It is not a measured cause: there is no on-device log from the owner's phone in this round.

When vision declines, the bubble text is `LivePipelineStatus.bubbleLines` (`LivePipelineStatus.kt:84`), which prepends `RuntimeSnapshot.lines` (`RuntimeDiagnostics.kt:206-227`):

- `BLOKK:` the first blocking reason (`LivePipelineStatus.firstBlockingReason`, vision branch at `:186-187`)
- `VISION: HOLD —` plus the reason (`RuntimeDiagnostics.kt:234-242`)
- compact lines also include `grid=`, `board=`, `unk=` (`LivePipelineStatus.kt:89-90`)
- `FRAME: ... age=…ms`
- `A11Y:`
- `DISPATCH:` (`NOT STARTED` unless a dispatch result exists)
- `VERIFY:` which stays off `SUCCESS` on a HOLD

`bubble_showsHoldReasonAndConfidencesWhenVisionDeclines` builds a RUNNING snapshot with reason `HOLD: grid confidence 0.972 < 0.980 (Decision AI blocked)`, grid 0.972, board 0.961, unknown 1, age 180, A11Y CONNECTED. It asserts the text contains `VISION: HOLD`, `0.972`, `0.980`, `grid=0.972`, `board=0.961`, `unk=1`, `age=180ms`, `A11Y: CONNECTED`, `DISPATCH: NOT STARTED`, and does not contain `VERIFY: SUCCESS`.

Other on-device lines that already explain a non-playing loop, unchanged as user-facing facts: `MODE IDLE — nyomd meg az INDÍTÁS-t`, `ACCESSIBILITY: DISCONNECTED`, `CAPTURE: OFF`, `FRAME stale ageMs=…`.

## Concrete wiring fixes in this round (not phone-proven)

1. API 34+ `createScreenCaptureIntent()` can share only this app. After `moveTaskToBack`, those frames are not Match Masters, vision HOLDs, and nothing is dispatched. SDK ≥ 34 now requests the entire default display. The user still has to approve it.
2. `AutomaticInputEngine`'s default executor was `RecordingInputGestureExecutor(ready = false)`. A forgotten install never dispatched, and a test recorder was one constructor away from the engine. The default is now `UninstalledGestureExecutor`. The live session goes through `ProductionInstall`, which refuses the recorder and the shell executor.
3. `tryExecute(context = null)` used `evaluateGate` defaults (connected, fresh). The production executor now refuses a null context and a simulated context before it consults the service.
4. Capture size prefers `maximumWindowMetrics`, the same space the touch test uses, and refuses to start if the size is 0. Equality with accessibility coordinates remains device-dependent.
5. Plan-time age could pass the recheck after analysis made the frame stale. `dispatchChecked` now recomputes age from `elapsedRealtime` when both samples exist.

## Claims ledger

| Claim | Evidence | Status |
|---|---|---|
| Live session installs `AccessibilityGestureExecutor` and not the recorder | `AutoPlaySession.kt:136`; `productionInstall_isAccessibilityExecutor_notRecording` | verified by unit test |
| Recorder, shell, and uninstalled executors cannot pass `requireProductionExecutor` | `ProductionPath.kt:66-70`; `recordingShellAndUninstalled_cannotBeInstalledAsProduction` | verified by unit test |
| Engine default is not the test recorder | `AutomaticInputEngine.kt:20`; `defaultEngine_isUninstalled_notTheTestRecorder` | verified by unit test |
| Null context cannot dispatch on the production executor | `AutomaticInputEngine.kt:100-106`; `productionExecutor_refusesNullContext_withoutConsultingService` | verified by unit test |
| `simulated = true` cannot dispatch on the production executor | `DispatchRecheck.kt:77`; `productionExecutor_refusesSimulatedContext_withoutConsultingService`; `guard_simulatedContext_...` | verified by unit test |
| Production context is never marked simulated | `ProductionPath.kt:136`; `productionCycleContext_copiesMeasurements_andIsNeverSimulated` | verified by unit test |
| Each listed unsafe condition does not call `dispatchGesture` | `guard_*` tests via `dispatchChecked` | verified by unit test. `super.dispatchGesture` **unproven on device**. The channel is `CountingChannel`. |
| Accessibility drop after planning is re-read | `AccessibilityGestureExecutor.kt:48`; `guard_a11yDroppedAfterPlan_realDispatcher_rereadsChannel` | verified by unit test |
| Age is remeasured at dispatch when both monotonic samples exist | `DispatchRecheck.kt:72-76`; `guard_frameAgedDuringAnalysis_...` | verified by unit test with an injected clock. The Android `SystemClock` call is in `FrameClock.tryElapsed` and is **unproven on device**. |
| `onCancelled` is Failed, not VERIFY SUCCESS | `GestureCallbackPolicy.kt:58`; `callback_onCancelled_...`; service calls `decide` at `MatchMastersAccessibilityService.kt:214` | verified by unit test of the policy. A real `GestureResultCallback` is **unproven on device**. |
| A callback that never arrives is a timeout failure | `GestureCallbackPolicy.kt:52`; `callback_neverArrives_...` | verified by unit test of the policy. **Unproven on device**. |
| `onCompleted` is not VERIFY SUCCESS | `VerificationPolicy.afterDispatch`; `callback_onCompleted_staysVerifyPending`; `happyPath_...` | verified by unit test |
| Verify requires a frame strictly later than dispatch | `VerifyObservation.frameIsAfterDispatch`; `verify_frameNotLaterThanDispatch_cannotSucceed`; bubble passes times at `FloatingBubbleService.kt:708-715` | verified by unit test of the policy. The earlier dispatch in that test is a **simulation** recorder. |
| No new frame cannot verify | `verify_noNewFrame_cannotSucceed` | verified by unit test; dispatch side is **simulated** |
| Identical pre-move board cannot verify | `InputFeedbackVerifier.kt:57-58`; `verify_identicalBoard_cannotSucceed` | verified by unit test; dispatch side is **simulated** |
| A simulation SUCCESS is not a phone touch | `verify_onlyNewFreshChangedBoard_...` asserts `SIMULATION_RECORDING` | simulated only |
| Cell centers match an independent double formula; density is not applied; rotation is refused | `coordinate_independentCenters_...` | verified by unit test. Pixel equality with the accessibility screen is **unproven on device**. |
| API 34+ requests the entire default display | `MainActivity.kt:294`; `captureConsent_api34RequestsEntireDisplay_...` | verified by unit test of the SDK selector. The user's consent dialog and the resulting frames are **unproven on device**. |
| Capture size prefers maximum window metrics | `CaptureDisplaySize.kt:29`; `captureSize_prefersMaximumWindowMetrics_...` | verified by unit test of the pure function. A real `WindowManager` is **unproven on device**. |
| Bubble shows HOLD reason, grid, board, unknown, age, a11y, and does not show VERIFY SUCCESS | `RuntimeSnapshot.visionLine`; `bubble_showsHoldReasonAndConfidencesWhenVisionDeclines` | verified by unit test of the text builder. The on-screen overlay is **unproven on device** (same function). |
| Checked-in `pvp_board.jpg` passes at grid 0.9872 | `RealFrameExportTest` `EXPECTED_GRID_CONF = 0.9872f` | verified by unit test on a fixture, not a live frame |
| A live frame HOLDs because grid confidence was tuned to a synthetic board, and that is why the phone does not play | formula `BoardFinder.kt:686`; threshold 0.98; fallback 0.72 | **unproven on device**. Leading suspect only. Thresholds were not changed. |
| A real phone received an automatic touch from this APK | no operator log, no Tier E note | **unproven on device** |
| One-step smoke dispatches | `OneStepSmokeController.kt:170` passes no context; production executor refuses null | verified in code: it HOLDs. Not a dispatch path. |
| TESZT ÉRINTÉS is inside the vision gate chain | `AutomaticTouchTest.kt:136` calls `dispatch`, not `dispatchChecked` | verified in code: it is a separate manual path |

## Known gaps

- Unit tests do not execute `super.dispatchGesture`.
- Vision, capture-on, sequence, input-enabled, and coordinates are re-checked on the permit. They are not re-sampled from a new bitmap inside `dispatchChecked`. Accessibility and monotonic age are.
- The live loop stores the frame size as the screen size, so the mismatch refusal does not fire on that path. A wrong capture size still dispatches at identity, into the wrong pixels, if vision PASSes.
- `verify_onlyNewFreshChangedBoard_canSucceed_andStaysSimulation` can return SUCCESS without timestamps. That test is labeled simulation. The bubble always passes timestamps.
- FLAG_NOT_TOUCHABLE timing versus the touch-test delay is unproven.
- Process death still returns the session to IDLE until INDÍTÁS (pre-existing).
- Debug APK SHA-256 is not stable across the push build and the PR merge build.

## Key diff hunks

`app/build.gradle.kts`:

```diff
-        versionCode = 14
-        versionName = "0.24.2-runtime-diagnostics"
+        versionCode = 15
+        versionName = "0.24.3-production-path-audit"
```

`MainActivity.kt` `launchProjectionPermission`:

```diff
-        projectionLauncher.launch(mpm.createScreenCaptureIntent())
+        val intent = if (
+            CaptureConsent.modeForSdk(Build.VERSION.SDK_INT) ==
+            CaptureConsent.Mode.ENTIRE_DEFAULT_DISPLAY
+        ) {
+            val config = MediaProjectionConfig.createConfigForDefaultDisplay()
+            mpm.createScreenCaptureIntent(config)
+        } else {
+            mpm.createScreenCaptureIntent()
+        }
+        projectionLauncher.launch(intent)
```

`AutoPlaySession.kt`:

```diff
-        executor = AccessibilityGestureExecutor(),
+        executor = ProductionInstall.accessibilityExecutor(),
```

`AutomaticInputEngine.kt` default and the dispatch branch:

```diff
-    private val executor: InputGestureExecutor = RecordingInputGestureExecutor(ready = false),
+    private val executor: InputGestureExecutor = UninstalledGestureExecutor(),
```

```diff
+        if (ProductionPath.isProductionExecutor(executor)) {
+            val refusal = ProductionPath.productionReadinessRefusal(context)
+            if (refusal != null) {
+                stateMachine.onInputBlocked(refusal)
+                return ExecuteResult.Held(refusal)
+            }
+        }
```

```diff
-        val dispatch = executor.dispatch(gesture)
+        val dispatch = if (context != null && executor is AccessibilityGestureExecutor) {
+            val permit = DispatchPermit.from(...)
+            executor.dispatchChecked(permit)
+        } else {
+            executor.dispatch(gesture)
+        }
```

`AccessibilityGestureExecutor.kt` `dispatchChecked` (current lines 46-58):

```kotlin
fun dispatchChecked(permit: DispatchPermit): InputDispatchResult {
    val live = serviceProvider()?.canDispatchGestures() == true
    val again = DispatchRecheck.evaluate(
        permit.copy(a11yConnected = permit.a11yConnected && live),
        nowElapsedMs = nowElapsedMs(),
    )
    if (!again.allow) {
        return InputDispatchResult.Failed(
            "TOCTOU recheck blocked dispatchGesture: ${again.reason}",
        )
    }
    return dispatch(permit.gesture)
}
```

`DispatchRecheck.kt` age recompute (current lines 71-86):

```kotlin
fun evaluate(permit: DispatchPermit, nowElapsedMs: Long = 0L): Result {
    val ageMs = if (permit.capturedElapsedMs > 0L && nowElapsedMs > 0L) {
        FrameClock.ageMs(permit.capturedElapsedMs, nowElapsedMs)
    } else {
        permit.frameAgeMs
    }
    // ...
    if (ageMs > GestureFailSafe.MAX_FRAME_AGE_MS) {
        return Result(false, "stale frame age=${ageMs}ms")
    }
}
```

`FloatingBubbleService.kt` context and verify times:

```diff
-                    val cycleContext = RuntimeCycleContext(
+                    val cycleContext = ProductionCycleContext.fromLoopObservation(
                         ...
-                        simulated = false,
+                        capturedElapsedMs = useFrame.elapsedRealtimeMs,
                     )
```

```diff
+                                val dispatchCompletedAtMs = System.currentTimeMillis()
+                                val dispatchCompletedElapsedMs = FrameClock.tryElapsed()
                                 ...
                                         VerifyObservation(
                                             newFrameAccepted = true,
                                             frameFresh = true,
+                                            frameTimestampMs = afterFrame.timestampMs,
+                                            dispatchCompletedAtMs = dispatchCompletedAtMs,
+                                            frameElapsedMs = afterFrame.elapsedRealtimeMs,
+                                            dispatchCompletedElapsedMs = dispatchCompletedElapsedMs,
                                         ),
```

`BoardFinder.kt:686` (unchanged formula, cited because of the HOLD hypothesis):

```kotlin
val conf = (1f - (relVarX + relVarY) * 1.5f).coerceIn(projectionMinConfidence, 0.99f)
```

Diff stat `22eca0a..eadf97c`: 25 files, +1737 / −70. New files include `ProductionPath.kt`, `DispatchRecheck.kt`, `FrameClock.kt`, `GestureCallbackPolicy.kt`, `CaptureConsent.kt`, `CaptureDisplaySize.kt`, `ProductionPathIntegrationTest.kt`, `ProductionDispatchGuardTest.kt`.

## Report block

```
VERSION:
0.24.3-production-path-audit (versionCode 15)

COMMIT:
eadf97cc3ca185defbc8f44ce19dc80bf1027582
branch cursor/production-path-audit-0.24.3-7ea6
diff base 22eca0a01725518ee504ce13b35c9610aa34bef3

CI RUN:
https://github.com/kovdacs/MatchMastersBot-Private/actions/runs/37773265584 (push, eadf97cc3ca185defbc8f44ce19dc80bf1027582)
https://github.com/kovdacs/MatchMastersBot-Private/actions/runs/37773270896 (pull_request, head eadf97c, merge checkout 01a59ea85cdffdeea6e35748dfd5d1a7cffe69e2)

CI STATUS:
SUCCESS (both runs). Each log: 388 PASSED, testDebugUnitTest BUILD SUCCESSFUL, assembleDebug BUILD SUCCESSFUL.

APK ARTIFACT:
Match3Analyzer-0.24.3-production-path-audit-eadf97c.apk
25071083 bytes
SHA-256 6f5b7577c610872abbee61b89658fa4c051075bb0592ea313066911041871e3c
versionName 0.24.3-production-path-audit versionCode 15
https://github.com/kovdacs/MatchMastersBot-Private/actions/runs/37773265584/artifacts/11548449616
PR merge-sha copy: Match3Analyzer-0.24.3-production-path-audit-01a59ea.apk
25071083 bytes
SHA-256 a523228045752c8c28c3869617d0ac991baf061ee1f9ab83f998fd9ada052627
https://github.com/kovdacs/MatchMastersBot-Private/actions/runs/37773270896/artifacts/11549091317
Same size and versionCode. SHA-256 differs (debug zip timestamps / signing). Not a phone-touch proof.

TESTS BEFORE:
342 @Test methods on 22eca0a (0.24.2-runtime-diagnostics).

TESTS AFTER:
388 tests, 0 failures, 0 skipped. +46 −0, all in ProductionPathIntegrationTest (21) and ProductionDispatchGuardTest (25). Local testDebugUnitTest and both CI logs agree. assembleDebug BUILD SUCCESSFUL locally and in both runs. No @Ignore.

PRODUCTION PATH AUDIT:
Real production call chain: MainActivity.beginAutoPlaySetup (MainActivity.kt:168) → launchProjectionPermission entire default display on SDK≥34 (MainActivity.kt:294) → ScreenCaptureManager.start (ScreenCaptureManager.kt:66) → FloatingBubbleService.ensureLoopRunning (FloatingBubbleService.kt:422) → analyzeFrame (FloatingBubbleService.kt:785) → ProductionCycleContext.fromLoopObservation simulated=false (FloatingBubbleService.kt:560, ProductionPath.kt:136) → AutoPlayController.runCycleIfActive (AutoPlayController.kt:164) → InputLoopController.runAnalyzeAndMaybeInput (InputLoopController.kt:53) → MoveAnalysisEngine.analyze (InputLoopController.kt:109) → AutomaticInputEngine.tryExecute (AutomaticInputEngine.kt:93) → AccessibilityGestureExecutor.dispatchChecked (AccessibilityGestureExecutor.kt:46) → MatchMastersAccessibilityService.dispatchGesture super.dispatchGesture (MatchMastersAccessibilityService.kt:168) → verify only on a new fresh frame strictly later than dispatch (FloatingBubbleService.kt:705, InputLoopController.kt:266).
Separation from simulation: ProductionInstall refuses RecordingInputGestureExecutor and ShellInputGestureExecutor. Engine default is UninstalledGestureExecutor. forbidden_* and verify_*_simulationProbe tests use the recorder and assert SimulationMarker / SIMULATION_RECORDING. guard_* tests use the production executor; CountingChannel replaces only the framework call.
Blocking tests: one guard_* test per unsafe condition asserts dispatchGestureCalls == 0 through dispatchChecked, including TOCTOU a11y re-read and monotonic age remeasure. happyPath asserts the channel is called once and VERIFY stays PENDING. onCancelled and a never-arriving callback are Failed, not VERIFY SUCCESS.
Coordinate transformation evidence: identity only; no dp, no rotation scale. Independent double expected for ROI (37,511,1043,2018) cell (4,2)→(4,3) is start (396.2857142857, 1479.7857142857) end (540.0, 1479.7857142857), tolerance 0.05, not multiplied by density 2.75. Rotation 1080×2400 vs 2400×1080 is REFUSED. Device pixel equality remains unproven.

REMAINING BLOCKERS:
1. No operator evidence on a phone. LIVE PHONE: NOT TESTED. FIRST REAL AUTOMATIC TOUCH: NOT PROVEN.
2. Live vision may HOLD. gridConfidence formula is tight (cap 0.99, gate 0.98, EVEN_SPLIT fallback 0.72). pvp_board.jpg passes at 0.9872. A different live frame may not. Thresholds were not loosened. The bubble shows VISION HOLD, the reason, grid, board, unknown, age, A11Y, and DISPATCH NOT STARTED.
3. Capture-bitmap pixels are sent unchanged. Whether they match the accessibility screen (cutout, OEM scale, rotation) is device-dependent. The live loop copies frame size into screen size, so the mismatch guard does not fire on that path.
4. super.dispatchGesture is not executed by unit tests.
5. TESZT ÉRINTÉS calls dispatch() directly and skips vision gates. One-step smoke calls tryExecute without a context and therefore HOLDs on the production executor.
6. API 34+ entire-display consent still requires the user to approve. Not proven until this APK is installed.
7. After process death the session is IDLE until INDÍTÁS.

LIVE PHONE: NOT TESTED

FIRST REAL AUTOMATIC TOUCH: NOT PROVEN
```
