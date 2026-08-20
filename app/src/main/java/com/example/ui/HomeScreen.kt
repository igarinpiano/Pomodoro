package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Vibration
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

    // Dynamic notification permission for Android 13+
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

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

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.statusBars,
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header: Clean App Title
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ポモドーロタイマー",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Main Pomodoro Display Circle (Perfect 1:1 Circle)
            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
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
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Bottom Actions: Settings & Quick Notification Toggles (horizontal 3)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
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

                // Bottom Right: Horizontal 3 Notification Toggles (Sound / Vibrate / Light)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1. Sound (Speaker icon) - left
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
                            imageVector = if (timerState.settings.soundEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                            contentDescription = "サウンド切り替え",
                            tint = if (timerState.settings.soundEnabled) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }

                    // 2. Vibrate - center
                    IconButton(
                        onClick = { viewModel.toggleQuickVibrate(context) },
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                color = if (timerState.settings.vibrateEnabled) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                shape = CircleShape
                            )
                            .testTag("footer_vibrate_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Vibration,
                            contentDescription = "バイブ切り替え",
                            tint = if (timerState.settings.vibrateEnabled) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }

                    // 3. Light (Flash) - right
                    IconButton(
                        onClick = { viewModel.toggleQuickFlash(context) },
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                color = if (timerState.settings.flashEnabled) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                shape = CircleShape
                            )
                            .testTag("footer_flash_button")
                    ) {
                        Icon(
                            imageVector = if (timerState.settings.flashEnabled) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                            contentDescription = "ライト切り替え",
                            tint = if (timerState.settings.flashEnabled) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }

        // Settings Modal Sheet (Themes & Auto Start only - notifications separated to footer)
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
