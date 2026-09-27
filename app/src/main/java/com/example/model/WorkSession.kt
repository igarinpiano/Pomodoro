package com.example.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "work_sessions")
data class WorkSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val date: String, // "YYYY-MM-DD" for indexing & daily grouping
    val startTimeMillis: Long = System.currentTimeMillis(),
    val durationSeconds: Int = 0, // duration in seconds
    val sessionType: String = "POMODORO", // "POMODORO" or "STOPWATCH"
    val note: String = ""
)

enum class AppScreen {
    MAIN,
    CALENDAR,
    STATISTICS
}

enum class TimerMode(val label: String) {
    POMODORO("ポモドーロタイマー"),
    STOPWATCH("ストップウォッチ")
}

enum class StatsPeriod(val label: String) {
    WEEK("週"),
    MONTH("月"),
    YEAR("年")
}

data class StopwatchState(
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val elapsedSeconds: Int = 0,
    val startTimestampMillis: Long = 0L
) {
    val formattedTime: String
        get() {
            val hours = elapsedSeconds / 3600
            val minutes = (elapsedSeconds % 3600) / 60
            val seconds = elapsedSeconds % 60
            return String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        }
}
