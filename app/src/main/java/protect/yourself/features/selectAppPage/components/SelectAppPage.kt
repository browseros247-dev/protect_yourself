package protect.yourself.features.selectAppPage.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
            identifier
        )
    )
    val state by viewModel.state.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var showSelectedOnly by remember { mutableStateOf(false) }

    val selectedCount = state.allApps.count { it.isSelected }
    val displayedApps = if (showSelectedOnly) state.filteredApps.filter { it.isSelected }
                        else state.filteredApps

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title)
                        if (selectedCount > 0) {
                            Text(
                                text = "$selectedCount selected",
                                style = MaterialTheme.typography.bodySmall,
                                color = BrandOrange
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    viewModel.searchApp(it)
                },
                placeholder = { Text("Search apps…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
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

            if (state.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = BrandOrange)
                }
                return@Column
            }

            if (state.error != null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Failed to load apps", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
                        Text(state.error ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
                return@Column
            }

            if (displayedApps.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (showSelectedOnly) "No selected apps yet" else "No apps found",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                return@Column
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, bottom = 16.dp
                )
            ) {
                items(displayedApps) { app ->
                    AppRow(app) { viewModel.toggleAppSelection(app) }
                }
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
            // Real app icon
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
            if (app.isSelected) {
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
