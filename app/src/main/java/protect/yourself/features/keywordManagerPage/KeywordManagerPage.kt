package protect.yourself.features.keywordManagerPage

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import protect.yourself.database.selectedKeywords.SelectedKeywordItemModel
import protect.yourself.theme.BrandOrange

/**
 * KeywordManagerPage — UI for managing block/whitelist/setting-title keywords.
 *
 * Features:
 *  - 3 tabs: Blocklist, Whitelist, Setting Titles
 *  - Search field to filter the current tab
 *  - Add field to add new keyword to the current tab
 *  - List with delete button per entry
 *  - Live count per tab + total count in TopAppBar
 *
 * The page observes DB via Flow so changes are reflected immediately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeywordManagerPage(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: KeywordManagerViewModel = viewModel(
        factory = KeywordManagerViewModel.factory(context.applicationContext as android.app.Application)
    )
    val state by viewModel.state.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current

    var newKeywordText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Keyword Manager")
                        Text(
                            text = "${state.totalCount()} total • ${state.filteredKeywords().size} shown",
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
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Tab chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = state.activeTab == KeywordTab.BLOCKLIST,
                    onClick = { viewModel.setActiveTab(KeywordTab.BLOCKLIST) },
                    label = { Text("Blocklist (${state.blockKeywords.size})") }
                )
                FilterChip(
                    selected = state.activeTab == KeywordTab.WHITELIST,
                    onClick = { viewModel.setActiveTab(KeywordTab.WHITELIST) },
                    label = { Text("Whitelist (${state.whitelistKeywords.size})") }
                )
                FilterChip(
                    selected = state.activeTab == KeywordTab.SETTING_TITLES,
                    onClick = { viewModel.setActiveTab(KeywordTab.SETTING_TITLES) },
                    label = { Text("Titles (${state.settingTitleKeywords.size})") }
                )
            }

            // Add new keyword field
            OutlinedTextField(
                value = newKeywordText,
                onValueChange = { newKeywordText = it },
                placeholder = {
                    Text(
                        when (state.activeTab) {
                            KeywordTab.BLOCKLIST -> "Add a keyword to block (e.g. 'porn')"
                            KeywordTab.WHITELIST -> "Add a whitelist entry (e.g. 'reddit.com/r/nofap')"
                            KeywordTab.SETTING_TITLES -> "Add a settings page title to block (e.g. 'battery')"
                        }
                    )
                },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                trailingIcon = {
                    IconButton(
                        onClick = {
                            if (newKeywordText.isNotBlank()) {
                                when (state.activeTab) {
                                    KeywordTab.BLOCKLIST -> viewModel.addBlockKeyword(newKeywordText)
                                    KeywordTab.WHITELIST -> viewModel.addWhitelistKeyword(newKeywordText)
                                    KeywordTab.SETTING_TITLES -> viewModel.addSettingTitleKeyword(newKeywordText)
                                }
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .focusRequester(focusRequester),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (newKeywordText.isNotBlank()) {
                            when (state.activeTab) {
                                KeywordTab.BLOCKLIST -> viewModel.addBlockKeyword(newKeywordText)
                                KeywordTab.WHITELIST -> viewModel.addWhitelistKeyword(newKeywordText)
                                KeywordTab.SETTING_TITLES -> viewModel.addSettingTitleKeyword(newKeywordText)
                            }
                            newKeywordText = ""
                            keyboard?.hide()
                        }
                    }
                )
            )

            // Search field
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = { viewModel.search(it) },
                placeholder = { Text("Search…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                singleLine = true
            )

            // Description card for the active tab
            TabDescriptionCard(activeTab = state.activeTab)

            // Keyword list
            val filtered = state.filteredKeywords()
            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val (icon, message) = when (state.activeTab) {
                            KeywordTab.BLOCKLIST -> Icons.Filled.Block to "No blocklist keywords yet"
                            KeywordTab.WHITELIST -> Icons.Filled.Check to "No whitelist keywords yet"
                            KeywordTab.SETTING_TITLES -> Icons.Filled.Settings to "No setting title keywords yet"
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = BrandOrange,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (state.searchQuery.isNotBlank()) "No keywords match '$state.searchQuery'"
                            else message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp
                    )
                ) {
                    items(filtered, key = { it.key }) { keyword ->
                        KeywordRow(
                            keyword = keyword,
                            accentColor = when (state.activeTab) {
                                KeywordTab.BLOCKLIST -> MaterialTheme.colorScheme.error
                                KeywordTab.WHITELIST -> BrandOrange
                                KeywordTab.SETTING_TITLES -> MaterialTheme.colorScheme.primary
                            },
                            onDelete = { viewModel.deleteKeyword(keyword.key) }
                        )
                    }
                }
            }
        }
    }
}

// ===== Sub-components =====

@Composable
private fun TabDescriptionCard(activeTab: KeywordTab) {
    val (title, description) = when (activeTab) {
        KeywordTab.BLOCKLIST -> "Blocklist keywords" to "URLs and content matching these keywords will be blocked in supported browsers and apps."
        KeywordTab.WHITELIST -> "Whitelist keywords" to "URLs matching these keywords will be allowed even if a blocklist keyword would otherwise trigger a block."
        KeywordTab.SETTING_TITLES -> "Setting title keywords" to "Settings pages whose title contains any of these keywords will be blocked. Useful for blocking access to Device Admin, App Info, etc."
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = BrandOrange
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun KeywordRow(
    keyword: SelectedKeywordItemModel,
    accentColor: androidx.compose.ui.graphics.Color,
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
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Accent indicator
            Box(
                modifier = Modifier
                    .size(width = 4.dp, height = 24.dp)
                    .background(accentColor, RoundedCornerShape(2.dp))
            )
            Spacer(modifier = Modifier.size(12.dp))
            Text(
                text = keyword.keyword,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete keyword",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
