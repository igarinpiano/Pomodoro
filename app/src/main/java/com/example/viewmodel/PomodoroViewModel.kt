package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.WorkSessionRepository
import com.example.model.*
import com.example.service.PomodoroService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
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

    private val _timerMode = MutableStateFlow(TimerMode.POMODORO)
    val timerMode: StateFlow<TimerMode> = _timerMode.asStateFlow()

    // Calendar & Stats State
    private val todayString: String
        get() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private val _selectedDate = MutableStateFlow(todayString)
    val selectedDate: StateFlow<String> = _selectedDate.asStateFlow()

    private val _statsPeriod = MutableStateFlow(StatsPeriod.WEEK)
    val statsPeriod: StateFlow<StatsPeriod> = _statsPeriod.asStateFlow()

    private val _statsPeriodOffset = MutableStateFlow(0)
    val statsPeriodOffset: StateFlow<Int> = _statsPeriodOffset.asStateFlow()

    // Interactive Bar Chart selection in Stats: index of selected bar (-1 for none)
    private val _selectedBarIndex = MutableStateFlow<Int?>(null)
    val selectedBarIndex: StateFlow<Int?> = _selectedBarIndex.asStateFlow()

    // Reactive DB queries
    val datesWithWork: StateFlow<List<String>> = repository.datesWithWork
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allSessions: StateFlow<List<WorkSession>> = repository.allSessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalWorkSeconds: StateFlow<Long> = repository.totalWorkSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val sessionsForSelectedDate: StateFlow<List<WorkSession>> = _selectedDate
        .flatMapLatest { date -> repository.getSessionsForDate(date) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val workSecondsForSelectedDate: StateFlow<Long> = sessionsForSelectedDate
        .map { list -> list.sumOf { it.durationSeconds.toLong() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    fun initSettings(context: Context) {
        PomodoroService.initSettingsIfNeeded(context)
    }

    fun navigateTo(screen: AppScreen) {
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
        if (!timerState.value.isRunning && !timerState.value.isPaused) {
            PomodoroService.updateSettingsDirectly(context, newSettings)
        } else {
            val intent = Intent(context, PomodoroService::class.java).apply {
                action = PomodoroService.ACTION_UPDATE_SETTINGS
                putExtra(PomodoroService.EXTRA_WORK_MINS, newSettings.workDurationMinutes)
                putExtra(PomodoroService.EXTRA_BREAK_MINS, newSettings.breakDurationMinutes)
                putExtra(PomodoroService.EXTRA_TOTAL_SETS, newSettings.totalSets)
                putExtra(PomodoroService.EXTRA_SOUND_ENABLED, newSettings.soundEnabled)
                putExtra(PomodoroService.EXTRA_FLASH_ENABLED, newSettings.flashEnabled)
                putExtra(PomodoroService.EXTRA_VIBRATE_ENABLED, newSettings.vibrateEnabled)
                putExtra(PomodoroService.EXTRA_AUTO_START_BREAK, newSettings.autoStartBreak)
                putExtra(PomodoroService.EXTRA_AUTO_START_WORK, newSettings.autoStartWork)
                putExtra(PomodoroService.EXTRA_CONTINUE_WORK_UNTIL_MANUAL, newSettings.continueWorkUntilManual)
                putExtra(PomodoroService.EXTRA_CONTINUE_BREAK_UNTIL_MANUAL, newSettings.continueBreakUntilManual)
                putExtra(PomodoroService.EXTRA_THEME_MODE, newSettings.themeMode.name)
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
                PomodoroService.updateSettingsDirectly(context, newSettings)
            }
        }
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
}
