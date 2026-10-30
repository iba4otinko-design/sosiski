package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.InstalledAppInfo
import com.example.data.model.MoveDestinationOption
import com.example.data.model.formatCoastalDate
import com.example.data.model.formatFileSize
import com.example.ui.components.CompactSearchTriggerButton
import com.example.ui.components.ExpandableCompactSearchBar
import com.example.ui.icons.InstalledAppIcon

/**
 * Total Commander-inspired "Встановлені програми" screen accessible from the left drawer.
 * Lets users inspect installed Android apps, select one or multiple apps, copy their .apk
 * directly into any device folder or SAF folder, stage them into the Total Commander clipboard
 * to paste anywhere, or share/send .apk files directly.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InstalledAppsScreen(
    apps: List<InstalledAppInfo>,
    isLoading: Boolean,
    showSystemApps: Boolean,
    searchQuery: String,
    destinationOptions: List<MoveDestinationOption>,
    onBackToBeach: () -> Unit,
    onToggleShowSystemApps: (Boolean) -> Unit,
    onSearchChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onCopyAppsToFolder: (List<InstalledAppInfo>, MoveDestinationOption) -> Unit,
    onCopyAppsToCustomSafTree: (List<InstalledAppInfo>, Uri) -> Unit,
    onCopyAppsToTcClipboard: (List<InstalledAppInfo>) -> Unit,
    onShareAppsApk: (List<InstalledAppInfo>) -> Unit,
    onLaunchApp: (InstalledAppInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    var isSearchOpen by remember { mutableStateOf(searchQuery.isNotBlank()) }
    var selectedPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingSaveApps by remember { mutableStateOf<List<InstalledAppInfo>?>(null) }

    val customTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val toSave = pendingSaveApps
        if (uri != null && !toSave.isNullOrEmpty()) {
            onCopyAppsToCustomSafTree(toSave, uri)
            selectedPackages = emptySet()
        }
        pendingSaveApps = null
    }

    val filteredApps = remember(apps, searchQuery) {
        if (searchQuery.isBlank()) {
            apps
        } else {
            val q = searchQuery.trim().lowercase()
            apps.filter {
                it.lowerAppName.contains(q) || it.lowerPackageName.contains(q)
            }
        }
    }

    val selectedApps = remember(apps, selectedPackages) {
        apps.filter { it.packageName in selectedPackages }
    }

    BackHandler {
        if (selectedPackages.isNotEmpty()) {
            selectedPackages = emptySet()
        } else if (isSearchOpen || searchQuery.isNotBlank()) {
            onSearchChange("")
            isSearchOpen = false
        } else {
            onBackToBeach()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            // Compact Top Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                IconButton(
                    onClick = onBackToBeach,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("installed_apps_back_btn")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.btn_back)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.installed_apps_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.installed_apps_subtitle_fmt, filteredApps.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                FilterChip(
                    selected = showSystemApps,
                    onClick = { onToggleShowSystemApps(!showSystemApps) },
                    label = {
                        Text(
                            text = stringResource(R.string.installed_apps_show_system),
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    modifier = Modifier.testTag("installed_apps_system_chip")
                )

                CompactSearchTriggerButton(
                    isSearchOpen = isSearchOpen,
                    hasActiveQuery = searchQuery.isNotBlank(),
                    onToggle = {
                        if (isSearchOpen) {
                            onSearchChange("")
                            isSearchOpen = false
                        } else {
                            isSearchOpen = true
                        }
                    },
                    testTag = "installed_apps_search_lupe"
                )

                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("installed_apps_refresh_btn")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null
                    )
                }
            }

            ExpandableCompactSearchBar(
                expanded = isSearchOpen || searchQuery.isNotBlank(),
                query = searchQuery,
                onQueryChange = onSearchChange,
                onCloseSearch = { isSearchOpen = false },
                inputTestTag = "installed_apps_search_input"
            )

            // Multi-select summary bar
            AnimatedVisibility(
                visible = selectedApps.isNotEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                val totalSelectedBytes = remember(selectedApps) {
                    selectedApps.sumOf { it.sizeBytes }
                }
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { selectedPackages = emptySet() },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.sel_clear_all))
                            }
                            Text(
                                text = stringResource(
                                    R.string.sel_count_and_size,
                                    selectedApps.size,
                                    formatFileSize(totalSelectedBytes)
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        TextButton(
                            onClick = { selectedPackages = filteredApps.map { it.packageName }.toSet() }
                        ) {
                            Icon(Icons.Outlined.SelectAll, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.sel_select_all))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (isLoading && filteredApps.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (filteredApps.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Outlined.Android,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.installed_apps_empty),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 92.dp, top = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("installed_apps_list")
                ) {
                    items(
                        items = filteredApps,
                        key = { it.packageName },
                        contentType = { "installed_app_row" }
                    ) { app ->
                        val isSelected = app.packageName in selectedPackages
                        InstalledAppRowCard(
                            app = app,
                            isSelected = isSelected,
                            onRowClick = {
                                if (selectedPackages.isNotEmpty()) {
                                    selectedPackages = if (isSelected) {
                                        selectedPackages - app.packageName
                                    } else {
                                        selectedPackages + app.packageName
                                    }
                                } else {
                                    pendingSaveApps = listOf(app)
                                }
                            },
                            onRowLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                selectedPackages = if (isSelected) {
                                    selectedPackages - app.packageName
                                } else {
                                    selectedPackages + app.packageName
                                }
                            },
                            onSaveApkToFolder = { pendingSaveApps = listOf(app) },
                            onCopyTc = { onCopyAppsToTcClipboard(listOf(app)) },
                            onShareApk = { onShareAppsApk(listOf(app)) },
                            onLaunch = { onLaunchApp(app) }
                        )
                    }
                }
            }
        }

        // Bottom batch action bar when multiple apps are selected
        AnimatedVisibility(
            visible = selectedApps.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = { pendingSaveApps = selectedApps },
                        modifier = Modifier.testTag("installed_apps_batch_save_btn")
                    ) {
                        Icon(Icons.Outlined.SaveAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.installed_apps_copy_to_folder))
                    }
                    OutlinedButton(
                        onClick = {
                            onCopyAppsToTcClipboard(selectedApps)
                            selectedPackages = emptySet()
                        },
                        modifier = Modifier.testTag("installed_apps_batch_tc_btn")
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.installed_apps_copy_tc))
                    }
                    OutlinedButton(
                        onClick = {
                            onShareAppsApk(selectedApps)
                            selectedPackages = emptySet()
                        },
                        modifier = Modifier.testTag("installed_apps_batch_share_btn")
                    ) {
                        Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.installed_apps_share_apk))
                    }
                }
            }
        }
    }

    val appsToSave = pendingSaveApps
    if (!appsToSave.isNullOrEmpty()) {
        AlertDialog(
            onDismissRequest = { pendingSaveApps = null },
            title = {
                Text(stringResource(R.string.installed_apps_dest_dialog_title, appsToSave.size))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    destinationOptions.forEach { dest ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onCopyAppsToFolder(appsToSave, dest)
                                    selectedPackages = emptySet()
                                    pendingSaveApps = null
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.FolderOpen,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = dest.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = dest.subtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = { customTreeLauncher.launch(null) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.installed_apps_pick_other_folder))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = {
                                onCopyAppsToTcClipboard(appsToSave)
                                selectedPackages = emptySet()
                                pendingSaveApps = null
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.installed_apps_copy_tc), maxLines = 1)
                        }
                        FilledTonalButton(
                            onClick = {
                                onShareAppsApk(appsToSave)
                                selectedPackages = emptySet()
                                pendingSaveApps = null
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.installed_apps_share_apk), maxLines = 1)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pendingSaveApps = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InstalledAppRowCard(
    app: InstalledAppInfo,
    isSelected: Boolean,
    onRowClick: () -> Unit,
    onRowLongClick: () -> Unit,
    onSaveApkToFolder: () -> Unit,
    onCopyTc: () -> Unit,
    onShareApk: () -> Unit,
    onLaunch: () -> Unit
) {
    val metaLine = remember(app.versionName, app.sizeBytes, app.lastUpdatedMillis) {
        "v${app.versionName} • ${formatFileSize(app.sizeBytes)} • ${formatCoastalDate(app.lastUpdatedMillis)}"
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 3.dp else 1.dp),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onRowClick,
                onLongClick = onRowLongClick
            )
            .testTag("installed_app_row_${app.packageName}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            InstalledAppIcon(
                packageName = app.packageName,
                apkFilePath = app.apkFilePath,
                lastUpdatedMillis = app.lastUpdatedMillis,
                appName = app.appName,
                size = 46.dp,
                isSelected = isSelected
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.appName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = metaLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(
                onClick = onSaveApkToFolder,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("save_apk_${app.packageName}")
            ) {
                Icon(
                    imageVector = Icons.Outlined.SaveAlt,
                    contentDescription = stringResource(R.string.installed_apps_copy_to_folder),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            IconButton(
                onClick = onCopyTc,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("copy_tc_apk_${app.packageName}")
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(R.string.installed_apps_copy_tc)
                )
            }

            IconButton(
                onClick = onShareApk,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("share_apk_${app.packageName}")
            ) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = stringResource(R.string.installed_apps_share_apk)
                )
            }

            IconButton(
                onClick = onLaunch,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("launch_app_${app.packageName}")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = stringResource(R.string.installed_apps_open_app),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
