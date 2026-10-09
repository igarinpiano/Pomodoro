package com.example.ui.calendar

import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.viewmodel.PomodoroViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    val workSecondsForSelectedDate by viewModel.workSecondsForSelectedDate.collectAsStateWithLifecycle()
    val dailyWorkSeconds by viewModel.dailyWorkSeconds.collectAsStateWithLifecycle()
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
    var showEditTimeDialog by remember { mutableStateOf(false) }
    var editingHours by remember { mutableIntStateOf(0) }
    var editingMinutes by remember { mutableIntStateOf(0) }
    var editingSeconds by remember { mutableIntStateOf(0) }

    val monthFormat = remember { SimpleDateFormat("yyyy/MM", Locale.US) }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val exportFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                viewModel.exportDataToFile(uri)
                    .onSuccess {
                        Toast.makeText(context, "ファイルに保存しました", Toast.LENGTH_SHORT).show()
                        showExportDialog = false
                    }
                    .onFailure {
                        Toast.makeText(context, "ファイルの保存に失敗しました", Toast.LENGTH_SHORT).show()
                    }
            }
        }
    }

    val importFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                viewModel.importDataFromFile(uri)
                    .onSuccess { count ->
                        Toast.makeText(context, "${count}日分をインポートしました", Toast.LENGTH_SHORT).show()
                        showImportDialog = false
                    }
                    .onFailure { err ->
                        Toast.makeText(context, "インポートに失敗しました: ${err.message}", Toast.LENGTH_SHORT).show()
                    }
            }
        }
    }

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
                                text = { Text("エクスポート") },
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
                                text = { Text("インポート") },
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
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
        modifier = modifier
    ) { innerPadding ->
        // Main Calendar Card (Dark / Elevated M3 Container matching user's design)
        val calendarCard: @Composable () -> Unit = {
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
                                        }

                                        // Orange checkmark badge on top-right of circle if work was done
                                        // （円でクリップされる Box の外に置き、バッジが欠けないようにする）
                                        if (hasWork) {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .size(12.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0xFFFF9800)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Check,
                                                    contentDescription = "作業あり",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(8.dp)
                                                )
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
                                contentDescription = "グラフを見る",
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

                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }

        // Work Time Statistics Card (作業時間の統計)
        val statisticsCard: @Composable () -> Unit = {
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
                                    text = "作業日数",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${datesWithWork.size} 日",
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
                        dailyWorkSeconds = dailyWorkSeconds,
                        onNavigateToStats = onNavigateToStats
                    )
                }
            }
        }

        val contentModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(horizontal = 20.dp, vertical = 8.dp)

        if (isLandscape) {
            // 横向き: カレンダーと統計を左右に並べ、それぞれ縦にスクロールできるようにする
            Row(
                modifier = contentModifier,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    calendarCard()
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    statisticsCard()
                }
            }
        } else {
            LazyColumn(
                modifier = contentModifier,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item { calendarCard() }
                item { statisticsCard() }
            }
        }
    }

    // Export Dialog
    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("エクスポート") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("日付ごとの作業時間（JSON）")
                    OutlinedTextField(
                        value = exportJsonText,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                    )
                    OutlinedButton(
                        onClick = {
                            val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                            exportFileLauncher.launch("pomodoro_$stamp.json")
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("export_save_file_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Save,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("ファイルに保存")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(exportJsonText))
                        Toast.makeText(context, "クリップボードにコピーしました", Toast.LENGTH_SHORT).show()
                        showExportDialog = false
                    },
                    modifier = Modifier.testTag("export_copy_button")
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
            title = { Text("インポート") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("JSONファイルを選ぶか、内容を貼り付けてください。同じ日付の記録は上書きされます。")
                    OutlinedButton(
                        onClick = {
                            // 端末によって .json が text/plain などで扱われることがあるため、広めに受け付ける
                            importFileLauncher.launch(
                                arrayOf("application/json", "text/*", "application/octet-stream")
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("import_pick_file_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("ファイルを選択")
                    }
                    OutlinedTextField(
                        value = importJsonText,
                        onValueChange = { importJsonText = it },
                        placeholder = { Text("{\n  \"dailyRecords\": [\n    {\"date\": \"2026-09-27\", \"durationSeconds\": 3600}\n  ]\n}") },
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
                                Toast.makeText(context, "${count}日分をインポートしました", Toast.LENGTH_SHORT).show()
                                showImportDialog = false
                            }.onFailure { err ->
                                Toast.makeText(context, "インポートに失敗しました: ${err.message}", Toast.LENGTH_SHORT).show()
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
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

                    // Quick adjustment chips (wrap onto a second row on narrow screens)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
                    ) {
                        fun addMinutes(minutes: Int) {
                            val currentM = mStr.toIntOrNull() ?: 0
                            val currentH = hStr.toIntOrNull() ?: 0
                            val newTotalM = currentH * 60 + currentM + minutes
                            hStr = (newTotalM / 60).toString()
                            mStr = (newTotalM % 60).toString()
                        }
                        SuggestionChip(
                            onClick = { addMinutes(15) },
                            label = { Text("+15分", fontSize = 11.sp) }
                        )
                        SuggestionChip(
                            onClick = { addMinutes(30) },
                            label = { Text("+30分", fontSize = 11.sp) }
                        )
                        SuggestionChip(
                            onClick = { addMinutes(60) },
                            label = { Text("+1時間", fontSize = 11.sp) }
                        )
                        SuggestionChip(
                            onClick = {
                                hStr = "0"
                                mStr = "0"
                                sStr = "0"
                            },
                            label = { Text("リセット", fontSize = 11.sp) }
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
                        // 1日の作業時間は24時間までに丸める
                        val totalSecs = (h * 3600L + m * 60L + s).coerceIn(0L, 24 * 3600L)
                        viewModel.updateWorkTimeForDate(selectedDate, totalSecs)
                        Toast.makeText(context, "保存しました", Toast.LENGTH_SHORT).show()
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
    dailyWorkSeconds: Map<String, Long>,
    onNavigateToStats: () -> Unit
) {
    val dayKeyFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    val dayLabelFormat = remember { SimpleDateFormat("E", Locale.JAPANESE) }

    // Last 7 days including today
    val recentDays = remember(dailyWorkSeconds) {
        val list = mutableListOf<Pair<String, Long>>()
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -6)
        for (i in 0 until 7) {
            val key = dayKeyFormat.format(cal.time)
            val label = dayLabelFormat.format(cal.time)
            list.add(Pair(label, dailyWorkSeconds[key] ?: 0L))
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
            Text(
                text = "直近7日間",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 7 bars preview
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                recentDays.forEach { (label, secs) ->
                    val fraction = (secs.toFloat() / maxSecs.toFloat()).coerceIn(0.06f, 1f)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        // 棒は曜日ラベルを除いた残りの高さの中で伸ばす（最大の日でもラベルが押し出されない）
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.BottomCenter
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
                        }
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
