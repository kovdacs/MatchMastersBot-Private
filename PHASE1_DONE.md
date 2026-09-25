# PHASE 1 DONE — Match3 Vision Analyzer

**Completed:** 2026-09-20 (Europe/Vienna)  
**Project root:** `/workspace/match3-vision-ai/android/`

## What was built

1. **Gradle Android project** (Kotlin, Compose, minSdk 29, compileSdk/targetSdk 35)
   - Wrapper (Gradle 8.7), `settings.gradle.kts`, root + `app/build.gradle.kts`
   - Compose BOM, activity-compose, lifecycle-viewmodel-compose, coroutines, Timber
   - JUnit + Truth unit tests
   - **No OpenCV** dependency (Phase 2)

2. **Capture stack**
   - `CaptureConfig` — default 5 FPS, clamp helpers
   - `CaptureFrame` + `ContentRoi`
   - `LetterboxDetector` — near-black bars → content ROI (JVM-testable via pixel arrays)
   - `ScreenCaptureManager` — ImageReader + VirtualDisplay, `StateFlow` of latest frame
   - `CaptureService` — FGS with `foregroundServiceType=mediaProjection`

3. **UI**
   - `MainActivity` — MediaProjection (+ POST_NOTIFICATIONS) permission flow
   - `AnalyzerScreen` + `AnalyzerViewModel` — Start/Stop, status, preview, ROI, subtitle
     *“Analyzer only — no automatic input”*

4. **Manifest** — FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PROJECTION, POST_NOTIFICATIONS; **no** AccessibilityService

5. **Docs** — `README.md`, `docs/ARCHITECTURE.md` (copy), this file

6. **Future stubs** (KDoc only): `vision/`, `board/`, `validation/`, `moves/`, `simulation/`, `ai/`, `orchestration/`, `logging/`, `di/`

7. **Unit tests** (8 cases): CaptureConfig FPS clamp + LetterboxDetector black bars / pillarbox / thin-strip / luma

## How to verify

```bash
cd /workspace/match3-vision-ai/android
# Set sdk.dir in local.properties
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

On device: Start → grant capture → confirm notification + frame preview + ROI text → Stop.

## Gradle / tests on this box

| Check | Result |
|-------|--------|
| Java / JDK | **Not installed** on the build box |
| `ANDROID_HOME` | **Unset** |
| `./gradlew test` | **Not run** — blocked by missing JDK + Android SDK |

Project structure and sources are complete for Android Studio on a machine with SDK/JDK 17.

## No touch automation

Confirmed: no AccessibilityService, no GestureDescription, no MotionEvent injection, no auto-play. Analyzer / display only.

## Key paths

- Project root: `/workspace/match3-vision-ai/android/`
- App sources: `app/src/main/java/com/match3vision/analyzer/`
- Tests: `app/src/test/java/com/match3vision/analyzer/capture/`
- Architecture: `docs/ARCHITECTURE.md` (also `/workspace/match3-vision-ai/ARCHITECTURE.md`)
