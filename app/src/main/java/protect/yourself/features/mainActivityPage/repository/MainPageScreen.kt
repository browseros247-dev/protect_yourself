package protect.yourself.features.mainActivityPage.repository

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import protect.yourself.R

/**
 * Bottom nav screens.
 *
 * Original had: NopoX, Streak, Premium, Profile.
 * Rebuild (per user): Premium tab replaced with About.
 */
sealed class MainPageScreen(
    val route: String,
    @StringRes val resourceId: Int,
    @DrawableRes val icon: Int
) {

    data object NopoX : MainPageScreen(
        route = "NopoX",
        resourceId = R.string.app_name,
        icon = R.drawable.ic_launcher_foreground
    )

    data object Streak : MainPageScreen(
        route = "Streak",
        resourceId = R.string.streak,
        icon = R.drawable.ic_fire
    )

    data object About : MainPageScreen(
        route = "About",
        resourceId = R.string.about,
        icon = R.drawable.ic_info
    )

    data object Profile : MainPageScreen(
        route = "Profile",
        resourceId = R.string.profile,
        icon = R.drawable.ic_profile
    )

    companion object {
        /** Ordered list of bottom-nav items. */
        val all: List<MainPageScreen> = listOf(NopoX, Streak, About, Profile)
    }
}
