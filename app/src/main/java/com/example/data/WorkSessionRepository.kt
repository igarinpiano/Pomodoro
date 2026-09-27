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
                    durationSeconds = durationSeconds.toInt(),
                    sessionType = "MANUAL",
                    note = "手動入力"
                )
            )
        }
    }

    suspend fun getAllSessionsSnapshot(): List<WorkSession> {
        return dao.getAllSessionsSnapshot()
    }

    /**
     * JSON形式に全作業履歴をエクスポートする
     */
    suspend fun exportToJson(): String {
        val sessions = dao.getAllSessionsSnapshot()
        val jsonArray = JSONArray()
        for (session in sessions) {
            val obj = JSONObject().apply {
                put("id", session.id)
                put("date", session.date)
                put("startTimeMillis", session.startTimeMillis)
                put("durationSeconds", session.durationSeconds)
                put("sessionType", session.sessionType)
                put("note", session.note)
            }
            jsonArray.put(obj)
        }
        val root = JSONObject().apply {
            put("version", 1)
            put("appName", "PomodoroTimer")
            put("exportedAt", System.currentTimeMillis())
            put("sessionCount", sessions.size)
            put("sessions", jsonArray)
        }
        return root.toString(2)
    }

    /**
     * JSON形式から作業履歴をインポートする
     * @param jsonString インポート元のJSON文字列
     * @param clearExisting 既存データを消去して置き換えるか
     * @return インポートされたセッション数
     */
    suspend fun importFromJson(jsonString: String, clearExisting: Boolean = false): Result<Int> {
        return runCatching {
            val root = JSONObject(jsonString)
            val jsonArray = if (root.has("sessions")) {
                root.getJSONArray("sessions")
            } else if (jsonString.trim().startsWith("[")) {
                JSONArray(jsonString)
            } else {
                throw IllegalArgumentException("有効なセッションデータが見つかりませんでした")
            }

            val list = mutableListOf<WorkSession>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val date = obj.optString("date", "")
                val duration = obj.optInt("durationSeconds", 0)
                if (date.isNotBlank() && duration > 0) {
                    list.add(
                        WorkSession(
                            id = 0, // Auto-generate IDs on import to avoid conflicts
                            date = date,
                            startTimeMillis = obj.optLong("startTimeMillis", System.currentTimeMillis()),
                            durationSeconds = duration,
                            sessionType = obj.optString("sessionType", "POMODORO"),
                            note = obj.optString("note", "")
                        )
                    )
                }
            }

            if (clearExisting) {
                dao.clearAll()
            }

            if (list.isNotEmpty()) {
                dao.insertAll(list)
            }

            list.size
        }
    }
}
