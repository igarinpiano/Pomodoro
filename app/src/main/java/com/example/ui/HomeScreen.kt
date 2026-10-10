package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.AppScreen
import com.example.model.PomodoroSettings
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

    LaunchedEffect(Unit) {
        if (!viewModel.hasRequestedNotificationPermission &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
        ) {
            viewModel.hasRequestedNotificationPermission = true
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val isAnyTimerActive = (timerState.isRunning || timerState.isPaused || timerState.overtimeSeconds > 0) ||
                           (stopwatchState.isRunning || stopwatchState.isPaused || stopwatchState.elapsedSeconds > 0)
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // ナビゲーションバー（3ボタン式）や横向き時のカメラ切り欠きにボタンが重ならないようにする
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
        modifier = modifier
    ) { innerPadding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(horizontal = 24.dp, vertical = 10.dp)

        // Main Display Circle (Either Pomodoro or Stopwatch)
        val timerDisplay: @Composable () -> Unit = {
            if (timerMode == TimerMode.POMODORO) {
                TimerCircleDisplay(
                    timerState = timerState,
                    onStart = { viewModel.startTimer(context) },
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
        val modeSelector: @Composable (Modifier) -> Unit = { selectorModifier ->
            ModeSelector(
                timerMode = timerMode,
                isAnyTimerActive = isAnyTimerActive,
                onSelectMode = { viewModel.setTimerMode(it) },
                modifier = selectorModifier
            )
        }
        val bottomActions: @Composable (Modifier) -> Unit = { actionsModifier ->
            BottomActions(
                settings = timerState.settings,
                onOpenSettings = { showSettingsSheet = true },
                onToggleSound = { viewModel.toggleQuickSound(context) },
                onToggleVibrate = { viewModel.toggleQuickVibrate(context) },
                onToggleFlash = { viewModel.toggleQuickFlash(context) },
                modifier = actionsModifier
            )
        }

        if (isLandscape) {
            // 横向き: 左にタイマーの円、右にタイトル・モード切り替え・各種ボタンを並べる
            Row(
                modifier = contentModifier,
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    timerDisplay()
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    MainHeader(onOpenCalendar = onOpenCalendar)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        modeSelector(Modifier.fillMaxWidth())
                    }
                    bottomActions(Modifier)
                }
            }
        } else {
            Column(
                modifier = contentModifier,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                MainHeader(onOpenCalendar = onOpenCalendar)

                // Center Area: Mode Selector and Timer Circle Display centered together
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    modeSelector(Modifier.fillMaxWidth(0.88f))

                    Spacer(modifier = Modifier.height(20.dp))

                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        timerDisplay()
                    }
                }

                bottomActions(Modifier.padding(bottom = 12.dp))
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

// Header: Title and Calendar Icon Button on Top-Right
@Composable
private fun MainHeader(onOpenCalendar: () -> Unit) {
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
}

// Mode Selector Pill: [ ポモドーロタイマー ] [ ストップウォッチ ]
@Composable
private fun ModeSelector(
    timerMode: TimerMode,
    isAnyTimerActive: Boolean,
    onSelectMode: (TimerMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
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
                        onSelectMode(mode)
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
}

// Bottom Actions: Settings on left, Quick Toggles (Sound / Vibrate / Light) on right
@Composable
private fun BottomActions(
    settings: PomodoroSettings,
    onOpenSettings: () -> Unit,
    onToggleSound: () -> Unit,
    onToggleVibrate: () -> Unit,
    onToggleFlash: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onOpenSettings,
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

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QuickToggleButton(
                enabled = settings.soundEnabled,
                icon = if (settings.soundEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                contentDescription = "サウンド切り替え",
                testTag = "footer_sound_button",
                onClick = onToggleSound
            )
            QuickToggleButton(
                enabled = settings.vibrateEnabled,
                icon = Icons.Filled.Vibration,
                contentDescription = "バイブ切り替え",
                testTag = "footer_vibrate_button",
                onClick = onToggleVibrate
            )
            QuickToggleButton(
                enabled = settings.flashEnabled,
                icon = if (settings.flashEnabled) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                contentDescription = "ライト切り替え",
                testTag = "footer_flash_button",
                onClick = onToggleFlash
            )
        }
    }
}

@Composable
private fun QuickToggleButton(
    enabled: Boolean,
    icon: ImageVector,
    contentDescription: String,
    testTag: String,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .background(
                color = if (enabled) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                shape = CircleShape
            )
            .testTag(testTag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}
