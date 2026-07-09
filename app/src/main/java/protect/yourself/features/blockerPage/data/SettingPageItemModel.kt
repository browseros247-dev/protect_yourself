package protect.yourself.features.blockerPage.data

import protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers

/**
 * Setting page item model — represents one row in the BlockerPage settings list.
 *
 * Ported from original SettingPageItemModel.kt.
 */
data class SettingPageItemModel(
    val identifier: SettingPageItemIdentifiers,
    val title: String,
    val info: String? = null,
    val switchKey: String? = null,
    val switchValue: Boolean = false,
    val actionLabel: String? = null,
    val isSection: Boolean = false,
    val isVisible: Boolean = true
)
