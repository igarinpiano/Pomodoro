package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeUp
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
    var soundEnabled by remember { mutableStateOf(settings.soundEnabled) }
    var flashEnabled by remember { mutableStateOf(settings.flashEnabled) }
    var vibrateEnabled by remember { mutableStateOf(settings.vibrateEnabled) }
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
                Text(
                    text = "ダークテーマ (節電) は OLED 画面のバッテリー消費を軽減します",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                    AppThemeMode.DARK -> Icons.Filled.BatterySaver
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

            Text(
                text = "完了アラート通知",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            // Sound Toggle
            SettingToggleRow(
                icon = { Icon(Icons.Filled.VolumeUp, contentDescription = null) },
                title = "サウンド効果音",
                subtitle = "作業・休憩の完了時にアラート音を再生",
                checked = soundEnabled,
                onCheckedChange = { soundEnabled = it },
                testTag = "sound_toggle"
            )

            // Flashlight Toggle
            SettingToggleRow(
                icon = { Icon(Icons.Filled.FlashOn, contentDescription = null) },
                title = "フラッシュライト点滅",
                subtitle = "完了時にカメラLEDフラッシュを点滅通知",
                checked = flashEnabled,
                onCheckedChange = { flashEnabled = it },
                testTag = "flash_toggle"
            )

            // Vibration Toggle
            SettingToggleRow(
                icon = { Icon(Icons.Filled.Vibration, contentDescription = null) },
                title = "バイブレーション",
                subtitle = "完了時に端末バイブを振動通知",
                checked = vibrateEnabled,
                onCheckedChange = { vibrateEnabled = it },
                testTag = "vibrate_toggle"
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Save Button
            Button(
                onClick = {
                    onSaveSettings(
                        settings.copy(
                            soundEnabled = soundEnabled,
                            flashEnabled = flashEnabled,
                            vibrateEnabled = vibrateEnabled,
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
