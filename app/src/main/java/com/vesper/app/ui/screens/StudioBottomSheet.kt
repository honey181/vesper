package com.vesper.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vesper.app.domain.model.AppSettings
import com.vesper.app.domain.model.ArtStyleOption
import com.vesper.app.domain.model.GenerationRequestOverrides
import com.vesper.app.domain.model.WallpaperTarget
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StudioBottomSheet(
    settings: AppSettings,
    onDismiss: () -> Unit,
    onGenerateCustom: (GenerationRequestOverrides) -> Unit
) {
    val defaults = remember { AppSettings() }
    var seedNote by remember { mutableStateOf(settings.customSeedNote) }
    var subtlety by remember { mutableFloatStateOf(settings.subtletyLevel.toFloat()) }
    var selectedStyle by remember { mutableStateOf(settings.artStyleOption) }
    var customStyleText by remember { mutableStateOf(settings.customStyleDirective) }
    var selectedTarget by remember { mutableStateOf(settings.wallpaperTarget) }
    var selectedSize by remember { mutableStateOf(settings.imageSizeTier) }

    val isModifiedFromDefault = seedNote != defaults.customSeedNote ||
        subtlety.roundToInt() != defaults.subtletyLevel ||
        selectedStyle != defaults.artStyleOption ||
        customStyleText != defaults.customStyleDirective ||
        selectedTarget != defaults.wallpaperTarget ||
        selectedSize != defaults.imageSizeTier

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Optional Parameter Studio",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    enabled = isModifiedFromDefault,
                    onClick = {
                        seedNote = defaults.customSeedNote
                        subtlety = defaults.subtletyLevel.toFloat()
                        selectedStyle = defaults.artStyleOption
                        customStyleText = defaults.customStyleDirective
                        selectedTarget = defaults.wallpaperTarget
                        selectedSize = defaults.imageSizeTier
                    }
                ) {
                    Text("Reset to Default")
                }
            }
            Text(
                text = "One-off override for this generation. Your daily autopilot remains unchanged unless you save defaults in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = seedNote,
                onValueChange = { seedNote = it },
                label = { Text("Optional Prompt Seed / Nudge") },
                placeholder = { Text("e.g., lean colder, architectural shadow, emphasize tonight's moon") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Visual Subtlety", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = when {
                            subtlety >= 70f -> "Cipher (${subtlety.roundToInt()})"
                            subtlety >= 35f -> "Poetic (${subtlety.roundToInt()})"
                            else -> "Direct (${subtlety.roundToInt()})"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = subtlety,
                    onValueChange = { subtlety = it },
                    valueRange = 0f..100f
                )
            }

            Text("Art Medium", style = MaterialTheme.typography.titleMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ArtStyleOption.entries.forEach { option ->
                    FilterChip(
                        selected = selectedStyle == option,
                        onClick = { selectedStyle = option },
                        label = { Text(option.label) }
                    )
                }
            }

            if (selectedStyle == ArtStyleOption.CUSTOM) {
                OutlinedTextField(
                    value = customStyleText,
                    onValueChange = { customStyleText = it },
                    label = { Text("Custom Art Medium Directive") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Text("Resolution Tier", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("4K", "2K", "1K").forEach { tier ->
                    FilterChip(
                        selected = selectedSize == tier,
                        onClick = { selectedSize = tier },
                        label = { Text(tier) }
                    )
                }
            }

            Text("Screen Target", style = MaterialTheme.typography.titleMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WallpaperTarget.entries.forEach { target ->
                    FilterChip(
                        selected = selectedTarget == target,
                        onClick = { selectedTarget = target },
                        label = { Text(target.label) }
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    onGenerateCustom(
                        GenerationRequestOverrides(
                            customSeedOverride = seedNote.ifBlank { null },
                            styleOverride = selectedStyle,
                            customStyleTextOverride = customStyleText.ifBlank { null },
                            subtletyOverride = subtlety.roundToInt(),
                            targetOverride = selectedTarget,
                            imageSizeOverride = selectedSize
                        )
                    )
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Generate Now ($selectedSize)")
            }
        }
    }
}
