package protect.yourself.features.blockerPage.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import protect.yourself.features.blockerPage.utils.BlockingValidator
import protect.yourself.features.blockerPage.utils.ValidationResult
import protect.yourself.features.blockerPage.utils.toUserMessage
import protect.yourself.features.keywordManagerPage.KeywordManagerEvent
import protect.yourself.features.keywordManagerPage.KeywordManagerViewModel
import protect.yourself.features.packageIntentPage.PackageIntentEvent
import protect.yourself.features.packageIntentPage.PackageIntentViewModel
import protect.yourself.theme.BrandOrange
import timber.log.Timber

/**
 * UnifiedBlockingPage — a single scrollable page that merges ALL blocking-list
 * management functionality into ONE card:
 *
 *  1. Content Blocklist keywords (URL/text matching)
 *  2. Content Whitelist keywords (URL override)
 *  3. Setting Titles (settings pages blocked by title)
 *  4. Blocked Package Names (exact-match app blocking)
 *  5. Blocked Intent/Class Names (substring-match app blocking)
 *
 * UI structure (single card):
 *  ┌──────────────────────────────────────────────────────────┐
 *  │ Header: title + total count                              │
 *  │ Tab row: Blocklist | Whitelist | Titles | Packages | Intents
 *  │ Conditional master switch (Titles / Packages+Intents)    │
 *  │ Search field                                             │
 *  │ Add field with inline validation                         │
 *  │ Horizontal divider                                       │
 *  │ Item list (each row: accent | title | delete button)     │
 *  │ Empty / no-match state                                   │
 *  └──────────────────────────────────────────────────────────┘
 *
 * All five lists share the same UI surface — the active tab determines which
 * list is shown, what validation rules apply, and whether a master switch is
 * rendered above the search field.
 *
 * Each list has its own ViewModel for state + persistence. The page subscribes
 * to both ViewModels' state flows + one-shot event flows (for toast feedback).
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

    // ---- UI state ----
    // rememberSaveable so the active tab + search query survive screen rotation.
    var activeTab by rememberSaveable { mutableStateOf(UnifiedBlockingTab.BLOCKLIST) }
    var searchText by rememberSaveable { mutableStateOf("") }
    var newEntryText by rememberSaveable { mutableStateOf("") }
    // Inline validation error message for the add field. Null = no error.
    var inlineError by remember { mutableStateOf<String?>(null) }

    // Delete confirmation state (typed union — covers all 5 list types).
    var itemToDelete by remember { mutableStateOf<DeleteTarget?>(null) }

    // ---- Toast feedback from ViewModels ----
    LaunchedEffect(Unit) {
        Timber.i("UnifiedBlockingPage: subscribed to KeywordManager events")
        keywordViewModel.events.collect { event ->
            handleKeywordEvent(event, context) { inlineError = it }
        }
    }
    LaunchedEffect(Unit) {
        Timber.i("UnifiedBlockingPage: subscribed to PackageIntent events")
        packageIntentViewModel.events.collect { event ->
            handlePackageIntentEvent(event, context) { inlineError = it }
        }
    }

    // ---- Derived per-tab data ----
    val tabData by remember(activeTab) {
        derivedStateOf { activeTab.data(keywordState, packageIntentState) }
    }

    val filteredItems by remember(tabData, searchText) {
        derivedStateOf {
            if (searchText.isBlank()) tabData.items
            else tabData.items.filter { it.title.contains(searchText, ignoreCase = true) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Blocking Lists")
                        Text(
                            text = "${keywordState.userKeywordCount()} custom entries • " +
                                "${packageIntentState.blockedPackages.size} packages • " +
                                "${packageIntentState.blockedIntents.size} intents",
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // ============================================================
            // THE SINGLE CARD — header + tabs + switch + search + add
            // ============================================================
            item {
                UnifiedBlockingCard(
                    activeTab = activeTab,
                    tabData = tabData,
                    keywordState = keywordState,
                    packageIntentState = packageIntentState,
                    searchText = searchText,
                    onSearchTextChange = {
                        searchText = it
                        inlineError = null
                    },
                    newEntryText = newEntryText,
                    onNewEntryTextChange = {
                        newEntryText = it
                        // Clear inline error as the user types
                        inlineError = null
                    },
                    inlineError = inlineError,
                    onAddClick = {
                        handleAdd(
                            activeTab = activeTab,
                            text = newEntryText,
                            keywordViewModel = keywordViewModel,
                            packageIntentViewModel = packageIntentViewModel,
                            existingItems = tabData.items.map { it.title },
                            onError = { inlineError = it },
                            onSuccess = {
                                newEntryText = ""
                                inlineError = null
                                keyboard?.hide()
                            }
                        )
                    },
                    onTabSelected = { tab ->
                        activeTab = tab
                        searchText = ""
                        newEntryText = ""
                        inlineError = null
                        Timber.d("UnifiedBlockingPage: tab switched to ${tab.label}")
                    },
                    onToggleSettingTitleSwitch = { enabled ->
                        keywordViewModel.setSettingTitleSwitchEnabled(enabled)
                    },
                    onTogglePackageIntentSwitch = { enabled ->
                        packageIntentViewModel.setSwitchEnabled(enabled)
                    }
                )
            }

            // ============================================================
            // ITEM LIST (rendered as flat rows directly below the card to
            // keep the LazyColumn performant for 500+ keywords)
            // ============================================================
            if (filteredItems.isEmpty()) {
                item {
                    EmptyState(
                        activeTab = activeTab,
                        searchQuery = searchText
                    )
                }
            } else {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${filteredItems.size} of ${tabData.items.size} ${activeTab.label.lowercase()}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                items(filteredItems, key = { it.key }) { item ->
                    ItemRow(
                        item = item,
                        onDelete = {
                            itemToDelete = when (activeTab) {
                                UnifiedBlockingTab.BLOCKLIST ->
                                    DeleteTarget.BlocklistKeyword(item)
                                UnifiedBlockingTab.WHITELIST ->
                                    DeleteTarget.WhitelistKeyword(item)
                                UnifiedBlockingTab.SETTING_TITLES ->
                                    DeleteTarget.SettingTitleKeyword(item)
                                UnifiedBlockingTab.PACKAGES ->
                                    DeleteTarget.PackageEntry(item)
                                UnifiedBlockingTab.INTENTS ->
                                    DeleteTarget.IntentEntry(item)
                            }
                        }
                    )
                }
            }

            // ============================================================
            // Help card (tab-specific examples + match semantics)
            // ============================================================
            item {
                TabHelpCard(activeTab = activeTab)
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }

    // ---- Delete confirmation dialog (context-aware) ----
    itemToDelete?.let { target ->
        val dialogInfo = target.toDialogInfo()
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text(dialogInfo.title) },
            text = { Text(dialogInfo.message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete(
                            target = target,
                            keywordViewModel = keywordViewModel,
                            packageIntentViewModel = packageIntentViewModel
                        )
                        itemToDelete = null
                    }
                ) {
                    Text(
                        "Delete",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
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

// ===== Tab definition =====

/**
 * The five tabs of the unified blocking page. Each tab corresponds to one of
 * the five blocking lists managed by this page.
 */
enum class UnifiedBlockingTab(
    val label: String,
    val accentColor: Color,
    val icon: ImageVector,
    val description: String
) {
    BLOCKLIST(
        label = "Blocklist",
        accentColor = Color(0xFFD32F2F), // red — block
        icon = Icons.Filled.Block,
        description = "URLs and content matching these keywords will be blocked in supported browsers and apps."
    ),
    WHITELIST(
        label = "Whitelist",
        accentColor = BrandOrange,
        icon = Icons.Filled.Check,
        description = "URLs matching these keywords will be allowed even if a blocklist keyword would otherwise trigger a block."
    ),
    SETTING_TITLES(
        label = "Setting Titles",
        accentColor = Color(0xFF1976D2), // blue — system
        icon = Icons.Filled.Settings,
        description = "Settings pages whose title contains any of these keywords will be blocked. Useful for blocking access to Device Admin, App Info, etc."
    ),
    PACKAGES(
        label = "Packages",
        accentColor = Color(0xFF7B1FA2), // purple — app
        icon = Icons.Filled.Apps,
        description = "Apps whose package name EXACTLY matches any entry will be blocked on launch. Package names look like 'com.example.app'."
    ),
    INTENTS(
        label = "Intents",
        accentColor = Color(0xFF00838F), // teal — class
        icon = Icons.Filled.Code,
        description = "Apps whose activity/class name CONTAINS any entry (as a substring) will be blocked on launch. Examples: 'MainActivity', 'LoginActivity'."
    );

    /**
     * Compute the per-tab data (list of items, switch state, etc.) from the
     * two ViewModels' states.
     */
    fun data(
        keywordState: protect.yourself.features.keywordManagerPage.KeywordManagerState,
        packageIntentState: protect.yourself.features.packageIntentPage.PackageIntentState
    ): TabData {
        return when (this) {
            BLOCKLIST -> TabData(
                // UB-01 fix: filter out system-defined (preset) keywords —
                // only show user-added keywords in the UI.
                items = keywordState.blockKeywords
                    .filterNot { keywordState.isSystemDefined(it) }
                    .map { it.toItem(accent = accentColor, subtitle = "Blocklist keyword") },
                masterSwitchState = null // blocklist is gated by PornBlocker switch elsewhere
            )
            WHITELIST -> TabData(
                // UB-01 fix: filter out system-defined (preset) keywords.
                items = keywordState.whitelistKeywords
                    .filterNot { keywordState.isSystemDefined(it) }
                    .map { it.toItem(accent = accentColor, subtitle = "Whitelist keyword") },
                masterSwitchState = null
            )
            SETTING_TITLES -> TabData(
                items = keywordState.settingTitleKeywords.map {
                    it.toItem(accent = accentColor, subtitle = "Settings title")
                },
                masterSwitchState = keywordState.isSettingTitleSwitchOn
            )
            PACKAGES -> TabData(
                items = packageIntentState.blockedPackages.map {
                    it.toItem(accent = accentColor, subtitle = "Package name")
                },
                masterSwitchState = packageIntentState.isSwitchOn
            )
            INTENTS -> TabData(
                items = packageIntentState.blockedIntents.map {
                    it.toItem(accent = accentColor, subtitle = "Intent/Class")
                },
                masterSwitchState = packageIntentState.isSwitchOn
            )
        }
    }
}

/**
 * Per-tab data computed from the ViewModel states.
 *
 * @param items list of items to display (unfiltered — search applied separately)
 * @param masterSwitchState state of the tab's master switch, or null if the tab
 *  has no master switch (Blocklist/Whitelist are gated by PornBlocker which is
 *  configured elsewhere)
 */
data class TabData(
    val items: List<ItemRepresentation>,
    val masterSwitchState: Boolean?
)

/**
 * A unified representation of a list item (regardless of whether the source is
 * SelectedKeywordItemModel or SelectedAppItemModel). Used so the LazyColumn can
 * render all five list types with the same composable.
 */
data class ItemRepresentation(
    val key: String,
    val title: String,
    val subtitle: String,
    val accentColor: Color
)

/** Convert a keyword model to the unified representation. */
private fun SelectedKeywordItemModel.toItem(
    accent: Color = BrandOrange,
    subtitle: String = "Keyword"
): ItemRepresentation = ItemRepresentation(
    key = key,
    title = keyword,
    subtitle = subtitle,
    accentColor = accent
)

/** Convert a package model to the unified representation. */
private fun SelectedAppItemModel.toItem(
    accent: Color = BrandOrange,
    subtitle: String = "Package"
): ItemRepresentation = ItemRepresentation(
    key = key,
    title = packageName,
    subtitle = subtitle,
    accentColor = accent
)

// ===== The single card composable =====

/**
 * UnifiedBlockingCard — the ONE card that contains the tab row, optional
 * master switch, search field, and add field with inline validation.
 *
 * The list of items is rendered BELOW this card (still inside the same
 * LazyColumn) to keep large lists (500+ keywords) performant.
 */
@Composable
private fun UnifiedBlockingCard(
    activeTab: UnifiedBlockingTab,
    tabData: TabData,
    keywordState: protect.yourself.features.keywordManagerPage.KeywordManagerState,
    packageIntentState: protect.yourself.features.packageIntentPage.PackageIntentState,
    searchText: String,
    onSearchTextChange: (String) -> Unit,
    newEntryText: String,
    onNewEntryTextChange: (String) -> Unit,
    inlineError: String?,
    onAddClick: () -> Unit,
    onTabSelected: (UnifiedBlockingTab) -> Unit,
    onToggleSettingTitleSwitch: (Boolean) -> Unit,
    onTogglePackageIntentSwitch: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // ---------- Header ----------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            activeTab.accentColor.copy(alpha = 0.15f),
                            RoundedCornerShape(10.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        activeTab.icon,
                        contentDescription = null,
                        tint = activeTab.accentColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Blocking Lists",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = activeTab.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ---------- Tab row (horizontally scrollable) ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                UnifiedBlockingTab.entries.forEach { tab ->
                    // UB-01 fix: show USER-ADDED count, not total (which includes
                    // 1189+ system presets). This gives the user meaningful numbers.
                    val count = when (tab) {
                        UnifiedBlockingTab.BLOCKLIST -> keywordState.blockKeywords.count { !keywordState.isSystemDefined(it) }
                        UnifiedBlockingTab.WHITELIST -> keywordState.whitelistKeywords.count { !keywordState.isSystemDefined(it) }
                        UnifiedBlockingTab.SETTING_TITLES -> keywordState.settingTitleKeywords.size
                        UnifiedBlockingTab.PACKAGES -> packageIntentState.blockedPackages.size
                        UnifiedBlockingTab.INTENTS -> packageIntentState.blockedIntents.size
                    }
                    FilterChip(
                        selected = tab == activeTab,
                        onClick = { onTabSelected(tab) },
                        label = { Text("${tab.label} ($count)") },
                        leadingIcon = {
                            Icon(
                                tab.icon,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ---------- Conditional master switch ----------
            // Setting Titles, Packages, and Intents tabs each have a master
            // switch that gates enforcement. Blocklist/Whitelist are gated by
            // the PornBlocker switch which lives elsewhere.
            tabData.masterSwitchState?.let { isOn ->
                MasterSwitchRow(
                    activeTab = activeTab,
                    isOn = isOn,
                    onToggle = { enabled ->
                        when (activeTab) {
                            UnifiedBlockingTab.SETTING_TITLES -> onToggleSettingTitleSwitch(enabled)
                            UnifiedBlockingTab.PACKAGES,
                            UnifiedBlockingTab.INTENTS -> onTogglePackageIntentSwitch(enabled)
                            else -> Unit
                        }
                    }
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            // ---------- Search field ----------
            OutlinedTextField(
                value = searchText,
                onValueChange = onSearchTextChange,
                placeholder = { Text("Search ${activeTab.label.lowercase()}…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchText.isNotEmpty()) {
                        IconButton(onClick = { onSearchTextChange("") }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Clear search")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ---------- Add field with inline validation ----------
            OutlinedTextField(
                value = newEntryText,
                onValueChange = onNewEntryTextChange,
                placeholder = { Text(activeTab.addPlaceholder()) },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                trailingIcon = {
                    IconButton(
                        onClick = onAddClick,
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
                isError = inlineError != null,
                supportingText = {
                    if (inlineError != null) {
                        Text(
                            text = inlineError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text(
                            text = activeTab.addHelperText(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { onAddClick() }
                )
            )
        }
    }
}

/**
 * Per-tab placeholder for the add field.
 */
private fun UnifiedBlockingTab.addPlaceholder(): String = when (this) {
    UnifiedBlockingTab.BLOCKLIST -> "Add a keyword to block (e.g. 'porn')"
    UnifiedBlockingTab.WHITELIST -> "Add a whitelist entry (e.g. 'reddit.com/r/nofap')"
    UnifiedBlockingTab.SETTING_TITLES -> "Add a settings title to block (e.g. 'battery')"
    UnifiedBlockingTab.PACKAGES -> "Add a package name (e.g. com.tiktok.android)"
    UnifiedBlockingTab.INTENTS -> "Add a class name (e.g. MainActivity)"
}

/**
 * Per-tab helper text shown below the add field when no error is present.
 */
private fun UnifiedBlockingTab.addHelperText(): String = when (this) {
    UnifiedBlockingTab.BLOCKLIST -> "Min 2 chars, max 100. Case-insensitive substring match."
    UnifiedBlockingTab.WHITELIST -> "Min 2 chars, max 100. Overrides blocklist match."
    UnifiedBlockingTab.SETTING_TITLES -> "Min 2 chars, max 100. Matched against settings page titles."
    UnifiedBlockingTab.PACKAGES -> "Must look like 'com.example.app' (lowercase, dots, no spaces). Exact match."
    UnifiedBlockingTab.INTENTS -> "Letters, digits, dots, _ and \$ only. Substring match against class names."
}

/**
 * Master switch row. Renders differently based on whether the switch is for
 * setting-title blocking or package+intent blocking.
 */
@Composable
private fun MasterSwitchRow(
    activeTab: UnifiedBlockingTab,
    isOn: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val (title, subtitle) = when (activeTab) {
        UnifiedBlockingTab.SETTING_TITLES -> "Block Settings Pages by Title" to
            if (isOn) "Settings pages matching your titles will be blocked."
            else "Titles are saved but NOT enforced. Toggle on to enable."
        UnifiedBlockingTab.PACKAGES, UnifiedBlockingTab.INTENTS -> "Package + Intent Blocking" to
            if (isOn) "Apps matching your package/intent lists will be blocked on launch."
            else "Entries are saved but NOT enforced. Toggle on to enable."
        else -> return
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isOn) activeTab.accentColor.copy(alpha = 0.12f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
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
                    .size(36.dp)
                    .background(
                        if (isOn) activeTab.accentColor
                        else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(9.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (activeTab == UnifiedBlockingTab.SETTING_TITLES)
                        Icons.Filled.Settings
                    else Icons.Filled.Android,
                    contentDescription = null,
                    tint = if (isOn) Color.White
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = isOn, onCheckedChange = onToggle)
        }
    }
}

// ===== Item row =====

/**
 * A single list item rendered as a small card with accent indicator, title,
 * subtitle, and delete button.
 */
@Composable
private fun ItemRow(
    item: ItemRepresentation,
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
                .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 4.dp, height = 28.dp)
                    .background(item.accentColor, RoundedCornerShape(2.dp))
            )
            Spacer(modifier = Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = if (item.subtitle.contains("Package") || item.subtitle.contains("Intent"))
                        FontFamily.Monospace
                    else FontFamily.Default,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete ${item.subtitle}",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ===== Empty state =====

/**
 * Empty-state card shown when the current tab has no items (or no matches for
 * the active search query).
 */
@Composable
private fun EmptyState(
    activeTab: UnifiedBlockingTab,
    searchQuery: String
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = activeTab.icon,
                contentDescription = null,
                tint = activeTab.accentColor.copy(alpha = 0.6f),
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (searchQuery.isNotBlank())
                    "No ${activeTab.label.lowercase()} match '$searchQuery'"
                else "No ${activeTab.label.lowercase()} yet",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (searchQuery.isNotBlank())
                    "Try a different search term, or clear the search to see all entries."
                else activeTab.emptyHelpText(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Per-tab help text shown in the empty state (no items + no search).
 */
private fun UnifiedBlockingTab.emptyHelpText(): String = when (this) {
    UnifiedBlockingTab.BLOCKLIST -> "No custom blocklist keywords yet. Add keywords above to block URLs and content that match them. System-defined keywords are hidden but still active."
    UnifiedBlockingTab.WHITELIST -> "No custom whitelist keywords yet. Add keywords above to whitelist specific URLs (overrides the blocklist). System-defined keywords are hidden but still active."
    UnifiedBlockingTab.SETTING_TITLES -> "Add settings page titles above (e.g. 'battery', 'apps') to block access to those pages."
    UnifiedBlockingTab.PACKAGES -> "Add package names above (e.g. com.tiktok.android) to block those apps on launch."
    UnifiedBlockingTab.INTENTS -> "Add class names above (e.g. MainActivity) to block apps whose activities contain that name."
}

// ===== Help card =====

/**
 * A small help card with tab-specific examples + match semantics. Rendered at
 * the bottom of the list so the user always has reference info available
 * without leaving the page.
 */
@Composable
private fun TabHelpCard(activeTab: UnifiedBlockingTab) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = BrandOrange,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "How ${activeTab.label.lowercase()} matching works",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = BrandOrange
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = BrandOrange.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = activeTab.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Examples:",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            activeTab.examples().forEach { example ->
                Text(
                    text = "  •  $example",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Per-tab example entries shown in the help card.
 */
private fun UnifiedBlockingTab.examples(): List<String> = when (this) {
    UnifiedBlockingTab.BLOCKLIST -> listOf("porn", "xxx", "adult", "nsfw")
    UnifiedBlockingTab.WHITELIST -> listOf("reddit.com/r/nofap", "wikipedia.org", "example.com/educational")
    UnifiedBlockingTab.SETTING_TITLES -> listOf("battery", "apps", "device admin", "accessibility")
    UnifiedBlockingTab.PACKAGES -> listOf("com.tiktok.android", "com.instagram.android", "com.snapchat.android")
    UnifiedBlockingTab.INTENTS -> listOf("MainActivity", "LoginActivity", "SettingsActivity")
}

// ===== Add handler =====

/**
 * Validate the input against the active tab's rules and dispatch to the
 * appropriate ViewModel method. Sets [onError] for inline validation errors.
 *
 * The validation is also done inside the ViewModel (single source of truth),
 * but pre-validating here gives the user instant inline feedback without
 * waiting for the ViewModel event round-trip.
 */
private fun handleAdd(
    activeTab: UnifiedBlockingTab,
    text: String,
    keywordViewModel: KeywordManagerViewModel,
    packageIntentViewModel: PackageIntentViewModel,
    existingItems: List<String>,
    onError: (String) -> Unit,
    onSuccess: () -> Unit
) {
    // Pick the right validator for the tab
    val result: ValidationResult = when (activeTab) {
        UnifiedBlockingTab.BLOCKLIST,
        UnifiedBlockingTab.WHITELIST,
        UnifiedBlockingTab.SETTING_TITLES ->
            BlockingValidator.validateKeyword(text, existingItems)
        UnifiedBlockingTab.PACKAGES ->
            BlockingValidator.validatePackageName(text, existingItems)
        UnifiedBlockingTab.INTENTS ->
            BlockingValidator.validateIntentName(text, existingItems)
    }

    val errorMessage = result.toUserMessage()
    if (errorMessage != null) {
        // Show inline error; ViewModel will ALSO emit an event (for the toast)
        // but we set the inline error immediately for instant feedback.
        onError(errorMessage)
        Timber.w("UnifiedBlockingPage: add rejected (tab=${activeTab.label}, input='$text', reason=$result)")
        // Still dispatch to the ViewModel so its event flow fires (the toast
        // provides a second feedback channel for the user).
    }

    when (activeTab) {
        UnifiedBlockingTab.BLOCKLIST -> keywordViewModel.addBlockKeyword(text)
        UnifiedBlockingTab.WHITELIST -> keywordViewModel.addWhitelistKeyword(text)
        UnifiedBlockingTab.SETTING_TITLES -> keywordViewModel.addSettingTitleKeyword(text)
        UnifiedBlockingTab.PACKAGES -> packageIntentViewModel.addPackageEntry(text)
        UnifiedBlockingTab.INTENTS -> packageIntentViewModel.addIntentEntry(text)
    }

    // Only clear the input + hide keyboard if validation passed. The ViewModel
    // is the source of truth — if it accepts, the new item will appear in the
    // list and we can safely clear. If it rejects, we keep the input so the
    // user can edit and retry.
    if (errorMessage == null) {
        onSuccess()
    }
}

// ===== Event handlers =====

/**
 * Handle a [KeywordManagerEvent] from the KeywordManagerViewModel.
 *
 * @param setError callback to set the inline error text (null to clear)
 */
private fun handleKeywordEvent(
    event: KeywordManagerEvent,
    context: android.content.Context,
    setError: (String?) -> Unit
) {
    when (event) {
        is KeywordManagerEvent.Added -> {
            setError(null)
            Toast.makeText(
                context,
                "Added '${event.entry}' to ${event.listName}",
                Toast.LENGTH_SHORT
            ).show()
            Timber.d("UnifiedBlockingPage: keyword event Added '${event.entry}' to ${event.listName}")
        }
        is KeywordManagerEvent.Deleted -> {
            Toast.makeText(
                context,
                "Removed '${event.entry}'",
                Toast.LENGTH_SHORT
            ).show()
        }
        is KeywordManagerEvent.ValidationFailed -> {
            val msg = event.result.toUserMessage() ?: "Invalid input"
            setError(msg)
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
        is KeywordManagerEvent.Error -> {
            Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
        }
    }
}

/**
 * Handle a [PackageIntentEvent] from the PackageIntentViewModel.
 */
private fun handlePackageIntentEvent(
    event: PackageIntentEvent,
    context: android.content.Context,
    setError: (String?) -> Unit
) {
    when (event) {
        is PackageIntentEvent.Added -> {
            setError(null)
            Toast.makeText(
                context,
                "Added '${event.entry}' to ${event.listName}",
                Toast.LENGTH_SHORT
            ).show()
            Timber.d("UnifiedBlockingPage: package/intent event Added '${event.entry}' to ${event.listName}")
        }
        is PackageIntentEvent.Deleted -> {
            Toast.makeText(
                context,
                "Removed '${event.entry}' from ${event.listName}",
                Toast.LENGTH_SHORT
            ).show()
        }
        is PackageIntentEvent.ValidationFailed -> {
            val msg = event.result.toUserMessage() ?: "Invalid input"
            setError(msg)
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
        is PackageIntentEvent.Error -> {
            Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
        }
    }
}

// ===== Delete targets =====

/**
 * Sealed class representing what item is pending deletion confirmation.
 *
 * Each subclass carries enough context to:
 *  1. Show a context-aware confirmation dialog (title + message)
 *  2. Dispatch the delete to the right ViewModel method
 */
private sealed class DeleteTarget {
    abstract val item: ItemRepresentation

    data class BlocklistKeyword(override val item: ItemRepresentation) : DeleteTarget()
    data class WhitelistKeyword(override val item: ItemRepresentation) : DeleteTarget()
    data class SettingTitleKeyword(override val item: ItemRepresentation) : DeleteTarget()
    data class PackageEntry(override val item: ItemRepresentation) : DeleteTarget()
    data class IntentEntry(override val item: ItemRepresentation) : DeleteTarget()
}

/**
 * Build a context-aware dialog (title + message) for the pending delete.
 */
private fun DeleteTarget.toDialogInfo(): DialogInfo {
    val listName = when (this) {
        is DeleteTarget.BlocklistKeyword -> "blocklist"
        is DeleteTarget.WhitelistKeyword -> "whitelist"
        is DeleteTarget.SettingTitleKeyword -> "setting titles"
        is DeleteTarget.PackageEntry -> "blocked packages"
        is DeleteTarget.IntentEntry -> "blocked intents"
    }
    return DialogInfo(
        title = "Delete from $listName?",
        message = "\"${item.title}\" will be removed from your $listName. This cannot be undone."
    )
}

private data class DialogInfo(val title: String, val message: String)

/**
 * Dispatch the delete to the appropriate ViewModel method.
 */
private fun confirmDelete(
    target: DeleteTarget,
    keywordViewModel: KeywordManagerViewModel,
    packageIntentViewModel: PackageIntentViewModel
) {
    when (target) {
        is DeleteTarget.BlocklistKeyword,
        is DeleteTarget.WhitelistKeyword,
        is DeleteTarget.SettingTitleKeyword -> {
            keywordViewModel.deleteKeyword(target.item.key)
            Timber.i("UnifiedBlockingPage: deleting keyword '${target.item.title}' (key=${target.item.key})")
        }
        is DeleteTarget.PackageEntry -> {
            packageIntentViewModel.deletePackage(target.item.key)
            Timber.i("UnifiedBlockingPage: deleting package '${target.item.title}' (key=${target.item.key})")
        }
        is DeleteTarget.IntentEntry -> {
            packageIntentViewModel.deleteIntent(target.item.key)
            Timber.i("UnifiedBlockingPage: deleting intent '${target.item.title}' (key=${target.item.key})")
        }
    }
}
