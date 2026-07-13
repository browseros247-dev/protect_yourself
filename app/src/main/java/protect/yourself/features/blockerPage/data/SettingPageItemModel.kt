package protect.yourself.features.blockerPage.data

import protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers

/**
 * Setting page item model — represents one row in the BlockerPage settings list.
 *
 * Ported from original SettingPageItemModel.kt.
 *
 * @param isDisabled When true, the switch/action is rendered as disabled (greyed out,
 *   not tappable). Used when a prerequisite setting is not enabled. The
 *   [dependencyMessage] is shown as a subtitle explaining what must be enabled first.
 * @param dependencyMessage When [isDisabled] is true, this message explains which
 *   prerequisite setting must be enabled first. Example:
 *   "Enable VPN (DNS blocking) first to use SafeSearch enforcement."
 */
data class SettingPageItemModel(
    val identifier: SettingPageItemIdentifiers,
    val title: String,
    val info: String? = null,
    val switchKey: String? = null,
    val switchValue: Boolean = false,
    val actionLabel: String? = null,
    val isSection: Boolean = false,
    val isDisabled: Boolean = false,
    val dependencyMessage: String? = null
)
