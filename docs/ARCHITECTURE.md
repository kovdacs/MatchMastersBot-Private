# Match-3 Vision & AI — Technical Architecture

**Projekt:** Match3 Vision Analyzer (kutatási / prototípus)  
**Platform:** Android 10+ (API 29+)  
**Nyelv:** Kotlin  
**UI:** Jetpack Compose  
**Mód:** Analyzer only — nincs automatikus érintés, swipe vagy online játékvezérlés  

---

## 1. Cél és határok

### Cél
Egy Android alkalmazás, amely:
1. képernyőképet készít (MediaProjection),
2. megtalálja a 7×7 match-3 táblát,
3. felismeri a köveket és speciális elemeket,
4. UNKNOWN-ként kezeli a bizonytalan cellákat,
5. generálja az összes érvényes csere-lépést,
6. szimulálja a cascade-eket,
7. TOP-5 lépést értékel magyarázattal,
8. a kiválasztott lépést **csak a UI-n jeleníti meg** (overlay / lista).

### Expliciten tilos ebben a projektben
- AccessibilityService alapú játékvezérlés
- GestureDescription / inject touch
- Automata játékciklus (AUTO play)
- Online ellenfél elleni beavatkozás

Az AccessibilityService **csak mint elvetett opció** szerepel az architektúrában (miért nem használjuk).

---

## 2. Magas szintű architektúra

```
┌─────────────────────────────────────────────────────────────┐
│                     Android UI (Compose)                     │
│  Start/Stop · Confidence · Board · TOP-5 · Explanation · Log │
└────────────────────────────┬────────────────────────────────┘
                             │ ViewModel / StateFlow
┌────────────────────────────▼────────────────────────────────┐
│                    AnalysisOrchestrator                       │
│         (pipeline vezérlés, HOLD gate, retry, log)            │
└──┬──────────┬──────────┬──────────┬──────────┬──────────────┘
   │          │          │          │          │
   ▼          ▼          ▼          ▼          ▼
 Capture   Vision     Board      Simulation  Decision
 Manager   Pipeline   Models     Engine      Engine
```

### Pipeline (egyszeri vagy folyamatos analysis frame)

```
SCREENSHOT
  → BoardFinder (ROI + geometria)
  → GridBuilder (7×7 cella koordináták)
  → TileRecognizer (szín + forma → TileType + confidence)
  → SpecialDetector (bomb, lightning, arrow overlay)
  → GameStateBuilder
  → ValidationGate  ──fail──► HOLD (nincs döntés)
  → MoveGenerator
  → MoveSimulator
  → DecisionEngine → TOP-5 + explanation
  → UI render (debug overlay opcionális)
```

---

## 3. Technológia döntések

| Terület | Választás | Indoklás |
|--------|-----------|----------|
| Screen capture | **MediaProjection + ImageReader** | Root nélkül, hivatalos API, folyamatos frame |
| Kép feldolgozás | **OpenCV (Android)** | Edge, contour, projection, HSV — megbízható CV |
| ML (opcionális Phase 4+) | **TensorFlow Lite** | Tile osztályozás, ha a klasszikus CV kevés |
| Alternatív ML | ONNX Runtime | Csak ha TFLite nem elég / meglévő ONNX modell |
| UI | Jetpack Compose | Modern, state-driven debug UI |
| DI | Hilt | Tesztelhetőség |
| Coroutine | Kotlin Coroutines + Flow | Capture loop, UI state |
| Logging | Timber + strukturált JSON export | Diagnosztika |
| Touch automation | **Nincs** | Kutatási hatókör |

### API-k használata
- `MediaProjectionManager` — felhasználói engedély után capture
- `VirtualDisplay` + `ImageReader` — RGBA frame
- `Foreground Service` — folyamatos capture Android 10+ szabályoknak megfelelően
- OpenCV `Imgproc`, `Core`, `Imgcodecs`
- **Nem** használjuk: `AccessibilityService` (input injektálás)

---

## 4. Modulok és fájlstruktúra

```
app/
├── build.gradle.kts
├── src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/match3vision/analyzer/
│   │   ├── Match3AnalyzerApp.kt
│   │   ├── MainActivity.kt
│   │   │
│   │   ├── capture/
│   │   │   ├── ScreenCaptureManager.kt
│   │   │   ├── CaptureConfig.kt
│   │   │   ├── CaptureFrame.kt
│   │   │   ├── LetterboxDetector.kt
│   │   │   └── CaptureService.kt          # Foreground service
│   │   │
│   │   ├── vision/
│   │   │   ├── BoardFinder.kt
│   │   │   ├── GridBuilder.kt
│   │   │   ├── TileRecognizer.kt
│   │   │   ├── SpecialDetector.kt
│   │   │   ├── ColorAnalyzer.kt
│   │   │   ├── ShapeAnalyzer.kt
│   │   │   ├── ProjectionAnalyzer.kt
│   │   │   ├── OcclusionHandler.kt
│   │   │   └── VisionPipeline.kt
│   │   │
│   │   ├── board/
│   │   │   ├── TileType.kt
│   │   │   ├── Tile.kt
│   │   │   ├── CellCoord.kt
│   │   │   ├── BoardGeometry.kt
│   │   │   ├── BoardSnapshot.kt
│   │   │   └── GameState.kt
│   │   │
│   │   ├── validation/
│   │   │   ├── ValidationGate.kt
│   │   │   ├── ConfidenceThresholds.kt
│   │   │   └── HoldReason.kt
│   │   │
│   │   ├── moves/
│   │   │   ├── Move.kt
│   │   │   ├── MoveGenerator.kt
│   │   │   └── MatchDetector.kt
│   │   │
│   │   ├── simulation/
│   │   │   ├── MoveSimulator.kt
│   │   │   ├── CascadeResult.kt
│   │   │   ├── GravityEngine.kt
│   │   │   ├── SpecialActivator.kt
│   │   │   └── SpawnModel.kt              # valószínűségi új tile
│   │   │
│   │   ├── ai/
│   │   │   ├── DecisionEngine.kt
│   │   │   ├── MoveEvaluation.kt
│   │   │   ├── ScoreComponents.kt
│   │   │   └── ExplanationBuilder.kt
│   │   │
│   │   ├── orchestration/
│   │   │   ├── AnalysisOrchestrator.kt
│   │   │   ├── AnalysisResult.kt
│   │   │   └── AnalysisMode.kt            # ANALYZER only
│   │   │
│   │   ├── logging/
│   │   │   ├── AnalysisLogger.kt
│   │   │   ├── LogEntry.kt
│   │   │   └── LogExporter.kt
│   │   │
│   │   ├── ui/
│   │   │   ├── AnalyzerViewModel.kt
│   │   │   ├── AnalyzerScreen.kt
│   │   │   ├── components/
│   │   │   │   ├── BoardPreview.kt
│   │   │   │   ├── TopMovesList.kt
│   │   │   │   ├── ConfidenceBar.kt
│   │   │   │   └── DebugOverlay.kt
│   │   │   └── theme/
│   │   │
│   │   └── di/
│   │       └── AppModule.kt
│   │
│   └── res/
│       ├── xml/file_paths.xml
│       └── ...
│
├── src/test/java/.../          # Unit tesztek (JVM)
└── src/androidTest/java/.../   # Instrumented tesztek

docs/
└── ARCHITECTURE.md             # ez a dokumentum

dataset/                        # későbbi ML / kalibráció
├── normal/
├── special/
├── unknown/
├── booster/
├── banner/
└── game_over/
```

---

## 5. Adatmodellek

### TileType
```kotlin
enum class TileType {
    BLUE_STAR,
    RED_CIRCLE,
    YELLOW_TRIANGLE,
    GREEN_DIAMOND,
    PURPLE_SQUARE,
    ORANGE_HEX,
    BOMB,
    LIGHTNING,
    ARROW_H,        // horizontal directional
    ARROW_V,        // vertical directional
    EMPTY,
    UNKNOWN
}
```

### Tile
```kotlin
data class Tile(
    val type: TileType,
    val confidence: Float,          // 0f..1f
    val colorConfidence: Float,
    val shapeConfidence: Float,
    val specialOverlay: SpecialOverlay? = null
)
```

### BoardGeometry
```kotlin
data class BoardGeometry(
    val boardRect: RectF,           // képernyő koordináta
    val cellRects: Array<Array<RectF>>, // [7][7]
    val rowGutters: FloatArray,     // 8 vonal (0..7)
    val colGutters: FloatArray,
    val gridConfidence: Float,
    val method: GeometryMethod      // PROJECTION | EDGE | CONTOUR | FALLBACK
)
```

### BoardSnapshot
```kotlin
data class BoardSnapshot(
    val tiles: Array<Array<Tile>>,  // [7][7]
    val geometry: BoardGeometry,
    val boardConfidence: Float,
    val unknownCount: Int,
    val timestampMs: Long
)
```

### GameState
```kotlin
data class GameState(
    val board: Array<Array<Tile>>,
    val score: Int?,
    val opponentScore: Int?,
    val round: Int?,
    val playerBooster: Float?,      // 0..1 charge, null = nem detektált
    val opponentBooster: Float?,
    val turn: TurnState,            // PLAYER | OPPONENT | UNKNOWN
    val animationsActive: Boolean,
    val gameOver: Boolean,
    val boardConfidence: Float,
    val gridConfidence: Float,
    val unknownCount: Int
)
```

### Move / MoveEvaluation
```kotlin
data class Move(val from: CellCoord, val to: CellCoord)

data class MoveEvaluation(
    val move: Move,
    val expectedScore: Double,
    val cascadeDepth: Int,
    val specialCreated: List<TileType>,
    val boosterGain: Double,
    val risk: Double,
    val confidence: Float,
    val components: ScoreComponents,
    val explanation: String
)
```

### Validation / HOLD
```kotlin
data class ConfidenceThresholds(
    val minBoardConfidence: Float = 0.95f,
    val minGridConfidence: Float = 0.98f,
    val maxUnknownCount: Int = 1
)

sealed class HoldReason {
    data class LowBoardConfidence(val value: Float) : HoldReason()
    data class LowGridConfidence(val value: Float) : HoldReason()
    data class TooManyUnknown(val count: Int) : HoldReason()
    data class AnimationActive(val detail: String) : HoldReason()
    data class TurnNotPlayer(val turn: TurnState) : HoldReason()
    object CaptureFailed : HoldReason()
    object BoardNotFound : HoldReason()
}
```

**Szabály:** UNKNOWN cellát tilos kitalálni. Ha a gate fail → `AnalysisResult.Hold(reason)`, TOP-5 üres.

---

## 6. Vision pipeline részletek

### 6.1 ScreenCaptureManager (PHASE 1)
- MediaProjection token fogadása az Activity-ből
- VirtualDisplay → ImageReader (RGBA_8888)
- Capture rate: pl. 5–10 FPS analysis módban (állítható)
- LetterboxDetector: fekete sávok detektálása, aktív tartalom ROI
- Aspect ratio / density független: mindig pixel koordináták + normalized ROI
- Kimenet: `CaptureFrame(bitmap|mat, width, height, timestamp)`

### 6.2 BoardFinder (PHASE 2)
Prioritásos stratégia (első sikeres + confidence):

1. **Projection analysis** (preferált)  
   - Szürkeárnyalat → optional blur → vertical/horizontal projection  
   - Gutter csúcsok keresése (periodikus 8 vonal 7 cellához)  
   - Periodicitás score → gridConfidence

2. **Edge + gutter detection**  
   - Canny / Sobel → HoughLinesP vagy morphológiai zárás  
   - Domináns vízszintes/függőleges vonalak klaszterezése

3. **Contour detection**  
   - Nagy négyszögű kontúr a játéktábla keretére  
   - Perspective correction ha szükséges (getPerspectiveTransform)

4. **Geometriai fallback**  
   - Kalibrált ROI arányok (config) + finomhangolás  
   - Alacsonyabb confidence → könnyen HOLD

**Nem** elsődleges: vak `width/7` felosztás a teljes képernyőn.

### 6.3 GridBuilder (PHASE 3)
- 7×7 `cellRects` a gutter vonalakból
- OcclusionHandler: banner / popup maszk → érintett cellák UNKNOWN-ra vagy gate fail
- Validáció: cella méret konzisztencia, aspect ~1.0, min cella méret

### 6.4 TileRecognizer (PHASE 4)
Kombinált döntés:

```
colorScore  = HSV/HSL histogram + dominant hue buckets
shapeScore  = contour approx (csúcsok száma) + circularity + template
finalType   = reconcile(color, shape)
confidence  = weighted(colorScore, shapeScore)
if confidence < tileThreshold → UNKNOWN
```

Szín bucket-ek (példa, kalibrálandó):
- blue, red, yellow, green, purple, orange

Forma jelek:
- star (sok csúcs), circle, triangle (3), diamond (4 ferde), square, hex (6)

### 6.5 SpecialDetector
- Overlay / fényesség / extra kontúr a cellán belül
- Bomb: kerek + sötét mag / ismert template
- Lightning: hosszúkás fényes forma
- Arrow: irányvektor a kontúrból

---

## 7. Move generation & simulation

### MoveGenerator (PHASE 6)
Minden cellára 4 irány (jobb, le prioritás a duplikáció elkerülésére: csak `right` és `down` csere, hogy minden élt egyszer nézzünk — vagy mind a 4 + dedupe).

Érvényes, ha swap után van legalább egy match (≥3 azonos alaptípus).

### MoveSimulator (PHASE 7)
Teljes ciklus egy lépésre:

1. Swap
2. Match detection (sor/oszlop ≥3; L/T alak special létrehozás szabályokkal)
3. Removal
4. Special activation (ha a match vagy a special érintett)
5. Gravity
6. Spawn (SpawnModel: ha RNG ismeretlen → várható érték / Monte Carlo N futás)
7. Ismétlés amíg van match (cascade)
8. CascadeResult: score, depth, specials, final board distribution

### DecisionEngine (PHASE 8)
```
EV = w1*ImmediateScore
   + w2*CascadePotential
   + w3*SpecialCreation
   + w4*BoosterGain
   + w5*ExtraMoveProxy
   + w6*FutureBoardQuality
   - w7*Risk
```

- Minden MoveEvaluation kap magyarázatot (ExplanationBuilder)
- TOP-5 rendezés EV szerint
- Selected move = TOP-1 (csak megjelenítés)

---

## 8. Orchestrator és biztonsági gate

```kotlin
suspend fun analyzeOnce(): AnalysisResult {
    val frame = captureManager.latest() ?: return Hold(CaptureFailed)
    val geometry = boardFinder.find(frame)
    if (geometry == null) return Hold(BoardNotFound)
    val board = visionPipeline.recognize(frame, geometry)
    val state = gameStateBuilder.build(board, frame)
    val hold = validationGate.check(state)
    if (hold != null) return Hold(hold)
    val moves = moveGenerator.generate(state.board)
    val evals = moves.map { decisionEngine.evaluate(it, state) }
        .sortedByDescending { it.expectedScore }
        .take(5)
    return Ready(state, evals)
}
```

Alapértelmezett mód: **ANALYZER** (egyetlen mód ebben a projektben).

---

## 9. Android UI (PHASE 9)

Compose képernyő elemei:
- Start Analysis / Stop
- Analyzer status (Idle / Capturing / Analyzing / HOLD / Ready)
- Board confidence, Grid confidence, UNKNOWN count
- Detected board (színkódolt 7×7 rács)
- TOP-5 moves lista
- Selected move kiemelés
- Explanation szöveg
- Debug overlay toggle (cellahatárok, típus címkék, confidence)
- Log export gomb (JSON / TXT → Downloads / share sheet)

MediaProjection engedély flow: Start → rendszer dialog → Service indul.

---

## 10. Dependency-k (app/build.gradle.kts)

```kotlin
// AndroidX / Compose
implementation("androidx.core:core-ktx:1.13.1")
implementation("androidx.activity:activity-compose:1.9.2")
implementation(platform("androidx.compose:compose-bom:2024.09.00"))
implementation("androidx.compose.ui:ui")
implementation("androidx.compose.material3:material3")
implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")

// DI / async
implementation("com.google.dagger:hilt-android:2.52")
kapt("com.google.dagger:hilt-compiler:2.52")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

// Vision
implementation("org.opencv:opencv:4.10.0") // vagy AAR manuális / Maven Central fork

// Opcionális ML (Phase 4+)
// implementation("org.tensorflow:tensorflow-lite:2.14.0")
// implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")

// Logging
implementation("com.jakewharton.timber:timber:5.0.1")

// Test
testImplementation("junit:junit:4.13.2")
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
testImplementation("com.google.truth:truth:1.4.4")
testImplementation("io.mockk:mockk:1.13.12")
androidTestImplementation("androidx.test.ext:junit:1.2.1")
```

### Manifest permissionök
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<!-- WRITE/READ csak log export régebbi API-n, ha kell -->
```

Service:
```xml
<service
    android:name=".capture.CaptureService"
    android:exported="false"
    android:foregroundServiceType="mediaProjection" />
```

---

## 11. Tesztstratégia

### Unit (JVM, OpenCV native nélkül ahol lehet — tiszta logika)
1. MatchDetector — sor/oszlop match
2. MoveGenerator — érvényes cserék száma ismert boardon
3. MoveSimulator — gravity, cascade depth
4. SpecialActivator — bomb / lightning hatás
5. DecisionEngine — TOP-5 rendezés, súlyok
6. ValidationGate — UNKNOWN / confidence HOLD
7. ExplanationBuilder — nem üres magyarázat
8. SpawnModel — valószínűségi eloszlás összeg = 1
9. Tile reconcile — konfliktus → UNKNOWN
10. ScoreComponents — EV számítás

### Vision unit / instrumented (asset képekkel)
11. ProjectionAnalyzer — szintetikus 7×7 rács PNG
12. BoardFinder — mintakép ROI
13. GridBuilder — 49 cella
14. ColorAnalyzer — szín bucket
15. ShapeAnalyzer — háromszög/kör
16. SpecialDetector — bomb fixture
17. LetterboxDetector — fekete sáv
18. OcclusionHandler — banner maszk
19. End-to-end VisionPipeline fixture
20. Confidence aggregáció

### Capture / UI
21. CaptureConfig FPS clamp
22. AnalysisOrchestrator HOLD path
23. LogExporter JSON schema
24. ViewModel state transitions
25. GameState builder null HUD mezők

Cél: **≥25 automatizált teszt** Phase 10-re; minden phase végén a phase-hez tartozó tesztek zöldek.

---

## 12. Fejlesztési mérföldkövek

| Phase | Deliverable | Done kritérium |
|-------|-------------|----------------|
| **1** | Screen capture | Engedély → foreground service → Bitmap/Mat frame; letterbox ROI; unit/config tesztek |
| **2** | BoardFinder | Fixture képen megtalálja a tábla rectet + method tag |
| **3** | 7×7 grid | 49 cella, gridConfidence, nem vak width/7 |
| **4** | Tile recognition | 6 alaptípus + UNKNOWN; szín+forma; special stub |
| **5** | GameState | Modell + builder + confidence mezők |
| **6** | Move generation | Összes érvényes swap |
| **7** | Move simulation | Cascade + gravity + special |
| **8** | DecisionEngine | TOP-5 + explanation |
| **9** | Android UI | Compose képernyő, overlay, log export |
| **10** | Integráció | Orchestrator loop, HOLD, ≥25 teszt, README |

---

## 13. Kockázatok és mitigáció

| Kockázat | Hatás | Megoldás |
|----------|-------|----------|
| Különböző telefon felbontás / notch | Rossz ROI | Letterbox + normalized geometry + kalibráció |
| Animáció közbeni frame | Hamis board | Frame differencia / motion score → animationsActive |
| Ismeretlen special kinézet | Rossz típus | UNKNOWN + HOLD; dataset bővítés |
| OpenCV AAR méret / ABI | Build hiba | jniLibs szűrés; prefab |
| MediaProjection Android 14+ változások | Capture break | targetSdk ellenőrzés, FGS type |
| RNG spawn a játékban | Szimuláció eltérés | Monte Carlo + confidence a döntésen |
| ToS / jogi | — | Analyzer only; nincs input inject; kutatási scope |

---

## 14. PHASE 1 scope (következő implementáció)

Pontosan ezek készülnek el először, fordítható Android projektként:

1. Gradle projektváz (Kotlin, Compose, minSdk 29)
2. `CaptureConfig`, `CaptureFrame`
3. `LetterboxDetector`
4. `ScreenCaptureManager` + `CaptureService`
5. `MainActivity` MediaProjection permission flow
6. Egyszerű Compose UI: Start / Stop / utolsó frame preview / status
7. Unit tesztek: LetterboxDetector, CaptureConfig
8. README: build, telepítés, futtatás

A BoardFinder és a többi phase **nem** része a Phase 1 PR-nek — csak stub/interface, ha kell a DI-hez.

---

## 15. Összefoglaló döntések

1. **Capture:** MediaProjection + ImageReader + FGS  
2. **Vision:** OpenCV elsődleges; TFLite opcionális később  
3. **Grid:** Projection / edge / contour, majd fallback — nem vak width/7  
4. **Biztonság:** confidence gate + UNKNOWN → HOLD  
5. **AI:** teljes szimulátor + súlyozott EV + TOP-5 + magyarázat  
6. **Automation:** nincs — csak megjelenítés  
7. **Haladás:** phase-enként fordítható, tesztelt állapot  

