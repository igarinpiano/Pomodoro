package com.example

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.example.model.PomodoroTimerState
import com.example.ui.components.TimerCircleDisplay
import com.example.ui.theme.PomodoroTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [34])
class GreetingScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun pomodoro_display_screenshot() {
    composeTestRule.setContent {
      PomodoroTheme {
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

    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/pomodoro_display.png")
  }
}
