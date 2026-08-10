package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.PomodoroTimerState

@Composable
fun ControlPanel(
    timerState: PomodoroTimerState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSkip: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Settings Button
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier.testTag("settings_button")
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "設定",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Main Play/Pause Button
        val isRunningOrPaused = timerState.isRunning || timerState.isPaused
        LargeFloatingActionButton(
            onClick = {
                when {
                    !timerState.isRunning && !timerState.isPaused -> onStart()
                    timerState.isRunning -> onPause()
                    timerState.isPaused -> onResume()
                }
            },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = CircleShape,
            modifier = Modifier
                .size(72.dp)
                .testTag("main_play_pause_fab")
        ) {
            Icon(
                imageVector = if (timerState.isRunning) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (timerState.isRunning) "一時停止" else "開始/再開",
                modifier = Modifier.size(36.dp)
            )
        }

        // Controls when active
        if (isRunningOrPaused) {
            // Skip Set Button
            IconButton(
                onClick = onSkip,
                modifier = Modifier.testTag("skip_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = "セットをスキップ",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Stop / Reset Button
            IconButton(
                onClick = onStop,
                modifier = Modifier.testTag("stop_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Stop,
                    contentDescription = "停止・リセット",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        } else {
            // Spacer to balance layout when not running
            Spacer(modifier = Modifier.width(48.dp))
        }
    }
}
