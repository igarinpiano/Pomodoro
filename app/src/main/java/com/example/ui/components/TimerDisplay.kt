package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.PomodoroPhase
import com.example.model.PomodoroTimerState

const val MAX_WORK_MINUTES = 120
const val MAX_BREAK_MINUTES = 60
const val MAX_TOTAL_SETS = 12

private enum class EditDialogType { NONE, SETS, WORK, BREAK }

@Composable
fun TimerCircleDisplay(
    timerState: PomodoroTimerState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSkip: () -> Unit,
    onStop: () -> Unit,
    onUpdateWorkMins: (Int) -> Unit,
    onUpdateBreakMins: (Int) -> Unit,
    onUpdateTotalSets: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val isRunningOrPaused = timerState.isRunning || timerState.isPaused
    val isCompleted = timerState.phase == PomodoroPhase.COMPLETED

    // Material 3 Color Role (Strictly aligns with user's Material You theme)
    val strokeColor = when (timerState.phase) {
        PomodoroPhase.WORK -> MaterialTheme.colorScheme.primary
        PomodoroPhase.BREAK -> MaterialTheme.colorScheme.tertiary
        PomodoroPhase.COMPLETED -> MaterialTheme.colorScheme.secondary
    }
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHigh

    val animatedProgress by animateFloatAsState(
        targetValue = timerState.progress,
        animationSpec = tween(durationMillis = 300),
        label = "GaugeProgress"
    )

    Box(
        modifier = modifier
            .sizeIn(maxWidth = 310.dp, maxHeight = 310.dp)
            .aspectRatio(1f)
            .testTag("timer_circle_container"),
        contentAlignment = Alignment.Center
    ) {
        // Outer Arc Canvas (clean single-color M3 gauge) - all rings share same center/radius
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 10.dp.toPx()
            val diameter = size.minDimension - strokeWidth
            val radius = diameter / 2f
            val topLeft = Offset(
                x = (size.width - diameter) / 2f,
                y = (size.height - diameter) / 2f
            )
            val arcSize = Size(diameter, diameter)

            // Background track ring (subtle) - base for all states
            drawCircle(
                color = trackColor.copy(alpha = 0.5f),
                radius = radius,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            if (isCompleted) {
                // Completed full ring
                drawCircle(
                    color = strokeColor,
                    radius = radius,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            } else if (isRunningOrPaused) {
                val sweepAngle = 360f * animatedProgress
                drawArc(
                    color = strokeColor,
                    startAngle = -90f,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            } else {
                // Standby indicator ring - keep thin design but same centerline as track/progress
                drawCircle(
                    color = strokeColor.copy(alpha = 0.5f),
                    radius = radius,
                    style = Stroke(width = strokeWidth / 2f, cap = StrokeCap.Round)
                )
            }
        }

        // Inner Timer Content (Directly inside without background decorative circle fill)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                isCompleted -> {
                    CompletedTimerView(
                        totalSets = timerState.totalSets,
                        onNext = onStop
                    )
                }
                isRunningOrPaused -> {
                    ActiveTimerView(
                        timerState = timerState,
                        onPause = onPause,
                        onResume = onResume,
                        onSkip = onSkip,
                        onStop = onStop
                    )
                }
                else -> {
                    SetupTimerView(
                        timerState = timerState,
                        onStart = onStart,
                        onUpdateWorkMins = onUpdateWorkMins,
                        onUpdateBreakMins = onUpdateBreakMins,
                        onUpdateTotalSets = onUpdateTotalSets
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveTimerView(
    timerState: PomodoroTimerState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSkip: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier.padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Set Counter & Phase Indicator
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "${timerState.currentSet} / ${timerState.totalSets}",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("active_set_text")
            )
            Text(
                text = timerState.phase.label,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = when (timerState.phase) {
                    PomodoroPhase.WORK -> MaterialTheme.colorScheme.primary
                    PomodoroPhase.BREAK -> MaterialTheme.colorScheme.tertiary
                    PomodoroPhase.COMPLETED -> MaterialTheme.colorScheme.secondary
                },
                modifier = Modifier.testTag("active_phase_text")
            )
        }

        // Large Center Clock Text (mm:ss)
        Text(
            text = timerState.formattedTime,
            fontSize = 58.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            letterSpacing = (-1).sp,
            modifier = Modifier
                .padding(vertical = 4.dp)
                .testTag("active_clock_text")
        )

        // Bottom Control Buttons (Stop ■, Play/Pause ▶/||, Skip ⏭)
        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onStop,
                modifier = Modifier.testTag("active_stop_button")
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Stop,
                            contentDescription = "停止",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            IconButton(
                onClick = { if (timerState.isRunning) onPause() else onResume() },
                modifier = Modifier.testTag("active_play_pause_button")
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(54.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (timerState.isRunning) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (timerState.isRunning) "一時停止" else "再開",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }

            IconButton(
                onClick = onSkip,
                modifier = Modifier.testTag("active_skip_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = "スキップ",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(34.dp)
                )
            }
        }
    }
}

@Composable
private fun CompletedTimerView(
    totalSets: Int,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "🎉",
                fontSize = 40.sp
            )
            Text(
                text = "全セット達成！",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("completed_title_text")
            )
            Text(
                text = "全${totalSets}セット お疲れ様でした！",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("completed_subtitle_text")
            )
        }

        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(44.dp)
                .testTag("completed_next_button")
        ) {
            Text("次へ", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SetupTimerView(
    timerState: PomodoroTimerState,
    onStart: () -> Unit,
    onUpdateWorkMins: (Int) -> Unit,
    onUpdateBreakMins: (Int) -> Unit,
    onUpdateTotalSets: (Int) -> Unit
) {
    val settings = timerState.settings
    var activeDialog by remember { mutableStateOf(EditDialogType.NONE) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 16.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        // Top: Loop / Set Stepper
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "ループ",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(
                    onClick = { if (settings.totalSets > 1) onUpdateTotalSets(settings.totalSets - 1) },
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("setup_total_sets_minus")
                ) {
                    Icon(
                        Icons.Filled.ChevronLeft,
                        contentDescription = "減らす",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .clickable { activeDialog = EditDialogType.SETS }
                ) {
                    Text(
                        text = "${settings.totalSets}",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .testTag("setup_total_sets_value")
                    )
                }

                IconButton(
                    onClick = { if (settings.totalSets < MAX_TOTAL_SETS) onUpdateTotalSets(settings.totalSets + 1) },
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("setup_total_sets_plus")
                ) {
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = "増やす",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Center: Work & Break Steppers
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircleSettingButton(
                title = "作業",
                valueMins = settings.workDurationMinutes,
                onValueChange = onUpdateWorkMins,
                onValueClick = { activeDialog = EditDialogType.WORK },
                maxMins = MAX_WORK_MINUTES,
                testTag = "work_mins_circle"
            )

            CircleSettingButton(
                title = "休憩",
                valueMins = settings.breakDurationMinutes,
                onValueChange = onUpdateBreakMins,
                onValueClick = { activeDialog = EditDialogType.BREAK },
                maxMins = MAX_BREAK_MINUTES,
                testTag = "break_mins_circle"
            )
        }

        // Bottom Start Button
        IconButton(
            onClick = onStart,
            modifier = Modifier
                .size(60.dp)
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape
                )
                .testTag("setup_start_button")
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "スタート",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(38.dp)
            )
        }
    }

    // Direct Keybaord Input Dialogs
    when (activeDialog) {
        EditDialogType.SETS -> {
            NumberInputDialog(
                title = "ループ数",
                initialValue = settings.totalSets,
                minValue = 1,
                maxValue = MAX_TOTAL_SETS,
                unit = "回",
                onConfirm = onUpdateTotalSets,
                onDismiss = { activeDialog = EditDialogType.NONE }
            )
        }
        EditDialogType.WORK -> {
            NumberInputDialog(
                title = "作業時間",
                initialValue = settings.workDurationMinutes,
                minValue = 1,
                maxValue = MAX_WORK_MINUTES,
                unit = "分",
                onConfirm = onUpdateWorkMins,
                onDismiss = { activeDialog = EditDialogType.NONE }
            )
        }
        EditDialogType.BREAK -> {
            NumberInputDialog(
                title = "休憩時間",
                initialValue = settings.breakDurationMinutes,
                minValue = 1,
                maxValue = MAX_BREAK_MINUTES,
                unit = "分",
                onConfirm = onUpdateBreakMins,
                onDismiss = { activeDialog = EditDialogType.NONE }
            )
        }
        EditDialogType.NONE -> {}
    }
}

@Composable
private fun CircleSettingButton(
    title: String,
    valueMins: Int,
    onValueChange: (Int) -> Unit,
    onValueClick: () -> Unit,
    maxMins: Int,
    testTag: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .size(86.dp)
                .testTag(testTag)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Decrease
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { if (valueMins > 1) onValueChange(valueMins - 1) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.ChevronLeft,
                        contentDescription = "減らす",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Value Text (Clickable for direct keyboard input)
                Text(
                    text = "$valueMins",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onValueClick() }
                        .padding(horizontal = 2.dp, vertical = 2.dp)
                )

                // Right Increase
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { if (valueMins < maxMins) onValueChange(valueMins + 1) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = "増やす",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun NumberInputDialog(
    title: String,
    initialValue: Int,
    minValue: Int,
    maxValue: Int,
    unit: String,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val initialStr = initialValue.toString()
    var textFieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = initialStr,
                selection = TextRange(0, initialStr.length)
            )
        )
    }
    val focusRequester = remember { FocusRequester() }
    var isError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "$title の入力") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = textFieldValue,
                    onValueChange = { input ->
                        val digits = input.text.filter { it.isDigit() }
                        textFieldValue = input.copy(text = digits)
                        val num = digits.toIntOrNull()
                        isError = num == null || num !in minValue..maxValue
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            val num = textFieldValue.text.toIntOrNull()
                            if (num != null && num in minValue..maxValue) {
                                onConfirm(num)
                                onDismiss()
                            }
                        }
                    ),
                    suffix = if (unit.isNotEmpty()) { { Text(unit) } } else null,
                    isError = isError,
                    supportingText = {
                        if (isError) {
                            Text("$minValue 〜 $maxValue の数値を入力してください")
                        } else {
                            Text("キーボードで直接入力できます")
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val num = textFieldValue.text.toIntOrNull()
                    if (num != null && num in minValue..maxValue) {
                        onConfirm(num)
                        onDismiss()
                    }
                },
                enabled = !isError && textFieldValue.text.isNotEmpty()
            ) {
                Text("確定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        }
    )
}

