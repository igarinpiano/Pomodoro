package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.AppThemeMode
import com.example.model.PomodoroSettings
import com.example.util.PreferencesManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("ポモドーロタイマー", appName)
  }

  @Test
  fun `save and load preferences`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefsManager = PreferencesManager(context)

    val customSettings = PomodoroSettings(
      workDurationMinutes = 30,
      breakDurationMinutes = 10,
      totalSets = 6,
      soundEnabled = false,
      flashEnabled = true,
      vibrateEnabled = false,
      autoStartBreak = false,
      autoStartWork = true,
      themeMode = AppThemeMode.DARK
    )

    prefsManager.saveSettings(customSettings)
    val loaded = prefsManager.loadSettings()

    assertEquals(30, loaded.workDurationMinutes)
    assertEquals(10, loaded.breakDurationMinutes)
    assertEquals(6, loaded.totalSets)
    assertFalse(loaded.soundEnabled)
    assertTrue(loaded.flashEnabled)
    assertFalse(loaded.vibrateEnabled)
    assertFalse(loaded.autoStartBreak)
    assertTrue(loaded.autoStartWork)
    assertEquals(AppThemeMode.DARK, loaded.themeMode)
  }

  @Test
  fun `room database insert and json export import`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = com.example.data.AppDatabase.getInstance(context)
    val repo = com.example.data.WorkSessionRepository(db.workSessionDao())
    repo.clearAll()

    val session1 = com.example.model.WorkSession(
      date = "2026-09-26",
      startTimeMillis = 1700000000000L,
      durationSeconds = 1500,
      sessionType = "POMODORO"
    )
    val session2 = com.example.model.WorkSession(
      date = "2026-09-26",
      startTimeMillis = 1700003600000L,
      durationSeconds = 900,
      sessionType = "STOPWATCH"
    )

    repo.insertSession(session1)
    repo.insertSession(session2)

    val all = repo.getAllSessionsSnapshot()
    assertEquals(2, all.size)

    val json = repo.exportToJson()
    assertTrue(json.contains("\"durationSeconds\": 1500"))
    assertTrue(json.contains("\"sessionType\": \"POMODORO\""))
    assertTrue(json.contains("\"sessionType\": \"STOPWATCH\""))

    repo.clearAll()
    assertEquals(0, repo.getAllSessionsSnapshot().size)

    val importResult = repo.importFromJson(json)
    assertTrue(importResult.isSuccess)
    assertEquals(2, importResult.getOrNull())
    assertEquals(2, repo.getAllSessionsSnapshot().size)
  }
}
