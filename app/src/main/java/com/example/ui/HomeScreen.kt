package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.SettingsSheet
import com.example.ui.components.TimerCircleDisplay
import com.example.viewmodel.PomodoroViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: PomodoroViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val timerState by viewModel.timerState.collectAsStateWithLifecycle()

    var showSettingsSheet by remember { mutableStateOf(false) }

    // Dynamic camera permission for LED flash notification
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header: Clean App Title without timestamp
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ポモドーロタイマー",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Main Pomodoro Display Circle (Screen 1 & 2 UI)
            TimerCircleDisplay(
                timerState = timerState,
                onStart = {
                    if (timerState.settings.flashEnabled && !hasCameraPermission) {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                    viewModel.startTimer(context)
                },
                onPause = { viewModel.pauseTimer(context) },
                onResume = { viewModel.resumeTimer(context) },
                onSkip = { viewModel.skipSet(context) },
                onStop = { viewModel.stopTimer(context) },
                onUpdateWorkMins = { viewModel.updateInlineSettings(context, workMins = it) },
                onUpdateBreakMins = { viewModel.updateInlineSettings(context, breakMins = it) },
                onUpdateTotalSets = { viewModel.updateInlineSettings(context, totalSets = it) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Bottom Actions: Settings & Quick Sound Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Bottom Left: Settings Button
                IconButton(
                    onClick = { showSettingsSheet = true },
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = CircleShape
                        )
                        .testTag("footer_settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "設定",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Bottom Right: Quick Sound Toggle Button
                IconButton(
                    onClick = { viewModel.toggleQuickSound(context) },
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            color = if (timerState.settings.soundEnabled) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            shape = CircleShape
                        )
                        .testTag("footer_sound_button")
                ) {
                    Icon(
                        imageVector = if (timerState.settings.soundEnabled) Icons.Filled.MusicNote else Icons.Filled.MusicOff,
                        contentDescription = "サウンド切り替え",
                        tint = if (timerState.settings.soundEnabled) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }

        // Settings Modal Sheet (Themes & Notification Alerts)
        if (showSettingsSheet) {
            SettingsSheet(
                settings = timerState.settings,
                onSaveSettings = { newSettings ->
                    viewModel.updateSettings(context, newSettings)
                },
                onDismissRequest = { showSettingsSheet = false }
            )
        }
    }
}
