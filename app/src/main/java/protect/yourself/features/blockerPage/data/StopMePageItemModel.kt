package protect.yourself.features.blockerPage.data

/**
 * Stop Me page item model — represents one row in the Stop Me page.
 */
data class StopMePageItemModel(
    val title: String,
    val info: String? = null,
    val durationMillis: Long = 0,
    val daysBitmask: Int = 0,
    val startTimeMillis: Long = 0,
    val isSection: Boolean = false,
    val isCustom: Boolean = false
)

/**
 * Stop Me schedule item model — for scheduled sessions.
 */
data class StopMeSchedulePageItemModel(
    val key: String,
    val title: String,
    val daysBitmask: Int,
    val startTimeMillis: Long,
    val durationMillis: Long
)
