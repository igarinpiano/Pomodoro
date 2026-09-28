package com.example.data

import com.example.model.WorkSession
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

class WorkSessionRepository(private val dao: WorkSessionDao) {

    val allSessions: Flow<List<WorkSession>> = dao.getAllSessions()
    val datesWithWork: Flow<List<String>> = dao.getDatesWithWork()
    val totalWorkSeconds: Flow<Long> = dao.getTotalWorkSeconds()

    fun getSessionsForDate(date: String): Flow<List<WorkSession>> {
        return dao.getSessionsForDate(date)
    }

    fun getSessionsBetweenDates(startDate: String, endDate: String): Flow<List<WorkSession>> {
        return dao.getSessionsBetweenDates(startDate, endDate)
    }

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
        dao.deleteSessionsForDate(date)
        if (durationSeconds > 0) {
            dao.insert(
                WorkSession(
                    date = date,
                    startTimeMillis = System.currentTimeMillis(),
                    durationSeconds = durationSeconds.toInt()
                )
            )
        }
    }

    suspend fun getAllSessionsSnapshot(): List<WorkSession> {
        return dao.getAllSessionsSnapshot()
    }

    /**
     * JSON形式に日付ごとの作業時間をエクスポートする
     */
    suspend fun exportToJson(): String {
        val sessions = dao.getAllSessionsSnapshot()
        // 日付ごとに合計作業時間を集計
        val dailyMap = sortedMapOf<String, Long>()
        for (session in sessions) {
            dailyMap[session.date] = (dailyMap[session.date] ?: 0L) + session.durationSeconds
        }

        val jsonArray = JSONArray()
        for ((date, seconds) in dailyMap) {
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
            val trimmed = jsonString.trim()
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
                if (date.isNotBlank() && duration > 0) {
                    importedDaily[date] = (importedDaily[date] ?: 0L) + duration
                }
            }

            if (clearExisting) {
                dao.clearAll()
            }

            val listToInsert = importedDaily.map { (date, duration) ->
                WorkSession(
                    id = 0,
                    date = date,
                    startTimeMillis = System.currentTimeMillis(),
                    durationSeconds = duration.toInt()
                )
            }

            if (listToInsert.isNotEmpty()) {
                if (!clearExisting) {
                    for (entry in listToInsert) {
                        dao.deleteSessionsForDate(entry.date)
                        dao.insert(entry)
                    }
                } else {
                    dao.insertAll(listToInsert)
                }
            }

            listToInsert.size
        }
    }
}
