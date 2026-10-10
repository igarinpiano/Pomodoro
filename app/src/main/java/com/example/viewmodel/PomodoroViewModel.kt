package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.WorkSessionRepository
import com.example.model.*
import com.example.service.PomodoroService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class PomodoroViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: WorkSessionRepository =
        WorkSessionRepository(AppDatabase.getInstance(application).workSessionDao())

    val timerState: StateFlow<PomodoroTimerState> = PomodoroService.timerState
    val stopwatchState: StateFlow<StopwatchState> = PomodoroService.stopwatchState

    // Navigation and Mode
    private val _currentScreen = MutableStateFlow(AppScreen.MAIN)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()

    // 計測を続けたまま画面だけが作り直された場合（最近使ったアプリから消した後など）でも、
    // 計測中のストップウォッチの画面に戻れるようにする
    private val _timerMode = MutableStateFlow(
        if (PomodoroService.stopwatchState.value.let { it.isRunning || it.isPaused || it.elapsedSeconds > 0 }) {
            TimerMode.STOPWATCH
        } else {
            TimerMode.POMODORO
        }
    )
    val timerMode: StateFlow<TimerMode> = _timerMode.asStateFlow()

    // Calendar & Stats State
    private val todayString: String
        get() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private var lastKnownToday = todayString

    private val _selectedDate = MutableStateFlow(lastKnownToday)
    val selectedDate: StateFlow<String> = _selectedDate.asStateFlow()

    private val _statsPeriod = MutableStateFlow(StatsPeriod.WEEK)
    val statsPeriod: StateFlow<StatsPeriod> = _statsPeriod.asStateFlow()

    private val _statsPeriodOffset = MutableStateFlow(0)
    val statsPeriodOffset: StateFlow<Int> = _statsPeriodOffset.asStateFlow()

    // Interactive Bar Chart selection in Stats: index of selected bar (-1 for none)
    private val _selectedBarIndex = MutableStateFlow<Int?>(null)
    val selectedBarIndex: StateFlow<Int?> = _selectedBarIndex.asStateFlow()

    // Reactive DB queries
    // カレンダーと統計は日別の合計だけあれば足りるので、1本のクエリからすべて導出する
    val dailyWorkSeconds: StateFlow<Map<String, Long>> = repository.dailyTotals
        .map { totals -> totals.associate { it.date to it.totalSeconds } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val datesWithWork: StateFlow<Set<String>> = dailyWorkSeconds
        .map { totals -> totals.filterValues { it > 0 }.keys }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val totalWorkSeconds: StateFlow<Long> = dailyWorkSeconds
        .map { totals -> totals.values.sum() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val workSecondsForSelectedDate: StateFlow<Long> = dailyWorkSeconds
        .combine(_selectedDate) { totals, date -> totals[date] ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    // 通知の許可は起動ごとに1回だけ尋ねる（画面を行き来するたびに出さない）
    var hasRequestedNotificationPermission = false

    fun initSettings(context: Context) {
        PomodoroService.initSettingsIfNeeded(context)
    }

    fun navigateTo(screen: AppScreen) {
        if (screen == AppScreen.CALENDAR) {
            // アプリを開いたまま日付が変わった場合に、選択が前日のまま残らないようにする
            val today = todayString
            if (today != lastKnownToday) {
                if (_selectedDate.value == lastKnownToday) {
                    _selectedDate.value = today
                }
                lastKnownToday = today
            }
        }
        _currentScreen.value = screen
        _selectedBarIndex.value = null
    }

    fun setTimerMode(mode: TimerMode) {
        _timerMode.value = mode
    }

    fun selectDate(date: String) {
        _selectedDate.value = date
    }

    fun setStatsPeriod(period: StatsPeriod) {
        _statsPeriod.value = period
        _statsPeriodOffset.value = 0
        _selectedBarIndex.value = null
    }

    fun shiftStatsPeriod(delta: Int) {
        _statsPeriodOffset.value += delta
        _selectedBarIndex.value = null
    }

    fun selectBar(index: Int?) {
        _selectedBarIndex.value = if (_selectedBarIndex.value == index) null else index
    }

    // --- Pomodoro Service Actions ---

    fun startTimer(context: Context) {
        sendServiceAction(context, PomodoroService.ACTION_START)
    }

    fun pauseTimer(context: Context) {
        sendServiceAction(context, PomodoroService.ACTION_PAUSE)
    }

    fun resumeTimer(context: Context) {
        sendServiceAction(context, PomodoroService.ACTION_RESUME)
    }

    fun skipSet(context: Context) {
        sendServiceAction(context, PomodoroService.ACTION_SKIP)
    }

    fun nextPhase(context: Context) {
        sendServiceAction(context, PomodoroService.ACTION_NEXT_PHASE)
    }

    fun stopTimer(context: Context) {
        sendServiceAction(context, PomodoroService.ACTION_STOP)
    }

    // --- Stopwatch Service Actions ---

    fun startStopwatch(context: Context) {
        sendServiceAction(context, PomodoroService.ACTION_STOPWATCH_START)
    }

    fun pauseStopwatch(context: Context) {
        sendServiceAction(context, PomodoroService.ACTION_STOPWATCH_PAUSE)
    }

    fun stopStopwatch(context: Context) {
        sendServiceAction(context, PomodoroService.ACTION_STOPWATCH_STOP)
    }

    // --- Settings Updates ---

    fun updateSettings(context: Context, newSettings: PomodoroSettings) {
        PomodoroService.updateSettings(context, newSettings)
    }

    fun updateInlineSettings(
        context: Context,
        workMins: Int? = null,
        breakMins: Int? = null,
        totalSets: Int? = null
    ) {
        val current = timerState.value.settings
        val updated = current.copy(
            workDurationMinutes = workMins ?: current.workDurationMinutes,
            breakDurationMinutes = breakMins ?: current.breakDurationMinutes,
            totalSets = totalSets ?: current.totalSets
        )
        updateSettings(context, updated)
    }

    fun toggleQuickSound(context: Context) {
        val current = timerState.value.settings
        val updated = current.copy(soundEnabled = !current.soundEnabled)
        updateSettings(context, updated)
    }

    fun toggleQuickVibrate(context: Context) {
        val current = timerState.value.settings
        val updated = current.copy(vibrateEnabled = !current.vibrateEnabled)
        updateSettings(context, updated)
    }

    fun toggleQuickFlash(context: Context) {
        val current = timerState.value.settings
        val updated = current.copy(flashEnabled = !current.flashEnabled)
        updateSettings(context, updated)
    }

    // --- JSON Export & Import ---

    suspend fun exportDataToJson(): String {
        return repository.exportToJson()
    }

    suspend fun importDataFromJson(json: String, clearExisting: Boolean = false): Result<Int> {
        return repository.importFromJson(json, clearExisting)
    }

    suspend fun exportDataToFile(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val json = repository.exportToJson()
            // 既存のファイルに上書きする場合に古い内容の末尾が残らないよう、切り詰めて開く
            val stream = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException("ファイルを開けませんでした")
            stream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        }.onFailure { if (it is CancellationException) throw it }
    }

    suspend fun importDataFromFile(uri: Uri): Result<Int> {
        val text = withContext(Dispatchers.IO) {
            runCatching {
                val stream = getApplication<Application>().contentResolver.openInputStream(uri)
                    ?: throw IOException("ファイルを開けませんでした")
                stream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val content = StringBuilder()
                    val buffer = CharArray(8 * 1024)
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        content.append(buffer, 0, read)
                        // 誤って巨大なファイルを選んでもメモリを使い切らないようにする
                        if (content.length > MAX_IMPORT_CHARS) {
                            throw IOException("ファイルが大きすぎます")
                        }
                    }
                    content.toString()
                }
            }.onFailure { if (it is CancellationException) throw it }
        }
        return text.fold(
            onSuccess = { repository.importFromJson(it) },
            onFailure = { Result.failure(it) }
        )
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteSession(sessionId)
        }
    }

    fun updateWorkTimeForDate(date: String, durationSeconds: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.setWorkDurationForDate(date, durationSeconds)
        }
    }

    private fun sendServiceAction(context: Context, action: String) {
        val intent = Intent(context, PomodoroService::class.java).apply {
            this.action = action
        }
        startServiceInternal(context, intent)
    }

    private fun startServiceInternal(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private companion object {
        const val MAX_IMPORT_CHARS = 5 * 1024 * 1024
    }
}
