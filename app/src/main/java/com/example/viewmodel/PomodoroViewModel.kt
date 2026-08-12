package com.example.viewmodel

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModel
import com.example.model.PomodoroSettings
import com.example.model.PomodoroTimerState
import com.example.service.PomodoroService
import kotlinx.coroutines.flow.StateFlow

class PomodoroViewModel : ViewModel() {

    val timerState: StateFlow<PomodoroTimerState> = PomodoroService.timerState

    fun initSettings(context: Context) {
        PomodoroService.initSettingsIfNeeded(context)
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

    fun stopTimer(context: Context) {
        sendServiceAction(context, PomodoroService.ACTION_STOP)
    }

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
