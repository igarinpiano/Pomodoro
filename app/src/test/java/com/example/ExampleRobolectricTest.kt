package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.WorkSessionRepository
import com.example.model.AppThemeMode
import com.example.model.PomodoroSettings
import com.example.model.WorkSession
import com.example.util.PreferencesManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class ExampleRobolectricTest {

  private lateinit var context: Context
  private lateinit var db: AppDatabase
  private lateinit var repo: WorkSessionRepository

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    repo = WorkSessionRepository(db.workSessionDao())
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun `read string from context`() {
    val appName = context.getString(R.string.app_name)
    assertEquals("ポモドーロタイマー", appName)
  }

  @Test
  fun `save and load preferences`() {
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
  fun `export aggregates sessions by date and import restores them`() = runBlocking {
    repo.insertSession(WorkSession(date = "2026-09-26", durationSeconds = 1500, sessionType = "POMODORO"))
    repo.insertSession(WorkSession(date = "2026-09-26", durationSeconds = 900, sessionType = "STOPWATCH"))
    repo.insertSession(WorkSession(date = "2026-09-27", durationSeconds = 600))

    val json = repo.exportToJson()
    val root = JSONObject(json)
    val records = root.getJSONArray("dailyRecords")
    assertEquals(2, root.getInt("daysCount"))
    assertEquals("2026-09-26", records.getJSONObject(0).getString("date"))
    assertEquals(2400L, records.getJSONObject(0).getLong("durationSeconds"))
    assertEquals("2026-09-27", records.getJSONObject(1).getString("date"))
    assertEquals(600L, records.getJSONObject(1).getLong("durationSeconds"))

    repo.clearAll()
    assertEquals(0, repo.getAllSessionsSnapshot().size)

    val importResult = repo.importFromJson(json)
    assertEquals(2, importResult.getOrNull())
    val restored = repo.getAllSessionsSnapshot()
    assertEquals(2, restored.size)
    assertEquals(3000, restored.sumOf { it.durationSeconds })
  }

  @Test
  fun `import replaces records of the imported dates only`() = runBlocking {
    repo.insertSession(WorkSession(date = "2026-09-26", durationSeconds = 100))
    repo.insertSession(WorkSession(date = "2026-09-26", durationSeconds = 200))
    repo.insertSession(WorkSession(date = "2026-09-27", durationSeconds = 300))

    // 先頭に BOM が付いたファイルの内容でも読み込める
    val json = "﻿" + """{"dailyRecords":[{"date":"2026-09-26","durationSeconds":3600}]}"""
    assertEquals(1, repo.importFromJson(json).getOrNull())

    val totals = db.workSessionDao().getDailyTotals().first().associate { it.date to it.totalSeconds }
    assertEquals(mapOf("2026-09-26" to 3600L, "2026-09-27" to 300L), totals)
  }

  @Test
  fun `import skips invalid dates and rejects unusable data`() = runBlocking {
    val mixed = """
      [
        {"date":"2026-09-26","durationSeconds":60},
        {"date":"2026/09/27","durationSeconds":60},
        {"date":"2026-13-40","durationSeconds":60},
        {"date":"2026-09-28","durationSeconds":0}
      ]
    """.trimIndent()
    assertEquals(1, repo.importFromJson(mixed).getOrNull())
    assertEquals(listOf("2026-09-26"), repo.getAllSessionsSnapshot().map { it.date })

    val noValidRecords = repo.importFromJson("""{"dailyRecords":[{"date":"yesterday","durationSeconds":60}]}""")
    assertTrue(noValidRecords.isFailure)

    val malformed = repo.importFromJson("this is not json")
    assertTrue(malformed.isFailure)
    assertEquals("JSONの形式が正しくありません", malformed.exceptionOrNull()?.message)

    // 失敗したインポートで既存の記録が消えていないこと
    assertEquals(1, repo.getAllSessionsSnapshot().size)
  }

  @Test
  fun `editing a day replaces its sessions with a single total`() = runBlocking {
    repo.insertSession(WorkSession(date = "2026-09-26", durationSeconds = 100))
    repo.insertSession(WorkSession(date = "2026-09-26", durationSeconds = 200))

    repo.setWorkDurationForDate("2026-09-26", 5400L)
    assertEquals(listOf(5400), repo.getAllSessionsSnapshot().map { it.durationSeconds })

    repo.setWorkDurationForDate("2026-09-26", 0L)
    assertEquals(0, repo.getAllSessionsSnapshot().size)
  }
}
