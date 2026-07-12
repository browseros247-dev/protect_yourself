package protect.yourself.features.mainActivityPage.repository

import androidx.annotation.StringRes
import protect.yourself.R

/**
 * Bottom nav screens.
 *
 * 3 tabs: Home, Streak, Profile.
 * About tab removed per user request — About info is accessible from Profile page.
 *
 * The `route` field is used as a stable string identifier for deep-linking
 * (e.g. `EXTRA_OPEN_TAB = "Streak"` from StreakWidget). The `icon` field was
 * removed because MainActivity uses the `vectorIcon()` extension function for
 * ImageVector-based icons instead of @DrawableRes ints.
 */
enum class MainPageScreen(
    val route: String,
    @StringRes val resourceId: Int
) {
    Home(
        route = "Home",
        resourceId = R.string.app_name
    ),
    Streak(
        route = "Streak",
        resourceId = R.string.streak
    ),
    Profile(
        route = "Profile",
        resourceId = R.string.profile
    );

    companion object {
        val all: List<MainPageScreen> = values().toList()

        /** Returns the matching tab for a deep-link route string, or null. */
        fun fromRoute(route: String?): MainPageScreen? =
            values().firstOrNull { it.route == route }
    }
}
