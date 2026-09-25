# Match3 Vision Analyzer

**HU (rövid):** Kutatási Android prototípus — csak képernyőelemzés (MediaProjection). Nincs AccessibilityService, nincs érintés-injektálás, nincs automatikus játékvezérlés. Phase 1–2: capture + vision analyzer (board/grid/tiles/gate).

**EN:** Research Android prototype that **analyzes** a match-3 board from screen capture. **Analyzer only** — no touch injection, no AccessibilityService, no gameplay automation of any commercial game.

## Phase 1 scope

- MediaProjection + `ImageReader` + `VirtualDisplay`
- Foreground service (`mediaProjection` type)
- Letterbox / pillarbox content ROI detection
- Jetpack Compose UI: Start / Stop / status / last frame preview / ROI text
- Unit tests: `CaptureConfig`, `LetterboxDetector`

## Phase 2 scope

- Vision pipeline: `BoardFinder` (projection + even_split), occlusion, color, shape, special, reconcile, validation gate
- Pure Kotlin / ARGB buffers — JVM unit tests without OpenCV or Robolectric
- UI: **Analyze last frame** → PASS/HOLD status (analyzer only; Decision AI not implemented)
- See `PHASE2_DONE.md`

Phases 1–18 implemented (analyzer only). See `PROJECT_STATUS.md` and `ARCHITECTURE_FINAL.md`. Gradle tests: ENVIRONMENT_BLOCKED without JDK 17 + SDK.

## Requirements

- Android Studio Hedgehog+ / AGP 8.5+
- JDK 17
- Android SDK with `compileSdk 35`, device/emulator **API 29+**
- Copy `local.properties.example` → `local.properties` and set `sdk.dir`

## Open / build

```bash
cd android   # this directory (project root)
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Or open the `android/` folder in Android Studio (File → Open).

## Run on device

1. Install debug APK / Run from Android Studio.
2. Tap **Start** → grant notification permission (API 33+) if asked → grant **screen capture**.
3. A foreground notification appears while capturing.
4. Preview and content ROI update in the UI.
5. Tap **Stop** (or the notification action) to end capture.

## Package

- Application ID / namespace: `com.match3vision.analyzer`
- App name: **Match3 Vision Analyzer**
- minSdk 29, targetSdk / compileSdk 35

## Explicit non-goals (this project)

- No `AccessibilityService`
- No `GestureDescription` / inject touch
- No AUTO-play loop
- No automation of commercial online games

## License / ethics

Research / educational prototype. Use only on content you are allowed to capture. Respect game Terms of Service — this app does not control games.
