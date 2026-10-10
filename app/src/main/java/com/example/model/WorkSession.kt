package com.example.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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

/** 1件の計測のうち、ある1日に属する部分 */
data class DaySlice(
    val date: String, // "YYYY-MM-DD"
    val startTimeMillis: Long,
    val durationSeconds: Int
)

/**
 * 終了時刻から [durationSeconds] さかのぼった区間を、0時で区切って日付ごとに分ける。
 * 日付をまたいだ計測が、終了した日だけにまとめて記録されないようにするためのもの。
 */
fun splitByDay(
    endTimeMillis: Long,
    durationSeconds: Int,
    timeZone: TimeZone = TimeZone.getDefault()
): List<DaySlice> {
    if (durationSeconds <= 0) return emptyList()

    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { this.timeZone = timeZone }
    val calendar = Calendar.getInstance(timeZone)
    val slices = mutableListOf<DaySlice>()
    var sliceStart = endTimeMillis - durationSeconds * 1000L
    var remainingSeconds = durationSeconds

    while (remainingSeconds > 0) {
        calendar.timeInMillis = sliceStart
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        calendar.add(Calendar.DAY_OF_MONTH, 1)
        val nextMidnight = calendar.timeInMillis

        // 最後の日には残り全部を割り当て、合計が元の秒数と必ず一致するようにする
        val seconds = if (nextMidnight >= endTimeMillis) {
            remainingSeconds
        } else {
            ((nextMidnight - sliceStart + 500L) / 1000L).toInt().coerceIn(0, remainingSeconds)
        }
        if (seconds > 0) {
            slices.add(DaySlice(dateFormat.format(Date(sliceStart)), sliceStart, seconds))
            remainingSeconds -= seconds
        }
        if (nextMidnight >= endTimeMillis) break
        sliceStart = nextMidnight
    }
    return slices
}

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
