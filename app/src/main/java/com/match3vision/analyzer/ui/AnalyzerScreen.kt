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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
            Text("Match3 Vision Analyzer", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(state.subtitle, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)

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
                ) { Text("Start") }
                OutlinedButton(
                    onClick = onStopCapture,
                    enabled = state.status == AnalyzerStatus.Capturing ||
                        state.status == AnalyzerStatus.AwaitingPermission,
                    modifier = Modifier.weight(1f),
                ) { Text("Stop") }
            }

            OutlinedButton(
                onClick = { viewModel.analyzeLastFrame() },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.lastFrameBitmap != null,
            ) { Text("Analyze last frame") }

            Section("LIVE FRAME") {
                FramePreview(state.lastFrameBitmap)
                Text(state.visionStatusText, fontWeight = FontWeight.Medium)
                if (state.visionDebugText.isNotBlank()) {
                    Text(state.visionDebugText, style = MaterialTheme.typography.bodySmall)
                }
            }

            Section("BOARD") { BoardGrid(state.boardGridLabels) }
            Section("GAME STATE") { Text(state.gameStateText.ifBlank { "—" }) }

            Section("TOP MOVES") {
                if (state.gateHold) {
                    Text(
                        state.holdMessage ?: "HOLD — Decision AI blocked",
                        color = Color(0xFFB71C1C),
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    val lines = state.topMovesText.take(5)
                    if (lines.isEmpty()) Text("No moves") else lines.forEach { Text(it) }
                }
            }

            Section("WHY") { Text(state.whyText.ifBlank { "—" }) }
            Section("CONFIDENCE") { Text(state.confidenceText.ifBlank { "—" }) }
            Section("RISK") { Text(state.riskText.ifBlank { "—" }) }
            Section("EXPECTED VALUE") { Text(state.expectedValueText.ifBlank { "—" }) }

            Text(
                "Analyzer only — no AccessibilityService, no touch injection, no game automation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            )
        }
    }
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
            Text("Status: ${state.status.name}", fontWeight = FontWeight.SemiBold)
            Text(state.statusMessage)
            Text("Frame size: ${state.frameWidth}×${state.frameHeight}")
            Text("Content ROI: ${state.contentRoiText}")
            Text("Frames received: ${state.frameCount}")
        }
    }
}

@Composable
private fun BoardGrid(labels: List<String>) {
    if (labels.size != 49) {
        Text("No board labels yet")
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
                contentDescription = "Last captured frame",
                modifier = Modifier.fillMaxSize().padding(4.dp),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text("No frame yet", color = Color.Gray)
        }
    }
}
