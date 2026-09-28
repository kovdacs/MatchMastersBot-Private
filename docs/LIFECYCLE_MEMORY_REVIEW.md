# Lifecycle / memory review (0.23.0-audit-pack)

| Resource | Owner | Stop / destroy behavior | Notes |
|----------|-------|-------------------------|-------|
| Bitmap (capture) | `ScreenCaptureManager` | Recycle previous on new emit; recycle on `release()` | Fixed in audit pack |
| `ImageReader` | `ScreenCaptureManager.stop` | listener null + `close()` | OK |
| `VirtualDisplay` | `ScreenCaptureManager.stop` | `release()` | OK |
| `MediaProjection` | `ScreenCaptureManager.stop` | unregister callback + `stop()` | OK |
| `CaptureService` | `ACTION_STOP` / `onDestroy` | `captureManager.release()` | OK |
| Activity | `MainActivity` | stop bubble/capture via STOP paths | UI bitmap held in ViewModel — not recycled (shared with capture emission; capture owns recycle) |
| Floating bubble | `FloatingBubbleService` | `ACTION_STOP_ALL` removes view; cancels `loopJob` | `frameSequenceGate.reset` via `AutoPlaySession.endSession` |
| Coroutines | bubble `scope` / ViewModel `viewModelScope` | job cancel on stop / `onCleared` | OK |
| Input state | `InputEnableSwitch` + `BotStateMachine` | STOP disables; `resetForNewSession` clears | START→STOP→START covered by `RestartCycleTest` |
| Frame sequence | `FrameSequenceGate` | reset on begin/end session | SAME/OLD blocked after gesture |

**Residual risk:** ViewModel `lastFrameBitmap` may point at a recycled bitmap if UI reads after
capture recycle of the same instance. Prefer copy-on-publish for UI preview in a later pass if
crashes appear; capture path now recycles only the *previous* emission after swap.
