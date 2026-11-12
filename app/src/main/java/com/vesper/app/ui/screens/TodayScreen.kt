package com.vesper.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.vesper.app.R
import com.vesper.app.domain.model.WallpaperDossier
import com.vesper.app.domain.model.WallpaperTarget
import com.vesper.app.ui.VesperUiState
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun TodayScreen(
    state: VesperUiState,
    onGenerateNow: () -> Unit,
    onOpenStudio: () -> Unit,
    onOpenSettings: () -> Unit,
    onApplyDossier: (WallpaperDossier, WallpaperTarget) -> Unit,
    onSaveToGallery: (WallpaperDossier) -> Unit,
    onToggleFavorite: (String) -> Unit
) {
    val latest = state.latestDossier
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (state.isGenerating) {
            GenerationBanner(progress = state.generationProgress)
        }

        if (latest == null) {
            EmptyTodayCard(
                hasApiKey = state.settings.apiKey.isNotBlank(),
                screenWidth = state.deviceScreenSpec.widthPx,
                screenHeight = state.deviceScreenSpec.heightPx,
                isGenerating = state.isGenerating,
                onOpenSettings = onOpenSettings,
                onGenerateFirst = onGenerateNow,
                onOpenStudio = onOpenStudio
            )
        } else {
            DossierHeroPreview(
                dossier = latest,
                isGenerating = state.isGenerating,
                defaultTarget = state.settings.wallpaperTarget,
                onGenerateNow = onGenerateNow,
                onOpenStudio = onOpenStudio,
                onApply = { target -> onApplyDossier(latest, target) },
                onSave = { onSaveToGallery(latest) },
                onToggleFavorite = { onToggleFavorite(latest.id) }
            )

            DossierIntelCard(dossier = latest)
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun GenerationBanner(progress: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.5.dp,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Crafting 4K Wallpaper",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = progress.ifBlank { "Working..." },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                )
            }
        }
    }
}

@Composable
private fun EmptyTodayCard(
    hasApiKey: Boolean,
    screenWidth: Int,
    screenHeight: Int,
    isGenerating: Boolean,
    onOpenSettings: () -> Unit,
    onGenerateFirst: () -> Unit,
    onOpenStudio: () -> Unit
) {
    val uriHandler = LocalUriHandler.current

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_vesper_sparkle),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Text(
                text = "Vesper · Ambient Reality Wallpaper",
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = "Every cycle, Vesper quietly scans what is happening around your city and the world this week, encodes those real events into subtle museum-grade art via Nano Banana 2.1, and renders it to your exact ${screenWidth}x${screenHeight} display.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!hasApiKey) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Step 1 · Add Your Google AI Studio Key",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "If you have Google AI Pro, claim your \$10/month Developer Credit and link it to your Cloud Billing project so 4K daily runs stay covered.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { uriHandler.openUri("https://aistudio.google.com/app/apikey") }
                            ) {
                                Text("AI Studio Key")
                            }
                            OutlinedButton(
                                onClick = { uriHandler.openUri("https://me.developers.google.com/benefits") }
                            ) {
                                Text("AI Pro Credits")
                            }
                        }
                    }
                }
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open Settings & Paste API Key")
                }
            } else {
                Button(
                    onClick = onGenerateFirst,
                    enabled = !isGenerating,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(painterResource(R.drawable.ic_vesper_sparkle), contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Generate First Discovery")
                }
                OutlinedButton(
                    onClick = onOpenStudio,
                    enabled = !isGenerating,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(painterResource(R.drawable.ic_vesper_tune), contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Open Optional Parameter Studio")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DossierHeroPreview(
    dossier: WallpaperDossier,
    isGenerating: Boolean,
    defaultTarget: WallpaperTarget,
    onGenerateNow: () -> Unit,
    onOpenStudio: () -> Unit,
    onApply: (WallpaperTarget) -> Unit,
    onSave: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    var previewHomeTwin by remember(dossier.id) { mutableStateOf(false) }
    val homePath = dossier.homeImagePath
    val hasHomeTwin = homePath != null && File(homePath).exists()
    val shownPath = if (previewHomeTwin && homePath != null && File(homePath).exists()) {
        homePath
    } else {
        dossier.lockImagePath
    }

    val aspect = (dossier.targetWidthPx.toFloat() / dossier.targetHeightPx.coerceAtLeast(1).toFloat())
        .coerceIn(0.40f, 0.70f)

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .clip(RoundedCornerShape(24.dp))
        ) {
            AsyncImage(
                model = File(shownPath),
                contentDescription = dossier.clueTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // Top bar: Lock/Home preview switch + Favorite
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasHomeTwin) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = !previewHomeTwin,
                            onClick = { previewHomeTwin = false },
                            label = { Text("Lock") },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_vesper_lock), contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                        FilterChip(
                            selected = previewHomeTwin,
                            onClick = { previewHomeTwin = true },
                            label = { Text("Home") },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_vesper_home), contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                    }
                } else {
                    Surface(
                        color = Color.Black.copy(alpha = 0.50f),
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            text = "${dossier.imageSizeUsed} · ${dossier.targetWidthPx}×${dossier.targetHeightPx}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.45f))
                ) {
                    Icon(
                        painter = painterResource(if (dossier.isFavorite) R.drawable.ic_vesper_favorite_filled else R.drawable.ic_vesper_favorite_border),
                        contentDescription = "Favorite",
                        tint = if (dossier.isFavorite) MaterialTheme.colorScheme.primary else Color.White
                    )
                }
            }

            // Bottom gradient + title overlay
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))
                        )
                    )
                    .padding(18.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = dossier.clueTitle,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White
                    )
                    Text(
                        text = "${dossier.locationLabel} · ${dossier.weatherLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.80f)
                    )
                }
            }
        }

        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = onGenerateNow,
                enabled = !isGenerating
            ) {
                Icon(painterResource(R.drawable.ic_vesper_refresh), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Surprise Again")
            }
            ElevatedButton(
                onClick = { onApply(defaultTarget) }
            ) {
                Icon(painterResource(R.drawable.ic_vesper_check), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Apply")
            }
            OutlinedButton(onClick = onOpenStudio, enabled = !isGenerating) {
                Icon(painterResource(R.drawable.ic_vesper_tune), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Studio")
            }
            OutlinedButton(onClick = onSave) {
                Icon(painterResource(R.drawable.ic_vesper_download), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Save")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DossierIntelCard(
    dossier: WallpaperDossier,
    initiallyExpanded: Boolean = true
) {
    var showTechnical by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val uriHandler = LocalUriHandler.current
    val dateLabel = remember(dossier.createdAtEpochMs) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(dossier.createdAtEpochMs))
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Today's Dossier · Briefing",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "$dateLabel · ${if (dossier.wasAutoRun) "Autopilot" else "Manual"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    painter = painterResource(if (expanded) R.drawable.ic_vesper_expand_less else R.drawable.ic_vesper_expand_more),
                    contentDescription = "Toggle dossier"
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = dossier.realWorldBriefing,
                        style = MaterialTheme.typography.bodyMedium
                    )

                    if (dossier.encodedClues.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        Text(
                            text = "Hidden Visual Clues",
                            style = MaterialTheme.typography.titleMedium
                        )
                        dossier.encodedClues.forEach { clue ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = clue.visualElement,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                Text(
                                    text = clue.realWorldMeaning,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Text(
                        text = "Grounded Sources",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (dossier.groundingSources.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            dossier.groundingSources.forEach { src ->
                                AssistChip(
                                    onClick = { runCatching { uriHandler.openUri(src.url) } },
                                    label = { Text(src.title, maxLines = 1) },
                                    trailingIcon = {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_vesper_open_in_new),
                                            contentDescription = "Open source",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "No web source links were attached for this run (Search Grounding was off or returned zero citations).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showTechnical = !showTechnical },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Art Direction & Model Metadata",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Icon(
                            painter = painterResource(if (showTechnical) R.drawable.ic_vesper_expand_less else R.drawable.ic_vesper_expand_more),
                            contentDescription = "Toggle metadata"
                        )
                    }

                    AnimatedVisibility(visible = showTechnical) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Medium: ${dossier.artMedium}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "Palette: ${dossier.paletteSummary}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "Models: ${dossier.textModelUsed} → ${dossier.imageModelUsed} (${dossier.imageSizeUsed}, ${dossier.aspectRatioUsed} → ${dossier.targetWidthPx}×${dossier.targetHeightPx})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Prompt: ${dossier.imagePromptUsed}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
