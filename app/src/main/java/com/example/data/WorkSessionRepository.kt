package com.example.data

import com.example.model.WorkSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

class WorkSessionRepository(private val dao: WorkSessionDao) {

    val dailyTotals: Flow<List<DailyWorkTotal>> = dao.getDailyTotals()

    suspend fun insertSession(session: WorkSession): Long {
        return dao.insert(session)
    }

    suspend fun deleteSession(id: Long) {
        dao.deleteById(id)
    }

    suspend fun clearAll() {
        dao.clearAll()
    }

    suspend fun setWorkDurationForDate(date: String, durationSeconds: Long) {
        val session = if (durationSeconds > 0) {
            WorkSession(
                date = date,
                startTimeMillis = System.currentTimeMillis(),
                durationSeconds = durationSeconds.toSafeInt()
            )
        } else {
            null
        }
        dao.replaceSessionsForDate(date, session)
    }

    suspend fun getAllSessionsSnapshot(): List<WorkSession> {
        return dao.getAllSessionsSnapshot()
    }

    /**
     * JSON形式に日付ごとの作業時間をエクスポートする
     */
    suspend fun exportToJson(): String {
        val jsonArray = JSONArray()
        for ((date, seconds) in dao.getDailyTotalsSnapshot()) {
            if (seconds > 0) {
                val obj = JSONObject().apply {
                    put("date", date)
                    put("durationSeconds", seconds)
                }
                jsonArray.put(obj)
            }
        }

        val root = JSONObject().apply {
            put("version", 1)
            put("appName", "PomodoroTimer")
            put("exportedAt", System.currentTimeMillis())
            put("daysCount", jsonArray.length())
            put("dailyRecords", jsonArray)
        }
        return root.toString(2)
    }

    /**
     * JSON形式から作業履歴をインポートする
     * @param jsonString インポート元のJSON文字列
     * @param clearExisting 既存データを消去して置き換えるか
     * @return インポートされた日数
     */
    suspend fun importFromJson(jsonString: String, clearExisting: Boolean = false): Result<Int> {
        return runCatching {
            // ファイルから読み込んだ場合に付くことがある BOM を取り除く
            val trimmed = jsonString.removePrefix("\uFEFF").trim()
            val jsonArray = if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                val root = JSONObject(trimmed)
                when {
                    root.has("dailyRecords") -> root.getJSONArray("dailyRecords")
                    root.has("records") -> root.getJSONArray("records")
                    root.has("sessions") -> root.getJSONArray("sessions")
                    else -> throw IllegalArgumentException("有効な作業データが見つかりませんでした")
                }
            }

            // 日付ごとに作業時間を集約
            val importedDaily = mutableMapOf<String, Long>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val date = obj.optString("date", "")
                val duration = obj.optLong("durationSeconds", 0L).let {
                    if (it == 0L) obj.optLong("seconds", 0L) else it
                }
                // カレンダーに表示できない日付の記録が合計にだけ混ざらないよう、形式を確認する
                if (isValidDate(date) && duration > 0) {
                    importedDaily[date] = (importedDaily[date] ?: 0L) + duration
                }
            }

            if (importedDaily.isEmpty()) {
                throw IllegalArgumentException("有効な作業データが見つかりませんでした")
            }

            val listToInsert = importedDaily.map { (date, duration) ->
                WorkSession(
                    id = 0,
                    date = date,
                    startTimeMillis = System.currentTimeMillis(),
                    durationSeconds = duration.toSafeInt()
                )
            }

            dao.replaceSessions(listToInsert, clearExisting)

            listToInsert.size
        }.recoverCatching { error ->
            throw when (error) {
                is CancellationException -> error
                is JSONException -> IllegalArgumentException("JSONの形式が正しくありません", error)
                else -> error
            }
        }.onFailure { if (it is CancellationException) throw it }
    }

    private fun isValidDate(date: String): Boolean {
        if (!DATE_PATTERN.matches(date)) return false
        return try {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(date) != null
        } catch (_: ParseException) {
            false
        }
    }

    private fun Long.toSafeInt(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    private companion object {
        val DATE_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")
    }
}
