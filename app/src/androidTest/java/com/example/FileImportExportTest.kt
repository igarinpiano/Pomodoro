package com.example

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.AppDatabase
import com.example.model.WorkSession
import com.example.viewmodel.PomodoroViewModel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 記録のファイル書き出し／読み込みを、実際の Android 上で確認する。
 * （システムのファイル選択画面は経由せず、アプリ側の読み書き処理だけを対象にする）
 */
@RunWith(AndroidJUnit4::class)
class FileImportExportTest {

  @Test
  fun exportToFileAndImportBack() = runBlocking<Unit> {
    val application = ApplicationProvider.getApplicationContext<Application>()
    val dao = AppDatabase.getInstance(application).workSessionDao()
    dao.clearAll()
    dao.insertAll(
      listOf(
        WorkSession(date = "2026-09-26", durationSeconds = 1500),
        WorkSession(date = "2026-09-26", durationSeconds = 900),
        WorkSession(date = "2026-09-27", durationSeconds = 600)
      )
    )
    val viewModel = PomodoroViewModel(application)

    // すでに長い内容が入っているファイルに上書きしても、古い内容の末尾が残らないこと
    val file = File(application.cacheDir, "export_test.json")
    file.writeText("x".repeat(20_000))
    val uri = Uri.fromFile(file)

    assertTrue(viewModel.exportDataToFile(uri).isSuccess)
    val exported = JSONObject(file.readText())
    assertEquals(2, exported.getInt("daysCount"))
    assertTrue(file.length() < 20_000)

    dao.clearAll()
    assertEquals(2, viewModel.importDataFromFile(uri).getOrNull())
    val totals = dao.getDailyTotalsSnapshot().associate { it.date to it.totalSeconds }
    assertEquals(mapOf("2026-09-26" to 2400L, "2026-09-27" to 600L), totals)

    // 読めないファイルはエラーとして返り、記録は変わらない
    assertTrue(viewModel.importDataFromFile(Uri.fromFile(File(application.cacheDir, "missing.json"))).isFailure)
    assertEquals(2, dao.getDailyTotalsSnapshot().size)

    dao.clearAll()
    file.delete()
  }
}
