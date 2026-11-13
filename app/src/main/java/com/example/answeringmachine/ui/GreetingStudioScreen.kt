package com.example.answeringmachine.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.theme.MonoFontFamily

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GreetingStudioScreen(
    uiState: AnsweringUiState,
    onSelectCustomGreeting: (Boolean) -> Unit,
    onTogglePreview: () -> Unit,
    onPlayTestBeep: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onDeleteCustomGreeting: () -> Unit,
    onSelectRingDelay: (Int) -> Unit,
    onSelectMaxDuration: (Int) -> Unit,
    onRequestMicPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    val studio = uiState.studio
    val settings = uiState.settings

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "greeting_source") {
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f),
                            shape = MaterialTheme.shapes.large
                        )
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.RecordVoiceOver,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Outgoing Greeting Audio",
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                        Text(
                            text = "Choose what callers hear right after CallDeck answers. Built-in studio audio uses res/raw/greeting.mp3, or record your own voice greeting.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            FilterChip(
                                selected = !settings.useCustomGreeting,
                                onClick = { onSelectCustomGreeting(false) },
                                label = { Text("Built-in Studio Chime") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.MusicNote,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("chip_builtin_greeting"),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                            FilterChip(
                                selected = settings.useCustomGreeting && uiState.hasCustomGreeting,
                                onClick = { onSelectCustomGreeting(true) },
                                enabled = uiState.hasCustomGreeting,
                                label = {
                                    Text(if (uiState.hasCustomGreeting) "My Voice Greeting" else "Custom (Empty)")
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.Mic,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("chip_custom_greeting"),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (settings.useCustomGreeting && uiState.hasCustomGreeting) {
                                        "ACTIVE: CUSTOM VOICE"
                                    } else {
                                        "ACTIVE: BUILT-IN GREETING.MP3"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                Text(
                                    text = "${formatMillis(studio.preview.positionMillis)} / ${formatMillis(studio.preview.durationMillis)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = MonoFontFamily,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            LinearProgressIndicator(
                                progress = { studio.preview.progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(CircleShape),
                                color = MaterialTheme.colorScheme.secondary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                FilledTonalButton(
                                    onClick = onTogglePreview,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("preview_greeting_button")
                                ) {
                                    Icon(
                                        imageVector = if (studio.preview.isPlaying) {
                                            Icons.Filled.Stop
                                        } else {
                                            Icons.Filled.PlayArrow
                                        },
                                        contentDescription = null
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (studio.preview.isPlaying) "Stop Preview" else "Preview Greeting")
                                }
                                OutlinedButton(
                                    onClick = onPlayTestBeep,
                                    modifier = Modifier.testTag("test_beep_button")
                                ) {
                                    Text("Test Tone")
                                }
                            }
                        }
                    }
                }
            }

            item(key = "record_studio") {
                val recButtonColor by animateColorAsState(
                    targetValue = if (studio.isRecording) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    label = "recButtonColor"
                )

                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = if (studio.isRecording) {
                                MaterialTheme.colorScheme.error.copy(alpha = 0.55f)
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
                            },
                            shape = MaterialTheme.shapes.large
                        )
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Custom Voice Studio",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = if (studio.isRecording) {
                                    "REC ${formatMillis(studio.elapsedMillis)}"
                                } else if (uiState.hasCustomGreeting) {
                                    "SAVED"
                                } else {
                                    "READY"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = MonoFontFamily,
                                color = if (studio.isRecording) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }

                        LiveVuWaveform(
                            amplitude = studio.amplitude,
                            isActive = studio.isRecording,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = {
                                    if (studio.isRecording) {
                                        onStopRecording()
                                    } else if (!uiState.readiness.hasRecordAudio) {
                                        onRequestMicPermission()
                                    } else {
                                        onStartRecording()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = recButtonColor,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("record_greeting_button")
                            ) {
                                Icon(
                                    imageVector = if (studio.isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                                    contentDescription = null
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = when {
                                        studio.isRecording -> "Stop & Save Greeting"
                                        !uiState.readiness.hasRecordAudio -> "Allow Mic to Record"
                                        uiState.hasCustomGreeting -> "Re-record Greeting"
                                        else -> "Record Custom Greeting"
                                    }
                                )
                            }

                            if (uiState.hasCustomGreeting && !studio.isRecording) {
                                OutlinedButton(
                                    onClick = onDeleteCustomGreeting,
                                    modifier = Modifier.testTag("delete_custom_greeting_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.DeleteOutline,
                                        contentDescription = "Delete custom greeting",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item(key = "timing_controls") {
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f),
                            shape = MaterialTheme.shapes.large
                        )
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Timer,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                text = "Answering Timing",
                                style = MaterialTheme.typography.titleLarge
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Ring Delay Before Auto-Answer",
                                style = MaterialTheme.typography.titleSmall
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(0 to "Instant", 3 to "3 sec", 6 to "6 sec", 10 to "10 sec")
                                    .forEach { (sec, label) ->
                                        FilterChip(
                                            selected = settings.ringDelaySeconds == sec,
                                            onClick = { onSelectRingDelay(sec) },
                                            label = { Text(label) },
                                            modifier = Modifier.testTag("ring_delay_$sec")
                                        )
                                    }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Maximum Caller Recording Length",
                                style = MaterialTheme.typography.titleSmall
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(30 to "30 sec", 60 to "60 sec", 120 to "2 min")
                                    .forEach { (sec, label) ->
                                        FilterChip(
                                            selected = settings.maxRecordingSeconds == sec,
                                            onClick = { onSelectMaxDuration(sec) },
                                            label = { Text(label) },
                                            modifier = Modifier.testTag("max_duration_$sec")
                                        )
                                    }
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun LiveVuWaveform(
    amplitude: Float,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val barCount = 28
    val history = remember { mutableStateListOf<Float>().apply { repeat(barCount) { add(0.08f) } } }

    LaunchedEffect(amplitude, isActive) {
        if (isActive) {
            if (history.size >= barCount) history.removeAt(0)
            history.add(amplitude.coerceIn(0.08f, 1f))
        } else {
            for (i in 0 until history.size) {
                history[i] = 0.08f
            }
        }
    }

    val activeColor = MaterialTheme.colorScheme.primary
    val idleColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    val trackBg = MaterialTheme.colorScheme.surfaceContainerLow

    Canvas(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(trackBg)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        val barWidth = (size.width / barCount) * 0.58f
        val gap = (size.width - barWidth * barCount) / (barCount - 1).coerceAtLeast(1)
        val centerY = size.height / 2f

        for (i in 0 until barCount) {
            val level = history.getOrElse(i) { 0.08f }
            val barHeight = (size.height * level).coerceAtLeast(6.dp.toPx())
            val x = i * (barWidth + gap)
            drawRoundRect(
                color = if (isActive) activeColor else idleColor,
                topLeft = Offset(x, centerY - barHeight / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}
