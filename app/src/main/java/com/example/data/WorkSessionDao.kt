package com.example.data

import androidx.room.*
import com.example.model.WorkSession
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkSessionDao {
    @Query("SELECT * FROM work_sessions ORDER BY startTimeMillis DESC")
    fun getAllSessions(): Flow<List<WorkSession>>

    @Query("SELECT * FROM work_sessions WHERE date = :date ORDER BY startTimeMillis ASC")
    fun getSessionsForDate(date: String): Flow<List<WorkSession>>

    @Query("SELECT * FROM work_sessions WHERE date BETWEEN :startDate AND :endDate ORDER BY date ASC")
    fun getSessionsBetweenDates(startDate: String, endDate: String): Flow<List<WorkSession>>

    @Query("SELECT DISTINCT date FROM work_sessions WHERE durationSeconds > 0")
    fun getDatesWithWork(): Flow<List<String>>

    @Query("SELECT COALESCE(SUM(durationSeconds), 0) FROM work_sessions")
    fun getTotalWorkSeconds(): Flow<Long>

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
}
