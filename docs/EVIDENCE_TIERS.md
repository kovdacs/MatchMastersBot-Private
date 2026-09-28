# Evidence tiers — do not mix

| Tier | Meaning | Examples |
|------|---------|----------|
| **A — runtime proven** | Observed on a running process with logs/UI | Bubble loop HOLD diag lines; a11y CONNECTED flags |
| **B — build/test proven** | CI `testDebugUnitTest` + `assembleDebug` green | analyzer-ci run URL, JUnit asserts |
| **C — synthetic vision proven** | Synthetic / realistic-synthetic fixtures PASS/HOLD as asserted | `VisionPipelineTest`, `RealisticSyntheticVisionTest` |
| **D — real Match Masters frame proven** | Checked-in JPEG(s) through VisionPipeline in CI | `RealFrameVisionTest` on `pvp_board.jpg` |
| **E — real phone proven** | Device install + live MediaProjection of Match Masters play | Requires operator APK sideload + screenshots/logs |

**Honesty rule:** screenshots / logs showing Vision HOLD `unknownCount` are **not** evidence of
live `dispatchGesture` success. Do not claim E without device evidence.
