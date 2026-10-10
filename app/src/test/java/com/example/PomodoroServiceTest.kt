package com.example

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.model.PomodoroPhase
import com.example.model.PomodoroSettings
import com.example.model.WorkSession
import com.example.service.PomodoroService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

// 最小対応の Android 7.0（通知チャンネルなし）と、制限が増えた新しいバージョンの両方で確認する
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 28, 34])
class PomodoroServiceTest {

  private lateinit var context: Context
  private lateinit var controller: ServiceController<PomodoroService>
  private lateinit var originalRecorder: (Context, WorkSession) -> Unit
  private val recorded = mutableListOf<WorkSession>()

  private val timerState get() = PomodoroService.timerState.value
  private val stopwatchState get() = PomodoroService.stopwatchState.value

  // 日付をまたぐ時刻に実行されると1件の計測が2日分に分かれるため、件数ではなく合計で確認する
  private val recordedSeconds get() = recorded.sumOf { it.durationSeconds }

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    PomodoroService.resetForTest()
    originalRecorder = PomodoroService.sessionRecorder
    PomodoroService.sessionRecorder = { _, session -> recorded += session }
    applySettings(PomodoroSettings())
    controller = Robolectric.buildService(PomodoroService::class.java).create()
  }

  @After
  fun tearDown() {
    controller.destroy()
    PomodoroService.sessionRecorder = originalRecorder
    PomodoroService.resetForTest()
  }

  // 音・バイブ・ライトは端末機能に依存するためテストでは無効にする
  private fun applySettings(settings: PomodoroSettings) {
    PomodoroService.updateSettings(
      context,
      settings.copy(soundEnabled = false, vibrateEnabled = false, flashEnabled = false)
    )
  }

  private fun send(action: String) {
    controller.get().onStartCommand(Intent(context, PomodoroService::class.java).setAction(action), 0, 0)
    shadowOf(Looper.getMainLooper()).idle()
  }

  /** ティッカーを動かしながら時間を進める（画面点灯中に相当） */
  private fun runFor(duration: Duration) {
    shadowOf(Looper.getMainLooper()).idleFor(duration)
  }

  /** ティッカーを動かさずに時間だけ進める（端末スリープ中に相当） */
  private fun sleepFor(duration: Duration) {
    ShadowSystemClock.advanceBy(duration)
  }

  private fun liveNotificationTitle(): String {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val notification = shadowOf(manager).getNotification(PomodoroService.NOTIFICATION_ID)
    return notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
  }

  @Test
  fun `start runs the first work phase`() {
    send(PomodoroService.ACTION_START)

    assertTrue(timerState.isRunning)
    assertEquals(PomodoroPhase.WORK, timerState.phase)
    assertEquals(1, timerState.currentSet)
    assertEquals(25 * 60, timerState.timeLeftSeconds)

    runFor(Duration.ofSeconds(61))
    assertEquals(25 * 60 - 61, timerState.timeLeftSeconds)
    assertEquals("作業中 [1/4] - 残り 23:59", liveNotificationTitle())
  }

  @Test
  fun `work phase completes into break and records the full work time`() {
    send(PomodoroService.ACTION_START)
    runFor(Duration.ofSeconds(25 * 60 + 1))

    assertEquals(PomodoroPhase.BREAK, timerState.phase)
    assertTrue(timerState.isRunning)
    assertEquals(25 * 60, recordedSeconds)
    assertEquals(PomodoroService.SESSION_TYPE_POMODORO, recorded.first().sessionType)
  }

  @Test
  fun `alarm arriving after the ticker already advanced does not skip the next phase`() {
    send(PomodoroService.ACTION_START)
    runFor(Duration.ofSeconds(25 * 60 + 1))
    assertEquals(PomodoroPhase.BREAK, timerState.phase)

    // 同じ時刻にセットされていたアラームが遅れて届く
    send(PomodoroService.ACTION_ALARM_COMPLETE)

    assertEquals(PomodoroPhase.BREAK, timerState.phase)
    assertEquals(1, timerState.currentSet)
    assertEquals(25 * 60, recordedSeconds)
  }

  @Test
  fun `alarm completes the phase with the full duration even if the ticker was asleep`() {
    send(PomodoroService.ACTION_START)
    sleepFor(Duration.ofMinutes(25))

    send(PomodoroService.ACTION_ALARM_COMPLETE)

    assertEquals(PomodoroPhase.BREAK, timerState.phase)
    assertEquals(5 * 60, timerState.timeLeftSeconds)
    assertEquals(25 * 60, recordedSeconds)
  }

  @Test
  fun `alarm before the target time is ignored`() {
    send(PomodoroService.ACTION_START)
    sleepFor(Duration.ofMinutes(10))

    send(PomodoroService.ACTION_ALARM_COMPLETE)

    assertEquals(PomodoroPhase.WORK, timerState.phase)
    assertTrue(timerState.isRunning)
    assertTrue(recorded.isEmpty())
  }

  @Test
  fun `pause keeps the remaining time and repeated pause does not change it`() {
    send(PomodoroService.ACTION_START)
    runFor(Duration.ofSeconds(10))
    send(PomodoroService.ACTION_PAUSE)

    assertTrue(timerState.isPaused)
    assertFalse(timerState.isRunning)
    assertEquals(25 * 60 - 10, timerState.timeLeftSeconds)
    assertEquals("作業中 [1/4] - 残り 24:50", liveNotificationTitle())

    sleepFor(Duration.ofMinutes(5))
    send(PomodoroService.ACTION_PAUSE)
    assertEquals(25 * 60 - 10, timerState.timeLeftSeconds)

    send(PomodoroService.ACTION_RESUME)
    runFor(Duration.ofSeconds(10))
    assertTrue(timerState.isRunning)
    assertEquals(25 * 60 - 20, timerState.timeLeftSeconds)
  }

  @Test
  fun `stop records the elapsed work time and stops the service`() {
    send(PomodoroService.ACTION_START)
    sleepFor(Duration.ofSeconds(90))
    send(PomodoroService.ACTION_STOP)

    assertEquals(90, recordedSeconds)
    assertFalse(timerState.isRunning)
    assertFalse(timerState.isPaused)
    assertEquals(PomodoroPhase.WORK, timerState.phase)
    assertEquals(25 * 60, timerState.timeLeftSeconds)
    assertTrue(shadowOf(controller.get()).isStoppedBySelf)
  }

  @Test
  fun `stop without an active timer just stops the service`() {
    send(PomodoroService.ACTION_STOP)
    send(PomodoroService.ACTION_STOPWATCH_STOP)
    send(PomodoroService.ACTION_PAUSE)
    send(PomodoroService.ACTION_SKIP)

    assertTrue(recorded.isEmpty())
    assertFalse(timerState.isRunning)
    assertEquals(PomodoroPhase.WORK, timerState.phase)
    assertTrue(shadowOf(controller.get()).isStoppedBySelf)
  }

  @Test
  fun `completed state survives a settings change until dismissed`() {
    applySettings(PomodoroSettings(totalSets = 1))
    send(PomodoroService.ACTION_START)
    runFor(Duration.ofSeconds(25 * 60 + 1))
    assertEquals(PomodoroPhase.COMPLETED, timerState.phase)
    // 完了後は常駐通知を残さない（サービスはアラート音が鳴り終わるまで残す）
    assertTrue(shadowOf(controller.get()).isForegroundStopped)
    assertFalse(shadowOf(controller.get()).isStoppedBySelf)

    // 完了画面でクイックトグルを操作しても、完了状態のままサービスだけ取り残されない
    PomodoroService.updateSettings(context, timerState.settings.copy(vibrateEnabled = true))
    assertEquals(PomodoroPhase.COMPLETED, timerState.phase)
    assertTrue(timerState.settings.vibrateEnabled)

    send(PomodoroService.ACTION_STOP)
    assertEquals(PomodoroPhase.WORK, timerState.phase)
    assertEquals(25 * 60, recordedSeconds)
    assertTrue(shadowOf(controller.get()).isStoppedBySelf)
  }

  @Test
  fun `overtime keeps counting until next is pressed and is included in the record`() {
    applySettings(PomodoroSettings(continueWorkUntilManual = true))
    send(PomodoroService.ACTION_START)
    runFor(Duration.ofSeconds(25 * 60 + 5))

    assertEquals(PomodoroPhase.WORK, timerState.phase)
    assertTrue(timerState.isRunning)
    assertEquals(0, timerState.timeLeftSeconds)
    assertEquals(5, timerState.overtimeSeconds)
    assertEquals("作業継続中 [1/4] - 超過 +00:05", liveNotificationTitle())

    send(PomodoroService.ACTION_NEXT_PHASE)
    assertEquals(PomodoroPhase.BREAK, timerState.phase)
    assertEquals(0, timerState.overtimeSeconds)
    assertEquals(25 * 60 + 5, recordedSeconds)
  }

  @Test
  fun `skip moves through phases and finishes after the last work set`() {
    applySettings(PomodoroSettings(totalSets = 2, autoStartBreak = false))
    send(PomodoroService.ACTION_START)
    sleepFor(Duration.ofSeconds(30))

    send(PomodoroService.ACTION_SKIP)
    assertEquals(PomodoroPhase.BREAK, timerState.phase)
    assertTrue(timerState.isPaused)
    assertEquals(30, recordedSeconds)

    send(PomodoroService.ACTION_SKIP)
    assertEquals(PomodoroPhase.WORK, timerState.phase)
    assertEquals(2, timerState.currentSet)
    assertTrue(timerState.isRunning)

    send(PomodoroService.ACTION_SKIP)
    assertEquals(PomodoroPhase.COMPLETED, timerState.phase)
    assertEquals(30, recordedSeconds)
  }

  @Test
  fun `stopwatch pause and stop record the measured time`() {
    send(PomodoroService.ACTION_STOPWATCH_START)
    runFor(Duration.ofSeconds(65))
    send(PomodoroService.ACTION_STOPWATCH_PAUSE)

    assertTrue(stopwatchState.isPaused)
    assertEquals(65, stopwatchState.elapsedSeconds)
    assertEquals("ストップウォッチ - 00:01:05", liveNotificationTitle())

    sleepFor(Duration.ofMinutes(1))
    send(PomodoroService.ACTION_STOPWATCH_PAUSE)
    assertEquals(65, stopwatchState.elapsedSeconds)

    send(PomodoroService.ACTION_STOPWATCH_START)
    sleepFor(Duration.ofSeconds(10))
    send(PomodoroService.ACTION_STOPWATCH_STOP)

    assertEquals(75, recordedSeconds)
    assertEquals(PomodoroService.SESSION_TYPE_STOPWATCH, recorded.first().sessionType)
    assertEquals(0, stopwatchState.elapsedSeconds)
    assertFalse(stopwatchState.isRunning)
    assertTrue(shadowOf(controller.get()).isStoppedBySelf)
  }
}
