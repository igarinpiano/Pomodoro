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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.PomodoroPhase
import com.example.model.PomodoroTimerState
import com.example.model.StopwatchState

const val MAX_WORK_MINUTES = 120
const val MAX_BREAK_MINUTES = 60
const val MAX_TOTAL_SETS = 12

private enum class EditDialogType { NONE, SETS, WORK, BREAK }

private val CIRCLE_DESIGN_SIZE = 310.dp

/**
 * 円の中身は 310dp を基準にレイアウトしているため、それより狭い場合（横向き・分割画面・小型端末）は
 * 中身ごと等倍で縮小し、ボタンや文字がはみ出さないようにする。
 */
@Composable
private fun CircleContainer(
    testTag: String,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    BoxWithConstraints(
        modifier = modifier
            .sizeIn(maxWidth = CIRCLE_DESIGN_SIZE, maxHeight = CIRCLE_DESIGN_SIZE)
            .aspectRatio(1f)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        val density = LocalDensity.current
        val scale = (minOf(maxWidth, maxHeight) / CIRCLE_DESIGN_SIZE).coerceIn(0.1f, 1f)
        val scaledDensity = remember(density, scale) {
            Density(density.density * scale, density.fontScale)
        }
        CompositionLocalProvider(LocalDensity provides scaledDensity) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
                content = content
            )
        }
    }
}

@Composable
fun TimerCircleDisplay(
    timerState: PomodoroTimerState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSkip: () -> Unit,
    onStop: () -> Unit,
    onNextPhase: () -> Unit = onSkip,
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

    CircleContainer(
        testTag = "timer_circle_container",
        modifier = modifier
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
                // Calm, stable color change when paused (no blinking)
                val arcColor = if (timerState.isPaused) strokeColor.copy(alpha = 0.38f) else strokeColor
                drawArc(
                    color = arcColor,
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
                        onStop = onStop,
                        onNextPhase = onNextPhase
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
    onStop: () -> Unit,
    onNextPhase: () -> Unit = onSkip
) {
    val isOvertime = timerState.overtimeSeconds > 0

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

            // Fixed-height (34dp) status badge container: zero layout jump or size change on pause
            Box(
                modifier = Modifier.height(34.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isOvertime) {
                    Surface(
                        shape = RoundedCornerShape(17.dp),
                        color = if (timerState.isPaused) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("active_overtime_badge")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 14.dp)
                        ) {
                            if (timerState.isPaused) {
                                Icon(
                                    imageVector = Icons.Filled.Pause,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                            } else {
                                val dotColor = if (timerState.phase == PomodoroPhase.BREAK) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(dotColor, CircleShape)
                                )
                            }
                            val activeLabel = if (timerState.phase == PomodoroPhase.BREAK) "休憩継続中" else "作業継続中"
                            val textColor = when {
                                timerState.isPaused -> MaterialTheme.colorScheme.onSecondaryContainer
                                timerState.phase == PomodoroPhase.BREAK -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.primary
                            }
                            Text(
                                text = if (timerState.isPaused) "一時停止中" else activeLabel,
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = textColor
                            )
                        }
                    }
                } else if (timerState.isPaused) {
                    // Paused state: distinct calm color change in exact same pill dimensions
                    Surface(
                        shape = RoundedCornerShape(17.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("active_paused_badge")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 14.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Pause,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "一時停止中",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                } else {
                    // Running state: subtle surface container in exact same pill dimensions
                    Surface(
                        shape = RoundedCornerShape(17.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("active_running_badge")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 14.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        color = when (timerState.phase) {
                                            PomodoroPhase.WORK -> MaterialTheme.colorScheme.primary
                                            PomodoroPhase.BREAK -> MaterialTheme.colorScheme.tertiary
                                            PomodoroPhase.COMPLETED -> MaterialTheme.colorScheme.secondary
                                        },
                                        shape = CircleShape
                                    )
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
                    }
                }
            }
        }

        // Large Center Clock Text (mm:ss) - steady calm color shift when paused (no blinking)
        Text(
            text = timerState.formattedTime,
            fontSize = 58.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = if (timerState.isPaused) {
                MaterialTheme.colorScheme.secondary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            letterSpacing = (-1).sp,
            modifier = Modifier
                .padding(vertical = 4.dp)
                .testTag("active_clock_text")
        )

        // Bottom Control Buttons (Stop ■, Play/Pause ▶/||, Skip ⏭ / 次へ) - strictly fixed sizes
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onStop,
                modifier = Modifier
                    .size(52.dp)
                    .testTag("active_stop_button")
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Stop,
                            contentDescription = "停止",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            // Fixed size 56.dp in all states: color shift indicates paused vs running!
            IconButton(
                onClick = { if (timerState.isPaused) onResume() else onPause() },
                modifier = Modifier
                    .size(56.dp)
                    .testTag("active_play_pause_button")
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (timerState.isPaused) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (timerState.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = if (timerState.isPaused) "再開" else "一時停止",
                            tint = if (timerState.isPaused) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            },
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }

            if (isOvertime) {
                Button(
                    onClick = onNextPhase,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .height(44.dp)
                        .testTag("active_next_phase_button")
                ) {
                    Text("次へ", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(modifier = Modifier.width(2.dp))
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            } else {
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
                text = "全セット完了",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("completed_title_text")
            )
            Text(
                text = "${totalSets}セット お疲れ様でした",
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
        // Top: Set Stepper
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "セット",
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
                contentDescription = "開始",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(38.dp)
            )
        }
    }

    // Direct Keybaord Input Dialogs
    when (activeDialog) {
        EditDialogType.SETS -> {
            NumberInputDialog(
                title = "セット数",
                initialValue = settings.totalSets,
                minValue = 1,
                maxValue = MAX_TOTAL_SETS,
                unit = "セット",
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
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
                    supportingText = { Text("$minValue〜$maxValue$unit") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )

                // 入力欄が配置された後でフォーカスを要求する（ダイアログの外側で要求すると、
                // 中身の構築より先に実行されて例外になることがある）
                LaunchedEffect(Unit) {
                    focusRequester.requestFocus()
                }
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
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        }
    )
}

@Composable
fun StopwatchCircleDisplay(
    stopwatchState: StopwatchState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val strokeColor = MaterialTheme.colorScheme.primary

    CircleContainer(
        testTag = "stopwatch_circle_container",
        modifier = modifier
    ) {
        // Outer Arc Canvas - gauge stays strictly in initial state at all times per user request
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 10.dp.toPx()
            val diameter = size.minDimension - strokeWidth
            val radius = diameter / 2f

            // Base track ring
            drawCircle(
                color = trackColor.copy(alpha = 0.5f),
                radius = radius,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Initial standby indicator ring (kept unchanged at all times per user request)
            drawCircle(
                color = strokeColor.copy(alpha = 0.5f),
                radius = radius,
                style = Stroke(width = strokeWidth / 2f, cap = StrokeCap.Round)
            )
        }

        // Inner Stopwatch Controls & Time Display (Matches Screenshot 4 & 6)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            // Fixed-height (34dp) status container: zero layout jump or height shift on pause
            Box(
                modifier = Modifier.height(34.dp),
                contentAlignment = Alignment.Center
            ) {
                if (stopwatchState.isPaused) {
                    Surface(
                        shape = RoundedCornerShape(17.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("stopwatch_paused_badge")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 14.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Pause,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "一時停止中",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                } else if (stopwatchState.isRunning) {
                    Surface(
                        shape = RoundedCornerShape(17.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("stopwatch_status_label")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 14.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                            )
                            Text(
                                text = "計測中",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // Big 00:00:00 Time - steady calm color change when paused (no blinking)
            Text(
                text = stopwatchState.formattedTime,
                fontSize = 42.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace,
                color = if (stopwatchState.isPaused) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                letterSpacing = 1.sp,
                modifier = Modifier.testTag("stopwatch_time_text")
            )

            // Buttons - strictly fixed sizes so UI never jumps
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!stopwatchState.isRunning && !stopwatchState.isPaused) {
                    // Stopped state (at 00:00:00): Big Start Button (Screenshot 4)
                    IconButton(
                        onClick = onStart,
                        modifier = Modifier
                            .size(56.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                            .testTag("stopwatch_start_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "開始",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                } else if (stopwatchState.isRunning) {
                    // Running: Stop (Square, 52dp) and Pause (Bars, 52dp)
                    IconButton(
                        onClick = onStop,
                        modifier = Modifier
                            .size(52.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                            .testTag("stopwatch_stop_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Stop,
                            contentDescription = "停止して記録",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    IconButton(
                        onClick = onPause,
                        modifier = Modifier
                            .size(52.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                            .testTag("stopwatch_pause_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Pause,
                            contentDescription = "一時停止",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                } else {
                    // Paused: Stop (Square, 52dp) and Resume (Play, exact same 52dp size with color highlight)
                    IconButton(
                        onClick = onStop,
                        modifier = Modifier
                            .size(52.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                            .testTag("stopwatch_stop_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Stop,
                            contentDescription = "停止して記録",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    IconButton(
                        onClick = onStart,
                        modifier = Modifier
                            .size(52.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                            .testTag("stopwatch_resume_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "再開",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }
        }
    }
}


