package com.example.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuOpen
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.ConnectedFolderEntity
import com.example.data.model.DrawerCategoryType
import com.example.data.model.DrawerSelectionMode
import com.example.data.model.ExternalDocumentProviderItem
import com.example.data.model.MainDestination

private data class DrawerCategorySpec(
    val type: DrawerCategoryType,
    val labelRes: Int,
    val icon: ImageVector,
    val testTag: String
)

/**
 * Left navigation drawer modeled after Android's standard Files (DocumentsUI) drawer.
 * Strictly omits items already present in the bottom bar (Пляж, Мушлі/Зображення, Завантаження, Пристрій).
 */
@Composable
fun FilesSideDrawerSheet(
    isDark: Boolean,
    currentDestination: MainDestination,
    activeDrawerMode: DrawerSelectionMode,
    installedProviders: List<ExternalDocumentProviderItem>,
    connectedFolders: List<ConnectedFolderEntity>,
    onSelectCategory: (DrawerCategoryType) -> Unit,
    onSelectInstalledApps: () -> Unit = {},
    onSelectProvider: (ExternalDocumentProviderItem) -> Unit,
    onSelectCustomFolder: (ConnectedFolderEntity) -> Unit,
    onConnectExternalFolder: () -> Unit,
    onCloseDrawer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val sheetBg = if (isDark) Color(0xFF1E1F22) else MaterialTheme.colorScheme.surface
    val dividerColor = if (isDark) Color.White.copy(alpha = 0.08f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)

    // Categories NOT present in the bottom navigation bar
    val categorySpecs = remember {
        listOf(
            DrawerCategorySpec(
                DrawerCategoryType.RECENT,
                R.string.drawer_cat_recent,
                Icons.Outlined.Schedule,
                "drawer_item_recent"
            ),
            DrawerCategorySpec(
                DrawerCategoryType.VIDEOS,
                R.string.drawer_cat_videos,
                Icons.Outlined.Movie,
                "drawer_item_videos"
            ),
            DrawerCategorySpec(
                DrawerCategoryType.AUDIO,
                R.string.drawer_cat_audio,
                Icons.Outlined.AudioFile,
                "drawer_item_audio"
            ),
            DrawerCategorySpec(
                DrawerCategoryType.DOCUMENTS,
                R.string.drawer_cat_documents,
                Icons.Outlined.Description,
                "drawer_item_documents"
            ),
            DrawerCategorySpec(
                DrawerCategoryType.STARRED,
                R.string.drawer_cat_starred,
                Icons.Outlined.StarOutline,
                "drawer_item_starred"
            ),
            DrawerCategorySpec(
                DrawerCategoryType.TRASH,
                R.string.drawer_cat_trash,
                Icons.Outlined.DeleteOutline,
                "drawer_item_trash"
            )
        )
    }

    // Extra SAF folders mounted by user that do NOT duplicate bottom bar (exclude primary: and primary:Download)
    // and aren't already represented by an installed provider row
    val providerAuthorities = remember(installedProviders) {
        installedProviders.map { it.authority }.toSet()
    }
    val customExtraFolders = remember(connectedFolders, providerAuthorities) {
        connectedFolders.filter { entity ->
            val uri = runCatching { Uri.parse(entity.treeUri) }.getOrNull() ?: return@filter false
            val auth = uri.authority.orEmpty()
            val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrDefault("")
            val isRootOrDownloads = docId.equals("primary:", ignoreCase = true) ||
                docId.equals("primary:Download", ignoreCase = true)
            !isRootOrDownloads && auth !in providerAuthorities
        }
    }

    ModalDrawerSheet(
        drawerContainerColor = sheetBg,
        windowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier
            .widthIn(min = 280.dp, max = 328.dp)
            .fillMaxHeight()
            .testTag("files_side_drawer")
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            // 1. Minimalist Coastal Drawer Header
            item(key = "drawer_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 10.dp, top = 8.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Waves,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.drawer_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    IconButton(
                        onClick = onCloseDrawer,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("drawer_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.MenuOpen,
                            contentDescription = stringResource(R.string.btn_close)
                        )
                    }
                }
            }

            // 2. Standard Files Categories (excluding bottom bar tabs)
            item(key = "section_categories_label") {
                Text(
                    text = stringResource(R.string.drawer_section_categories),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
                )
            }

            items(categorySpecs, key = { it.type.name }) { spec ->
                val isSelected = currentDestination == MainDestination.DRAWER_COLLECTION &&
                    (activeDrawerMode as? DrawerSelectionMode.Category)?.type == spec.type

                NavigationDrawerItem(
                    label = {
                        Text(
                            text = stringResource(spec.labelRes),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                        )
                    },
                    icon = {
                        Icon(
                            imageVector = spec.icon,
                            contentDescription = null
                        )
                    },
                    selected = isSelected,
                    onClick = { onSelectCategory(spec.type) },
                    modifier = Modifier
                        .padding(NavigationDrawerItemDefaults.ItemPadding)
                        .testTag(spec.testTag)
                )
            }

            // 2b. Total Commander-style Installed Applications (.apk) manager
            item(key = "installed_apps_entry") {
                val isAppsSelected = currentDestination == MainDestination.DRAWER_COLLECTION &&
                    activeDrawerMode is DrawerSelectionMode.InstalledApps

                NavigationDrawerItem(
                    label = {
                        Column {
                            Text(
                                text = stringResource(R.string.drawer_installed_apps),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isAppsSelected) FontWeight.SemiBold else FontWeight.Medium
                            )
                            Text(
                                text = stringResource(R.string.drawer_installed_apps_sub),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.Android,
                            contentDescription = null
                        )
                    },
                    selected = isAppsSelected,
                    onClick = onSelectInstalledApps,
                    modifier = Modifier
                        .padding(NavigationDrawerItemDefaults.ItemPadding)
                        .testTag("drawer_item_installed_apps")
                )
            }

            // 3. Divider + Installed DocumentsProviders (exactly like Android Files screenshot)
            item(key = "providers_divider") {
                HorizontalDivider(
                    color = dividerColor,
                    modifier = Modifier.padding(vertical = 10.dp)
                )
                Text(
                    text = stringResource(R.string.drawer_section_providers),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                )
            }

            items(installedProviders, key = { "prov:${it.authority}" }) { provider ->
                val isSelected = currentDestination == MainDestination.DRAWER_COLLECTION &&
                    (activeDrawerMode as? DrawerSelectionMode.Provider)?.provider?.authority == provider.authority

                DocumentProviderDrawerRow(
                    context = context,
                    provider = provider,
                    isSelected = isSelected,
                    dividerColor = dividerColor,
                    onClick = { onSelectProvider(provider) }
                )
            }

            // 4. Custom user-mounted folders (excluding primary: & primary:Download)
            items(customExtraFolders, key = { "custom:${it.treeUri}" }) { folder ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .clickable { onSelectCustomFolder(folder) }
                        .padding(horizontal = 22.dp, vertical = 12.dp)
                        .testTag("drawer_custom_folder_${folder.displayName}"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FolderOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(18.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = folder.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                HorizontalDivider(
                    color = dividerColor,
                    modifier = Modifier.padding(start = 76.dp)
                )
            }

            // 5. Connect additional folder / USB / provider tree action
            item(key = "connect_new_storage") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .clickable { onConnectExternalFolder() }
                        .padding(horizontal = 22.dp, vertical = 14.dp)
                        .testTag("drawer_connect_folder_button"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AddCircleOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(18.dp))
                    Text(
                        text = stringResource(R.string.drawer_connect_storage_btn),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun DocumentProviderDrawerRow(
    context: Context,
    provider: ExternalDocumentProviderItem,
    isSelected: Boolean,
    dividerColor: Color,
    onClick: () -> Unit
) {
    val iconBitmap: ImageBitmap? = remember(provider.authority, provider.packageName) {
        loadProviderIconBitmap(context, provider.authority, provider.packageName)
    }

    val rowBg = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    } else {
        Color.Transparent
    }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(rowBg)
                .minimumInteractiveComponentSize()
                .clickable(onClick = onClick)
                .padding(horizontal = 22.dp, vertical = 13.dp)
                .testTag("drawer_provider_${provider.authority}"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap,
                    contentDescription = provider.title,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(6.dp))
                )
            } else {
                val fallbackIcon = when {
                    provider.isSystemDiagnostic -> Icons.Outlined.BugReport
                    provider.isCloudLike -> Icons.Outlined.CloudQueue
                    else -> Icons.Outlined.FolderSpecial
                }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = fallbackIcon,
                        contentDescription = provider.title,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(18.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = provider.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (provider.subtitle.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = provider.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        HorizontalDivider(
            color = dividerColor,
            modifier = Modifier.padding(start = 76.dp)
        )
    }
}

private fun loadProviderIconBitmap(
    context: Context,
    authority: String,
    packageName: String
): ImageBitmap? {
    return runCatching {
        val pm = context.packageManager
        val drawable = pm.resolveContentProvider(authority, 0)?.loadIcon(pm)
            ?: if (packageName.isNotBlank()) pm.getApplicationIcon(packageName) else null
        if (drawable == null) return@runCatching null
        val sizePx = 96
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        bmp.asImageBitmap()
    }.getOrNull()
}
