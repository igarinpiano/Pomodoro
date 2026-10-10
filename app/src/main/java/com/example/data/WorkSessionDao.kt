package com.example.data

import androidx.room.*
import com.example.model.WorkSession
import kotlinx.coroutines.flow.Flow

/** 1日分の合計作業時間 */
data class DailyWorkTotal(
    val date: String,
    val totalSeconds: Long
)

@Dao
interface WorkSessionDao {
    // 画面側で全セッションを読み込んで集計しなくて済むよう、日別の合計はDBで求める
    @Query("SELECT date, SUM(durationSeconds) AS totalSeconds FROM work_sessions GROUP BY date ORDER BY date ASC")
    fun getDailyTotals(): Flow<List<DailyWorkTotal>>

    @Query("SELECT date, SUM(durationSeconds) AS totalSeconds FROM work_sessions GROUP BY date ORDER BY date ASC")
    suspend fun getDailyTotalsSnapshot(): List<DailyWorkTotal>

    @Query("SELECT * FROM work_sessions")
    suspend fun getAllSessionsSnapshot(): List<WorkSession>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: WorkSession): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sessions: List<WorkSession>)

    @Update
    suspend fun update(session: WorkSession)

    @Delete
    suspend fun delete(session: WorkSession)

    @Query("DELETE FROM work_sessions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM work_sessions WHERE date = :date")
    suspend fun deleteSessionsForDate(date: String)

    @Query("DELETE FROM work_sessions")
    suspend fun clearAll()

    /** 渡した日付の記録を、渡したセッションで置き換える（clearExisting なら全件を置き換える） */
    @Transaction
    suspend fun replaceSessions(sessions: List<WorkSession>, clearExisting: Boolean) {
        if (clearExisting) {
            clearAll()
        } else {
            sessions.map { it.date }.distinct().forEach { deleteSessionsForDate(it) }
        }
        insertAll(sessions)
    }

    @Transaction
    suspend fun replaceSessionsForDate(date: String, session: WorkSession?) {
        deleteSessionsForDate(date)
        if (session != null) {
            insert(session)
        }
    }
}
