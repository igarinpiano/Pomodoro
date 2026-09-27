package com.example.util

import android.content.Context
import android.content.SharedPreferences
import com.example.model.AppThemeMode
import com.example.model.PomodoroSettings

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("pomodoro_prefs", Context.MODE_PRIVATE)

    fun saveSettings(settings: PomodoroSettings) {
        prefs.edit().apply {
            putInt(KEY_WORK_MINS, settings.workDurationMinutes)
            putInt(KEY_BREAK_MINS, settings.breakDurationMinutes)
            putInt(KEY_TOTAL_SETS, settings.totalSets)
            putBoolean(KEY_SOUND_ENABLED, settings.soundEnabled)
            putBoolean(KEY_FLASH_ENABLED, settings.flashEnabled)
            putBoolean(KEY_VIBRATE_ENABLED, settings.vibrateEnabled)
            putBoolean(KEY_AUTO_START_BREAK, settings.autoStartBreak)
            putBoolean(KEY_AUTO_START_WORK, settings.autoStartWork)
            putBoolean(KEY_CONTINUE_WORK_UNTIL_MANUAL, settings.continueWorkUntilManual)
            putBoolean(KEY_CONTINUE_BREAK_UNTIL_MANUAL, settings.continueBreakUntilManual)
            putString(KEY_THEME_MODE, settings.themeMode.name)
            apply()
        }
    }

    fun loadSettings(): PomodoroSettings {
        val workMins = prefs.getInt(KEY_WORK_MINS, 25)
        val breakMins = prefs.getInt(KEY_BREAK_MINS, 5)
        val totalSets = prefs.getInt(KEY_TOTAL_SETS, 4)
        val soundEnabled = prefs.getBoolean(KEY_SOUND_ENABLED, true)
        val flashEnabled = prefs.getBoolean(KEY_FLASH_ENABLED, false)
        val vibrateEnabled = prefs.getBoolean(KEY_VIBRATE_ENABLED, true)
        val autoStartBreak = prefs.getBoolean(KEY_AUTO_START_BREAK, true)
        val autoStartWork = prefs.getBoolean(KEY_AUTO_START_WORK, true)
        val continueWorkUntilManual = prefs.getBoolean(KEY_CONTINUE_WORK_UNTIL_MANUAL, false)
        val continueBreakUntilManual = prefs.getBoolean(KEY_CONTINUE_BREAK_UNTIL_MANUAL, false)
        val themeModeStr = prefs.getString(KEY_THEME_MODE, AppThemeMode.SYSTEM.name)
        val themeMode = try {
            AppThemeMode.valueOf(themeModeStr ?: AppThemeMode.SYSTEM.name)
        } catch (_: Exception) {
            AppThemeMode.SYSTEM
        }

        return PomodoroSettings(
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
    }

    companion object {
        private const val KEY_WORK_MINS = "work_mins"
        private const val KEY_BREAK_MINS = "break_mins"
        private const val KEY_TOTAL_SETS = "total_sets"
        private const val KEY_SOUND_ENABLED = "sound_enabled"
        private const val KEY_FLASH_ENABLED = "flash_enabled"
        private const val KEY_VIBRATE_ENABLED = "vibrate_enabled"
        private const val KEY_AUTO_START_BREAK = "auto_start_break"
        private const val KEY_AUTO_START_WORK = "auto_start_work"
        private const val KEY_CONTINUE_WORK_UNTIL_MANUAL = "continue_work_until_manual"
        private const val KEY_CONTINUE_BREAK_UNTIL_MANUAL = "continue_break_until_manual"
        private const val KEY_THEME_MODE = "theme_mode"
    }
}
