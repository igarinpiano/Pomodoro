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
    }
}
