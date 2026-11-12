package com.vesper.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.vesper.app.R
import com.vesper.app.domain.model.WallpaperDossier
import com.vesper.app.domain.model.WallpaperTarget
import com.vesper.app.ui.VesperUiState
import java.io.File
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(
    state: VesperUiState,
    onApplyDossier: (WallpaperDossier, WallpaperTarget) -> Unit,
    onSaveToGallery: (WallpaperDossier) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onDeleteDossier: (String) -> Unit
) {
    var favoritesOnly by remember { mutableStateOf(false) }
    var inspected by remember { mutableStateOf<WallpaperDossier?>(null) }

    val items = remember(state.dossiers, favoritesOnly) {
        if (favoritesOnly) state.dossiers.filter { it.isFavorite } else state.dossiers
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Archive (${items.size})",
                style = MaterialTheme.typography.titleLarge
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !favoritesOnly,
                    onClick = { favoritesOnly = false },
                    label = { Text("All") }
                )
                FilterChip(
                    selected = favoritesOnly,
                    onClick = { favoritesOnly = true },
                    label = { Text("Favorites") }
                )
            }
        }

        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (favoritesOnly) "No favorite wallpapers yet." else "Generated wallpapers and their daily dossiers will appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(items, key = { it.id }) { dossier ->
                    ArchiveThumbnailCard(
                        dossier = dossier,
                        onClick = { inspected = dossier },
                        onToggleFavorite = { onToggleFavorite(dossier.id) }
                    )
                }
            }
        }
    }

    inspected?.let { selected ->
        val latestVersion = state.dossiers.find { it.id == selected.id } ?: selected
        ModalBottomSheet(onDismissRequest = { inspected = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                AsyncImage(
                    model = File(latestVersion.lockImagePath),
                    contentDescription = latestVersion.clueTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.56f)
                        .clip(RoundedCornerShape(18.dp))
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onApplyDossier(latestVersion, state.settings.wallpaperTarget) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(painterResource(R.drawable.ic_vesper_check), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Apply")
                    }
                    OutlinedButton(
                        onClick = { onSaveToGallery(latestVersion) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(painterResource(R.drawable.ic_vesper_download), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Save")
                    }
                }
                DossierIntelCard(dossier = latestVersion, initiallyExpanded = true)
                TextButton(
                    onClick = {
                        onDeleteDossier(latestVersion.id)
                        inspected = null
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Icon(painterResource(R.drawable.ic_vesper_delete), contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    Text("Delete Entry", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun ArchiveThumbnailCard(
    dossier: WallpaperDossier,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val dateLabel = remember(dossier.createdAtEpochMs) {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(dossier.createdAtEpochMs))
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.48f)
        ) {
            AsyncImage(
                model = File(dossier.lockImagePath),
                contentDescription = dossier.clueTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(34.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.45f))
            ) {
                Icon(
                    painter = painterResource(if (dossier.isFavorite) R.drawable.ic_vesper_favorite_filled else R.drawable.ic_vesper_favorite_border),
                    contentDescription = "Toggle favorite",
                    tint = if (dossier.isFavorite) MaterialTheme.colorScheme.primary else Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.86f))
                        )
                    )
                    .padding(10.dp)
            ) {
                Column {
                    Text(
                        text = dossier.clueTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 2
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = dateLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.75f)
                    )
                }
            }
        }
    }
}
