package com.match3vision.analyzer.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.match3vision.analyzer.input.AutoPlayController
import com.match3vision.analyzer.overlay.AutoPlaySession

@Composable
fun AnalyzerScreen(
    viewModel: AnalyzerViewModel,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit = {},
    onOpenOverlaySettings: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val auto by AutoPlaySession.ui.collectAsStateWithLifecycle()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // During bubble FUT: hide huge debug overlays so MediaProjection sees Match Masters
        // (Activity may briefly resume; compact panel + bubble only).
        if (auto.compactUi || auto.mode == AutoPlayController.Mode.RUNNING) {
            CompactRunningPanel(
                mode = auto.mode,
                statusText = auto.statusText,
                moveCount = auto.moveCount,
                frameGateText = state.frameGateText,
                frameCount = state.frameCount,
                onStop = onStopCapture,
            )
            return@Surface
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Match3 Vision Elemző",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                state.subtitle,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )

            Text(
                "1. INDÍTÁS → engedélyek\n" +
                    "2. Buborék megjelenik\n" +
                    "3. Nyisd meg a Match Masters-t\n" +
                    "4. Buborék: INDÍTÁS → auto húzás\n" +
                    "5. SZÜNET vagy STOP",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )

            Button(
                onClick = {
                    viewModel.onStartRequested()
                    onStartCapture()
                },
                enabled = state.status != AnalyzerStatus.AwaitingPermission,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2E7D32),
                ),
            ) {
                Text(
                    "INDÍTÁS",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            OutlinedButton(
                onClick = onStopCapture,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("STOP — buborék + rögzítés leállítása") }

            PermissionStatusCard(
                overlayReady = auto.overlayReady,
                a11yReady = auto.a11yReady,
                a11ySettingsEnabled = auto.a11ySettingsEnabled,
                captureReady = auto.captureReady,
                bubbleVisible = auto.bubbleVisible,
                mode = auto.mode,
                statusText = auto.statusText,
                moveCount = auto.moveCount,
                diagnosticsText = auto.diagnostics.bubbleLines(),
                onOpenOverlay = onOpenOverlaySettings,
                onOpenA11y = onOpenAccessibilitySettings,
            )

            StatusCard(state)

            Section("ÉLŐ KÉP") {
                FramePreview(state.lastFrameBitmap)
                Text(state.visionStatusText, fontWeight = FontWeight.Medium)
                if (state.visionDebugText.isNotBlank()) {
                    Text(state.visionDebugText, style = MaterialTheme.typography.bodySmall)
                }
            }

            Section("TÁBLA") { BoardGrid(state.boardGridLabels) }

            Section("LEGJOBB LÉPÉSEK") {
                if (state.gateHold) {
                    Text(
                        state.holdMessage ?: "TARTÁS — döntési AI blokkolva",
                        color = Color(0xFFB71C1C),
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    val lines = state.topMovesText.take(5)
                    if (lines.isEmpty()) Text("Nincs lépés") else lines.forEach { Text(it) }
                }
            }

            // Advanced one-step smoke kept for controlled debugging (secondary).
            Section("HALADÓ — EGYLÉPÉSES PRÓBA") {
                Text(
                    "Alapból KI. Maximum 1 automatikus húzás. A buborék autojátszás helyett.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Automatikus húzás engedélyezése")
                    Switch(
                        checked = state.inputEnabled,
                        onCheckedChange = { viewModel.setInputEnabled(it) },
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Egy lépéses próba")
                    Switch(
                        checked = state.smokeEnabled,
                        onCheckedChange = { viewModel.setSmokeEnabled(it) },
                    )
                }
                Text(
                    "fázis=${smokePhaseHu(state.smokePhase)}  húzások=${state.smokeSwipeCount}/1",
                    fontWeight = FontWeight.Medium,
                )
                Text(state.smokeStatusText, style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = { viewModel.runOneStepSmoke() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.smokeRunning && state.lastFrameBitmap != null,
                ) {
                    Text(if (state.smokeRunning) "Futtatás folyamatban…" else "Futtatás")
                }
                OutlinedButton(
                    onClick = { viewModel.resetSmokeSession() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Visszaállítás") }
                Text(
                    state.smokeLogText.ifBlank { "(üres)" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
            }

            Text(
                "Auto: egy fő INDÍTÁS indítja a kört (ha ACCESSIBILITY: CONNECTED). " +
                    "Buborék SZÜNET/STOP; buborék INDÍTÁS folytatáshoz.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun PermissionStatusCard(
    overlayReady: Boolean,
    a11yReady: Boolean,
    a11ySettingsEnabled: Boolean,
    captureReady: Boolean,
    bubbleVisible: Boolean,
    mode: AutoPlayController.Mode,
    statusText: String,
    moveCount: Int,
    diagnosticsText: String,
    onOpenOverlay: () -> Unit,
    onOpenA11y: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("ENGEDÉLYEK / AUTO / DIAG", fontWeight = FontWeight.SemiBold)
            Text("Lebegő buborék: ${if (overlayReady) "OK" else "kell"}")
            Text(
                "ACCESSIBILITY: ${if (a11yReady) "CONNECTED" else "DISCONNECTED"}" +
                    if (a11ySettingsEnabled && !a11yReady) " (settings on)" else "",
                fontWeight = FontWeight.Bold,
                color = if (a11yReady) Color(0xFF2E7D32) else Color(0xFFB71C1C),
            )
            Text("Rögzítés: ${if (captureReady) "ON" else "OFF"}")
            Text("Buborék látszik: ${if (bubbleVisible) "igen" else "nem"}")
            Text(
                "Mód: ${autoModeHu(mode)} · húzások=$moveCount",
                fontWeight = FontWeight.Medium,
            )
            Text(statusText, style = MaterialTheme.typography.bodySmall)
            Text(
                diagnosticsText,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!overlayReady) {
                    OutlinedButton(onClick = onOpenOverlay) { Text("Buborék engedély") }
                }
                if (!a11yReady) {
                    OutlinedButton(onClick = onOpenA11y) { Text("Kisegítő beállítás") }
                }
            }
        }
    }
}

internal fun autoModeHu(mode: AutoPlayController.Mode): String = when (mode) {
    AutoPlayController.Mode.IDLE -> "VÁRAKOZIK"
    AutoPlayController.Mode.RUNNING -> "FUT"
    AutoPlayController.Mode.PAUSED -> "SZÜNET"
    AutoPlayController.Mode.STOPPED -> "LEÁLLÍTVA"
}

/** Hungarian labels for smoke phase enum names (engine enums stay English). */
internal fun smokePhaseHu(phase: String): String = when (phase) {
    "IDLE" -> "VÁRAKOZIK"
    "RUNNING" -> "FUT"
    "AWAITING_FEEDBACK" -> "VISSZAJELZÉSRE VÁR"
    "HOLD" -> "TARTÁS"
    "STOP" -> "LEÁLLÍTVA"
    "SUCCESS_READY_FOR_NEXT" -> "SIKER — KÉSZ"
    else -> phase
}

internal fun statusHu(status: AnalyzerStatus): String = when (status) {
    AnalyzerStatus.Idle -> "Tétlen"
    AnalyzerStatus.AwaitingPermission -> "Engedélyre vár"
    AnalyzerStatus.Capturing -> "Rögzít"
    AnalyzerStatus.Stopped -> "Leállítva"
    AnalyzerStatus.Error -> "Hiba"
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun StatusCard(state: AnalyzerUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Állapot: ${statusHu(state.status)}", fontWeight = FontWeight.SemiBold)
            Text(state.statusMessage)
            Text("Képkocka méret: ${state.frameWidth}×${state.frameHeight}")
            Text("Tartalom ROI: ${state.contentRoiText}")
            Text(
                "Elemző képkocka: ${state.frameGateText}",
                fontWeight = FontWeight.Medium,
                color = if (state.analysisFrameFrozen) Color(0xFF1565C0) else Color(0xFF2E7D32),
            )
            Text("Fogadott képkockák: ${state.frameCount}")
        }
    }
}

@Composable
private fun BoardGrid(labels: List<String>) {
    if (labels.size != 49) {
        Text("Még nincs tábla")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (r in 0 until 7) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (c in 0 until 7) {
                    val label = labels[r * 7 + c]
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                if (label.startsWith("UNK") || label == "?") Color(0xFF424242)
                                else Color(0xFF1B5E20),
                                RoundedCornerShape(4.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label.take(4), color = Color.White, fontSize = 8.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
private fun FramePreview(bitmap: Bitmap?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null && !bitmap.isRecycled) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Utolsó rögzített képkocka",
                modifier = Modifier.fillMaxSize().padding(4.dp),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text("Még nincs képkocka", color = Color.Gray)
        }
    }
}

@Composable
private fun CompactRunningPanel(
    mode: AutoPlayController.Mode,
    statusText: String,
    moveCount: Int,
    frameGateText: String,
    frameCount: Long,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Auto fut — nagy UI elrejtve",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Mód: ${autoModeHu(mode)} · húzások=$moveCount",
            fontWeight = FontWeight.Medium,
        )
        Text(statusText, style = MaterialTheme.typography.bodyMedium)
        Text(
            "Elemző képkocka: $frameGateText",
            color = Color(0xFF2E7D32),
            fontWeight = FontWeight.Medium,
        )
        Text("Fogadott képkockák: $frameCount")
        Text(
            "A buborék kis overlay. Nyisd meg a Match Masters-t a háttérben.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
            Text("STOP — buborék + rögzítés leállítása")
        }
    }
}
