package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.AppThemeMode
import com.example.model.PomodoroSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: PomodoroSettings,
    onSaveSettings: (PomodoroSettings) -> Unit,
    onDismissRequest: () -> Unit
) {
    var autoStartBreak by remember { mutableStateOf(settings.autoStartBreak) }
    var autoStartWork by remember { mutableStateOf(settings.autoStartWork) }
    var continueWorkUntilManual by remember { mutableStateOf(settings.continueWorkUntilManual) }
    var continueBreakUntilManual by remember { mutableStateOf(settings.continueBreakUntilManual) }
    var themeMode by remember { mutableStateOf(settings.themeMode) }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = "設定",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )

            HorizontalDivider()

            // Theme Mode Selector
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "テーマ設定",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppThemeMode.entries.forEach { mode ->
                        val isSelected = themeMode == mode
                        FilterChip(
                            selected = isSelected,
                            onClick = { themeMode = mode },
                            label = { Text(mode.label, style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = {
                                val icon = when (mode) {
                                    AppThemeMode.SYSTEM -> Icons.Filled.SettingsSuggest
                                    AppThemeMode.LIGHT -> Icons.Filled.LightMode
                                    AppThemeMode.DARK -> Icons.Filled.DarkMode
                                }
                                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("theme_chip_${mode.name.lowercase()}")
                        )
                    }
                }
            }

            HorizontalDivider()

            // Auto Start Settings Section
            Text(
                text = "タイマーの自動開始",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            SettingToggleRow(
                icon = { Icon(Icons.Filled.Coffee, contentDescription = null) },
                title = "休憩の自動開始",
                subtitle = "作業終了後に自動で休憩を開始",
                checked = autoStartBreak,
                onCheckedChange = { autoStartBreak = it },
                testTag = "auto_start_break_toggle"
            )

            SettingToggleRow(
                icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                title = "作業の自動開始",
                subtitle = "休憩終了後に自動で作業を開始",
                checked = autoStartWork,
                onCheckedChange = { autoStartWork = it },
                testTag = "auto_start_work_toggle"
            )

            SettingToggleRow(
                icon = { Icon(Icons.Filled.HourglassTop, contentDescription = null) },
                title = "手動切り替えまで作業継続",
                subtitle = "作業終了後も手動切替まで計測継続",
                checked = continueWorkUntilManual,
                onCheckedChange = { continueWorkUntilManual = it },
                testTag = "continue_work_until_manual_toggle"
            )

            SettingToggleRow(
                icon = { Icon(Icons.Filled.Coffee, contentDescription = null) },
                title = "手動切り替えまで休憩継続",
                subtitle = "休憩終了後も手動切替まで計測継続",
                checked = continueBreakUntilManual,
                onCheckedChange = { continueBreakUntilManual = it },
                testTag = "continue_break_until_manual_toggle"
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Save Button
            Button(
                onClick = {
                    onSaveSettings(
                        settings.copy(
                            autoStartBreak = autoStartBreak,
                            autoStartWork = autoStartWork,
                            continueWorkUntilManual = continueWorkUntilManual,
                            continueBreakUntilManual = continueBreakUntilManual,
                            themeMode = themeMode
                        )
                    )
                    onDismissRequest()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("save_settings_button")
            ) {
                Text("設定を保存", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SettingToggleRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}
