package protect.yourself.features.mainActivityPage.repository

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import protect.yourself.R

/**
 * Bottom nav screens.
 *
 * Implemented as an enum to avoid data object initialization order issues
 * that caused NullPointerException on launch (sealed class with data objects
 * + companion object list had a race condition where the list was constructed
 * before the nested object singletons were initialized).
 *
 * Original had: Home, Streak, Premium, Profile.
 * Rebuild (per user): Premium tab replaced with About.
 * The "Home" tab displays as "Protect Yourself" (via R.string.app_name).
 */
enum class MainPageScreen(
    val route: String,
    @StringRes val resourceId: Int,
    @DrawableRes val icon: Int
) {
    Home(
        route = "Home",
        resourceId = R.string.app_name,
        icon = R.drawable.ic_launcher_foreground
    ),
    Streak(
        route = "Streak",
        resourceId = R.string.streak,
        icon = R.drawable.ic_fire
    ),
    About(
        route = "About",
        resourceId = R.string.about,
        icon = R.drawable.ic_info
    ),
    Profile(
        route = "Profile",
        resourceId = R.string.profile,
        icon = R.drawable.ic_profile
    );

    companion object {
        /** Ordered list of bottom-nav items. Safe — enum values are initialized at class load. */
        val all: List<MainPageScreen> = values().toList()
    }
}
