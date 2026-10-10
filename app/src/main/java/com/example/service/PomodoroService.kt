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
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.data.AppDatabase
import com.example.model.PomodoroPhase
import com.example.model.PomodoroSettings
import com.example.model.PomodoroTimerState
import com.example.model.StopwatchState
import com.example.model.WorkSession
import com.example.model.splitByDay
import com.example.util.FlashlightManager
import com.example.util.PreferencesManager
import com.example.util.SoundManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

class PomodoroService : Service() {

    // 状態の更新はすべてメインスレッドで行い、ティッカーと操作（一時停止・設定変更など）の競合を防ぐ
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var timerJob: Job? = null
    private var stopwatchJob: Job? = null
    private var hasAlertedForCurrentPhase = false

    private lateinit var soundManager: SoundManager
    private lateinit var flashlightManager: FlashlightManager
    private lateinit var alarmManager: AlarmManager

    private var targetEndElapsedRealtime: Long = 0L
    private var stopwatchStartElapsedRealtime: Long = 0L

    // 通知は毎秒作り直すため、PendingIntent は使い回す
    private val contentIntent: PendingIntent by lazy {
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
    private val actionIntents = mutableMapOf<String, PendingIntent>()

    override fun onCreate() {
        super.onCreate()
        soundManager = SoundManager(this)
        flashlightManager = FlashlightManager(this)
        alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        initSettingsIfNeeded(this)
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() で起動された場合、停止系のアクションでも startForeground() を
        // 呼ばないとクラッシュするため、必ず最初に呼ぶ
        startForegroundCompat()

        when (intent?.action) {
            ACTION_START -> startTimer()
            ACTION_PAUSE -> pauseTimer()
            ACTION_RESUME -> resumeTimer()
            ACTION_SKIP -> advancePhase(isSkip = true)
            ACTION_NEXT_PHASE -> advancePhase(isSkip = false)
            ACTION_STOP -> stopTimer()
            ACTION_ALARM_COMPLETE -> onAlarmFired()
            ACTION_STOPWATCH_START -> startStopwatch()
            ACTION_STOPWATCH_PAUSE -> pauseStopwatch()
            ACTION_STOPWATCH_STOP -> stopStopwatch()
        }

        settleForegroundState()
        return START_NOT_STICKY
    }

    /** 計測中のものがなければフォアグラウンドをやめ、常駐通知を消してサービスを終える */
    private fun settleForegroundState() {
        val timer = _timerState.value
        val stopwatch = _stopwatchState.value
        if (timer.isActive || stopwatch.isRunning || stopwatch.isPaused) return

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)

        // 完了時のアラート音を最後まで鳴らすため、完了画面が閉じられるまではサービス自体は残す
        if (timer.phase != PomodoroPhase.COMPLETED) {
            stopSelf()
        }
    }

    private fun startTimer() {
        val currentState = _timerState.value
        if (currentState.isActive) return
        // 画面側でも防いでいるが、ストップウォッチと同時には計測しない
        if (_stopwatchState.value.let { it.isRunning || it.isPaused }) return

        dismissEventNotification()
        hasAlertedForCurrentPhase = false
        val settings = currentState.settings
        val durationSecs = settings.workDurationMinutes * 60

        _timerState.value = currentState.copy(
            phase = PomodoroPhase.WORK,
            currentSet = 1,
            totalSets = settings.totalSets,
            isRunning = true,
            isPaused = false,
            totalDurationSeconds = durationSecs,
            timeLeftSeconds = durationSecs,
            overtimeSeconds = 0
        )

        targetEndElapsedRealtime = SystemClock.elapsedRealtime() + (durationSecs * 1000L)
        scheduleAlarm(targetEndElapsedRealtime)

        updateNotification()
        runTicker()
    }

    private fun pauseTimer() {
        val state = _timerState.value
        if (!state.isRunning) return

        timerJob?.cancel()
        cancelAlarm()
        _timerState.value = withCurrentTime(state).copy(isRunning = false, isPaused = true)
        updateNotification()
    }

    private fun resumeTimer() {
        val state = _timerState.value
        if (!state.isPaused) return

        if (state.overtimeSeconds > 0) {
            hasAlertedForCurrentPhase = true
            targetEndElapsedRealtime = SystemClock.elapsedRealtime() - (state.overtimeSeconds * 1000L)
        } else {
            val remainingSecs = state.timeLeftSeconds.coerceAtLeast(1)
            targetEndElapsedRealtime = SystemClock.elapsedRealtime() + (remainingSecs * 1000L)
            scheduleAlarm(targetEndElapsedRealtime)
        }

        _timerState.value = state.copy(isRunning = true, isPaused = false)
        updateNotification()
        runTicker()
    }

    private fun advancePhase(isSkip: Boolean) {
        if (!_timerState.value.isActive) return
        if (isSkip) dismissEventNotification()
        onPhaseComplete(isSkip)
    }

    private fun stopTimer() {
        dismissEventNotification()
        cancelAlarm()
        timerJob?.cancel()
        hasAlertedForCurrentPhase = false
        val state = _timerState.value

        // 作業の途中で停止した場合も、そこまでの作業時間を記録する
        if (state.phase == PomodoroPhase.WORK) {
            recordSession(elapsedSecondsInPhase(state), SESSION_TYPE_POMODORO)
        }

        _timerState.value = idleState(state.settings)
        updateNotification()
    }

    /** 端末のスリープでティッカーが止まっていても正しくなるよう、残り・超過秒を現在時刻から求める */
    private fun withCurrentTime(state: PomodoroTimerState): PomodoroTimerState {
        val remainingMs = targetEndElapsedRealtime - SystemClock.elapsedRealtime()
        return if (remainingMs > 0) {
            state.copy(timeLeftSeconds = ((remainingMs + 999) / 1000).toInt(), overtimeSeconds = 0)
        } else {
            val overtime = if (countsOvertime(state)) (-remainingMs / 1000).toInt() else 0
            state.copy(timeLeftSeconds = 0, overtimeSeconds = overtime)
        }
    }

    /** 現在のフェーズで実際に経過した秒数（超過分を含む） */
    private fun elapsedSecondsInPhase(state: PomodoroTimerState): Int {
        if (!state.isRunning) {
            return (state.totalDurationSeconds - state.timeLeftSeconds).coerceAtLeast(0) + state.overtimeSeconds
        }
        val remainingMs = targetEndElapsedRealtime - SystemClock.elapsedRealtime()
        val elapsed = ((state.totalDurationSeconds * 1000L - remainingMs) / 1000L).toInt().coerceAtLeast(0)
        return if (countsOvertime(state)) elapsed else elapsed.coerceAtMost(state.totalDurationSeconds)
    }

    private fun isOvertimeMode(state: PomodoroTimerState): Boolean =
        (state.phase == PomodoroPhase.WORK && state.settings.continueWorkUntilManual) ||
            (state.phase == PomodoroPhase.BREAK && state.settings.continueBreakUntilManual)

    // 超過計測中に継続設定をオフにされても、それまでの超過分は捨てない
    private fun countsOvertime(state: PomodoroTimerState): Boolean =
        isOvertimeMode(state) || state.overtimeSeconds > 0

    private fun recordSession(durationSecs: Int, sessionType: String) {
        // 日付をまたいだ計測は、0時で分けてそれぞれの日に記録する
        splitByDay(System.currentTimeMillis(), durationSecs).forEach { slice ->
            sessionRecorder(
                applicationContext,
                WorkSession(
                    date = slice.date,
                    startTimeMillis = slice.startTimeMillis,
                    durationSeconds = slice.durationSeconds,
                    sessionType = sessionType
                )
            )
        }
    }

    // --- Stopwatch Functions ---
    private fun startStopwatch() {
        val current = _stopwatchState.value
        if (current.isRunning || _timerState.value.isActive) return

        dismissEventNotification()
        val alreadyElapsed = current.elapsedSeconds
        stopwatchStartElapsedRealtime = SystemClock.elapsedRealtime() - (alreadyElapsed * 1000L)

        _stopwatchState.value = current.copy(
            isRunning = true,
            isPaused = false,
            startTimestampMillis = if (alreadyElapsed == 0) System.currentTimeMillis() else current.startTimestampMillis
        )
        updateNotification()
        runStopwatchTicker()
    }

    private fun pauseStopwatch() {
        val current = _stopwatchState.value
        if (!current.isRunning) return

        dismissEventNotification()
        stopwatchJob?.cancel()
        _stopwatchState.value = current.copy(
            isRunning = false,
            isPaused = true,
            elapsedSeconds = stopwatchElapsedSeconds()
        )
        updateNotification()
    }

    private fun stopStopwatch() {
        val current = _stopwatchState.value
        if (!current.isRunning && !current.isPaused) return

        dismissEventNotification()
        stopwatchJob?.cancel()
        val duration = if (current.isRunning) stopwatchElapsedSeconds() else current.elapsedSeconds
        recordSession(duration, SESSION_TYPE_STOPWATCH)
        _stopwatchState.value = StopwatchState()
        updateNotification()
    }

    private fun stopwatchElapsedSeconds(): Int =
        ((SystemClock.elapsedRealtime() - stopwatchStartElapsedRealtime) / 1000L).toInt().coerceAtLeast(0)

    private fun runStopwatchTicker() {
        stopwatchJob?.cancel()
        stopwatchJob = serviceScope.launch {
            while (isActive && _stopwatchState.value.isRunning) {
                _stopwatchState.value = _stopwatchState.value.copy(elapsedSeconds = stopwatchElapsedSeconds())
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

    private fun playAlerts(settings: PomodoroSettings) {
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

    private fun handleTargetReachedInOvertime() {
        if (hasAlertedForCurrentPhase) return
        hasAlertedForCurrentPhase = true
        cancelAlarm()

        val state = _timerState.value
        playAlerts(state.settings)

        val setLabel = "[${state.currentSet}/${state.settings.totalSets}]"
        if (state.phase == PomodoroPhase.WORK) {
            sendPushEventNotification(title = "作業終了 $setLabel", message = "作業継続中")
        } else {
            sendPushEventNotification(title = "休憩終了 $setLabel", message = "休憩継続中")
        }
    }

    private fun onAlarmFired() {
        val state = _timerState.value
        // ティッカーが先にフェーズを進めた後で遅れて届いたアラームは無視する
        // （無視しないと、始まったばかりの次のフェーズまで終了扱いになる）
        if (!state.isRunning || SystemClock.elapsedRealtime() < targetEndElapsedRealtime) return

        if (isOvertimeMode(state)) {
            handleTargetReachedInOvertime()
            _timerState.value = withCurrentTime(state)
            updateNotification()
        } else {
            onPhaseComplete(isSkip = false)
        }
    }

    private fun runTicker() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (isActive && _timerState.value.isRunning) {
                val state = _timerState.value
                val reachedTarget = SystemClock.elapsedRealtime() >= targetEndElapsedRealtime

                if (reachedTarget && !isOvertimeMode(state)) {
                    onPhaseComplete(isSkip = false)
                    break
                }
                if (reachedTarget) {
                    handleTargetReachedInOvertime()
                }
                _timerState.value = withCurrentTime(state)
                updateNotification()
                delay(1.seconds)
            }
        }
    }

    private fun onPhaseComplete(isSkip: Boolean) {
        cancelAlarm()
        timerJob?.cancel()
        val wasOvertime = hasAlertedForCurrentPhase
        hasAlertedForCurrentPhase = false

        val state = _timerState.value
        val settings = state.settings

        // スキップした場合も、そこまでの作業時間を記録する
        if (state.phase == PomodoroPhase.WORK) {
            recordSession(elapsedSecondsInPhase(state), SESSION_TYPE_POMODORO)
        }

        // 超過計測に入った時点で通知済みなら、ここでは鳴らさない
        if (!isSkip && !wasOvertime) {
            playAlerts(settings)
        }

        when (state.phase) {
            PomodoroPhase.WORK -> {
                if (state.currentSet >= settings.totalSets) {
                    completeAllSets(state, notify = !isSkip)
                } else {
                    enterPhase(
                        state = state,
                        phase = PomodoroPhase.BREAK,
                        currentSet = state.currentSet,
                        durationMins = settings.breakDurationMinutes,
                        autoStart = settings.autoStartBreak
                    )
                    if (!isSkip) {
                        val setLabel = "[${state.currentSet}/${settings.totalSets}]"
                        if (settings.autoStartBreak) {
                            sendPhaseStartNotification("休憩開始 $setLabel", settings.breakDurationMinutes)
                        } else {
                            // 自動開始しない設定では、まだ始まっていない休憩を「開始」と通知しない
                            sendPushEventNotification(title = "作業終了 $setLabel", message = "休憩は開始待ちです")
                        }
                    }
                }
            }
            PomodoroPhase.BREAK -> {
                if (state.currentSet < settings.totalSets) {
                    val nextSet = state.currentSet + 1
                    enterPhase(
                        state = state,
                        phase = PomodoroPhase.WORK,
                        currentSet = nextSet,
                        durationMins = settings.workDurationMinutes,
                        autoStart = settings.autoStartWork
                    )
                    if (!isSkip) {
                        if (settings.autoStartWork) {
                            sendPhaseStartNotification("作業開始 [$nextSet/${settings.totalSets}]", settings.workDurationMinutes)
                        } else {
                            sendPushEventNotification(
                                title = "休憩終了 [${state.currentSet}/${settings.totalSets}]",
                                message = "作業は開始待ちです"
                            )
                        }
                    }
                } else {
                    completeAllSets(state, notify = !isSkip)
                }
            }
            PomodoroPhase.COMPLETED -> stopTimer()
        }
    }

    private fun enterPhase(
        state: PomodoroTimerState,
        phase: PomodoroPhase,
        currentSet: Int,
        durationMins: Int,
        autoStart: Boolean
    ) {
        val durationSecs = durationMins * 60
        _timerState.value = state.copy(
            phase = phase,
            currentSet = currentSet,
            totalDurationSeconds = durationSecs,
            timeLeftSeconds = durationSecs,
            overtimeSeconds = 0,
            isRunning = autoStart,
            isPaused = !autoStart
        )

        if (autoStart) {
            targetEndElapsedRealtime = SystemClock.elapsedRealtime() + (durationSecs * 1000L)
            scheduleAlarm(targetEndElapsedRealtime)
            runTicker()
        }
        updateNotification()
    }

    private fun completeAllSets(state: PomodoroTimerState, notify: Boolean) {
        _timerState.value = state.copy(
            phase = PomodoroPhase.COMPLETED,
            isRunning = false,
            isPaused = false,
            timeLeftSeconds = 0,
            overtimeSeconds = 0
        )
        // 完了後は常駐通知を残さず、完了のプッシュ通知だけにする（同じ内容の通知が2件並ぶのを防ぐ）
        settleForegroundState()
        if (notify) {
            sendPushEventNotification(title = "全セット完了", message = "お疲れ様でした")
        }
    }

    private fun alarmPendingIntent(flags: Int): PendingIntent? {
        val intent = Intent(this, PomodoroNotificationReceiver::class.java).apply {
            action = ACTION_ALARM_COMPLETE
        }
        return PendingIntent.getBroadcast(this, ALARM_REQ_CODE, intent, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun scheduleAlarm(triggerAtElapsedMillis: Long) {
        val pendingIntent = alarmPendingIntent(PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val canScheduleExact =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

        try {
            if (canScheduleExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtElapsedMillis,
                    pendingIntent
                )
                return
            }
        } catch (_: SecurityException) {}

        try {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtElapsedMillis,
                pendingIntent
            )
        } catch (_: Exception) {}
    }

    private fun cancelAlarm() {
        val pendingIntent = alarmPendingIntent(PendingIntent.FLAG_NO_CREATE) ?: return
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            // 1. ライブ通知チャンネル（低重要度: サイレントで残り時間を毎秒更新）
            val liveChannel = NotificationChannel(
                CHANNEL_LIVE_ID,
                "タイマーの進行状況",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "残り時間や経過時間を表示します"
                setShowBadge(false)
            }
            manager.createNotificationChannel(liveChannel)

            // 2. イベント通知チャンネル（高重要度: Heads-up ポップアップバナー通知・自動消去）
            // 音とバイブはアプリ内のサウンド／バイブ設定に従ってアプリ側で鳴らすため、チャンネル自体は無音にする。
            // 作成済みチャンネルの音設定は変更できないので、旧チャンネルは削除して作り直す
            manager.deleteNotificationChannel(LEGACY_CHANNEL_EVENT_ID)
            val eventChannel = NotificationChannel(
                CHANNEL_EVENT_ID,
                "作業・休憩の切り替え",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "作業や休憩の開始・終了を通知します"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(true)
            }
            manager.createNotificationChannel(eventChannel)
        }
    }

    private fun sendPhaseStartNotification(title: String, durationMins: Int) {
        val mmss = String.format(Locale.US, "%02d:00", durationMins)
        sendPushEventNotification(title = title, message = "残り $mmss")
    }

    private fun sendPushEventNotification(title: String, message: String) {
        val builder = NotificationCompat.Builder(this, CHANNEL_EVENT_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Android 7 以前は音かバイブの指定がないとポップアップ表示されないため、空のパターンを指定する
            .setVibrate(longArrayOf(0L))
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

    private fun liveNotificationBuilder(title: String, text: String?, ongoing: Boolean): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_LIVE_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(ongoing)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

    private fun createNotification(state: PomodoroTimerState): Notification {
        val stopwatch = _stopwatchState.value
        // If Stopwatch is running or paused while Pomodoro is idle
        if (!state.isActive && (stopwatch.isRunning || stopwatch.isPaused)) {
            val builder = liveNotificationBuilder(
                title = "ストップウォッチ - ${stopwatch.formattedTime}",
                text = if (stopwatch.isPaused) PAUSED_LABEL else null,
                ongoing = stopwatch.isRunning
            )
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
                "停止して記録",
                getPendingIntent(ACTION_STOPWATCH_STOP)
            )
            return builder.build()
        }

        if (state.phase == PomodoroPhase.COMPLETED) {
            return liveNotificationBuilder(
                title = PomodoroPhase.COMPLETED.label,
                text = "お疲れ様でした",
                ongoing = false
            ).setCategory(NotificationCompat.CATEGORY_PROGRESS).build()
        }

        val isOvertime = state.overtimeSeconds > 0
        val setLabel = "[${state.currentSet}/${state.totalSets}]"
        val title = if (isOvertime) {
            val label = if (state.phase == PomodoroPhase.WORK) "作業継続中" else "休憩継続中"
            "$label $setLabel - 超過 ${state.formattedOvertime}"
        } else {
            "${state.phase.label} $setLabel - 残り ${state.formattedTime}"
        }

        val builder = liveNotificationBuilder(
            title = title,
            text = if (state.isPaused) PAUSED_LABEL else null,
            ongoing = state.isActive
        ).setCategory(NotificationCompat.CATEGORY_PROGRESS)

        val totalSecs = state.totalDurationSeconds.coerceAtLeast(1)
        val elapsedSecs = if (isOvertime) totalSecs else (totalSecs - state.timeLeftSeconds).coerceAtLeast(0)
        builder.setProgress(totalSecs, elapsedSecs, false)

        if (isOvertime) {
            builder.addAction(
                android.R.drawable.ic_media_next,
                "次へ",
                getPendingIntent(ACTION_NEXT_PHASE)
            )
        }

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

        if (!isOvertime) {
            builder.addAction(
                android.R.drawable.ic_media_next,
                "スキップ",
                getPendingIntent(ACTION_SKIP)
            )
        }

        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "停止",
            getPendingIntent(ACTION_STOP)
        )

        return builder.build()
    }

    private fun getPendingIntent(action: String): PendingIntent = actionIntents.getOrPut(action) {
        val intent = Intent(this, PomodoroNotificationReceiver::class.java).apply {
            this.action = action
        }
        PendingIntent.getBroadcast(
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
        // 計測中に破棄された場合は一時停止として残し、画面だけが「計測中」のまま固まるのを防ぐ
        val timer = _timerState.value
        if (timer.isRunning) {
            _timerState.value = withCurrentTime(timer).copy(isRunning = false, isPaused = true)
        }
        val stopwatch = _stopwatchState.value
        if (stopwatch.isRunning) {
            _stopwatchState.value = stopwatch.copy(
                isRunning = false,
                isPaused = true,
                elapsedSeconds = stopwatchElapsedSeconds()
            )
        }

        cancelAlarm()
        serviceScope.cancel()
        soundManager.release()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_LIVE_ID = "pomodoro_timer_live_channel"
        const val CHANNEL_EVENT_ID = "pomodoro_events_silent_channel"
        private const val LEGACY_CHANNEL_EVENT_ID = "pomodoro_events_channel"
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

        const val ACTION_STOPWATCH_START = "com.example.action.STOPWATCH_START"
        const val ACTION_STOPWATCH_PAUSE = "com.example.action.STOPWATCH_PAUSE"
        const val ACTION_STOPWATCH_STOP = "com.example.action.STOPWATCH_STOP"

        const val SESSION_TYPE_POMODORO = "POMODORO"
        const val SESSION_TYPE_STOPWATCH = "STOPWATCH"

        private const val PAUSED_LABEL = "一時停止中"

        private val _timerState = MutableStateFlow(PomodoroTimerState())
        val timerState: StateFlow<PomodoroTimerState> = _timerState.asStateFlow()

        private val _stopwatchState = MutableStateFlow(StopwatchState())
        val stopwatchState: StateFlow<StopwatchState> = _stopwatchState.asStateFlow()

        private var isSettingsInitialized = false

        // 記録直後にサービスが破棄されても書き込みを最後まで行えるよう、サービスとは別のスコープを使う
        private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @VisibleForTesting
        internal var sessionRecorder: (Context, WorkSession) -> Unit = { context, session ->
            persistenceScope.launch {
                try {
                    AppDatabase.getInstance(context).workSessionDao().insert(session)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 保存に失敗しても（空き容量不足など）タイマー自体は止めない
                    Log.e("PomodoroService", "Failed to record the session", e)
                }
            }
        }

        private val PomodoroTimerState.isActive: Boolean
            get() = isRunning || isPaused

        private fun idleState(settings: PomodoroSettings): PomodoroTimerState {
            val workSecs = settings.workDurationMinutes * 60
            return PomodoroTimerState(
                phase = PomodoroPhase.WORK,
                currentSet = 1,
                totalSets = settings.totalSets,
                totalDurationSeconds = workSecs,
                timeLeftSeconds = workSecs,
                settings = settings
            )
        }

        fun initSettingsIfNeeded(context: Context) {
            if (!isSettingsInitialized) {
                isSettingsInitialized = true
                _timerState.value = idleState(PreferencesManager(context).loadSettings())
            }
        }

        /** 設定を保存して状態に反映する。メインスレッドから呼ぶこと。 */
        fun updateSettings(context: Context, newSettings: PomodoroSettings) {
            PreferencesManager(context).saveSettings(newSettings)
            val current = _timerState.value
            // 計測中と完了画面の表示中は、進行状況を変えずに設定だけ差し替える
            _timerState.value = if (current.isActive || current.phase == PomodoroPhase.COMPLETED) {
                current.copy(totalSets = newSettings.totalSets, settings = newSettings)
            } else {
                idleState(newSettings)
            }
        }

        @VisibleForTesting
        internal fun resetForTest() {
            _timerState.value = PomodoroTimerState()
            _stopwatchState.value = StopwatchState()
            isSettingsInitialized = false
        }
    }
}
