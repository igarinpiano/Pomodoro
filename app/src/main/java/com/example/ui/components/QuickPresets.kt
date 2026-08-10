package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class PresetOption(
    val name: String,
    val workMins: Int,
    val breakMins: Int
)

val PRESET_OPTIONS = listOf(
    PresetOption("標準 (25/5分)", 25, 5),
    PresetOption("集中 (50/10分)", 50, 10),
    PresetOption("ショート (15/3分)", 15, 3)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickPresets(
    currentWorkMins: Int,
    currentBreakMins: Int,
    enabled: Boolean,
    onSelectPreset: (workMins: Int, breakMins: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "クイックプリセット",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PRESET_OPTIONS.forEach { preset ->
                val isSelected = currentWorkMins == preset.workMins && currentBreakMins == preset.breakMins
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelectPreset(preset.workMins, preset.breakMins) },
                    enabled = enabled,
                    label = {
                        Text(
                            text = preset.name,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    modifier = Modifier.testTag("preset_chip_${preset.workMins}_${preset.breakMins}")
                )
            }
        }
    }
}
