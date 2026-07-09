package protect.yourself.database.switchStatus

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Generic key-value store for switch states.
 *
 * 60+ keys (see SwitchIdentifier.kt).
 * Values are stored as String and parsed to Boolean / Int / String based on `type`.
 */
@Entity(tableName = "switch_status")
data class SwitchStatusItemModel(
    @PrimaryKey val key: String,
    val value: String,
    val type: String = "boolean"
) {
    fun asBoolean(): Boolean = value.toBooleanStrictOrNull() ?: false
    fun asInt(): Int = value.toIntOrNull() ?: 0
    fun asLong(): Long = value.toLongOrNull() ?: 0L
    fun asString(): String = value
}
