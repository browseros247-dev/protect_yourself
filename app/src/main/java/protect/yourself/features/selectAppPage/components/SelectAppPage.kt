package protect.yourself.features.selectAppPage.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.lifecycle.viewmodel.compose.viewModel
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.selectedApps.SelectedAppListIdentifier
import protect.yourself.features.selectAppPage.SelectAppPageViewModel
import protect.yourself.features.selectAppPage.data.DisplayAppsItemModel
import protect.yourself.theme.BrandOrange

/**
 * SelectAppPage — full-screen app picker used by multiple features
 * (block list, VPN whitelist, Stop Me whitelist, supported browsers, etc.).
 *
 * ## Bug fix (Whitelist Unsupported Browser card)
 *
 * For [SelectedAppListIdentifier.WHITELIST_UNSUPPORTED_BROWSER], the list
 * now contains **only** unsupported browsers (sourced from
 * [UnsupportedBrowserDetector]) instead of every installed app. See
 * [SelectAppPageViewModel] for the filtering logic.
 *
 * ## UI/UX improvements
 *
 *   - **Info banner**: For the unsupported-browser picker, an info card at
 *     the top of the list explains what an "unsupported browser" is and
 *     why the user might want to whitelist one. This eliminates the
 *     confusion users reported when the list was empty (no unsupported
 *     browsers installed) or full (showed every app).
 *   - **Empty state**: When the picker has zero entries (e.g. all installed
 *     browsers are already supported), a friendly empty-state card replaces
 *     the previous blank screen. The empty state also offers a "Refresh"
 *     button in case the user just installed a new browser.
 *   - **Loading skeleton**: While apps are being loaded, a shimmer-style
 *     placeholder row is shown instead of a bare spinner, so the user can
 *     see what the list will look like.
 *   - **Count chip**: The "All" filter chip now shows the live count of
 *     apps in the list, so the user can immediately see how many
 *     unsupported browsers were detected.
 *   - **Refresh action**: A refresh icon in the top app bar lets the user
 *     re-query the PackageManager — useful after installing a new browser.
 *   - **Sticky header**: The search bar + filter chips stay pinned to the
 *     top while the list scrolls underneath, so the user can always search
 *     without scrolling back up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectAppPage(
    identifier: SelectedAppListIdentifier,
    title: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: SelectAppPageViewModel = viewModel(
        factory = SelectAppPageViewModel.factory(
            AppDatabase.getInstance(context),
            identifier,
            context.applicationContext
        )
    )
    val state by viewModel.state.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var showSelectedOnly by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Collect toast messages from the ViewModel (e.g. VPN restart notifications)
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.navigation.collect { message ->
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    val selectedCount = state.allApps.count { it.isSelected }
    val displayedApps = remember(state.filteredApps, showSelectedOnly) {
        if (showSelectedOnly) state.filteredApps.filter { it.isSelected }
        else state.filteredApps
    }

    // True only for the unsupported-browser picker — drives the info banner
    // and the empty-state messaging.
    val isUnsupportedBrowserPicker = identifier == SelectedAppListIdentifier.WHITELIST_UNSUPPORTED_BROWSER

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, fontWeight = FontWeight.SemiBold)
                        if (selectedCount > 0) {
                            Text(
                                text = "$selectedCount selected",
                                style = MaterialTheme.typography.bodySmall,
                                color = BrandOrange
                            )
                        } else if (isUnsupportedBrowserPicker && !state.isLoading && state.error == null) {
                            Text(
                                text = "${state.allApps.size} unsupported browser${if (state.allApps.size != 1) "s" else ""} detected",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.refresh() },
                        enabled = !state.isLoading
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh list")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Info banner — only for the unsupported-browser picker
            if (isUnsupportedBrowserPicker) {
                UnsupportedBrowserInfoBanner(
                    totalDetected = state.allApps.size,
                    selectedCount = selectedCount
                )
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    viewModel.searchApp(it)
                },
                placeholder = {
                    Text(
                        if (isUnsupportedBrowserPicker) "Search unsupported browsers…"
                        else "Search apps…"
                    )
                },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = !showSelectedOnly,
                    onClick = { showSelectedOnly = false },
                    label = { Text("All (${state.filteredApps.size})") }
                )
                FilterChip(
                    selected = showSelectedOnly,
                    onClick = { showSelectedOnly = true },
                    label = { Text("Selected ($selectedCount)") }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Loading state — show shimmer-style placeholder rows
            if (state.isLoading) {
                LoadingPlaceholder()
                return@Column
            }

            // Error state — friendly card with retry
            if (state.error != null) {
                ErrorState(
                    message = state.error ?: "Unknown error",
                    onRetry = { viewModel.refresh() }
                )
                return@Column
            }

            // Empty state — different message for unsupported-browser picker
            if (displayedApps.isEmpty()) {
                EmptyState(
                    isUnsupportedBrowserPicker = isUnsupportedBrowserPicker,
                    showSelectedOnly = showSelectedOnly,
                    onRefresh = { viewModel.refresh() }
                )
                return@Column
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, bottom = 24.dp
                )
            ) {
                items(
                    items = displayedApps,
                    key = { it.packageName }
                ) { app ->
                    AppRow(app) { viewModel.toggleAppSelection(app) }
                }
            }
        }
    }
}

/**
 * Info banner shown at the top of the unsupported-browser picker. Explains
 * what an unsupported browser is and shows the current selection count.
 */
@Composable
private fun UnsupportedBrowserInfoBanner(
    totalDetected: Int,
    selectedCount: Int
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = BrandOrange,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Only unsupported browsers are listed",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = when {
                        totalDetected == 0 ->
                            "No unsupported browsers detected. Install a browser not in the supported list to see it here."
                        selectedCount == 0 ->
                            "Tap a browser below to let it bypass the unsupported-browser block."
                        else ->
                            "$selectedCount of $totalDetected browser${if (totalDetected != 1) "s" else ""} whitelisted."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Shimmer-style loading placeholder. Shows 6 skeleton rows so the user
 * immediately sees what the list will look like, instead of a bare spinner.
 */
@Composable
private fun LoadingPlaceholder() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Show a small spinner at the top so the user knows we're actively
        // loading (not just blank).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = BrandOrange
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Loading apps…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        repeat(6) {
            SkeletonRow()
        }
    }
}

@Composable
private fun SkeletonRow() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    )
            )
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .height(14.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(4.dp)
                        )
                )
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.4f)
                        .height(10.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(4.dp)
                        )
                )
            }
        }
    }
}

/**
 * Friendly empty state. For the unsupported-browser picker, the message
 * explains that no unsupported browsers were found and offers a Refresh
 * button. For other pickers, shows a generic "no apps match your search".
 */
@Composable
private fun EmptyState(
    isUnsupportedBrowserPicker: Boolean,
    showSelectedOnly: Boolean,
    onRefresh: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = if (isUnsupportedBrowserPicker) Icons.Filled.Check else Icons.Filled.Search,
                contentDescription = null,
                tint = BrandOrange,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = when {
                    showSelectedOnly -> "No selected apps yet"
                    isUnsupportedBrowserPicker -> "No unsupported browsers found"
                    else -> "No apps found"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = when {
                    showSelectedOnly -> "Tap an app above to add it to your selection."
                    isUnsupportedBrowserPicker ->
                        "Every browser on your device is in the supported list. " +
                            "If you installed a new browser, tap Refresh to scan again."
                    else -> "Try a different search term."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            if (isUnsupportedBrowserPicker && !showSelectedOnly) {
                Spacer(modifier = Modifier.height(16.dp))
                FilledTonalButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Refresh")
                }
            }
        }
    }
}

/**
 * Error state with retry button.
 */
@Composable
private fun ErrorState(
    message: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Failed to load apps",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            FilledTonalButton(onClick = onRetry) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Retry")
            }
        }
    }
}

@Composable
private fun AppRow(
    app: DisplayAppsItemModel,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (app.isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        ),
        onClick = onToggle
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Real app icon — converted lazily and cached by packageName
            if (app.icon != null) {
                val bitmap = remember(app.packageName) { drawableToBitmap(app.icon) }
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = app.appName,
                    modifier = Modifier.size(40.dp)
                )
            } else {
                // Fallback: first letter in colored box
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = app.appName.firstOrNull()?.toString() ?: "?",
                        color = BrandOrange,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.appName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AnimatedVisibility(
                visible = app.isSelected,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = BrandOrange
                )
            }
        }
    }
}

/**
 * Convert a Drawable to a Bitmap for Compose Image.
 */
private fun drawableToBitmap(drawable: Drawable): Bitmap {
    if (drawable is BitmapDrawable) {
        return drawable.bitmap
    }
    val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
    val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}
