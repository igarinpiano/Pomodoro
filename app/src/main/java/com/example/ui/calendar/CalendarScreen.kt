package com.example.ui.calendar

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.WorkSession
import com.example.viewmodel.PomodoroViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    viewModel: PomodoroViewModel,
    onBack: () -> Unit,
    onNavigateToStats: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    val datesWithWork by viewModel.datesWithWork.collectAsStateWithLifecycle()
    val selectedDate by viewModel.selectedDate.collectAsStateWithLifecycle()
    val sessionsForSelectedDate by viewModel.sessionsForSelectedDate.collectAsStateWithLifecycle()
    val workSecondsForSelectedDate by viewModel.workSecondsForSelectedDate.collectAsStateWithLifecycle()
    val allSessions by viewModel.allSessions.collectAsStateWithLifecycle()
    val totalAllTimeSeconds by viewModel.totalWorkSeconds.collectAsStateWithLifecycle()

    // Current displayed calendar month (Calendar object)
    var currentDisplayMonth by remember {
        val cal = Calendar.getInstance()
        try {
            val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(selectedDate)
            if (parsed != null) cal.time = parsed
        } catch (_: Exception) {}
        cal.set(Calendar.DAY_OF_MONTH, 1)
        mutableStateOf(cal)
    }

    var showMenu by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var exportJsonText by remember { mutableStateOf("") }
    var showImportDialog by remember { mutableStateOf(false) }
    var importJsonText by remember { mutableStateOf("") }
    var showSessionsDetailDialog by remember { mutableStateOf(false) }
    var showEditTimeDialog by remember { mutableStateOf(false) }
    var editingHours by remember { mutableIntStateOf(0) }
    var editingMinutes by remember { mutableIntStateOf(0) }
    var editingSeconds by remember { mutableIntStateOf(0) }

    val monthFormat = remember { SimpleDateFormat("yyyy/MM", Locale.US) }
    val dayKeyFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }

    fun formatSecondsToHMS(totalSecs: Long): String {
        val h = totalSecs / 3600
        val m = (totalSecs % 3600) / 60
        val s = totalSecs % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "カレンダー",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("calendar_back_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "戻る"
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.testTag("calendar_hamburger_menu_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Menu,
                                contentDescription = "メニュー"
                            )
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("データ管理 (JSON)", fontWeight = FontWeight.Bold) },
                                enabled = false,
                                onClick = {}
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("JSONエクスポート") },
                                leadingIcon = {
                                    Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(20.dp))
                                },
                                onClick = {
                                    showMenu = false
                                    coroutineScope.launch {
                                        val json = viewModel.exportDataToJson()
                                        exportJsonText = json
                                        showExportDialog = true
                                    }
                                },
                                modifier = Modifier.testTag("menu_export_json")
                            )
                            DropdownMenuItem(
                                text = { Text("JSONインポート") },
                                leadingIcon = {
                                    Icon(Icons.Filled.FileUpload, contentDescription = null, modifier = Modifier.size(20.dp))
                                },
                                onClick = {
                                    showMenu = false
                                    importJsonText = ""
                                    showImportDialog = true
                                },
                                modifier = Modifier.testTag("menu_import_json")
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.statusBars,
        modifier = modifier
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Main Calendar Card (Dark / Elevated M3 Container matching user's design)
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("calendar_card"),
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
                        // Month Selector Row: <  2026/09  >
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    val newCal = currentDisplayMonth.clone() as Calendar
                                    newCal.add(Calendar.MONTH, -1)
                                    currentDisplayMonth = newCal
                                },
                                modifier = Modifier
                                    .size(40.dp)
                                    .testTag("calendar_prev_month")
                            ) {
                                Icon(Icons.Filled.ChevronLeft, contentDescription = "前月")
                            }

                            Text(
                                text = monthFormat.format(currentDisplayMonth.time),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            IconButton(
                                onClick = {
                                    val newCal = currentDisplayMonth.clone() as Calendar
                                    newCal.add(Calendar.MONTH, 1)
                                    currentDisplayMonth = newCal
                                },
                                modifier = Modifier
                                    .size(40.dp)
                                    .testTag("calendar_next_month")
                            ) {
                                Icon(Icons.Filled.ChevronRight, contentDescription = "翌月")
                            }
                        }

                        // Day of week header: 日 月 火 水 木 金 土
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            val daysOfWeek = listOf("日", "月", "火", "水", "木", "金", "土")
                            daysOfWeek.forEachIndexed { index, day ->
                                val textColor = when (index) {
                                    0 -> Color(0xFFFF5252) // Sunday red
                                    6 -> Color(0xFF448AFF) // Saturday blue
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                Text(
                                    text = day,
                                    modifier = Modifier.weight(1f),
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = textColor
                                )
                            }
                        }

                        // Month Grid Generation
                        val calendarDays = remember(currentDisplayMonth) {
                            buildCalendarMonthDays(currentDisplayMonth)
                        }

                        // Grid Rows
                        calendarDays.chunked(7).forEach { week ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceAround
                            ) {
                                week.forEach { dayInfo ->
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(1f)
                                            .padding(3.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (dayInfo.isCurrentMonth) {
                                            val isSelected = dayInfo.dateString == selectedDate
                                            val hasWork = datesWithWork.contains(dayInfo.dateString)

                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                                        else MaterialTheme.colorScheme.surface
                                                    )
                                                    .border(
                                                        width = if (isSelected) 2.dp else 1.dp,
                                                        color = if (isSelected) MaterialTheme.colorScheme.primary
                                                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                                        shape = CircleShape
                                                    )
                                                    .clickable {
                                                        viewModel.selectDate(dayInfo.dateString)
                                                    }
                                                    .testTag("calendar_day_${dayInfo.dayNumber}"),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = dayInfo.dayNumber.toString(),
                                                    style = MaterialTheme.typography.bodyMedium.copy(
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                    ),
                                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                                    else MaterialTheme.colorScheme.onSurface
                                                )

                                                // Orange checkmark badge on top-right of circle if work was done
                                                if (hasWork) {
                                                    Box(
                                                        modifier = Modifier
                                                            .align(Alignment.TopEnd)
                                                            .padding(1.dp)
                                                            .size(12.dp)
                                                            .clip(CircleShape)
                                                            .background(Color(0xFFFF9800)),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Filled.Check,
                                                            contentDescription = "達成",
                                                            tint = Color.White,
                                                            modifier = Modifier.size(8.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        } else {
                                            // Empty cell for preceding/succeeding padding
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.2f))
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Bottom Section: Header with "作業時間" and stats icon button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "作業時間",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            // Quick stats icon button
                            IconButton(
                                onClick = onNavigateToStats,
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .testTag("calendar_to_stats_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Leaderboard,
                                    contentDescription = "統計へ移動",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )

                        // Bottom Row: Perfectly Centered Recorded Time & Edit Button on the right
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = formatSecondsToHMS(workSecondsForSelectedDate),
                                style = MaterialTheme.typography.headlineLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 36.sp,
                                    letterSpacing = 1.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .testTag("calendar_selected_day_work_time")
                            )

                            // Edit Button (pencil icon in square container as circled in screenshot)
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.8f),
                                border = androidx.compose.foundation.BorderStroke(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant
                                ),
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .size(42.dp)
                                    .clickable {
                                        editingHours = (workSecondsForSelectedDate / 3600).toInt()
                                        editingMinutes = ((workSecondsForSelectedDate % 3600) / 60).toInt()
                                        editingSeconds = (workSecondsForSelectedDate % 60).toInt()
                                        showEditTimeDialog = true
                                    }
                                    .testTag("calendar_edit_work_time_button")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Filled.Edit,
                                        contentDescription = "作業時間を変更",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }

                        // Stable fixed-height Breakdown row (prevents UI height jump across date selections)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            if (sessionsForSelectedDate.isNotEmpty()) {
                                TextButton(
                                    onClick = { showSessionsDetailDialog = true },
                                    modifier = Modifier.testTag("calendar_view_day_sessions_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.List,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "内訳 (${sessionsForSelectedDate.size}件)",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Work Time Statistics Card (作業時間の統計 - placed nicely below calendar)
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("calendar_statistics_card"),
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
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Title row with icon and navigation button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Filled.BarChart,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "作業時間の統計",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            FilledTonalButton(
                                onClick = onNavigateToStats,
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.testTag("calendar_to_stats_button")
                            ) {
                                Text("グラフを見る", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        // Summary metric chips
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Card(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "累計作業時間",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = formatSecondsToHMS(totalAllTimeSeconds),
                                        style = MaterialTheme.typography.titleLarge.copy(
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 0.5.sp
                                        ),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            Card(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "総セッション数",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${allSessions.size} 回",
                                        style = MaterialTheme.typography.titleLarge.copy(
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        // Recent 7-Day Mini Bar Graph Preview (clickable to open full interactive stats)
                        RecentDaysBarPreview(
                            allSessions = allSessions,
                            onNavigateToStats = onNavigateToStats
                        )
                    }
                }
            }
        }
    }

    // Detail Sessions List Dialog
    if (showSessionsDetailDialog) {
        AlertDialog(
            onDismissRequest = { showSessionsDetailDialog = false },
            title = { Text("$selectedDate の作業履歴 (${sessionsForSelectedDate.size}件)") },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sessionsForSelectedDate) { session ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                val (typeLabel, typeColor) = when (session.sessionType) {
                                    "POMODORO" -> "ポモドーロ作業" to MaterialTheme.colorScheme.primary
                                    "STOPWATCH" -> "ストップウォッチ" to MaterialTheme.colorScheme.secondary
                                    "MANUAL" -> "手動追加" to MaterialTheme.colorScheme.tertiary
                                    else -> session.sessionType to MaterialTheme.colorScheme.primary
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = typeLabel,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = typeColor
                                    )
                                    if (session.sessionType == "MANUAL") {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.tertiaryContainer
                                        ) {
                                            Text(
                                                text = "手動調整",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                val timeFormat = SimpleDateFormat("HH:mm", Locale.US)
                                val timingLabel = if (session.sessionType == "MANUAL") {
                                    "手動追加 (${timeFormat.format(Date(session.startTimeMillis))})"
                                } else {
                                    "開始: ${timeFormat.format(Date(session.startTimeMillis))}"
                                }
                                Text(
                                    text = "$timingLabel  |  時間: ${formatSecondsToHMS(session.durationSeconds.toLong())}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = { viewModel.deleteSession(session.id) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = "削除", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSessionsDetailDialog = false }) {
                    Text("閉じる")
                }
            }
        )
    }

    // Export Dialog
    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("JSONエクスポート完了") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("以下のJSONをコピーして保存してください:")
                    OutlinedTextField(
                        value = exportJsonText,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(exportJsonText))
                        Toast.makeText(context, "クリップボードにコピーしました", Toast.LENGTH_SHORT).show()
                        showExportDialog = false
                    }
                ) {
                    Text("コピー")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text("閉じる")
                }
            }
        )
    }

    // Import Dialog
    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("JSONインポート") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("エクスポートしたJSONデータを貼り付けてください:")
                    OutlinedTextField(
                        value = importJsonText,
                        onValueChange = { importJsonText = it },
                        placeholder = { Text("{\n  \"sessions\": [...]\n}") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            val result = viewModel.importDataFromJson(importJsonText)
                            result.onSuccess { count ->
                                Toast.makeText(context, "${count}件のセッションをインポートしました", Toast.LENGTH_SHORT).show()
                                showImportDialog = false
                            }.onFailure { err ->
                                Toast.makeText(context, "インポート失敗: ${err.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = importJsonText.isNotBlank()
                ) {
                    Text("インポート")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("キャンセル")
                }
            }
        )
    }

    // Edit Work Time Dialog (allows editing the selected day's work duration)
    if (showEditTimeDialog) {
        var hStr by remember(showEditTimeDialog) { mutableStateOf(editingHours.toString()) }
        var mStr by remember(showEditTimeDialog) { mutableStateOf(editingMinutes.toString()) }
        var sStr by remember(showEditTimeDialog) { mutableStateOf(editingSeconds.toString()) }

        AlertDialog(
            onDismissRequest = { showEditTimeDialog = false },
            title = {
                Text(
                    text = "作業時間の変更",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "${selectedDate.replace("-", "/")} の合計作業時間",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Hours : Minutes : Seconds row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Hours
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width(68.dp)
                        ) {
                            Text("時間", style = MaterialTheme.typography.labelSmall)
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = hStr,
                                onValueChange = { input ->
                                    if (input.isEmpty() || (input.length <= 2 && input.all { it.isDigit() })) {
                                        hStr = input
                                    }
                                },
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                                ),
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(
                                    textAlign = TextAlign.Center,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                ),
                                modifier = Modifier.testTag("edit_work_time_hours")
                            )
                        }

                        Text(
                            text = ":",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            modifier = Modifier.padding(horizontal = 6.dp)
                        )

                        // Minutes
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width(68.dp)
                        ) {
                            Text("分", style = MaterialTheme.typography.labelSmall)
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = mStr,
                                onValueChange = { input ->
                                    if (input.isEmpty() || (input.length <= 2 && input.all { it.isDigit() })) {
                                        val v = input.toIntOrNull() ?: 0
                                        if (v in 0..59 || input.isEmpty()) mStr = input
                                    }
                                },
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                                ),
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(
                                    textAlign = TextAlign.Center,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                ),
                                modifier = Modifier.testTag("edit_work_time_minutes")
                            )
                        }

                        Text(
                            text = ":",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            modifier = Modifier.padding(horizontal = 6.dp)
                        )

                        // Seconds
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width(68.dp)
                        ) {
                            Text("秒", style = MaterialTheme.typography.labelSmall)
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = sStr,
                                onValueChange = { input ->
                                    if (input.isEmpty() || (input.length <= 2 && input.all { it.isDigit() })) {
                                        val v = input.toIntOrNull() ?: 0
                                        if (v in 0..59 || input.isEmpty()) sStr = input
                                    }
                                },
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                                ),
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(
                                    textAlign = TextAlign.Center,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                ),
                                modifier = Modifier.testTag("edit_work_time_seconds")
                            )
                        }
                    }

                    // Quick adjustment chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SuggestionChip(
                            onClick = {
                                val currentM = mStr.toIntOrNull() ?: 0
                                val currentH = hStr.toIntOrNull() ?: 0
                                val newTotalM = currentH * 60 + currentM + 15
                                hStr = (newTotalM / 60).toString()
                                mStr = (newTotalM % 60).toString()
                            },
                            label = { Text("+15m", fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                        SuggestionChip(
                            onClick = {
                                val currentM = mStr.toIntOrNull() ?: 0
                                val currentH = hStr.toIntOrNull() ?: 0
                                val newTotalM = currentH * 60 + currentM + 30
                                hStr = (newTotalM / 60).toString()
                                mStr = (newTotalM % 60).toString()
                            },
                            label = { Text("+30m", fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                        SuggestionChip(
                            onClick = {
                                val currentH = hStr.toIntOrNull() ?: 0
                                hStr = (currentH + 1).toString()
                            },
                            label = { Text("+1h", fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                        SuggestionChip(
                            onClick = {
                                hStr = "0"
                                mStr = "0"
                                sStr = "0"
                            },
                            label = { Text("Reset", fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val h = hStr.toLongOrNull() ?: 0L
                        val m = mStr.toLongOrNull() ?: 0L
                        val s = sStr.toLongOrNull() ?: 0L
                        val totalSecs = (h * 3600L + m * 60L + s).coerceAtLeast(0L)
                        viewModel.updateWorkTimeForDate(selectedDate, totalSecs)
                        Toast.makeText(context, "作業時間を保存しました", Toast.LENGTH_SHORT).show()
                        showEditTimeDialog = false
                    },
                    modifier = Modifier.testTag("edit_work_time_confirm_button")
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditTimeDialog = false }) {
                    Text("キャンセル")
                }
            }
        )
    }
}

private data class CalendarDayInfo(
    val dayNumber: Int,
    val dateString: String,
    val isCurrentMonth: Boolean
)

private fun buildCalendarMonthDays(cal: Calendar): List<CalendarDayInfo> {
    val result = mutableListOf<CalendarDayInfo>()
    val tempCal = cal.clone() as Calendar
    tempCal.set(Calendar.DAY_OF_MONTH, 1)

    val firstDayOfWeek = tempCal.get(Calendar.DAY_OF_WEEK) // 1 (Sunday) .. 7 (Saturday)
    val leadingEmpty = firstDayOfWeek - 1

    for (i in 0 until leadingEmpty) {
        result.add(CalendarDayInfo(0, "", isCurrentMonth = false))
    }

    val maxDays = tempCal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    for (day in 1..maxDays) {
        tempCal.set(Calendar.DAY_OF_MONTH, day)
        result.add(
            CalendarDayInfo(
                dayNumber = day,
                dateString = sdf.format(tempCal.time),
                isCurrentMonth = true
            )
        )
    }

    // Complete grid to always have exactly 42 days (6 full weeks) for stable fixed height
    while (result.size < 42) {
        result.add(CalendarDayInfo(0, "", isCurrentMonth = false))
    }

    return result
}

@Composable
private fun RecentDaysBarPreview(
    allSessions: List<WorkSession>,
    onNavigateToStats: () -> Unit
) {
    val dayKeyFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    val dayLabelFormat = remember { SimpleDateFormat("E", Locale.JAPANESE) }

    // Last 7 days including today
    val recentDays = remember(allSessions) {
        val list = mutableListOf<Pair<String, Long>>()
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -6)
        for (i in 0 until 7) {
            val key = dayKeyFormat.format(cal.time)
            val label = dayLabelFormat.format(cal.time)
            val daySecs = allSessions.filter { it.date == key }.sumOf { it.durationSeconds.toLong() }
            list.add(Pair(label, daySecs))
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        list
    }

    val maxSecs = remember(recentDays) {
        maxOf(1L, recentDays.maxOf { it.second })
    }

    Surface(
        onClick = onNavigateToStats,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("stats_preview_click_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "直近7日間の作業状況",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "タップして詳細へ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // 7 bars preview
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom
            ) {
                recentDays.forEach { (label, secs) ->
                    val fraction = if (maxSecs > 0) (secs.toFloat() / maxSecs.toFloat()).coerceIn(0.06f, 1f) else 0.06f
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight(fraction)
                                .width(14.dp)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(
                                    if (secs > 0) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                )
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
