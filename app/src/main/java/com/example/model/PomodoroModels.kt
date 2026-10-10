package com.example.model

enum class PomodoroPhase(val label: String) {
    WORK("作業中"),
    BREAK("休憩中"),
    COMPLETED("全セット完了")
}

enum class AppThemeMode(val label: String) {
    SYSTEM("システム"),
    LIGHT("ライト"),
    DARK("ダーク")
}

data class PomodoroSettings(
    val workDurationMinutes: Int = 25,
    val breakDurationMinutes: Int = 5,
    val totalSets: Int = 4,
    val soundEnabled: Boolean = true,
    val flashEnabled: Boolean = false,
    val vibrateEnabled: Boolean = true,
    val autoStartBreak: Boolean = true,
    val autoStartWork: Boolean = true,
    val continueWorkUntilManual: Boolean = false,
    val continueBreakUntilManual: Boolean = false,
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
)

data class PomodoroTimerState(
    val phase: PomodoroPhase = PomodoroPhase.WORK,
    val currentSet: Int = 1,
    val totalSets: Int = 4,
    val totalDurationSeconds: Int = 25 * 60,
    val timeLeftSeconds: Int = 25 * 60,
    val overtimeSeconds: Int = 0,
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val settings: PomodoroSettings = PomodoroSettings()
) {
    // 作業時は時計回りにゲージが進み(0.0 -> 1.0)、休憩時は反時計回りに減る(1.0 -> 0.0)
    val progress: Float
        get() {
            if (totalDurationSeconds <= 0) return 0f
            // 超過中は「使い切った」状態を保つ（作業は満杯、休憩は空のまま）
            if (overtimeSeconds > 0) return if (phase == PomodoroPhase.BREAK) 0f else 1f
            val elapsed = (totalDurationSeconds - timeLeftSeconds).coerceAtLeast(0)
            return when (phase) {
                PomodoroPhase.WORK -> (elapsed.toFloat() / totalDurationSeconds.toFloat()).coerceIn(0f, 1f)
                PomodoroPhase.BREAK -> (timeLeftSeconds.toFloat() / totalDurationSeconds.toFloat()).coerceIn(0f, 1f)
                PomodoroPhase.COMPLETED -> 1f
            }
        }

    val formattedTime: String
        get() {
            if (overtimeSeconds > 0) {
                val totalElapsed = totalDurationSeconds + overtimeSeconds
                val minutes = totalElapsed / 60
                val seconds = totalElapsed % 60
                return String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
            }
            val minutes = timeLeftSeconds / 60
            val seconds = timeLeftSeconds % 60
            return String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
        }

    val formattedOvertime: String
        get() {
            val minutes = overtimeSeconds / 60
            val seconds = overtimeSeconds % 60
            return String.format(java.util.Locale.US, "+%02d:%02d", minutes, seconds)
        }
}
