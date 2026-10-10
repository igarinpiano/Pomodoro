package com.example

import com.example.model.PomodoroPhase
import com.example.model.PomodoroSettings
import com.example.model.PomodoroTimerState
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun testFormattedTime() {
        val state = PomodoroTimerState(
            timeLeftSeconds = 25 * 60
        )
        assertEquals("25:00", state.formattedTime)

        val state2 = PomodoroTimerState(
            timeLeftSeconds = 65
        )
        assertEquals("01:05", state2.formattedTime)
    }

    @Test
    fun testOvertimeFormatting() {
        val overtime = PomodoroTimerState(
            totalDurationSeconds = 25 * 60,
            timeLeftSeconds = 0,
            overtimeSeconds = 65
        )
        assertEquals("+01:05", overtime.formattedOvertime)
        // 超過中の時計表示は、フェーズ開始からの経過時間
        assertEquals("26:05", overtime.formattedTime)
        assertEquals(1f, overtime.progress, 0.001f)

        // 休憩のゲージは満杯から空へ減るので、超過中も空のまま
        val breakOvertime = overtime.copy(phase = PomodoroPhase.BREAK)
        assertEquals(0f, breakOvertime.progress, 0.001f)
    }

    private val tokyo = java.util.TimeZone.getTimeZone("Asia/Tokyo")

    private fun tokyoMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        java.util.Calendar.getInstance(tokyo).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    @Test
    fun testSplitByDayWithinOneDay() {
        val slices = com.example.model.splitByDay(tokyoMillis(2026, 10, 10, 12, 0), 25 * 60, tokyo)
        assertEquals(listOf("2026-10-10" to 25 * 60), slices.map { it.date to it.durationSeconds })
        assertEquals(tokyoMillis(2026, 10, 10, 11, 35), slices.single().startTimeMillis)
    }

    @Test
    fun testSplitByDayAcrossMidnight() {
        // 22:00 に始めて 0:10 に終えた 2時間10分
        val slices = com.example.model.splitByDay(tokyoMillis(2026, 10, 11, 0, 10), 2 * 3600 + 600, tokyo)
        assertEquals(
            listOf("2026-10-10" to 2 * 3600, "2026-10-11" to 600),
            slices.map { it.date to it.durationSeconds }
        )

        // 2回またぐ場合（26時間）
        val long = com.example.model.splitByDay(tokyoMillis(2026, 10, 12, 1, 0), 26 * 3600, tokyo)
        assertEquals(
            listOf("2026-10-10" to 3600, "2026-10-11" to 24 * 3600, "2026-10-12" to 3600),
            long.map { it.date to it.durationSeconds }
        )

        // 端数があっても合計は元の秒数と一致する
        val odd = com.example.model.splitByDay(tokyoMillis(2026, 10, 11, 0, 0) + 1_499L, 3, tokyo)
        assertEquals(3, odd.sumOf { it.durationSeconds })

        assertTrue(com.example.model.splitByDay(tokyoMillis(2026, 10, 10, 12, 0), 0, tokyo).isEmpty())
    }

    @Test
    fun testProgressCalculation() {
        val workState = PomodoroTimerState(
            phase = PomodoroPhase.WORK,
            totalDurationSeconds = 100,
            timeLeftSeconds = 50
        )
        // (100 - 50) / 100 = 0.5
        assertEquals(0.5f, workState.progress, 0.001f)

        val breakState = PomodoroTimerState(
            phase = PomodoroPhase.BREAK,
            totalDurationSeconds = 100,
            timeLeftSeconds = 50
        )
        // 50 / 100 = 0.5
        assertEquals(0.5f, breakState.progress, 0.001f)

        val completedState = PomodoroTimerState(
            phase = PomodoroPhase.COMPLETED
        )
        assertEquals(1f, completedState.progress, 0.001f)
    }

    @Test
    fun testDefaultSettings() {
        val settings = PomodoroSettings()
        assertEquals(25, settings.workDurationMinutes)
        assertEquals(5, settings.breakDurationMinutes)
        assertEquals(4, settings.totalSets)
        assertTrue(settings.soundEnabled)
        assertFalse(settings.flashEnabled)
        assertTrue(settings.vibrateEnabled)
        assertTrue(settings.autoStartBreak)
        assertTrue(settings.autoStartWork)
    }

    @Test
    fun testCompletedPhaseState() {
        val completedState = PomodoroTimerState(
            phase = PomodoroPhase.COMPLETED,
            currentSet = 4,
            totalSets = 4,
            timeLeftSeconds = 0,
            isRunning = false,
            isPaused = false
        )
        assertEquals(PomodoroPhase.COMPLETED, completedState.phase)
        assertEquals("00:00", completedState.formattedTime)
        assertEquals(1f, completedState.progress, 0.001f)
        assertFalse(completedState.isRunning)
        assertFalse(completedState.isPaused)
    }

    @Test
    fun testPhaseLabels() {
        assertEquals("作業中", PomodoroPhase.WORK.label)
        assertEquals("休憩中", PomodoroPhase.BREAK.label)
        assertEquals("全セット完了", PomodoroPhase.COMPLETED.label)
    }

    @Test
    fun testStopwatchState() {
        val stopwatch = com.example.model.StopwatchState(
            elapsedSeconds = 3665
        )
        // 1 hour, 1 minute, 5 seconds = 01:01:05
        assertEquals("01:01:05", stopwatch.formattedTime)

        val stopwatchZero = com.example.model.StopwatchState(
            elapsedSeconds = 0
        )
        assertEquals("00:00:00", stopwatchZero.formattedTime)
    }
}
