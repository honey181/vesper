package com.vesper.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.vesper.app.R
import com.vesper.app.domain.model.AppSettings
import com.vesper.app.domain.model.ArtStyleOption
import com.vesper.app.domain.model.LocationMode
import com.vesper.app.domain.model.ModelPolicy
import com.vesper.app.domain.model.NewsFocus
import com.vesper.app.domain.model.ScheduleMode
import com.vesper.app.domain.model.ThinkingLevelOption
import com.vesper.app.domain.model.WallpaperTarget
import com.vesper.app.ui.VesperUiState
import com.vesper.app.worker.ScheduleCoordinator
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: VesperUiState,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onShowStatus: (String) -> Unit = {},
    onRefreshModels: () -> Unit,
    onRequestLocationPermission: () -> Unit,
    onRequestCalendarPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit
) {
    val s = state.settings
    val catalog = state.discoveredModels
    val scroll = rememberScrollState()
    val defaults = remember { AppSettings() }
    val detectedW = state.deviceScreenSpec.widthPx
    val detectedH = state.deviceScreenSpec.heightPx
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var exactAlarmAllowed by remember {
        mutableStateOf(ScheduleCoordinator.canScheduleExactAlarms(context))
    }

    DisposableEffect(lifecycleOwner, s) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val allowedNow = ScheduleCoordinator.canScheduleExactAlarms(context)
                if (allowedNow != exactAlarmAllowed) {
                    exactAlarmAllowed = allowedNow
                    ScheduleCoordinator.syncSchedule(context, s)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var keyDraft by remember(s.apiKey) { mutableStateOf(s.apiKey) }
    var showKey by remember { mutableStateOf(false) }
    var customWidthDraft by remember(s.customWidthPx) { mutableStateOf(s.customWidthPx.toString()) }
    var customHeightDraft by remember(s.customHeightPx) { mutableStateOf(s.customHeightPx.toString()) }
    var textModelDraft by remember(s.pinnedTextModel) { mutableStateOf(s.pinnedTextModel) }
    var imageModelDraft by remember(s.pinnedImageModel) { mutableStateOf(s.pinnedImageModel) }
    var manualCityDraft by remember(s.manualCity) { mutableStateOf(s.manualCity) }
    var customStyleDraft by remember(s.customStyleDirective) { mutableStateOf(s.customStyleDirective) }
    var customSeedDraft by remember(s.customSeedNote) { mutableStateOf(s.customSeedNote) }
    var showResetAllDialog by remember { mutableStateOf(false) }
    var showClearKeyDialog by remember { mutableStateOf(false) }
    var showTimePickerDialog by remember { mutableStateOf(false) }
    var useClockDial by remember { mutableStateOf(false) }

    val keyDirty = s.apiKey.isNotBlank() || keyDraft.isNotBlank()
    val modelsDirty = s.hasCustomModels() ||
        textModelDraft != defaults.pinnedTextModel ||
        imageModelDraft != defaults.pinnedImageModel
    val scheduleDirty = s.hasCustomSchedule()
    val displayDirty = s.hasCustomDisplay(detectedW, detectedH) ||
        customWidthDraft != detectedW.toString() ||
        customHeightDraft != detectedH.toString()
    val intelDirty = s.hasCustomIntelligence() ||
        manualCityDraft != defaults.manualCity ||
        customStyleDraft != defaults.customStyleDirective ||
        customSeedDraft != defaults.customSeedNote
    val anyPrefsDirty = modelsDirty || scheduleDirty || displayDirty || intelDirty

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
            TextButton(
                onClick = { showResetAllDialog = true },
                enabled = anyPrefsDirty
            ) {
                Text("Reset All")
            }
        }

        // 1. API KEY
        SettingsSection(
            title = "Google AI Studio Key",
            resetLabel = "Clear",
            resetEnabled = keyDirty,
            onReset = {
                if (s.apiKey.isNotBlank()) {
                    showClearKeyDialog = true
                } else {
                    keyDraft = ""
                }
            }
        ) {
            OutlinedTextField(
                value = keyDraft,
                onValueChange = { keyDraft = it },
                label = { Text("Gemini API Key (stored via Android Keystore)") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            painter = painterResource(if (showKey) R.drawable.ic_vesper_visibility_off else R.drawable.ic_vesper_visibility),
                            contentDescription = "Toggle API key visibility"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onUpdateSettings { it.copy(apiKey = keyDraft.trim()) } },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Save Key")
                }
                OutlinedButton(
                    onClick = {
                        onUpdateSettings { it.copy(apiKey = keyDraft.trim()) }
                        onRefreshModels()
                    },
                    enabled = !state.isRefreshingModels,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(painterResource(R.drawable.ic_vesper_refresh), contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.isRefreshingModels) "Checking..." else "Verify Key")
                }
            }
        }

        // 2. MODELS (AUTO-UPDATING + MANUAL)
        SettingsSection(
            title = "Gemini Models (Auto-Updating & Manual)",
            resetEnabled = modelsDirty,
            onReset = {
                textModelDraft = defaults.pinnedTextModel
                imageModelDraft = defaults.pinnedImageModel
                onUpdateSettings { it.withDefaultsForModels() }
                onShowStatus("Models reset to defaults")
            }
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ModelPolicy.entries.forEach { policy ->
                    FilterChip(
                        selected = s.modelPolicy == policy,
                        onClick = { onUpdateSettings { it.copy(modelPolicy = policy) } },
                        label = { Text(policy.label) }
                    )
                }
            }
            Text(
                text = s.modelPolicy.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (catalog.lastCheckedEpochMs > 0L) {
                val checkedStr = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(catalog.lastCheckedEpochMs))
                Text(
                    text = "Last catalog sync: $checkedStr · Latest suggested: ${catalog.latestSuggestedTextModel ?: "-"} / ${catalog.latestSuggestedImageModel ?: "-"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            OutlinedButton(
                onClick = onRefreshModels,
                enabled = !state.isRefreshingModels,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.isRefreshingModels) "Refreshing Gemini Catalog..." else "Check for Newer Flash & Image Models")
            }

            if (catalog.textModels.isNotEmpty()) {
                Text("Discovered Flash / Pro Models", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    catalog.textModels.take(6).forEach { id ->
                        FilterChip(
                            selected = s.pinnedTextModel == id,
                            onClick = {
                                textModelDraft = id
                                onUpdateSettings { it.copy(pinnedTextModel = id) }
                            },
                            label = { Text(id) }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = textModelDraft,
                onValueChange = { textModelDraft = it },
                label = { Text("Intelligence Model ID (or gemini-flash-latest)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (catalog.imageModels.isNotEmpty()) {
                Text("Discovered Image Models", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    catalog.imageModels.take(6).forEach { id ->
                        FilterChip(
                            selected = s.pinnedImageModel == id,
                            onClick = {
                                imageModelDraft = id
                                onUpdateSettings { it.copy(pinnedImageModel = id) }
                            },
                            label = { Text(id) }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = imageModelDraft,
                onValueChange = { imageModelDraft = it },
                label = { Text("Image Renderer Model ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    onUpdateSettings {
                        it.copy(
                            pinnedTextModel = textModelDraft.trim(),
                            pinnedImageModel = imageModelDraft.trim()
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Model IDs")
            }

            Text("Nano Banana Thinking Depth", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThinkingLevelOption.entries.forEach { level ->
                    FilterChip(
                        selected = s.thinkingLevel == level,
                        onClick = { onUpdateSettings { it.copy(thinkingLevel = level) } },
                        label = { Text(level.label) }
                    )
                }
            }
        }

        // 3. SCHEDULE & AUTOMATION
        SettingsSection(
            title = "Schedule & Automation",
            resetEnabled = scheduleDirty,
            onReset = {
                onUpdateSettings { it.withDefaultsForSchedule() }
                onShowStatus("Schedule reset to defaults (Daily 06:30)")
            }
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScheduleMode.entries.forEach { mode ->
                    FilterChip(
                        selected = s.scheduleMode == mode,
                        onClick = { onUpdateSettings { it.copy(scheduleMode = mode) } },
                        label = { Text(mode.label) }
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Refresh Time: %02d:%02d".format(s.scheduleHour, s.scheduleMinute),
                    style = MaterialTheme.typography.bodyMedium
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        onUpdateSettings { it.copy(scheduleHour = (it.scheduleHour + 23) % 24) }
                    }) { Text("-1h") }
                    OutlinedButton(onClick = {
                        onUpdateSettings { it.copy(scheduleHour = (it.scheduleHour + 1) % 24) }
                    }) { Text("+1h") }
                    OutlinedButton(onClick = {
                        useClockDial = false
                        showTimePickerDialog = true
                    }) { Text("Set Time") }
                }
            }

            SwitchRow(
                title = "Require Unmetered Wi-Fi",
                checked = s.requireWifi,
                onCheckedChange = { v -> onUpdateSettings { it.copy(requireWifi = v) } }
            )
            SwitchRow(
                title = "Require Charging",
                checked = s.requireCharging,
                onCheckedChange = { v -> onUpdateSettings { it.copy(requireCharging = v) } }
            )
            SwitchRow(
                title = "Notify on New Wallpaper",
                checked = s.notifyOnAutoUpdate,
                onCheckedChange = { v ->
                    onUpdateSettings { it.copy(notifyOnAutoUpdate = v) }
                    if (v) onRequestNotificationPermission()
                }
            )
            Text(
                text = ScheduleCoordinator.formatNextScheduleSummary(context, s),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (s.scheduleMode != ScheduleMode.MANUAL_ONLY &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !exactAlarmAllowed
            ) {
                OutlinedButton(
                    onClick = {
                        val exactIntent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        runCatching { context.startActivity(exactIntent) }.onFailure {
                            val appSettingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            runCatching { context.startActivity(appSettingsIntent) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Allow Exact %02d:%02d Wake-Up (Silent)".format(s.scheduleHour, s.scheduleMinute))
                }
            }
        }

        // 4. DISPLAY & WALLPAPER TARGET
        SettingsSection(
            title = "Display Dimensions & Screen Targets",
            resetEnabled = displayDirty,
            onReset = {
                customWidthDraft = detectedW.toString()
                customHeightDraft = detectedH.toString()
                onUpdateSettings { it.withDefaultsForDisplay(detectedW, detectedH) }
                onShowStatus("Display & screen targets reset to defaults")
            }
        ) {
            Text(
                text = "Detected device screen: ${state.deviceScreenSpec.widthPx} × ${state.deviceScreenSpec.heightPx} px",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
            SwitchRow(
                title = "Auto-Detect Phone Dimensions",
                checked = s.autoDetectDimensions,
                onCheckedChange = { v -> onUpdateSettings { it.copy(autoDetectDimensions = v) } }
            )

            if (!s.autoDetectDimensions) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = customWidthDraft,
                        onValueChange = { customWidthDraft = it },
                        label = { Text("Width px") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = customHeightDraft,
                        onValueChange = { customHeightDraft = it },
                        label = { Text("Height px") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedButton(
                    onClick = {
                        val w = customWidthDraft.toIntOrNull() ?: state.deviceScreenSpec.widthPx
                        val h = customHeightDraft.toIntOrNull() ?: state.deviceScreenSpec.heightPx
                        onUpdateSettings { it.copy(customWidthPx = w, customHeightPx = h) }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Apply Custom Dimensions")
                }
            }

            SwitchRow(
                title = "Exact Native Pixel Crop & Fit",
                subtitle = "Prevents Android launcher stretching/zooming while preserving 4K sharpness.",
                checked = s.exactPixelFit,
                onCheckedChange = { v -> onUpdateSettings { it.copy(exactPixelFit = v) } }
            )

            Text("Resolution Tier", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("4K", "2K", "1K").forEach { tier ->
                    FilterChip(
                        selected = s.imageSizeTier == tier,
                        onClick = { onUpdateSettings { it.copy(imageSizeTier = tier) } },
                        label = { Text(tier) }
                    )
                }
            }

            Text("Gemini API Aspect Ratio", style = MaterialTheme.typography.labelMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("AUTO", "9:16", "2:3", "3:4", "4:5", "1:4", "1:1").forEach { ratio ->
                    FilterChip(
                        selected = s.aspectRatioOverride == ratio,
                        onClick = { onUpdateSettings { it.copy(aspectRatioOverride = ratio) } },
                        label = { Text(ratio) }
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Text("Wallpaper Target Mode", style = MaterialTheme.typography.labelMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WallpaperTarget.entries.forEach { target ->
                    FilterChip(
                        selected = s.wallpaperTarget == target,
                        onClick = { onUpdateSettings { it.copy(wallpaperTarget = target) } },
                        label = { Text(target.label) }
                    )
                }
            }
            Text(
                text = s.wallpaperTarget.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (s.wallpaperTarget == WallpaperTarget.LOCK_AND_CALM_HOME || s.wallpaperTarget == WallpaperTarget.HOME_ONLY) {
                Text("Calm Home Screen Blur (${s.homeCalmBlurRadius.roundToInt()} px)", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = s.homeCalmBlurRadius,
                    onValueChange = { v -> onUpdateSettings { it.copy(homeCalmBlurRadius = v) } },
                    valueRange = 0f..32f
                )
                Text("Calm Home Screen Dimming (${(s.homeCalmDimAlpha * 100).roundToInt()}%)", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = s.homeCalmDimAlpha,
                    onValueChange = { v -> onUpdateSettings { it.copy(homeCalmDimAlpha = v) } },
                    valueRange = 0f..0.75f
                )
            }
        }

        // 5. INTELLIGENCE, SUBTLETY & SIGNALS
        SettingsSection(
            title = "Intelligence & Spy Craft",
            resetEnabled = intelDirty,
            onReset = {
                manualCityDraft = defaults.manualCity
                customStyleDraft = defaults.customStyleDirective
                customSeedDraft = defaults.customSeedNote
                onUpdateSettings { it.withDefaultsForIntelligence() }
                onShowStatus("Intelligence & creative defaults restored")
            }
        ) {
            Text("Real-World Focus", style = MaterialTheme.typography.labelMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NewsFocus.entries.forEach { focus ->
                    FilterChip(
                        selected = s.newsFocus == focus,
                        onClick = { onUpdateSettings { it.copy(newsFocus = focus) } },
                        label = { Text(focus.label) }
                    )
                }
            }

            Text(
                text = "Visual Subtlety (${s.subtletyLevel}/100 · " + when {
                    s.subtletyLevel >= 70 -> "Cipher"
                    s.subtletyLevel >= 35 -> "Poetic"
                    else -> "Direct"
                } + ")",
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                text = "0–34: recognizable figurative scenes · 35–69: poetic metaphors · 70–100: abstract cipher clues",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = s.subtletyLevel.toFloat(),
                onValueChange = { v -> onUpdateSettings { it.copy(subtletyLevel = v.roundToInt()) } },
                valueRange = 0f..100f
            )

            Text("Location Source", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LocationMode.entries.forEach { mode ->
                    FilterChip(
                        selected = s.locationMode == mode,
                        onClick = {
                            onUpdateSettings { it.copy(locationMode = mode) }
                            if (mode == LocationMode.AUTO_COARSE) onRequestLocationPermission()
                        },
                        label = { Text(mode.label) }
                    )
                }
            }
            if (s.locationMode == LocationMode.AUTO_COARSE) {
                OutlinedButton(onClick = onRequestLocationPermission) {
                    Text("Grant Coarse City Location Permission")
                }
            }
            OutlinedTextField(
                value = manualCityDraft,
                onValueChange = { manualCityDraft = it },
                label = { Text("Fallback / Manual City (e.g., Austin, TX or Berlin)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                onClick = { onUpdateSettings { it.copy(manualCity = manualCityDraft.trim()) } },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save City")
            }

            SwitchRow(
                title = "Live Evening-News Search Grounding",
                subtitle = "Lets Gemini Flash scan significant civic, world & sky events active in your wallpaper window (plus major overnight shocks).",
                checked = s.enableSearchGrounding,
                onCheckedChange = { v -> onUpdateSettings { it.copy(enableSearchGrounding = v) } }
            )
            SwitchRow(
                title = "Weather & 24h Temp Delta",
                checked = s.enableWeatherContext,
                onCheckedChange = { v -> onUpdateSettings { it.copy(enableWeatherContext = v) } }
            )
            SwitchRow(
                title = "Calendar Busyness Count (No Titles)",
                subtitle = "Reads only event count today so brutal days get calmer compositions.",
                checked = s.enableCalendarDensity,
                onCheckedChange = { v ->
                    onUpdateSettings { it.copy(enableCalendarDensity = v) }
                    if (v) onRequestCalendarPermission()
                }
            )

            Text("Default Art Medium", style = MaterialTheme.typography.labelMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ArtStyleOption.entries.forEach { style ->
                    FilterChip(
                        selected = s.artStyleOption == style,
                        onClick = { onUpdateSettings { it.copy(artStyleOption = style) } },
                        label = { Text(style.label) }
                    )
                }
            }
            if (s.artStyleOption == ArtStyleOption.CUSTOM) {
                OutlinedTextField(
                    value = customStyleDraft,
                    onValueChange = { customStyleDraft = it },
                    label = { Text("Custom Default Style") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            OutlinedTextField(
                value = customSeedDraft,
                onValueChange = { customSeedDraft = it },
                label = { Text("Persistent Optional Seed / Rule") },
                placeholder = { Text("Leave blank for pure autonomous surprises") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                onClick = {
                    onUpdateSettings {
                        it.copy(
                            customStyleDirective = customStyleDraft.trim(),
                            customSeedNote = customSeedDraft.trim()
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Creative Defaults")
            }
        }

        OutlinedButton(
            onClick = { showResetAllDialog = true },
            enabled = anyPrefsDirty,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Reset All Preferences to Defaults")
        }

        Spacer(Modifier.height(20.dp))
    }

    if (showResetAllDialog) {
        AlertDialog(
            onDismissRequest = { showResetAllDialog = false },
            title = { Text("Reset all preferences?") },
            text = {
                Text("Restores models, schedule (Daily 06:30), display targets, and creative preferences back to Vesper factory defaults. Your saved API key and wallpaper archive will stay intact.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetAllDialog = false
                        textModelDraft = defaults.pinnedTextModel
                        imageModelDraft = defaults.pinnedImageModel
                        customWidthDraft = detectedW.toString()
                        customHeightDraft = detectedH.toString()
                        manualCityDraft = defaults.manualCity
                        customStyleDraft = defaults.customStyleDirective
                        customSeedDraft = defaults.customSeedNote
                        onUpdateSettings { it.withAllPreferencesReset(detectedW, detectedH) }
                        onShowStatus("Preferences reset to defaults (API key kept)")
                    }
                ) {
                    Text("Reset to Defaults")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetAllDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showClearKeyDialog) {
        AlertDialog(
            onDismissRequest = { showClearKeyDialog = false },
            title = { Text("Clear saved API key?") },
            text = {
                Text("Removes your Google AI Studio API key from encrypted storage. Vesper won't generate new wallpapers until a key is saved again.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearKeyDialog = false
                        keyDraft = ""
                        onUpdateSettings { it.copy(apiKey = "") }
                        onShowStatus("API key cleared")
                    }
                ) {
                    Text("Clear Key", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearKeyDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showTimePickerDialog) {
        val timePickerState = rememberTimePickerState(
            initialHour = s.scheduleHour.coerceIn(0, 23),
            initialMinute = s.scheduleMinute.coerceIn(0, 59),
            is24Hour = android.text.format.DateFormat.is24HourFormat(context)
        )
        AlertDialog(
            onDismissRequest = { showTimePickerDialog = false },
            title = { Text("Set Auto-Update Time") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (useClockDial) {
                        TimePicker(state = timePickerState)
                    } else {
                        TimeInput(state = timePickerState)
                    }
                    TextButton(onClick = { useClockDial = !useClockDial }) {
                        Text(if (useClockDial) "Type Time Manually" else "Use Clock Dial")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val h = timePickerState.hour.coerceIn(0, 23)
                        val m = timePickerState.minute.coerceIn(0, 59)
                        onUpdateSettings {
                            it.copy(scheduleHour = h, scheduleMinute = m)
                        }
                        onShowStatus("Refresh time set to %02d:%02d".format(h, m))
                        showTimePickerDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePickerDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    resetLabel: String = "Reset",
    resetEnabled: Boolean = false,
    onReset: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                )
                if (onReset != null) {
                    TextButton(
                        onClick = onReset,
                        enabled = resetEnabled
                    ) {
                        Text(resetLabel)
                    }
                }
            }
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
