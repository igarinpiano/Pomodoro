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
import com.example.data.AppDatabase
import com.example.model.AppThemeMode
import com.example.model.PomodoroPhase
import com.example.model.PomodoroSettings
import com.example.model.PomodoroTimerState
import com.example.model.StopwatchState
import com.example.model.WorkSession
import com.example.util.FlashlightManager
import com.example.util.PreferencesManager
import com.example.util.SoundManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

class PomodoroService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var timerJob: Job? = null
    private var stopwatchJob: Job? = null
    private val isTransitioning = AtomicBoolean(false)
    private var hasAlertedForCurrentPhase = false

    private lateinit var soundManager: SoundManager
    private lateinit var flashlightManager: FlashlightManager
    private lateinit var alarmManager: AlarmManager

    private var targetEndElapsedRealtime: Long = 0L
    private var stopwatchStartElapsedRealtime: Long = 0L

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

        // Ensure startForeground is called promptly to satisfy Android Foreground Service requirements
        if (action != ACTION_STOP && action != ACTION_STOPWATCH_STOP) {
            startForegroundCompat()
        }

        when (action) {
            ACTION_START -> startTimer()
            ACTION_PAUSE -> pauseTimer()
            ACTION_RESUME -> resumeTimer()
            ACTION_SKIP -> skipSet()
            ACTION_NEXT_PHASE -> onPhaseComplete(isSkip = false)
            ACTION_STOP -> stopTimer()
            ACTION_ALARM_COMPLETE -> {
                val state = _timerState.value
                val isOvertimeMode = (state.phase == PomodoroPhase.WORK && state.settings.continueWorkUntilManual) ||
                                     (state.phase == PomodoroPhase.BREAK && state.settings.continueBreakUntilManual)
                if (isOvertimeMode) {
                    handleTargetReachedInOvertime()
                } else {
                    onPhaseComplete(isSkip = false)
                }
            }
            ACTION_STOPWATCH_START -> startStopwatch()
            ACTION_STOPWATCH_PAUSE -> pauseStopwatch()
            ACTION_STOPWATCH_STOP -> stopStopwatch()
            ACTION_UPDATE_SETTINGS -> {
                val workMins = intent.getIntExtra(EXTRA_WORK_MINS, _timerState.value.settings.workDurationMinutes)
                val breakMins = intent.getIntExtra(EXTRA_BREAK_MINS, _timerState.value.settings.breakDurationMinutes)
                val totalSets = intent.getIntExtra(EXTRA_TOTAL_SETS, _timerState.value.settings.totalSets)
                val soundEnabled = intent.getBooleanExtra(EXTRA_SOUND_ENABLED, _timerState.value.settings.soundEnabled)
                val flashEnabled = intent.getBooleanExtra(EXTRA_FLASH_ENABLED, _timerState.value.settings.flashEnabled)
                val vibrateEnabled = intent.getBooleanExtra(EXTRA_VIBRATE_ENABLED, _timerState.value.settings.vibrateEnabled)
                val autoStartBreak = intent.getBooleanExtra(EXTRA_AUTO_START_BREAK, _timerState.value.settings.autoStartBreak)
                val autoStartWork = intent.getBooleanExtra(EXTRA_AUTO_START_WORK, _timerState.value.settings.autoStartWork)
                val continueWorkUntilManual = intent.getBooleanExtra(EXTRA_CONTINUE_WORK_UNTIL_MANUAL, _timerState.value.settings.continueWorkUntilManual)
                val continueBreakUntilManual = intent.getBooleanExtra(EXTRA_CONTINUE_BREAK_UNTIL_MANUAL, _timerState.value.settings.continueBreakUntilManual)
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
                        autoStartBreak = autoStartBreak,
                        autoStartWork = autoStartWork,
                        continueWorkUntilManual = continueWorkUntilManual,
                        continueBreakUntilManual = continueBreakUntilManual,
                        themeMode = themeMode
                    )
                )
            }
        }

        return START_STICKY
    }

    private fun startTimer() {
        dismissEventNotification()
        hasAlertedForCurrentPhase = false
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
            timeLeftSeconds = durationSecs,
            overtimeSeconds = 0
        )

        targetEndElapsedRealtime = SystemClock.elapsedRealtime() + (durationSecs * 1000L)
        scheduleAlarm(targetEndElapsedRealtime)

        startForegroundCompat()
        runTicker()
    }

    private fun pauseTimer() {
        val state = _timerState.value
        if (state.overtimeSeconds > 0) {
            _timerState.value = state.copy(
                isRunning = false,
                isPaused = true,
                timeLeftSeconds = 0
            )
        } else {
            val remaining = (((targetEndElapsedRealtime - SystemClock.elapsedRealtime()) + 999) / 1000).toInt().coerceAtLeast(0)
            _timerState.value = state.copy(
                isRunning = false,
                isPaused = true,
                timeLeftSeconds = remaining,
            )
        }
        cancelAlarm()
        timerJob?.cancel()
        updateNotification()
    }

    private fun resumeTimer() {
        val state = _timerState.value
        if (state.overtimeSeconds > 0) {
            targetEndElapsedRealtime = SystemClock.elapsedRealtime() - (state.overtimeSeconds * 1000L)
        } else {
            val remainingSecs = state.timeLeftSeconds.coerceAtLeast(1)
            targetEndElapsedRealtime = SystemClock.elapsedRealtime() + (remainingSecs * 1000L)
            scheduleAlarm(targetEndElapsedRealtime)
        }

        _timerState.value = state.copy(
            isRunning = true,
            isPaused = false,
        )
        startForegroundCompat()
        runTicker()
    }

    private fun skipSet() {
        dismissEventNotification()
        cancelAlarm()
        timerJob?.cancel()
        onPhaseComplete(isSkip = true)
    }

    private fun stopTimer() {
        dismissEventNotification()
        cancelAlarm()
        timerJob?.cancel()
        hasAlertedForCurrentPhase = false
        val state = _timerState.value
        val settings = state.settings

        // If stopped during overtime or work session, record the session to Room Database
        val durationSecs = (state.totalDurationSeconds - state.timeLeftSeconds) + state.overtimeSeconds
        if (state.phase == PomodoroPhase.WORK && durationSecs > 0) {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            serviceScope.launch(Dispatchers.IO) {
                AppDatabase.getInstance(this@PomodoroService).workSessionDao().insert(
                    WorkSession(
                        date = today,
                        startTimeMillis = System.currentTimeMillis() - (durationSecs * 1000L),
                        durationSeconds = durationSecs,
                        sessionType = "POMODORO"
                    )
                )
            }
        }

        val workSecs = settings.workDurationMinutes * 60
        _timerState.value = PomodoroTimerState(
            phase = PomodoroPhase.WORK,
            currentSet = 1,
            totalSets = settings.totalSets,
            totalDurationSeconds = workSecs,
            timeLeftSeconds = workSecs,
            overtimeSeconds = 0,
            isRunning = false,
            isPaused = false,
            settings = settings
        )
        if (!_stopwatchState.value.isRunning && !_stopwatchState.value.isPaused) {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {}
            stopSelf()
        } else {
            updateNotification()
        }
    }

    // --- Stopwatch Functions ---
    private fun startStopwatch() {
        dismissEventNotification()
        val current = _stopwatchState.value
        val alreadyElapsed = current.elapsedSeconds
        stopwatchStartElapsedRealtime = SystemClock.elapsedRealtime() - (alreadyElapsed * 1000L)

        _stopwatchState.value = current.copy(
            isRunning = true,
            isPaused = false,
            startTimestampMillis = if (alreadyElapsed == 0) System.currentTimeMillis() else current.startTimestampMillis
        )
        startForegroundCompat()
        runStopwatchTicker()
    }

    private fun pauseStopwatch() {
        dismissEventNotification()
        stopwatchJob?.cancel()
        val elapsed = ((SystemClock.elapsedRealtime() - stopwatchStartElapsedRealtime) / 1000L).toInt().coerceAtLeast(0)
        _stopwatchState.value = _stopwatchState.value.copy(
            isRunning = false,
            isPaused = true,
            elapsedSeconds = elapsed
        )
        updateNotification()
    }

    private fun stopStopwatch() {
        dismissEventNotification()
        stopwatchJob?.cancel()
        val duration = _stopwatchState.value.elapsedSeconds
        if (duration > 0) {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            serviceScope.launch(Dispatchers.IO) {
                AppDatabase.getInstance(this@PomodoroService).workSessionDao().insert(
                    WorkSession(
                        date = today,
                        startTimeMillis = System.currentTimeMillis() - (duration * 1000L),
                        durationSeconds = duration,
                        sessionType = "STOPWATCH"
                    )
                )
            }
        }
        _stopwatchState.value = StopwatchState()

        if (!_timerState.value.isRunning && !_timerState.value.isPaused) {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {}
            stopSelf()
        } else {
            updateNotification()
        }
    }

    private fun runStopwatchTicker() {
        stopwatchJob?.cancel()
        stopwatchJob = serviceScope.launch {
            while (isActive && _stopwatchState.value.isRunning) {
                val elapsed = ((SystemClock.elapsedRealtime() - stopwatchStartElapsedRealtime) / 1000L).toInt().coerceAtLeast(0)
                _stopwatchState.value = _stopwatchState.value.copy(elapsedSeconds = elapsed)
                if (!_timerState.value.isRunning) {
                    updateNotification()
                }
                delay(1.seconds)
            }
        }
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

    private fun handleTargetReachedInOvertime() {
        if (hasAlertedForCurrentPhase) return
        hasAlertedForCurrentPhase = true
        cancelAlarm()

        val state = _timerState.value
        val settings = state.settings

        if (settings.soundEnabled) {
            soundManager.playAlertSound()
        }
        if (settings.vibrateEnabled) {
            soundManager.vibrate()
        }
        if (settings.flashEnabled) {
            flashlightManager.flash(count = 6, delayMs = 180L, scope = serviceScope)
        }

        if (state.phase == PomodoroPhase.WORK) {
            sendPushEventNotification(
                title = "作業終了 [${state.currentSet}/${settings.totalSets}]",
                message = "作業を継続中"
            )
        } else {
            sendPushEventNotification(
                title = "休憩終了 [${state.currentSet}/${settings.totalSets}]",
                message = "休憩を継続中"
            )
        }
    }

    private fun runTicker() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (isActive && _timerState.value.isRunning && !_timerState.value.isPaused) {
                val now = SystemClock.elapsedRealtime()
                val state = _timerState.value

                val isOvertimeMode = (state.phase == PomodoroPhase.WORK && state.settings.continueWorkUntilManual) ||
                                     (state.phase == PomodoroPhase.BREAK && state.settings.continueBreakUntilManual)

                if (isOvertimeMode) {
                    val remaining = ((targetEndElapsedRealtime - now + 999) / 1000).toInt()
                    if (remaining > 0) {
                        _timerState.value = state.copy(timeLeftSeconds = remaining, overtimeSeconds = 0)
                    } else {
                        val overtime = ((now - targetEndElapsedRealtime) / 1000).toInt().coerceAtLeast(0)
                        if (!hasAlertedForCurrentPhase) {
                            handleTargetReachedInOvertime()
                        }
                        _timerState.value = state.copy(timeLeftSeconds = 0, overtimeSeconds = overtime)
                    }
                } else {
                    val remaining = ((targetEndElapsedRealtime - now + 999) / 1000).toInt().coerceAtLeast(0)
                    _timerState.value = state.copy(timeLeftSeconds = remaining, overtimeSeconds = 0)
                    if (remaining <= 0) {
                        onPhaseComplete(isSkip = false)
                        break
                    }
                }
                updateNotification()
                delay(1.seconds)
            }
        }
    }

    private fun onPhaseComplete(isSkip: Boolean) {
        if (!isTransitioning.compareAndSet(false, true)) {
            return
        }

        try {
            cancelAlarm()
            timerJob?.cancel()
            val wasOvertime = hasAlertedForCurrentPhase
            hasAlertedForCurrentPhase = false

            val state = _timerState.value
            val settings = state.settings

            // Save WORK session to local Room Database (even if skipped, preserve elapsed work time)
            if (state.phase == PomodoroPhase.WORK) {
                val durationSecs = (state.totalDurationSeconds - state.timeLeftSeconds) + state.overtimeSeconds
                if (durationSecs > 0) {
                    val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    serviceScope.launch(Dispatchers.IO) {
                        AppDatabase.getInstance(this@PomodoroService).workSessionDao().insert(
                            WorkSession(
                                date = today,
                                startTimeMillis = System.currentTimeMillis() - (durationSecs * 1000L),
                                durationSeconds = durationSecs,
                                sessionType = "POMODORO"
                            )
                        )
                    }
                }
            }

            // Alerts (sound, vibrate, flash) - only if not already alerted when target reached in overtime
            if (!isSkip && !wasOvertime) {
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
                        _timerState.value = state.copy(
                            phase = PomodoroPhase.COMPLETED,
                            isRunning = false,
                            isPaused = false,
                            timeLeftSeconds = 0,
                            overtimeSeconds = 0
                        )
                        updateNotification()
                        if (!isSkip) {
                            sendPushEventNotification(
                                title = "全セット完了",
                                message = "お疲れ様でした"
                            )
                        }
                    } else {
                        val nextDuration = settings.breakDurationMinutes * 60
                        val autoStart = settings.autoStartBreak
                        _timerState.value = state.copy(
                            phase = PomodoroPhase.BREAK,
                            totalDurationSeconds = nextDuration,
                            timeLeftSeconds = nextDuration,
                            overtimeSeconds = 0,
                            isRunning = autoStart,
                            isPaused = !autoStart
                        )

                        if (autoStart) {
                            targetEndElapsedRealtime = SystemClock.elapsedRealtime() + (nextDuration * 1000L)
                            scheduleAlarm(targetEndElapsedRealtime)
                            updateNotification()
                            runTicker()
                        } else {
                            updateNotification()
                        }

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
                        val autoStart = settings.autoStartWork
                        _timerState.value = state.copy(
                            phase = PomodoroPhase.WORK,
                            currentSet = nextSet,
                            totalDurationSeconds = nextDuration,
                            timeLeftSeconds = nextDuration,
                            overtimeSeconds = 0,
                            isRunning = autoStart,
                            isPaused = !autoStart
                        )

                        if (autoStart) {
                            targetEndElapsedRealtime = SystemClock.elapsedRealtime() + (nextDuration * 1000L)
                            scheduleAlarm(targetEndElapsedRealtime)
                            updateNotification()
                            runTicker()
                        } else {
                            updateNotification()
                        }

                        if (!isSkip) {
                            sendWorkNotification(
                                currentSet = nextSet,
                                totalSets = settings.totalSets,
                                durationMins = settings.workDurationMinutes
                            )
                        }
                    } else {
                        _timerState.value = state.copy(
                            phase = PomodoroPhase.COMPLETED,
                            isRunning = false,
                            isPaused = false,
                            timeLeftSeconds = 0,
                            overtimeSeconds = 0
                        )
                        updateNotification()
                        if (!isSkip) {
                            sendPushEventNotification(
                                title = "全セット完了",
                                message = "お疲れ様でした"
                            )
                        }
                    }
                }
                PomodoroPhase.COMPLETED -> {
                    stopTimer()
                }
            }
        } finally {
            isTransitioning.set(false)
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
        } catch (_: Exception) {}
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

            // 1. ライブ通知チャンネル（低重要度: サイレント＆毎秒プログレス/秒数表示）
            val liveChannel = NotificationChannel(
                CHANNEL_LIVE_ID,
                "タイマー 進行状況",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "タイマーの残り時間や秒数を表示します"
                setShowBadge(false)
            }
            manager.createNotificationChannel(liveChannel)

            // 2. イベント通知チャンネル（高重要度: Heads-up ポップアップバナー通知・自動消去）
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
        val mmss = String.format(Locale.US, "%02d:00", durationMins)
        sendPushEventNotification(
            title = "休憩開始 [$currentSet/$totalSets]",
            message = "残り時間 $mmss"
        )
    }

    private fun sendWorkNotification(
        currentSet: Int,
        totalSets: Int,
        durationMins: Int
    ) {
        val mmss = String.format(Locale.US, "%02d:00", durationMins)
        sendPushEventNotification(
            title = "作業開始 [$currentSet/$totalSets]",
            message = "残り時間 $mmss"
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

        // セクション通知：高重要度・端的な表示
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
        } catch (_: SecurityException) {}
    }

    private fun dismissEventNotification() {
        try {
            val manager = NotificationManagerCompat.from(this)
            manager.cancel(EVENT_NOTIFICATION_ID)
        } catch (_: Exception) {}
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

        val stopwatch = _stopwatchState.value
        // If Stopwatch is running or paused while Pomodoro is idle
        if (!state.isRunning && !state.isPaused && (stopwatch.isRunning || stopwatch.isPaused)) {
            val stStatus = if (stopwatch.isPaused) " (一時停止中)" else ""
            val builder = NotificationCompat.Builder(this, CHANNEL_LIVE_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("ストップウォッチ - ${stopwatch.formattedTime}")
                .setContentText("経過時間: ${stopwatch.formattedTime}$stStatus")
                .setSubText(stopwatch.formattedTime)
                .setContentIntent(contentIntent)
                .setOngoing(stopwatch.isRunning)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

            if (stopwatch.isRunning) {
                builder.addAction(
                    android.R.drawable.ic_media_pause,
                    "一時停止",
                    getPendingIntent(ACTION_STOPWATCH_PAUSE)
                )
            } else {
                builder.addAction(
                    android.R.drawable.ic_media_play,
                    "再開",
                    getPendingIntent(ACTION_STOPWATCH_START)
                )
            }
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "停止・記録",
                getPendingIntent(ACTION_STOPWATCH_STOP)
            )
            return builder.build()
        }

        if (state.overtimeSeconds > 0) {
            val status = if (state.isPaused) " (一時停止中)" else ""
            val otLabel = if (state.phase == PomodoroPhase.WORK) "作業継続中" else "休憩継続中"
            val otTitle = "$otLabel [${state.currentSet}/${state.totalSets}]"
            val otText = "超過時間: +${state.formattedOvertime}$status"

            val builder = NotificationCompat.Builder(this, CHANNEL_LIVE_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("$otTitle - +${state.formattedOvertime}")
                .setContentText(otText)
                .setSubText("+${state.formattedOvertime}")
                .setContentIntent(contentIntent)
                .setOngoing(state.isRunning || state.isPaused)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

            val totalSecs = state.totalDurationSeconds.coerceAtLeast(1)
            builder.setProgress(totalSecs, totalSecs, false)

            builder.addAction(
                android.R.drawable.ic_media_next,
                "次のセクションへ",
                getPendingIntent(ACTION_NEXT_PHASE)
            )

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
                android.R.drawable.ic_menu_close_clear_cancel,
                "停止",
                getPendingIntent(ACTION_STOP)
            )

            return builder.build()
        }

        val phaseLabel = if (state.phase == PomodoroPhase.WORK) "作業中" else "休憩中"
        val status = if (state.isPaused) " (一時停止中)" else ""
        val notifTitle = if (state.phase == PomodoroPhase.COMPLETED) "全セット完了" else "$phaseLabel [${state.currentSet}/${state.totalSets}]"
        val notifText = if (state.phase == PomodoroPhase.COMPLETED) "お疲れ様でした" else "残り時間: ${state.formattedTime}$status"

        val builder = NotificationCompat.Builder(this, CHANNEL_LIVE_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(if (state.phase == PomodoroPhase.COMPLETED) notifTitle else "$notifTitle - 残り ${state.formattedTime}")
            .setContentText(notifText)
            .setSubText(if (state.phase == PomodoroPhase.COMPLETED) null else state.formattedTime)
            .setContentIntent(contentIntent)
            .setOngoing(state.isRunning || state.isPaused)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (state.phase != PomodoroPhase.COMPLETED) {
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
        dismissEventNotification()
        cancelAlarm()
        timerJob?.cancel()
        stopwatchJob?.cancel()
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
        const val ACTION_NEXT_PHASE = "com.example.action.NEXT_PHASE"
        const val ACTION_STOP = "com.example.action.STOP"
        const val ACTION_ALARM_COMPLETE = "com.example.action.ALARM_COMPLETE"
        const val ACTION_UPDATE_SETTINGS = "com.example.action.UPDATE_SETTINGS"

        const val ACTION_STOPWATCH_START = "com.example.action.STOPWATCH_START"
        const val ACTION_STOPWATCH_PAUSE = "com.example.action.STOPWATCH_PAUSE"
        const val ACTION_STOPWATCH_STOP = "com.example.action.STOPWATCH_STOP"

        const val EXTRA_WORK_MINS = "extra_work_mins"
        const val EXTRA_BREAK_MINS = "extra_break_mins"
        const val EXTRA_TOTAL_SETS = "extra_total_sets"
        const val EXTRA_SOUND_ENABLED = "extra_sound_enabled"
        const val EXTRA_FLASH_ENABLED = "extra_flash_enabled"
        const val EXTRA_VIBRATE_ENABLED = "extra_vibrate_enabled"
        const val EXTRA_AUTO_START_BREAK = "extra_auto_start_break"
        const val EXTRA_AUTO_START_WORK = "extra_auto_start_work"
        const val EXTRA_CONTINUE_WORK_UNTIL_MANUAL = "extra_continue_work_until_manual"
        const val EXTRA_CONTINUE_BREAK_UNTIL_MANUAL = "extra_continue_break_until_manual"
        const val EXTRA_THEME_MODE = "extra_theme_mode"

        private val _timerState = MutableStateFlow(PomodoroTimerState())
        val timerState: StateFlow<PomodoroTimerState> = _timerState.asStateFlow()

        private val _stopwatchState = MutableStateFlow(StopwatchState())
        val stopwatchState: StateFlow<StopwatchState> = _stopwatchState.asStateFlow()

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
