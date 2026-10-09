package com.example

import android.Manifest
import android.app.Application
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.model.AppScreen
import com.example.model.AppThemeMode
import com.example.model.PomodoroPhase
import com.example.model.PomodoroTimerState
import com.example.model.StopwatchState
import com.example.model.WorkSession
import com.example.service.PomodoroService
import com.example.ui.HomeScreen
import com.example.ui.components.StopwatchCircleDisplay
import com.example.ui.components.TimerCircleDisplay
import com.example.ui.theme.PomodoroTheme
import com.example.viewmodel.PomodoroViewModel
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 各画面を縦・横の両方で一通り操作し、落ちずに描画できることを確認する。
 * `-Proborazzi.test.record=true` を付けて実行すると build/outputs/roborazzi に画像も保存される。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [34])
class ScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val application: Application
    get() = ApplicationProvider.getApplicationContext()

  @Before
  fun setUp() {
    PomodoroService.resetForTest()
    shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
  }

  @After
  fun tearDown() {
    AppDatabase.resetForTest()
    PomodoroService.resetForTest()
  }

  @OptIn(ExperimentalRoborazziApi::class)
  private fun capture(name: String) {
    composeTestRule.waitForIdle()
    captureScreenRoboImage("build/outputs/roborazzi/$name.png")
  }

  @OptIn(ExperimentalRoborazziApi::class)
  private fun captureNow(name: String) {
    captureScreenRoboImage("build/outputs/roborazzi/$name.png")
  }

  private fun withManualClock(block: () -> Unit) {
    composeTestRule.mainClock.autoAdvance = false
    try {
      block()
    } finally {
      composeTestRule.mainClock.autoAdvance = true
    }
  }

  @Test
  fun timer_circle_states() {
    var timerState by mutableStateOf(PomodoroTimerState())
    var stopwatchState by mutableStateOf<StopwatchState?>(null)
    // 横向きの小型端末を想定した、基準（310dp）より小さい表示も並べて確認する
    composeTestRule.setContent {
      PomodoroTheme(themeMode = AppThemeMode.LIGHT, dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
          ) {
            listOf(310.dp, 240.dp).forEach { size ->
              Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
                val stopwatch = stopwatchState
                if (stopwatch == null) {
                  TimerCircleDisplay(
                    timerState = timerState,
                    onStart = {},
                    onPause = {},
                    onResume = {},
                    onSkip = {},
                    onStop = {},
                    onUpdateWorkMins = {},
                    onUpdateBreakMins = {},
                    onUpdateTotalSets = {}
                  )
                } else {
                  StopwatchCircleDisplay(stopwatchState = stopwatch, onStart = {}, onPause = {}, onStop = {})
                }
              }
            }
          }
        }
      }
    }
    capture("circle_1_setup")

    timerState = PomodoroTimerState(isRunning = true, timeLeftSeconds = 18 * 60 + 5)
    capture("circle_2_running")

    timerState = PomodoroTimerState(
      phase = PomodoroPhase.BREAK,
      isPaused = true,
      totalDurationSeconds = 5 * 60,
      timeLeftSeconds = 3 * 60
    )
    capture("circle_3_break_paused")

    timerState = PomodoroTimerState(isRunning = true, timeLeftSeconds = 0, overtimeSeconds = 125)
    capture("circle_4_overtime")

    timerState = PomodoroTimerState(phase = PomodoroPhase.COMPLETED, timeLeftSeconds = 0)
    capture("circle_5_completed")

    stopwatchState = StopwatchState(isRunning = true, elapsedSeconds = 3725)
    capture("circle_6_stopwatch_running")

    stopwatchState = StopwatchState(isPaused = true, elapsedSeconds = 3725)
    capture("circle_7_stopwatch_paused")
  }

  @OptIn(ExperimentalRoborazziApi::class)
  @Test
  fun number_input_dialog() {
    composeTestRule.setContent {
      PomodoroTheme(themeMode = AppThemeMode.LIGHT, dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          Box(contentAlignment = Alignment.Center) {
            TimerCircleDisplay(
              timerState = PomodoroTimerState(),
              onStart = {},
              onPause = {},
              onResume = {},
              onSkip = {},
              onStop = {},
              onUpdateWorkMins = {},
              onUpdateBreakMins = {},
              onUpdateTotalSets = {}
            )
          }
        }
      }
    }
    composeTestRule.waitForIdle()

    // 入力欄にフォーカスが当たるとカーソルの点滅でアイドルにならないため、時計を手動で進める
    composeTestRule.mainClock.autoAdvance = false
    composeTestRule.onNodeWithTag("setup_total_sets_value", useUnmergedTree = true).performClick()
    composeTestRule.mainClock.advanceTimeBy(1_000)
    captureScreenRoboImage("build/outputs/roborazzi/number_dialog.png")
    composeTestRule.onNodeWithText("セット数").assertExists()
  }

  @Test
  fun all_screens_portrait() {
    walkThroughScreens("portrait", AppThemeMode.LIGHT)
  }

  @Test
  @Config(qualifiers = "+land")
  fun all_screens_landscape() {
    walkThroughScreens("landscape", AppThemeMode.DARK)
  }

  private fun walkThroughScreens(prefix: String, themeMode: AppThemeMode) {
    seedSessions()
    val viewModel = PomodoroViewModel(application)
    viewModel.initSettings(application)

    composeTestRule.setContent {
      PomodoroTheme(themeMode = themeMode, dynamicColor = false) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          HomeScreen(viewModel = viewModel)
        }
      }
    }
    capture("${prefix}_01_main")

    // 設定シート
    composeTestRule.onNodeWithTag("footer_settings_button").performClick()
    capture("${prefix}_03_settings")
    composeTestRule.onNodeWithTag("save_settings_button").performScrollTo().performClick()

    // ストップウォッチ
    composeTestRule.onNodeWithTag("mode_tab_stopwatch").performClick()
    capture("${prefix}_04_stopwatch")
    composeTestRule.onNodeWithTag("mode_tab_pomodoro").performClick()

    // カレンダー（記録の読み込みを待つ）
    composeTestRule.onNodeWithTag("header_calendar_button").performClick()
    composeTestRule.waitUntil(timeoutMillis = 10_000) {
      composeTestRule.onAllNodesWithText("3 日").fetchSemanticsNodes().isNotEmpty()
    }
    capture("${prefix}_05_calendar")

    composeTestRule.onNodeWithTag("calendar_edit_work_time_button").performScrollTo().performClick()
    capture("${prefix}_06_edit_dialog")
    composeTestRule.onNodeWithText("キャンセル").performClick()

    // メニューから開くダイアログは表示中にアイドルにならないため、閉じるまで時計を手動で進める
    composeTestRule.onNodeWithTag("calendar_hamburger_menu_button").performClick()
    composeTestRule.waitForIdle()
    withManualClock {
      composeTestRule.onNodeWithTag("menu_import_json").performClick()
      composeTestRule.mainClock.advanceTimeBy(1_000)
      captureNow("${prefix}_07_import_dialog")
      composeTestRule.onNodeWithTag("import_pick_file_button").assertExists()
      composeTestRule.onNodeWithText("キャンセル").performClick()
      composeTestRule.mainClock.advanceTimeBy(1_000)
    }

    composeTestRule.onNodeWithTag("calendar_hamburger_menu_button").performClick()
    composeTestRule.waitForIdle()
    withManualClock {
      composeTestRule.onNodeWithTag("menu_export_json").performClick()
      // エクスポート内容の読み込み（別スレッド）が終わってダイアログが出るまで待つ
      repeat(50) {
        Thread.sleep(20)
        shadowOf(Looper.getMainLooper()).idle()
        composeTestRule.mainClock.advanceTimeBy(100)
      }
      captureNow("${prefix}_08_export_dialog")
      composeTestRule.onNodeWithTag("export_save_file_button").assertExists()
      composeTestRule.onNodeWithText("閉じる").performClick()
      composeTestRule.mainClock.advanceTimeBy(1_000)
    }

    // 統計（週・月・年）
    composeTestRule.runOnIdle { viewModel.navigateTo(AppScreen.STATISTICS) }
    capture("${prefix}_09_stats_week")
    // 横向きではグラフがスクロール領域の外にあるので、スクロールせずにタップする
    val chartNode = composeTestRule.onNodeWithTag("stats_bar_chart_container")
    if (prefix == "portrait") chartNode.performScrollTo()
    chartNode.performClick()
    capture("${prefix}_10_stats_week_selected")
    composeTestRule.onNodeWithTag("stats_tab_month").performScrollTo().performClick()
    capture("${prefix}_11_stats_month")
    composeTestRule.onNodeWithTag("stats_tab_year").performScrollTo().performClick()
    capture("${prefix}_12_stats_year")
  }

  /** 今日・昨日・一昨日の3日分の記録を入れる */
  private fun seedSessions() = runBlocking {
    val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val calendar = Calendar.getInstance()
    val sessions = listOf(2 * 3600 + 1500, 3600, 5400).map { seconds ->
      val session = WorkSession(date = format.format(calendar.time), durationSeconds = seconds)
      calendar.add(Calendar.DAY_OF_YEAR, -1)
      session
    }
    AppDatabase.getInstance(application).workSessionDao().insertAll(sessions)
  }
}
