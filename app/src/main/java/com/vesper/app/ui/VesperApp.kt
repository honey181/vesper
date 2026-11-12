package com.vesper.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vesper.app.R
import com.vesper.app.ui.screens.ArchiveScreen
import com.vesper.app.ui.screens.SettingsScreen
import com.vesper.app.ui.screens.StudioBottomSheet
import com.vesper.app.ui.screens.TodayScreen
import com.vesper.app.ui.theme.VesperTheme

private enum class VesperTab(val label: String) {
    TODAY("Today"),
    ARCHIVE("Archive"),
    SETTINGS("Settings")
}

@Composable
fun VesperApp(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var currentTab by rememberSaveable { mutableStateOf(VesperTab.TODAY) }
    var showStudioSheet by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val locationPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            // User can still use manual city or timezone region seamlessly
        }
    }

    val calendarPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            viewModel.updateSettings { it.copy(enableCalendarDensity = false) }
        }
    }

    val notificationPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(state.statusMessage, state.errorMessage) {
        val msg = state.errorMessage ?: state.statusMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(
                message = msg,
                duration = if (state.errorMessage != null) SnackbarDuration.Long else SnackbarDuration.Short
            )
            viewModel.consumeMessages()
        }
    }

    VesperTheme {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentTab == VesperTab.TODAY,
                        onClick = { currentTab = VesperTab.TODAY },
                        icon = { Icon(painterResource(R.drawable.ic_vesper_sparkle), contentDescription = "Today") },
                        label = { Text(VesperTab.TODAY.label) }
                    )
                    NavigationBarItem(
                        selected = currentTab == VesperTab.ARCHIVE,
                        onClick = { currentTab = VesperTab.ARCHIVE },
                        icon = { Icon(painterResource(R.drawable.ic_vesper_archive), contentDescription = "Archive") },
                        label = { Text(VesperTab.ARCHIVE.label) }
                    )
                    NavigationBarItem(
                        selected = currentTab == VesperTab.SETTINGS,
                        onClick = { currentTab = VesperTab.SETTINGS },
                        icon = { Icon(painterResource(R.drawable.ic_vesper_settings), contentDescription = "Settings") },
                        label = { Text(VesperTab.SETTINGS.label) }
                    )
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                when (currentTab) {
                    VesperTab.TODAY -> TodayScreen(
                        state = state,
                        onGenerateNow = { viewModel.generateNow() },
                        onOpenStudio = { showStudioSheet = true },
                        onOpenSettings = { currentTab = VesperTab.SETTINGS },
                        onApplyDossier = viewModel::applyDossier,
                        onSaveToGallery = viewModel::saveDossierToGallery,
                        onToggleFavorite = viewModel::toggleFavorite
                    )
                    VesperTab.ARCHIVE -> ArchiveScreen(
                        state = state,
                        onApplyDossier = viewModel::applyDossier,
                        onSaveToGallery = viewModel::saveDossierToGallery,
                        onToggleFavorite = viewModel::toggleFavorite,
                        onDeleteDossier = viewModel::deleteDossier
                    )
                    VesperTab.SETTINGS -> SettingsScreen(
                        state = state,
                        onUpdateSettings = viewModel::updateSettings,
                        onShowStatus = viewModel::showStatus,
                        onRefreshModels = { viewModel.refreshModelsCatalog(testKeyFeedback = true) },
                        onRequestLocationPermission = {
                            locationPermLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                        },
                        onRequestCalendarPermission = {
                            calendarPermLauncher.launch(Manifest.permission.READ_CALENDAR)
                        },
                        onRequestNotificationPermission = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                    )
                }
            }

            if (showStudioSheet) {
                StudioBottomSheet(
                    settings = state.settings,
                    onDismiss = { showStudioSheet = false },
                    onGenerateCustom = { overrides -> viewModel.generateNow(overrides) }
                )
            }
        }
    }
}
