package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.model.PomodoroPhase
import com.example.model.PomodoroSettings
import com.example.model.PomodoroTimerState
import com.example.util.FlashlightManager
import com.example.util.SoundManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PomodoroService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var timerJob: Job? = null

    private lateinit var soundManager: SoundManager
    private lateinit var flashlightManager: FlashlightManager

    override fun onCreate() {
        super.onCreate()
        soundManager = SoundManager(this)
        flashlightManager = FlashlightManager(this)
        initSettingsIfNeeded(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        // Call startForegroundCompat if service was started or is active
        if (_timerState.value.isRunning || _timerState.value.isPaused || action == ACTION_START || action == ACTION_RESUME) {
            startForegroundCompat()
        }

        when (action) {
            ACTION_START -> startTimer()
            ACTION_PAUSE -> pauseTimer()
            ACTION_RESUME -> resumeTimer()
            ACTION_SKIP -> skipSet()
            ACTION_STOP -> stopTimer()
            ACTION_UPDATE_SETTINGS -> {
                val workMins = intent.getIntExtra(EXTRA_WORK_MINS, _timerState.value.settings.workDurationMinutes)
                val breakMins = intent.getIntExtra(EXTRA_BREAK_MINS, _timerState.value.settings.breakDurationMinutes)
                val totalSets = intent.getIntExtra(EXTRA_TOTAL_SETS, _timerState.value.settings.totalSets)
                val soundEnabled = intent.getBooleanExtra(EXTRA_SOUND_ENABLED, _timerState.value.settings.soundEnabled)
                val flashEnabled = intent.getBooleanExtra(EXTRA_FLASH_ENABLED, _timerState.value.settings.flashEnabled)
                val vibrateEnabled = intent.getBooleanExtra(EXTRA_VIBRATE_ENABLED, _timerState.value.settings.vibrateEnabled)
                val themeModeName = intent.getStringExtra(EXTRA_THEME_MODE)
                val themeMode = try {
                    if (themeModeName != null) com.example.model.AppThemeMode.valueOf(themeModeName)
                    else _timerState.value.settings.themeMode
                } catch (_: Exception) {
                    _timerState.value.settings.themeMode
                }

                updateSettings(
                    PomodoroSettings(
                        workDurationMinutes = workMins,
                        breakDurationMinutes = breakMins,
                        totalSets = totalSets,
                        soundEnabled = soundEnabled,
                        flashEnabled = flashEnabled,
                        vibrateEnabled = vibrateEnabled,
                        themeMode = themeMode
                    )
                )
            }
        }

        return START_STICKY
    }

    private fun startTimer() {
        val currentState = _timerState.value
        val durationSecs = if (currentState.phase == PomodoroPhase.WORK) {
            currentState.settings.workDurationMinutes * 60
        } else {
            currentState.settings.breakDurationMinutes * 60
        }

        _timerState.value = currentState.copy(
            isRunning = true,
            isPaused = false,
            totalDurationSeconds = durationSecs,
            timeLeftSeconds = durationSecs
        )

        startForegroundCompat()
        runTicker()
    }

    private fun pauseTimer() {
        _timerState.value = _timerState.value.copy(
            isRunning = false,
            isPaused = true
        )
        timerJob?.cancel()
        updateNotification()
    }

    private fun resumeTimer() {
        _timerState.value = _timerState.value.copy(
            isRunning = true,
            isPaused = false
        )
        startForegroundCompat()
        runTicker()
    }

    private fun skipSet() {
        timerJob?.cancel()
        onPhaseComplete(isSkip = true)
    }

    private fun stopTimer() {
        timerJob?.cancel()
        val settings = _timerState.value.settings
        _timerState.value = PomodoroTimerState(
            phase = PomodoroPhase.WORK,
            currentSet = 1,
            totalSets = settings.totalSets,
            totalDurationSeconds = settings.workDurationMinutes * 60,
            timeLeftSeconds = settings.workDurationMinutes * 60,
            isRunning = false,
            isPaused = false,
            settings = settings
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {}
        stopSelf()
    }

    private fun startForegroundCompat() {
        val notification = createNotification(_timerState.value)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (_: Exception) {}
        }
    }

    private fun updateSettings(newSettings: PomodoroSettings) {
        com.example.util.PreferencesManager(this).saveSettings(newSettings)
        val current = _timerState.value
        if (!current.isRunning && !current.isPaused) {
            val workSecs = newSettings.workDurationMinutes * 60
            _timerState.value = current.copy(
                totalSets = newSettings.totalSets,
                totalDurationSeconds = workSecs,
                timeLeftSeconds = workSecs,
                settings = newSettings
            )
        } else {
            _timerState.value = current.copy(
                totalSets = newSettings.totalSets,
                settings = newSettings
            )
        }
    }

    private fun runTicker() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (isActive && _timerState.value.isRunning && _timerState.value.timeLeftSeconds > 0) {
                delay(1000L)
                if (_timerState.value.isRunning && !_timerState.value.isPaused) {
                    val newTime = _timerState.value.timeLeftSeconds - 1
                    _timerState.value = _timerState.value.copy(timeLeftSeconds = newTime)
                    updateNotification()

                    if (newTime <= 0) {
                        onPhaseComplete(isSkip = false)
                        break
                    }
                }
            }
        }
    }

    private fun onPhaseComplete(isSkip: Boolean) {
        val state = _timerState.value
        val settings = state.settings

        if (!isSkip) {
            if (settings.soundEnabled) {
                soundManager.playAlertSound()
            }
            if (settings.vibrateEnabled) {
                soundManager.vibrate()
            }
            if (settings.flashEnabled) {
                flashlightManager.flash(count = 6, delayMs = 180L, scope = serviceScope)
            }
        }

        when (state.phase) {
            PomodoroPhase.WORK -> {
                val nextDuration = settings.breakDurationMinutes * 60
                _timerState.value = state.copy(
                    phase = PomodoroPhase.BREAK,
                    totalDurationSeconds = nextDuration,
                    timeLeftSeconds = nextDuration,
                    isRunning = true,
                    isPaused = false
                )
                updateNotification()
                runTicker()
            }
            PomodoroPhase.BREAK -> {
                if (state.currentSet < settings.totalSets) {
                    val nextSet = state.currentSet + 1
                    val nextDuration = settings.workDurationMinutes * 60
                    _timerState.value = state.copy(
                        phase = PomodoroPhase.WORK,
                        currentSet = nextSet,
                        totalDurationSeconds = nextDuration,
                        timeLeftSeconds = nextDuration,
                        isRunning = true,
                        isPaused = false
                    )
                    updateNotification()
                    runTicker()
                } else {
                    _timerState.value = state.copy(
                        phase = PomodoroPhase.COMPLETED,
                        isRunning = false,
                        isPaused = false,
                        timeLeftSeconds = 0
                    )
                    updateNotification()
                }
            }
            PomodoroPhase.COMPLETED -> {
                stopTimer()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ポモドーロタイマー ライブ通知",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "タイマーの残り時間や進捗状況をリアルタイム表示します"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(state: PomodoroTimerState): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when (state.phase) {
            PomodoroPhase.WORK -> "作業中 [セット ${state.currentSet}/${state.totalSets}]"
            PomodoroPhase.BREAK -> "休憩中 [セット ${state.currentSet}/${state.totalSets}]"
            PomodoroPhase.COMPLETED -> "🎉 全セット完了！お疲れ様でした！"
        }

        val text = if (state.phase == PomodoroPhase.COMPLETED) {
            "すべてのセットを達成しました"
        } else {
            "残り時間: ${state.formattedTime}"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(state.isRunning || state.isPaused)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (state.phase != PomodoroPhase.COMPLETED) {
            // Live Progress Bar
            val totalSecs = state.totalDurationSeconds.coerceAtLeast(1)
            val elapsedSecs = (totalSecs - state.timeLeftSeconds).coerceAtLeast(0)
            builder.setProgress(totalSecs, elapsedSecs, false)

            if (state.isRunning) {
                // Live Countdown Chronometer for real-time tick in notification bar
                val targetTimeMillis = System.currentTimeMillis() + (state.timeLeftSeconds * 1000L)
                builder.setWhen(targetTimeMillis)
                builder.setUsesChronometer(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    builder.setChronometerCountDown(true)
                }

                builder.addAction(
                    android.R.drawable.ic_media_pause,
                    "一時停止",
                    getPendingIntent(ACTION_PAUSE)
                )
            } else if (state.isPaused) {
                builder.setUsesChronometer(false)
                builder.addAction(
                    android.R.drawable.ic_media_play,
                    "再開",
                    getPendingIntent(ACTION_RESUME)
                )
            }

            builder.addAction(
                android.R.drawable.ic_media_next,
                "スキップ",
                getPendingIntent(ACTION_SKIP)
            )

            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "停止",
                getPendingIntent(ACTION_STOP)
            )
        }

        return builder.build()
    }

    private fun getPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, PomodoroNotificationReceiver::class.java).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(_timerState.value))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        timerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "pomodoro_timer_live_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.action.START"
        const val ACTION_PAUSE = "com.example.action.PAUSE"
        const val ACTION_RESUME = "com.example.action.RESUME"
        const val ACTION_SKIP = "com.example.action.SKIP"
        const val ACTION_STOP = "com.example.action.STOP"
        const val ACTION_UPDATE_SETTINGS = "com.example.action.UPDATE_SETTINGS"

        const val EXTRA_WORK_MINS = "extra_work_mins"
        const val EXTRA_BREAK_MINS = "extra_break_mins"
        const val EXTRA_TOTAL_SETS = "extra_total_sets"
        const val EXTRA_SOUND_ENABLED = "extra_sound_enabled"
        const val EXTRA_FLASH_ENABLED = "extra_flash_enabled"
        const val EXTRA_VIBRATE_ENABLED = "extra_vibrate_enabled"
        const val EXTRA_THEME_MODE = "extra_theme_mode"

        private val _timerState = MutableStateFlow(PomodoroTimerState())
        val timerState: StateFlow<PomodoroTimerState> = _timerState.asStateFlow()
        private var isSettingsInitialized = false

        fun initSettingsIfNeeded(context: Context) {
            if (!isSettingsInitialized) {
                isSettingsInitialized = true
                val savedSettings = com.example.util.PreferencesManager(context).loadSettings()
                val workSecs = savedSettings.workDurationMinutes * 60
                _timerState.value = _timerState.value.copy(
                    totalSets = savedSettings.totalSets,
                    totalDurationSeconds = workSecs,
                    timeLeftSeconds = workSecs,
                    settings = savedSettings
                )
            }
        }

        fun updateSettingsDirectly(context: Context, newSettings: PomodoroSettings) {
            com.example.util.PreferencesManager(context).saveSettings(newSettings)
            val current = _timerState.value
            if (!current.isRunning && !current.isPaused) {
                val workSecs = newSettings.workDurationMinutes * 60
                _timerState.value = current.copy(
                    totalSets = newSettings.totalSets,
                    totalDurationSeconds = workSecs,
                    timeLeftSeconds = workSecs,
                    settings = newSettings
                )
            } else {
                _timerState.value = current.copy(
                    totalSets = newSettings.totalSets,
                    settings = newSettings
                )
            }
        }
    }
}
