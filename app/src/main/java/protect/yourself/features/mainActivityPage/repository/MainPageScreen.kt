package protect.yourself.features.mainActivityPage.repository

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import protect.yourself.R

/**
 * Bottom nav screens.
 *
 * 3 tabs: Home, Streak, Profile.
 * About tab removed per user request — About info is accessible from Profile page.
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
    Profile(
        route = "Profile",
        resourceId = R.string.profile,
        icon = R.drawable.ic_profile
    );

    companion object {
        val all: List<MainPageScreen> = values().toList()
    }
}
