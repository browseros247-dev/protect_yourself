package protect.yourself.features.blockerPage.components

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import protect.yourself.database.selectedApps.SelectedAppItemModel
import protect.yourself.database.selectedKeywords.SelectedKeywordItemModel
import protect.yourself.features.keywordManagerPage.KeywordManagerViewModel
import protect.yourself.features.packageIntentPage.PackageIntentViewModel
import protect.yourself.theme.BrandOrange
import timber.log.Timber
/**
 * UnifiedBlockingPage — a single scrollable page that merges the functionality
 * of KeywordManagerPage and PackageIntentPage into one unified blocking
 * management page.
 *
 * Sections:
 *  1. Content Keywords — blocklist/whitelist keyword management
 *  2. Setting Titles — blocked settings page titles
 *  3. Package & Intent Blocking — package name + intent/class name blocking
 *
 * Each section uses its own ViewModel for state and persistence.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedBlockingPage(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val keywordViewModel: KeywordManagerViewModel = viewModel(
        factory = KeywordManagerViewModel.factory(context.applicationContext as android.app.Application)
    )
    val packageIntentViewModel: PackageIntentViewModel = viewModel(
        factory = PackageIntentViewModel.factory(context.applicationContext as android.app.Application)
    )
    val keywordState by keywordViewModel.state.collectAsState()
    val packageIntentState by packageIntentViewModel.state.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current

    // ---- Local UI state ----
    var selectedKeywordTab by remember { mutableStateOf(KeywordTabUnion.BLOCKLIST) }
    var newKeywordText by remember { mutableStateOf("") }
    var keywordSearchQuery by remember { mutableStateOf("") }
    var newSettingTitleText by remember { mutableStateOf("") }
    var newEntryText by remember { mutableStateOf("") }

    // Delete confirmation state (typed union)
    var itemToDelete by remember { mutableStateOf<DeleteTarget?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Unified Blocking Management")
                        Text(
                            text = "${keywordState.totalCount()} keywords • ${packageIntentState.blockedPackages.size} packages • ${packageIntentState.blockedIntents.size} intents",
                            style = MaterialTheme.typography.bodySmall,
                            color = BrandOrange
                        )
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
                .verticalScroll(rememberScrollState())
                .background(MaterialTheme.colorScheme.background)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ================================================================
            // SECTION 1: Content Keywords
            // ================================================================
            SectionCard(title = "Content Keywords") {
                // Tab chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedKeywordTab == KeywordTabUnion.BLOCKLIST,
                        onClick = {
                            selectedKeywordTab = KeywordTabUnion.BLOCKLIST
                            keywordSearchQuery = ""
                        },
                        label = { Text("Blocklist (${keywordState.blockKeywords.size})") }
                    )
                    FilterChip(
                        selected = selectedKeywordTab == KeywordTabUnion.WHITELIST,
                        onClick = {
                            selectedKeywordTab = KeywordTabUnion.WHITELIST
                            keywordSearchQuery = ""
                        },
                        label = { Text("Whitelist (${keywordState.whitelistKeywords.size})") }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Search field
                OutlinedTextField(
                    value = keywordSearchQuery,
                    onValueChange = { keywordSearchQuery = it },
                    placeholder = { Text("Search ${selectedKeywordTab.label.lowercase()} keywords…") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Add keyword field
                OutlinedTextField(
                    value = newKeywordText,
                    onValueChange = { newKeywordText = it },
                    placeholder = {
                        Text(
                            when (selectedKeywordTab) {
                                KeywordTabUnion.BLOCKLIST -> "Add a keyword to block (e.g. 'porn')"
                                KeywordTabUnion.WHITELIST -> "Add a whitelist entry (e.g. 'reddit.com/r/nofap')"
                            }
                        )
                    },
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                if (newKeywordText.isNotBlank()) {
                                    addKeyword(newKeywordText, selectedKeywordTab, keywordViewModel)
                                    newKeywordText = ""
                                    keyboard?.hide()
                                }
                            },
                            enabled = newKeywordText.isNotBlank()
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = "Add",
                                tint = if (newKeywordText.isNotBlank()) BrandOrange
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (newKeywordText.isNotBlank()) {
                                addKeyword(newKeywordText, selectedKeywordTab, keywordViewModel)
                                newKeywordText = ""
                                keyboard?.hide()
                            }
                        }
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Keyword list
                val filteredKeywords = keywordSearchQuery.let { query ->
                    val source = when (selectedKeywordTab) {
                        KeywordTabUnion.BLOCKLIST -> keywordState.blockKeywords
                        KeywordTabUnion.WHITELIST -> keywordState.whitelistKeywords
                    }
                    if (query.isBlank()) source
                    else source.filter { it.keyword.contains(query, ignoreCase = true) }
                }

                if (filteredKeywords.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = if (selectedKeywordTab == KeywordTabUnion.BLOCKLIST) Icons.Filled.Block else Icons.Filled.Check,
                                contentDescription = null,
                                tint = BrandOrange,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (keywordSearchQuery.isNotBlank())
                                    "No keywords match '$keywordSearchQuery'"
                                else
                                    "No ${selectedKeywordTab.label.lowercase()} keywords yet",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    filteredKeywords.forEach { keyword ->
                        KeywordChip(
                            keyword = keyword,
                            accentColor = if (selectedKeywordTab == KeywordTabUnion.BLOCKLIST)
                                MaterialTheme.colorScheme.error
                            else BrandOrange,
                            onDelete = { itemToDelete = DeleteTarget.Keyword(keyword) }
                        )
                    }
                }
            }

            // ================================================================
            // SECTION 2: Setting Titles
            // ================================================================
            SectionCard(title = "Setting Titles") {
                // Add setting title field
                OutlinedTextField(
                    value = newSettingTitleText,
                    onValueChange = { newSettingTitleText = it },
                    placeholder = { Text("Add a settings page title to block (e.g. 'battery')") },
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                if (newSettingTitleText.isNotBlank()) {
                                    keywordViewModel.addSettingTitleKeyword(newSettingTitleText)
                                    Timber.i("UnifiedBlockingPage: adding setting title keyword: $newSettingTitleText")
                                    newSettingTitleText = ""
                                    keyboard?.hide()
                                }
                            },
                            enabled = newSettingTitleText.isNotBlank()
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = "Add",
                                tint = if (newSettingTitleText.isNotBlank()) BrandOrange
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (newSettingTitleText.isNotBlank()) {
                                keywordViewModel.addSettingTitleKeyword(newSettingTitleText)
                                Timber.i("UnifiedBlockingPage: adding setting title keyword: $newSettingTitleText")
                                newSettingTitleText = ""
                                keyboard?.hide()
                            }
                        }
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (keywordState.settingTitleKeywords.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = null,
                                tint = BrandOrange,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "No setting title keywords yet",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    keywordState.settingTitleKeywords.forEach { keyword ->
                        KeywordChip(
                            keyword = keyword,
                            accentColor = MaterialTheme.colorScheme.primary,
                            onDelete = { itemToDelete = DeleteTarget.Keyword(keyword) }
                        )
                    }
                }
            }

            // ================================================================
            // SECTION 3: Package & Intent Blocking
            // ================================================================
            SectionCard(title = "Package & Intent Blocking") {
                // Master switch card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (packageIntentState.isSwitchOn)
                            BrandOrange.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.surface
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
                                    if (packageIntentState.isSwitchOn) BrandOrange
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(10.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Android,
                                contentDescription = null,
                                tint = if (packageIntentState.isSwitchOn) Color.White
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.size(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Blocking ${if (packageIntentState.isSwitchOn) "enabled" else "disabled"}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (packageIntentState.isSwitchOn)
                                    "Apps matching your list will be blocked on launch"
                                else "Toggle on to start blocking apps by package or intent name",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = packageIntentState.isSwitchOn,
                            onCheckedChange = {
                                packageIntentViewModel.toggleSwitch()
                                Timber.i("UnifiedBlockingPage: package+intent switch toggled")
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Add entry field
                OutlinedTextField(
                    value = newEntryText,
                    onValueChange = { newEntryText = it },
                    placeholder = {
                        Text("Use dots (.) for package names, text for intent names")
                    },
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                if (newEntryText.isNotBlank()) {
                                    packageIntentViewModel.addEntry(newEntryText)
                                    Timber.i("UnifiedBlockingPage: adding package/intent entry: $newEntryText")
                                    newEntryText = ""
                                    keyboard?.hide()
                                }
                            },
                            enabled = newEntryText.isNotBlank()
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = "Add",
                                tint = if (newEntryText.isNotBlank()) BrandOrange
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (newEntryText.isNotBlank()) {
                                packageIntentViewModel.addEntry(newEntryText)
                                Timber.i("UnifiedBlockingPage: adding package/intent entry: $newEntryText")
                                newEntryText = ""
                                keyboard?.hide()
                            }
                        }
                    )
                )

                // Auto-classification hint
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Info, contentDescription = null, tint = BrandOrange, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(
                            text = "Auto-classification: entries with dots (e.g. com.example.app) are treated as package names; entries without dots are treated as intent/class names.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Blocked packages section
                if (packageIntentState.blockedPackages.isNotEmpty()) {
                    SectionListHeader(
                        icon = Icons.Filled.Label,
                        title = "Blocked Package Names",
                        subtitle = "Exact match",
                        count = packageIntentState.blockedPackages.size
                    )
                    packageIntentState.blockedPackages.forEach { pkg ->
                        EntryChip(
                            title = pkg.packageName,
                            subtitle = "Package",
                            accentColor = MaterialTheme.colorScheme.primary,
                            onDelete = { itemToDelete = DeleteTarget.PackageEntry(pkg) }
                        )
                    }
                }

                // Blocked intents section
                if (packageIntentState.blockedIntents.isNotEmpty()) {
                    SectionListHeader(
                        icon = Icons.Filled.Code,
                        title = "Blocked Intent/Class Names",
                        subtitle = "Substring match",
                        count = packageIntentState.blockedIntents.size
                    )
                    packageIntentState.blockedIntents.forEach { intent ->
                        EntryChip(
                            title = intent.keyword,
                            subtitle = "Intent/Class",
                            accentColor = BrandOrange,
                            onDelete = { itemToDelete = DeleteTarget.IntentEntry(intent) }
                        )
                    }
                }

                // Combined empty state
                if (packageIntentState.blockedPackages.isEmpty() && packageIntentState.blockedIntents.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Filled.Android,
                                contentDescription = null,
                                tint = BrandOrange,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "No entries yet",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Add a package name (e.g. com.tiktok.android)
or an intent/class name (e.g. MainActivity)
using the field above.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // ---- Delete confirmation dialog ----
    itemToDelete?.let { target ->
        val (title, message) = when (target) {
            is DeleteTarget.Keyword -> {
                "Delete keyword?" to ""${target.keyword.keyword}" will be removed."
            }
            is DeleteTarget.PackageEntry -> {
                "Delete package entry?" to ""${target.pkg.packageName}" will be removed from blocked packages."
            }
            is DeleteTarget.IntentEntry -> {
                "Delete intent entry?" to ""${target.intent.keyword}" will be removed from blocked intents."
            }
        }

        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (target) {
                            is DeleteTarget.Keyword -> {
                                keywordViewModel.deleteKeyword(target.keyword.key)
                                Timber.i("UnifiedBlockingPage: deleting keyword: ${target.keyword.keyword}")
                            }
                            is DeleteTarget.PackageEntry -> {
                                packageIntentViewModel.deletePackage(target.pkg.key)
                                Timber.i("UnifiedBlockingPage: deleting package: ${target.pkg.packageName}")
                            }
                            is DeleteTarget.IntentEntry -> {
                                packageIntentViewModel.deleteIntent(target.intent.key)
                                Timber.i("UnifiedBlockingPage: deleting intent: ${target.intent.keyword}")
                            }
                        }
                        itemToDelete = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// ===== Private helpers =====

/**
 * Route a keyword add to the correct ViewModel method based on the active tab.
 */
private fun addKeyword(
    text: String,
    tab: KeywordTabUnion,
    viewModel: KeywordManagerViewModel
) {
    val trimmed = text.trim()
    when (tab) {
        KeywordTabUnion.BLOCKLIST -> {
            viewModel.addBlockKeyword(trimmed)
            Timber.i("UnifiedBlockingPage: adding blocklist keyword: $trimmed")
        }
        KeywordTabUnion.WHITELIST -> {
            viewModel.addWhitelistKeyword(trimmed)
            Timber.i("UnifiedBlockingPage: adding whitelist keyword: $trimmed")
        }
    }
}

// ===== Sub-components =====

/**
 * A themed card wrapping a page section with a title header.
 */
@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = BrandOrange
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = BrandOrange.copy(alpha = 0.2f)
            )
            content()
        }
    }
}

/**
 * A section list header with icon, title, subtitle, and count.
 */
@Composable
private fun SectionListHeader(
    icon: ImageVector,
    title: String,
    subtitle: String,
    count: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = BrandOrange, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.size(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "$title ($count)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * A keyword row card with accent indicator and delete button.
 */
@Composable
private fun KeywordChip(
    keyword: SelectedKeywordItemModel,
    accentColor: Color,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 4.dp, height = 20.dp)
                    .background(accentColor, RoundedCornerShape(2.dp))
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = keyword.keyword,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * A package/intent entry row card with accent indicator and delete button.
 */
@Composable
private fun EntryChip(
    title: String,
    subtitle: String,
    accentColor: Color,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 4.dp, height = 20.dp)
                    .background(accentColor, RoundedCornerShape(2.dp))
            )
            Spacer(modifier = Modifier.size(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ===== Local types =====

/**
 * Union type for the keyword tab state inside this page.
 */
private enum class KeywordTabUnion(val label: String) {
    BLOCKLIST("Blocklist"),
    WHITELIST("Whitelist")
}

/**
 * Sealed class representing what item is pending deletion confirmation.
 */
private sealed class DeleteTarget {
    data class Keyword(val keyword: SelectedKeywordItemModel) : DeleteTarget()
    data class PackageEntry(val pkg: SelectedAppItemModel) : DeleteTarget()
    data class IntentEntry(val intent: SelectedKeywordItemModel) : DeleteTarget()
}
