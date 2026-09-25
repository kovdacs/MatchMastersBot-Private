# realistic_synthetic

**REALISTIC_SYNTHETIC** fixtures for vision robustness tests.

- These are **NOT** real Match Masters screenshots.
- Primary fixture is built in-memory by `RealisticSyntheticFixture.kt` (ARGB IntArray).
- Ground truth is authored from construction parameters via `RealisticSyntheticGroundTruth`
  — **never** from VisionPipeline detector output.
- Optional saved PNG/ARGB dumps may be added later for debugging; absence is fine
  because the Kotlin builder always runs in JVM unit tests.

Do not rename or document these as real Match Masters frames.
