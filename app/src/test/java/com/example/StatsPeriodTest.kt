package com.example

import com.example.model.StatsPeriod
import com.example.ui.statistics.computeStatsData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** 統計画面の期間（週・月・年）の区切りと集計。週の始まりが月曜の地域設定でも日曜始まりで表示されること。 */
class StatsPeriodTest {

    private lateinit var originalLocale: Locale
    private lateinit var originalTimeZone: TimeZone

    @Before
    fun setUp() {
        originalLocale = Locale.getDefault()
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
        TimeZone.setDefault(originalTimeZone)
    }

    private fun day(year: Int, month: Int, day: Int): Calendar =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, 12, 0, 0)
        }

    private fun weekLabels(locale: Locale): List<String> {
        Locale.setDefault(locale)
        return listOf(
            computeStatsData(emptyMap(), StatsPeriod.WEEK, 0, day(2026, 10, 10)).periodLabel, // 土曜
            computeStatsData(emptyMap(), StatsPeriod.WEEK, 0, day(2026, 10, 11)).periodLabel, // 日曜
            computeStatsData(emptyMap(), StatsPeriod.WEEK, 0, day(2026, 10, 12)).periodLabel, // 月曜
            computeStatsData(emptyMap(), StatsPeriod.WEEK, -1, day(2026, 10, 10)).periodLabel,
            computeStatsData(emptyMap(), StatsPeriod.WEEK, 1, day(2026, 12, 30)).periodLabel // 年またぎ
        )
    }

    @Test
    fun weekAlwaysStartsOnSunday() {
        val expected = listOf(
            "2026/10/04〜2026/10/10",
            "2026/10/11〜2026/10/17",
            "2026/10/11〜2026/10/17",
            "2026/09/27〜2026/10/03",
            "2027/01/03〜2027/01/09"
        )
        assertEquals(expected, weekLabels(Locale.JAPAN))
        assertEquals(expected, weekLabels(Locale.US))
        // 週の始まりが月曜の地域
        assertEquals(expected, weekLabels(Locale.GERMANY))
        assertEquals(expected, weekLabels(Locale.UK))
    }

    @Test
    fun weekSumsEachDay() {
        Locale.setDefault(Locale.JAPAN)
        val totals = mapOf("2026-10-07" to 5400L, "2026-10-10" to 600L, "2026-10-11" to 9999L)
        val stats = computeStatsData(totals, StatsPeriod.WEEK, 0, day(2026, 10, 10))

        assertEquals(listOf(0L, 0L, 0L, 5400L, 0L, 0L, 600L), stats.items.map { it.seconds })
        assertEquals(6000L, stats.totalSeconds)
        assertEquals(5400L, stats.maxSeconds)
        assertEquals("4\n(日)", stats.items.first().shortLabel)
        assertEquals("10\n(土)", stats.items.last().shortLabel)
    }

    @Test
    fun monthHandlesDifferentLengths() {
        Locale.setDefault(Locale.JAPAN)
        // 31日の月の月末から翌月・翌々月へ
        val november = computeStatsData(mapOf("2026-11-30" to 60L), StatsPeriod.MONTH, 1, day(2026, 10, 31))
        assertEquals("2026/11", november.periodLabel)
        assertEquals(30, november.items.size)
        assertEquals(60L, november.items.last().seconds)

        val february = computeStatsData(emptyMap(), StatsPeriod.MONTH, 4, day(2026, 10, 31))
        assertEquals("2027/02", february.periodLabel)
        assertEquals(28, february.items.size)

        val leapFebruary = computeStatsData(emptyMap(), StatsPeriod.MONTH, 0, day(2028, 2, 10))
        assertEquals(29, leapFebruary.items.size)
    }

    @Test
    fun yearSumsEachMonth() {
        Locale.setDefault(Locale.JAPAN)
        val totals = mapOf(
            "2026-01-01" to 100L,
            "2026-01-31" to 200L,
            "2026-12-31" to 50L,
            "2025-12-31" to 7777L,
            "2027-01-01" to 8888L
        )
        val stats = computeStatsData(totals, StatsPeriod.YEAR, 0, day(2026, 10, 10))

        assertEquals("2026年", stats.periodLabel)
        assertEquals(12, stats.items.size)
        assertEquals(300L, stats.items[0].seconds)
        assertEquals(50L, stats.items[11].seconds)
        assertEquals(350L, stats.totalSeconds)

        assertEquals(7777L, computeStatsData(totals, StatsPeriod.YEAR, -1, day(2026, 10, 10)).totalSeconds)
    }
}
