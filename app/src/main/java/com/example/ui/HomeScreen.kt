package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.AppScreen
import com.example.model.TimerMode
import com.example.ui.calendar.CalendarScreen
import com.example.ui.components.SettingsSheet
import com.example.ui.components.StopwatchCircleDisplay
import com.example.ui.components.TimerCircleDisplay
import com.example.ui.statistics.StatisticsScreen
import com.example.viewmodel.PomodoroViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: PomodoroViewModel,
    modifier: Modifier = Modifier
) {
    val currentScreen by viewModel.currentScreen.collectAsStateWithLifecycle()

    when (currentScreen) {
        AppScreen.CALENDAR -> {
            CalendarScreen(
                viewModel = viewModel,
                onBack = { viewModel.navigateTo(AppScreen.MAIN) },
                onNavigateToStats = { viewModel.navigateTo(AppScreen.STATISTICS) },
                modifier = modifier
            )
        }
        AppScreen.STATISTICS -> {
            StatisticsScreen(
                viewModel = viewModel,
                onBack = { viewModel.navigateTo(AppScreen.CALENDAR) },
                modifier = modifier
            )
        }
        AppScreen.MAIN -> {
            MainTimerView(
                viewModel = viewModel,
                onOpenCalendar = { viewModel.navigateTo(AppScreen.CALENDAR) },
                modifier = modifier
            )
        }
    }
}

@Composable
private fun MainTimerView(
    viewModel: PomodoroViewModel,
    onOpenCalendar: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val timerState by viewModel.timerState.collectAsStateWithLifecycle()
    val stopwatchState by viewModel.stopwatchState.collectAsStateWithLifecycle()
    val timerMode by viewModel.timerMode.collectAsStateWithLifecycle()

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

    val isAnyTimerActive = (timerState.isRunning || timerState.isPaused || timerState.overtimeSeconds > 0) ||
                           (stopwatchState.isRunning || stopwatchState.isPaused || stopwatchState.elapsedSeconds > 0)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.statusBars,
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header: Title and Calendar Icon Button on Top-Right
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 4.dp),
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

                IconButton(
                    onClick = onOpenCalendar,
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = CircleShape
                        )
                        .testTag("header_calendar_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.CalendarMonth,
                        contentDescription = "カレンダー・統計",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Center Area: Mode Selector and Timer Circle Display centered together
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Mode Selector Pill: [ ポモドーロタイマー ] [ ストップウォッチ ]
                Row(
                    modifier = Modifier
                        .fillMaxWidth(0.88f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            if (isAnyTimerActive) MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TimerMode.entries.forEach { mode ->
                        val isSelected = timerMode == mode
                        val tabBackground = when {
                            isSelected && isAnyTimerActive -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f)
                            isSelected -> MaterialTheme.colorScheme.primaryContainer
                            else -> Color.Transparent
                        }
                        val textColor = when {
                            isAnyTimerActive && isSelected -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            isAnyTimerActive -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                            isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(18.dp))
                                .background(tabBackground)
                                .clickable(enabled = !isAnyTimerActive) {
                                    viewModel.setTimerMode(mode)
                                }
                                .testTag("mode_tab_${mode.name.lowercase()}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = mode.label,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                ),
                                color = textColor
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Main Display Circle (Either Pomodoro or Stopwatch)
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    if (timerMode == TimerMode.POMODORO) {
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
                            onNextPhase = { viewModel.nextPhase(context) },
                            onUpdateWorkMins = { viewModel.updateInlineSettings(context, workMins = it) },
                            onUpdateBreakMins = { viewModel.updateInlineSettings(context, breakMins = it) },
                            onUpdateTotalSets = { viewModel.updateInlineSettings(context, totalSets = it) }
                        )
                    } else {
                        StopwatchCircleDisplay(
                            stopwatchState = stopwatchState,
                            onStart = { viewModel.startStopwatch(context) },
                            onPause = { viewModel.pauseStopwatch(context) },
                            onStop = { viewModel.stopStopwatch(context) }
                        )
                    }
                }
            }

            // Bottom Actions: Settings on left, Quick Toggles on right
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
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Bottom Right: Horizontal 3 Notification Toggles (Sound / Vibrate / Light)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1. Sound (Speaker icon)
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

                    // 2. Vibrate
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

                    // 3. Light (Flash)
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

        // Settings Modal Sheet
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
