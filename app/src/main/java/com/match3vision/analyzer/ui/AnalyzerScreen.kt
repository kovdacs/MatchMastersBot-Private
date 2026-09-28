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

@Composable
fun AnalyzerScreen(
    viewModel: AnalyzerViewModel,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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

            StatusCard(state)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        viewModel.onStartRequested()
                        onStartCapture()
                    },
                    enabled = state.status != AnalyzerStatus.Capturing &&
                        state.status != AnalyzerStatus.AwaitingPermission,
                    modifier = Modifier.weight(1f),
                ) { Text("Indítás") }
                OutlinedButton(
                    onClick = onStopCapture,
                    enabled = state.status == AnalyzerStatus.Capturing ||
                        state.status == AnalyzerStatus.AwaitingPermission,
                    modifier = Modifier.weight(1f),
                ) { Text("Leállítás") }
            }

            OutlinedButton(
                onClick = { viewModel.analyzeLastFrame() },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.lastFrameBitmap != null,
            ) { Text("Utolsó képkocka elemzése") }

            Section("EGYLÉPÉSES PRÓBA") {
                Text(
                    "Alapból KI. Maximum 1 automatikus húzás. Kell a rendszer Kisegítő lehetőségek szolgáltatása.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "1. Kapcsold be: Automatikus húzás engedélyezése\n" +
                        "2. Kapcsold be: Egy lépéses próba\n" +
                        "3. Indítsd a rögzítést, válts Match Mastersre (tábla látszik)\n" +
                        "4. Gyere vissza ide — a tábla-kép FAGYASZTVA marad\n" +
                        "   (vagy használd az osztott képernyőt / PiP-et)\n" +
                        "5. Nyomd meg: Futtatás (max. 1 húzás)\n" +
                        "6. Új próba előtt: Visszaállítás",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
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
            }

            Section("PRÓBA NAPLÓ") {
                Text(
                    state.smokeLogText.ifBlank { "(üres — kapcsold be a kapcsolókat, majd Futtatás)" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
            }

            Section("ÉLŐ KÉP") {
                FramePreview(state.lastFrameBitmap)
                Text(state.visionStatusText, fontWeight = FontWeight.Medium)
                if (state.visionDebugText.isNotBlank()) {
                    Text(state.visionDebugText, style = MaterialTheme.typography.bodySmall)
                }
            }

            Section("TÁBLA") { BoardGrid(state.boardGridLabels) }
            Section("JÁTÉKÁLLAPOT") { Text(state.gameStateText.ifBlank { "—" }) }

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

            Section("MIÉRT") { Text(state.whyText.ifBlank { "—" }) }
            Section("BIZONYOSSÁG") { Text(state.confidenceText.ifBlank { "—" }) }
            Section("KOCKÁZAT") { Text(state.riskText.ifBlank { "—" }) }
            Section("VÁRHATÓ ÉRTÉK") { Text(state.expectedValueText.ifBlank { "—" }) }

            Text(
                "Bevitel alapból KI. Folyamatos autojátszás KI. Egylépéses próba csak kézi bekapcsolással.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            )
        }
    }
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
