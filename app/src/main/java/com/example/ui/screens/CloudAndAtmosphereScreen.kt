package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.BackupLogEntity
import com.example.data.model.AppPreferencesState
import com.example.data.model.ThemePreference
import com.example.data.model.formatCoastalDate
import com.example.data.model.formatFileSize

@Composable
fun CloudAndAtmosphereScreen(
    prefs: AppPreferencesState,
    backupLogs: List<BackupLogEntity>,
    isSyncing: Boolean,
    hasAllFilesAccess: Boolean = false,
    onBack: () -> Unit,
    onRequestAllFilesAccess: () -> Unit = {},
    onToggleShowHiddenFiles: () -> Unit = {},
    onToggleSurf: () -> Unit,
    onSurfVolumeChange: (Float) -> Unit,
    onToggleMusic: () -> Unit,
    onMusicVolumeChange: (Float) -> Unit,
    onToggleSfx: (Boolean) -> Unit,
    onSetTheme: (ThemePreference) -> Unit,
    onConnectSafCloud: (Uri) -> Unit,
    onSaveWebDav: (String, String, String) -> Unit,
    onToggleAutoBackup: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onDisconnectCloud: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showWebDavForm by remember { mutableStateOf(prefs.webDavUrl.isNotBlank()) }
    var webDavUrl by remember(prefs.webDavUrl) { mutableStateOf(prefs.webDavUrl) }
    var webDavUser by remember(prefs.webDavUsername) { mutableStateOf(prefs.webDavUsername) }
    var webDavPass by remember(prefs.webDavPassword) { mutableStateOf(prefs.webDavPassword) }

    val cloudFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) onConnectSafCloud(uri)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("cloud_and_atmosphere_screen")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.IconButton(
                onClick = onBack,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("settings_screen_back_btn")
            ) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.btn_back)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.screen_title_settings),
                style = MaterialTheme.typography.titleLarge
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 6.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
        // 0. Storage Access & Hidden Files (.dot files) Settings Card
        item(key = "storage_access_and_hidden_card") {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = stringResource(R.string.settings_storage_access_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FolderSpecial,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.all_files_banner_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = stringResource(
                                    if (hasAllFilesAccess) {
                                        R.string.all_files_granted_sub
                                    } else {
                                        R.string.all_files_banner_desc
                                    }
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!hasAllFilesAccess) {
                            Spacer(modifier = Modifier.width(8.dp))
                            FilledTonalButton(
                                onClick = onRequestAllFilesAccess,
                                modifier = Modifier.testTag("settings_grant_all_files_btn")
                            ) {
                                Text(stringResource(R.string.all_files_grant_btn))
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Visibility,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_show_hidden_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = stringResource(R.string.settings_show_hidden_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = prefs.showHiddenFiles,
                            onCheckedChange = { onToggleShowHiddenFiles() },
                            modifier = Modifier.testTag("switch_show_hidden_files")
                        )
                    }
                }
            }
        }

        // 1. Ocean Soundscape & Music Mixer
        item(key = "audio_atmosphere_card") {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = stringResource(R.string.audio_section_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Ocean Surf Toggle + Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Waves,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.audio_surf_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = stringResource(R.string.audio_surf_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = prefs.surfEnabled,
                            onCheckedChange = { onToggleSurf() },
                            modifier = Modifier.testTag("switch_surf_sound")
                        )
                    }

                    if (prefs.surfEnabled) {
                        Slider(
                            value = prefs.surfVolume,
                            onValueChange = onSurfVolumeChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("slider_surf_volume")
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                    // Coastal Background Music Toggle + Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.audio_music_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = stringResource(R.string.audio_music_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = prefs.musicEnabled,
                            onCheckedChange = { onToggleMusic() },
                            modifier = Modifier.testTag("switch_bg_music")
                        )
                    }

                    if (prefs.musicEnabled) {
                        Slider(
                            value = prefs.musicVolume,
                            onValueChange = onMusicVolumeChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("slider_music_volume")
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                    // Tactile UI SFX
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.audio_sfx_title),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = prefs.sfxEnabled,
                            onCheckedChange = onToggleSfx,
                            modifier = Modifier.testTag("switch_ui_sfx")
                        )
                    }
                }
            }
        }

        // 2. Minimalist Theme Selector (System / Light / Dark)
        item(key = "theme_selector_card") {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = stringResource(R.string.theme_section_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val options = listOf(
                            ThemePreference.SYSTEM to R.string.theme_system,
                            ThemePreference.LIGHT to R.string.theme_light,
                            ThemePreference.DARK to R.string.theme_dark
                        )
                        options.forEachIndexed { idx, (pref, labelRes) ->
                            SegmentedButton(
                                selected = prefs.themePreference == pref,
                                onClick = { onSetTheme(pref) },
                                shape = SegmentedButtonDefaults.itemShape(index = idx, count = options.size),
                                modifier = Modifier.testTag("theme_btn_${pref.name.lowercase()}")
                            ) {
                                Text(
                                    text = stringResource(labelRes),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Offline-First Reassurance Banner
        item(key = "offline_reassurance_card") {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.OfflinePin,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.cloud_optional_banner_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.cloud_optional_banner_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.88f)
                        )
                    }
                }
            }
        }

        // 4. Optional Cloud Storage Backup & Sync Card
        item(key = "cloud_sync_card") {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (prefs.isCloudConfigured) Icons.Outlined.CloudDone else Icons.Outlined.CloudUpload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.cloud_section_title),
                            style = MaterialTheme.typography.titleLarge
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.cloud_saf_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = stringResource(R.string.cloud_saf_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (prefs.safCloudFolderName.isNotBlank()) {
                        Text(
                            text = "Активна папка: ${prefs.safCloudFolderName}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = { cloudFolderLauncher.launch(null) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("choose_saf_cloud_btn")
                        ) {
                            Text(stringResource(R.string.btn_choose_cloud_folder))
                        }

                        if (prefs.isCloudConfigured) {
                            OutlinedButton(
                                onClick = onDisconnectCloud,
                                modifier = Modifier.testTag("disconnect_cloud_btn")
                            ) {
                                Icon(Icons.Outlined.LinkOff, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.btn_disconnect_cloud))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Optional WebDAV collapsible configuration
                    TextButton(
                        onClick = { showWebDavForm = !showWebDavForm },
                        modifier = Modifier.testTag("toggle_webdav_form_btn")
                    ) {
                        Text(stringResource(R.string.cloud_webdav_title))
                    }

                    if (showWebDavForm) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = webDavUrl,
                                onValueChange = { webDavUrl = it },
                                label = { Text(stringResource(R.string.cloud_webdav_url_hint)) },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("webdav_url_input")
                            )
                            OutlinedTextField(
                                value = webDavUser,
                                onValueChange = { webDavUser = it },
                                label = { Text(stringResource(R.string.cloud_webdav_user_hint)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = webDavPass,
                                onValueChange = { webDavPass = it },
                                label = { Text(stringResource(R.string.cloud_webdav_pass_hint)) },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            FilledTonalButton(
                                onClick = { onSaveWebDav(webDavUrl, webDavUser, webDavPass) },
                                modifier = Modifier.testTag("save_webdav_btn")
                            ) {
                                Text(stringResource(R.string.btn_save_webdav))
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.cloud_auto_backup),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = prefs.autoCloudBackup,
                            onCheckedChange = onToggleAutoBackup,
                            enabled = prefs.isCloudConfigured,
                            modifier = Modifier.testTag("switch_auto_cloud_backup")
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = onSyncNow,
                        enabled = prefs.isCloudConfigured && !isSyncing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("sync_cloud_now_btn")
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Icon(Icons.Outlined.CloudSync, contentDescription = null)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.btn_sync_now))
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (prefs.lastCloudSyncMillis > 0L) {
                            "Остання синхронізація: ${formatCoastalDate(prefs.lastCloudSyncMillis)}"
                        } else {
                            stringResource(R.string.cloud_never_synced)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (backupLogs.isNotEmpty()) {
            item(key = "backup_logs_header") {
                Text(
                    text = stringResource(R.string.cloud_history_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            items(backupLogs, key = { it.id }) { log ->
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = log.providerName,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = formatCoastalDate(log.timestampMillis),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "${log.message} (${log.syncedFilesCount} файлів, ${formatFileSize(log.syncedBytes)})",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (log.isSuccess) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                    }
                }
            }
        }
        }
    }
}
