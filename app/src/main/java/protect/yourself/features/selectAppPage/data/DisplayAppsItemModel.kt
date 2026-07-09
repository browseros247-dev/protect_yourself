package protect.yourself.features.selectAppPage.data

import android.graphics.drawable.Drawable

/**
 * Display model for an installed app.
 */
data class DisplayAppsItemModel(
    val packageName: String,
    val appName: String,
    val icon: Drawable? = null,
    val isSelected: Boolean = false
)
