package com.example.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.model.AppThemeMode
import com.example.model.PomodoroPhase
import com.example.model.PomodoroSettings
import com.example.model.PomodoroTimerState
import com.example.util.FlashlightManager
import com.example.util.PreferencesManager
import com.example.util.SoundManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration.Companion.seconds

class PomodoroService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var timerJob: Job? = null

    private lateinit var soundManager: SoundManager
    private lateinit var flashlightManager: FlashlightManager
    private lateinit var alarmManager: AlarmManager

    private var targetEndElapsedRealtime: Long = 0L

    override fun onCreate() {
        super.onCreate()
        soundManager = SoundManager(this)
        flashlightManager = FlashlightManager(this)
        alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        initSettingsIfNeeded(this)
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        // Call startForegroundCompat if service was started or is active
        if (((_timerState.value.isRunning || _timerState.value.isPaused)) ||
            ((action == ACTION_START || action == ACTION_RESUME || action == ACTION_ALARM_COMPLETE))
        ) {
            startForegroundCompat()
        }

        when (action) {
            ACTION_START -> startTimer()
            ACTION_PAUSE -> pauseTimer()
            ACTION_RESUME -> resumeTimer()
            ACTION_SKIP -> skipSet()
            ACTION_STOP -> stopTimer()
            ACTION_ALARM_COMPLETE -> onPhaseComplete(isSkip = false)
            ACTION_UPDATE_SETTINGS -> {
                val workMins = intent.getIntExtra(EXTRA_WORK_MINS, _timerState.value.settings.workDurationMinutes)
                val breakMins = intent.getIntExtra(EXTRA_BREAK_MINS, _timerState.value.settings.breakDurationMinutes)
                val totalSets = intent.getIntExtra(EXTRA_TOTAL_SETS, _timerState.value.settings.totalSets)
                val soundEnabled = intent.getBooleanExtra(EXTRA_SOUND_ENABLED, _timerState.value.settings.soundEnabled)
                val flashEnabled = intent.getBooleanExtra(EXTRA_FLASH_ENABLED, _timerState.value.settings.flashEnabled)
                val vibrateEnabled = intent.getBooleanExtra(EXTRA_VIBRATE_ENABLED, _timerState.value.settings.vibrateEnabled)
                val themeModeName = intent.getStringExtra(EXTRA_THEME_MODE)
                val themeMode = try {
                    if (themeModeName != null) AppThemeMode.valueOf(themeModeName)
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
        val settings = currentState.settings

        val initialPhase = PomodoroPhase.WORK
        val initialSet = 1

        val durationSecs = settings.workDurationMinutes * 60

        _timerState.value = currentState.copy(
            phase = initialPhase,
            currentSet = initialSet,
            totalSets = settings.totalSets,
            isRunning = true,
            isPaused = false,
            totalDurationSeconds = durationSecs,
            timeLeftSeconds = durationSecs
        )

        targetEndElapsedRealtime = SystemClock.elapsedRealtime() + (durationSecs * 1000L)
        scheduleAlarm(targetEndElapsedRealtime)

        startForegroundCompat()
        runTicker()
    }

    private fun pauseTimer() {
        val remaining = (((targetEndElapsedRealtime - SystemClock.elapsedRealtime()) + 999) / 1000).toInt().coerceAtLeast(0)
        _timerState.value = _timerState.value.copy(
            isRunning = false,
            isPaused = true,
            timeLeftSeconds = remaining,
        )
        cancelAlarm()
        timerJob?.cancel()
        updateNotification()
    }

    private fun resumeTimer() {
        val remainingSecs = _timerState.value.timeLeftSeconds.coerceAtLeast(1)
        targetEndElapsedRealtime = SystemClock.elapsedRealtime() + (remainingSecs * 1000L)
        scheduleAlarm(targetEndElapsedRealtime)

        _timerState.value = _timerState.value.copy(
            isRunning = true,
            isPaused = false,
        )
        startForegroundCompat()
        runTicker()
    }

    private fun skipSet() {
        cancelAlarm()
        timerJob?.cancel()
        onPhaseComplete(isSkip = true)
    }

    private fun stopTimer() {
        cancelAlarm()
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
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
        stopSelf()
    }

    private fun startForegroundCompat() {
        val notification = createNotification(_timerState.value)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (_: Exception) {}
        }
    }

    private fun updateSettings(newSettings: PomodoroSettings) {
        PreferencesManager(this).saveSettings(newSettings)
        val current = _timerState.value
        if (!current.isRunning && !current.isPaused) {
            val workSecs = newSettings.workDurationMinutes * 60
            _timerState.value = current.copy(
                phase = PomodoroPhase.WORK,
                currentSet = 1,
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
            while (isActive && _timerState.value.isRunning && !_timerState.value.isPaused) {
                val remaining = ((targetEndElapsedRealtime - SystemClock.elapsedRealtime() + 999) / 1000).toInt().coerceAtLeast(0)
                _timerState.value = _timerState.value.copy(timeLeftSeconds = remaining)
                updateNotification()

                if (remaining <= 0) {
                    onPhaseComplete(isSkip = false)
                    break
                }
                delay(1.seconds)
            }
        }
    }

    private fun onPhaseComplete(isSkip: Boolean) {
        cancelAlarm()
        timerJob?.cancel()

        val state = _timerState.value
        val settings = state.settings

        // アラート（音、バイブ、フラッシュ）
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
                if (state.currentSet >= settings.totalSets) {
                    // ★最終セットの作業終了：休憩は挟まずに直ちに全セット完了とする！
                    _timerState.value = state.copy(
                        phase = PomodoroPhase.COMPLETED,
                        isRunning = false,
                        isPaused = false,
                        timeLeftSeconds = 0,
                    )
                    updateNotification()
                    if (!isSkip) {
                        sendPushEventNotification(
                            title = "🎉 全セット完了！お疲れ様でした！",
                            message = "すべてのポモドーロセット（全${settings.totalSets}セット）を達成しました！"
                        )
                    }
                } else {
                    // 休憩フェーズへ移行
                    val nextDuration = settings.breakDurationMinutes * 60
                    _timerState.value = state.copy(
                        phase = PomodoroPhase.BREAK,
                        totalDurationSeconds = nextDuration,
                        timeLeftSeconds = nextDuration,
                        isRunning = true,
                        isPaused = false
                    )
                    targetEndElapsedRealtime = SystemClock.elapsedRealtime() + (nextDuration * 1000L)
                    scheduleAlarm(targetEndElapsedRealtime)
                    updateNotification()
                    runTicker()

                    // 休憩開始プッシュ通知
                    if (!isSkip) {
                        sendBreakNotification(
                            currentSet = state.currentSet,
                            totalSets = settings.totalSets,
                            durationMins = settings.breakDurationMinutes
                        )
                    }
                }
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
                    targetEndElapsedRealtime = SystemClock.elapsedRealtime() + (nextDuration * 1000L)
                    scheduleAlarm(targetEndElapsedRealtime)
                    updateNotification()
                    runTicker()
                } else {
                    _timerState.value = state.copy(
                        phase = PomodoroPhase.COMPLETED,
                        isRunning = false,
                        isPaused = false,
                        timeLeftSeconds = 0,
                    )
                    updateNotification()
                    if (!isSkip) {
                        sendPushEventNotification(
                            title = "🎉 全セット完了！お疲れ様でした！",
                            message = "すべてのポモドーロセットを達成しました！"
                        )
                    }
                }
            }
            PomodoroPhase.COMPLETED -> {
                stopTimer()
            }
        }
    }

    private fun scheduleAlarm(triggerAtElapsedMillis: Long) {
        val intent = Intent(this, PomodoroNotificationReceiver::class.java).apply {
            action = ACTION_ALARM_COMPLETE
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            ALARM_REQ_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtElapsedMillis,
                        pendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtElapsedMillis,
                        pendingIntent
                    )
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtElapsedMillis,
                    pendingIntent
                )
            }
        } catch (_: Exception) {
            // もし権限等で例外が発生してもTickerでフォールバック
        }
    }

    private fun cancelAlarm() {
        val intent = Intent(this, PomodoroNotificationReceiver::class.java).apply {
            action = ACTION_ALARM_COMPLETE
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            ALARM_REQ_CODE,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            // 1. ライブ通知チャンネル（低重要度: サイレント＆毎秒プログレス表示）
            val liveChannel = NotificationChannel(
                CHANNEL_LIVE_ID,
                "ポモドーロタイマー 進行状況",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "タイマーの残り時間や進捗状況を表示します"
                setShowBadge(false)
            }
            manager.createNotificationChannel(liveChannel)

            // 2. イベント通知チャンネル（高重要度: Heads-up ポップアップバナー通知）
            val eventChannel = NotificationChannel(
                CHANNEL_EVENT_ID,
                "セット開始・完了通知",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "作業や休憩の開始、完了時に画面上部にプッシュ通知します"
                enableVibration(true)
                setShowBadge(true)
            }
            manager.createNotificationChannel(eventChannel)
        }
    }

    private fun sendBreakNotification(
        currentSet: Int,
        totalSets: Int,
        durationMins: Int
    ) {
        sendPushEventNotification(
            title = "作業終了！休憩時間です [セット $currentSet/$totalSets 完了]",
            message = "少し体を休めましょう（${durationMins}分間）"
        )
    }

    private fun sendPushEventNotification(title: String, message: String) {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_EVENT_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        val manager = NotificationManagerCompat.from(this)
        try {
            manager.notify(EVENT_NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
            // Android 13+ で通知権限未許可の場合のエラーハンドリング
        }
    }

    private fun createNotification(state: PomodoroTimerState): Notification {
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

        val text = when {
            state.phase == PomodoroPhase.COMPLETED -> "すべてのセットを達成しました"
            state.isPaused -> "残り時間: ${state.formattedTime} (一時停止中)"
            else -> "残り時間: ${state.formattedTime}"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_LIVE_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(state.isRunning || state.isPaused)
            .setShowWhen(false)
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
                builder.addAction(
                    android.R.drawable.ic_media_pause,
                    "一時停止",
                    getPendingIntent(ACTION_PAUSE)
                )
            } else if (state.isPaused) {
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
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(_timerState.value))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cancelAlarm()
        timerJob?.cancel()
        serviceScope.cancel()
        soundManager.release()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_LIVE_ID = "pomodoro_timer_live_channel"
        const val CHANNEL_EVENT_ID = "pomodoro_events_channel"
        const val NOTIFICATION_ID = 1001
        const val EVENT_NOTIFICATION_ID = 2001
        private const val ALARM_REQ_CODE = 9999

        const val ACTION_START = "com.example.action.START"
        const val ACTION_PAUSE = "com.example.action.PAUSE"
        const val ACTION_RESUME = "com.example.action.RESUME"
        const val ACTION_SKIP = "com.example.action.SKIP"
        const val ACTION_STOP = "com.example.action.STOP"
        const val ACTION_ALARM_COMPLETE = "com.example.action.ALARM_COMPLETE"
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
                val savedSettings = PreferencesManager(context).loadSettings()
                val workSecs = savedSettings.workDurationMinutes * 60
                _timerState.value = _timerState.value.copy(
                    phase = PomodoroPhase.WORK,
                    currentSet = 1,
                    totalSets = savedSettings.totalSets,
                    totalDurationSeconds = workSecs,
                    timeLeftSeconds = workSecs,
                    settings = savedSettings
                )
            }
        }

        fun updateSettingsDirectly(context: Context, newSettings: PomodoroSettings) {
            PreferencesManager(context).saveSettings(newSettings)
            val current = _timerState.value
            if (!current.isRunning && !current.isPaused) {
                val workSecs = newSettings.workDurationMinutes * 60
                _timerState.value = current.copy(
                    phase = PomodoroPhase.WORK,
                    currentSet = 1,
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
