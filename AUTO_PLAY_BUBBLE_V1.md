# AUTO-PLAY BUBBLE UX V1

**Date:** 2026-09-28 (Europe/Vienna)  
**Version:** `0.21.0-auto-bubble`  
**Package:** `com.match3vision.analyzer.overlay` + `input.AutoPlayController`

## User flow (Hungarian UI)

1. Open **Match3 Vision Elemző**
2. Tap big **INDÍTÁS**
3. Grant only what’s missing: notifications → overlay → Accessibility → MediaProjection
4. Small movable bubble appears (top-end; drag via title)
5. Open **Match Masters** (analyzer goes background → live frames accepted)
6. Bubble **INDÍTÁS** → Vision → Move Analysis → Accessibility swipe → wait → re-analyze → next
7. Bubble **SZÜNET** pauses loop; **STOP** removes bubble + stops capture/input

## Safety

| Gate | Behavior |
|------|----------|
| Loop before bubble INDÍTÁS | **Never** — `AutoPlayController` returns null |
| `InputEnableSwitch` | Enabled only on bubble INDÍTÁS; off on SZÜNET/STOP |
| Vision / Move PASS gates | Unchanged (grid≥0.98, board≥0.95, unk≤1, move conf≥0.50) |
| Failsafe STOP | Pauses loop (bubble stays); user can INDÍTÁS again after reset path |
| App cold start | `AnalyzerViewModel(Application)` `@JvmOverloads` kept |

## Components

| Type | Role |
|------|------|
| `AutoPlayController` | IDLE/RUNNING/PAUSED/STOPPED; wraps `InputLoopController` |
| `AutoPlaySession` | Process-wide session + UI snapshot |
| `FloatingBubbleService` | SYSTEM_ALERT_WINDOW bubble + continuous loop coroutine |
| Existing | Vision / MoveAnalysis / AutomaticInputEngine / AccessibilityService |

## What is reused (not rewritten)

VisionPipeline, BoardFinder, Grid, Shape, Special, Decision/Move Analysis, AutomaticInputEngine, InputLoopController, CaptureService, AnalysisFrameGate.
