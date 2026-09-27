package com.example.ui.statistics

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.StatsPeriod
import com.example.model.WorkSession
import com.example.viewmodel.PomodoroViewModel
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max

@Composable
fun StatisticsScreen(
    viewModel: PomodoroViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }

    val allSessions by viewModel.allSessions.collectAsStateWithLifecycle()
    val totalAllTimeSeconds by viewModel.totalWorkSeconds.collectAsStateWithLifecycle()
    val statsPeriod by viewModel.statsPeriod.collectAsStateWithLifecycle()
    val periodOffset by viewModel.statsPeriodOffset.collectAsStateWithLifecycle()
    val selectedBarIndex by viewModel.selectedBarIndex.collectAsStateWithLifecycle()

    // Compute period dates & data based on period and offset
    val periodData = remember(allSessions, statsPeriod, periodOffset) {
        computeStatsData(allSessions, statsPeriod, periodOffset)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.statusBars,
        modifier = modifier
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Header: < カレンダーに戻る
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onBack,
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.testTag("stats_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "戻る",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "カレンダーに戻る",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }

            // Main Statistics Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("statistics_card"),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // 1. Period Selector Segmented Switch: [ 週 ] [ 月 ] [ 年 ]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                                .padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            StatsPeriod.entries.forEach { period ->
                                val isSelected = statsPeriod == period
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                            else Color.Transparent
                                        )
                                        .clickable {
                                            viewModel.setStatsPeriod(period)
                                        }
                                        .testTag("stats_tab_${period.name.lowercase()}"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = period.label,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        ),
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // 2. Navigation Row: < 2026/08/30~2026/09/05 >
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { viewModel.shiftStatsPeriod(-1) },
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("stats_prev_period")
                            ) {
                                Icon(Icons.Filled.ChevronLeft, contentDescription = "前へ")
                            }

                            Text(
                                text = periodData.periodLabel,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            IconButton(
                                onClick = { viewModel.shiftStatsPeriod(1) },
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("stats_next_period")
                            ) {
                                Icon(Icons.Filled.ChevronRight, contentDescription = "次へ")
                            }
                        }

                        // 3. 作業時間合計 (Period Total)
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "作業時間合計",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = formatSecondsToHMS(periodData.totalSeconds),
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag("stats_period_total_time")
                            )
                        }

                        // 4. Interactive Bar Chart with Grid and Tooltip
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp)
                                .padding(vertical = 8.dp)
                                .testTag("stats_bar_chart_container")
                        ) {
                            BarChartView(
                                items = periodData.items,
                                maxSeconds = periodData.maxSeconds,
                                selectedIndex = selectedBarIndex,
                                onSelectIndex = { viewModel.selectBar(it) }
                            )

                            // Tooltip Box when a bar is selected (Screenshot 3 style!)
                            selectedBarIndex?.let { index ->
                                if (index in periodData.items.indices) {
                                    val item = periodData.items[index]
                                    Card(
                                        shape = RoundedCornerShape(10.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surface
                                        ),
                                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                                        modifier = Modifier
                                            .align(Alignment.TopCenter)
                                            .border(
                                                width = 1.5.dp,
                                                color = MaterialTheme.colorScheme.primary,
                                                shape = RoundedCornerShape(10.dp)
                                            )
                                            .testTag("stats_selected_bar_tooltip")
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text(
                                                text = item.fullDateLabel,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = formatSecondsToHMS(item.seconds),
                                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 5. Bottom: 総作業時間 (All-time Total Work Time)
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "総作業時間",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = formatSecondsToHMS(totalAllTimeSeconds),
                        fontSize = 38.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        letterSpacing = 2.sp,
                        modifier = Modifier.testTag("stats_all_time_total")
                    )
                }
            }
        }
    }
}

@Composable
private fun BarChartView(
    items: List<ChartBarItem>,
    maxSeconds: Long,
    selectedIndex: Int?,
    onSelectIndex: (Int) -> Unit
) {
    val barColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
    val selectedBarColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    // Determine Y-axis max ceiling: at least 4 hours (14400s) or rounded up to nearest 1-2 hours
    val yCeilingSeconds = remember(maxSeconds) {
        val baseHours = max(1L, (maxSeconds + 3599) / 3600)
        max(4L, baseHours) * 3600L
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()

        // Reserve space for Y-axis labels on left (approx 42dp) and bottom for X labels (approx 36dp)
        val leftMargin = 110f
        val bottomMargin = 85f
        val chartAreaWidth = width - leftMargin
        val chartAreaHeight = height - bottomMargin

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(items) {
                    detectTapGestures { offset ->
                        if (offset.x >= leftMargin && offset.x <= width && offset.y <= chartAreaHeight + 20f) {
                            val relX = offset.x - leftMargin
                            val colWidth = chartAreaWidth / items.size.coerceAtLeast(1)
                            val idx = (relX / colWidth).toInt().coerceIn(0, items.size - 1)
                            onSelectIndex(idx)
                        }
                    }
                }
        ) {
            // Draw horizontal grid lines and Y-axis labels (00:00, 01:00, ...)
            val gridSteps = 4
            for (i in 0..gridSteps) {
                val ratio = i.toFloat() / gridSteps
                val y = chartAreaHeight * (1f - ratio)
                val secsForLine = (yCeilingSeconds * ratio).toLong()
                val h = secsForLine / 3600
                val label = String.format(Locale.US, "%02d:00", h)

                // Grid line
                drawLine(
                    color = gridColor,
                    start = Offset(leftMargin, y),
                    end = Offset(width, y),
                    strokeWidth = 1.dp.toPx()
                )

                // Y-axis text
                drawContext.canvas.nativeCanvas.apply {
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.GRAY
                        textSize = 24f
                        textAlign = android.graphics.Paint.Align.RIGHT
                        isAntiAlias = true
                    }
                    drawText(label, leftMargin - 12f, y + 8f, paint)
                }
            }

            if (items.isEmpty()) return@Canvas

            // Draw bars
            val colWidth = chartAreaWidth / items.size
            val barWidth = (colWidth * 0.65f).coerceAtLeast(6f)

            items.forEachIndexed { index, item ->
                val barHeight = if (yCeilingSeconds > 0) {
                    ((item.seconds.toFloat() / yCeilingSeconds.toFloat()) * chartAreaHeight).coerceAtMost(chartAreaHeight)
                } else 0f

                val x = leftMargin + (index * colWidth) + ((colWidth - barWidth) / 2f)
                val y = chartAreaHeight - barHeight

                val isSelected = selectedIndex == index

                // Bar rectangle with rounded top corners
                if (barHeight > 0f) {
                    drawRoundRect(
                        color = if (isSelected) selectedBarColor else barColor,
                        topLeft = Offset(x, y),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                    )

                    if (isSelected) {
                        // Highlight border
                        drawRoundRect(
                            color = Color(0xFF00E5FF), // glowing cyan highlight
                            topLeft = Offset(x - 2f, y - 2f),
                            size = Size(barWidth + 4f, barHeight + 2f),
                            cornerRadius = CornerRadius(7.dp.toPx(), 7.dp.toPx()),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                        )
                    }
                }

                // X-axis label (Day and day of week)
                // For weekly: show both lines e.g. "30\n(日)"
                // For monthly: show every 7th day or selected
                val shouldShowLabel = items.size <= 7 || index % 7 == 0 || index == items.size - 1 || isSelected
                if (shouldShowLabel) {
                    val labelText = item.shortLabel
                    val lines = labelText.split("\n")
                    lines.forEachIndexed { lineIdx, line ->
                        drawContext.canvas.nativeCanvas.apply {
                            val paint = android.graphics.Paint().apply {
                                color = if (isSelected) android.graphics.Color.WHITE else android.graphics.Color.LTGRAY
                                textSize = if (items.size > 14) 20f else 24f
                                textAlign = android.graphics.Paint.Align.CENTER
                                isFakeBoldText = isSelected
                                isAntiAlias = true
                            }
                            drawText(line, x + (barWidth / 2f), chartAreaHeight + 28f + (lineIdx * 24f), paint)
                        }
                    }
                }
            }
        }
    }
}

private data class ChartBarItem(
    val shortLabel: String,
    val fullDateLabel: String,
    val seconds: Long
)

private data class PeriodStats(
    val periodLabel: String,
    val totalSeconds: Long,
    val maxSeconds: Long,
    val items: List<ChartBarItem>
)

private fun computeStatsData(
    sessions: List<WorkSession>,
    period: StatsPeriod,
    offset: Int
): PeriodStats {
    val cal = Calendar.getInstance()

    return when (period) {
        StatsPeriod.WEEK -> {
            // Adjust calendar to requested week
            cal.add(Calendar.WEEK_OF_YEAR, offset)
            cal.firstDayOfWeek = Calendar.SUNDAY
            cal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)

            val weekDateFormat = SimpleDateFormat("yyyy/MM/dd", Locale.US)
            val dayKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val fullDateFormat = SimpleDateFormat("yyyy/MM/dd (E)", Locale.JAPAN)
            val dayOfWeekNames = listOf("日", "月", "火", "水", "木", "金", "土")

            val startCal = cal.clone() as Calendar
            val startDateStr = weekDateFormat.format(startCal.time)

            val items = mutableListOf<ChartBarItem>()
            var totalPeriodSecs = 0L
            var maxPeriodSecs = 0L

            for (i in 0..6) {
                val currentCal = startCal.clone() as Calendar
                currentCal.add(Calendar.DAY_OF_YEAR, i)
                val key = dayKeyFormat.format(currentCal.time)
                val dayOfMonth = currentCal.get(Calendar.DAY_OF_MONTH)
                val dayOfWeek = dayOfWeekNames[i]

                val daySecs = sessions.filter { it.date == key }.sumOf { it.durationSeconds.toLong() }
                totalPeriodSecs += daySecs
                if (daySecs > maxPeriodSecs) maxPeriodSecs = daySecs

                items.add(
                    ChartBarItem(
                        shortLabel = "$dayOfMonth\n($dayOfWeek)",
                        fullDateLabel = fullDateFormat.format(currentCal.time),
                        seconds = daySecs
                    )
                )
            }

            val endCal = startCal.clone() as Calendar
            endCal.add(Calendar.DAY_OF_YEAR, 6)
            val endDateStr = weekDateFormat.format(endCal.time)

            PeriodStats(
                periodLabel = "$startDateStr~$endDateStr",
                totalSeconds = totalPeriodSecs,
                maxSeconds = maxPeriodSecs,
                items = items
            )
        }
        StatsPeriod.MONTH -> {
            cal.add(Calendar.MONTH, offset)
            cal.set(Calendar.DAY_OF_MONTH, 1)

            val monthLabelFormat = SimpleDateFormat("yyyy/MM", Locale.US)
            val dayKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val fullDateFormat = SimpleDateFormat("yyyy/MM/dd (E)", Locale.JAPAN)

            val maxDaysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            val items = mutableListOf<ChartBarItem>()
            var totalPeriodSecs = 0L
            var maxPeriodSecs = 0L

            val dayOfWeekFormat = SimpleDateFormat("E", Locale.JAPAN)

            for (day in 1..maxDaysInMonth) {
                val currentCal = cal.clone() as Calendar
                currentCal.set(Calendar.DAY_OF_MONTH, day)
                val key = dayKeyFormat.format(currentCal.time)

                val daySecs = sessions.filter { it.date == key }.sumOf { it.durationSeconds.toLong() }
                totalPeriodSecs += daySecs
                if (daySecs > maxPeriodSecs) maxPeriodSecs = daySecs

                items.add(
                    ChartBarItem(
                        shortLabel = "$day\n(${dayOfWeekFormat.format(currentCal.time)})",
                        fullDateLabel = fullDateFormat.format(currentCal.time),
                        seconds = daySecs
                    )
                )
            }

            PeriodStats(
                periodLabel = monthLabelFormat.format(cal.time),
                totalSeconds = totalPeriodSecs,
                maxSeconds = maxPeriodSecs,
                items = items
            )
        }
        StatsPeriod.YEAR -> {
            cal.add(Calendar.YEAR, offset)
            val year = cal.get(Calendar.YEAR)
            val yearLabel = "$year"

            val items = mutableListOf<ChartBarItem>()
            var totalPeriodSecs = 0L
            var maxPeriodSecs = 0L

            for (m in 1..12) {
                val prefix = String.format(Locale.US, "%04d-%02d", year, m)
                val monthSecs = sessions.filter { it.date.startsWith(prefix) }.sumOf { it.durationSeconds.toLong() }
                totalPeriodSecs += monthSecs
                if (monthSecs > maxPeriodSecs) maxPeriodSecs = monthSecs

                items.add(
                    ChartBarItem(
                        shortLabel = "${m}月",
                        fullDateLabel = "${year}年${m}月",
                        seconds = monthSecs
                    )
                )
            }

            PeriodStats(
                periodLabel = yearLabel,
                totalSeconds = totalPeriodSecs,
                maxSeconds = maxPeriodSecs,
                items = items
            )
        }
    }
}

private fun formatSecondsToHMS(totalSecs: Long): String {
    val h = totalSecs / 3600
    val m = (totalSecs % 3600) / 60
    val s = totalSecs % 60
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}
